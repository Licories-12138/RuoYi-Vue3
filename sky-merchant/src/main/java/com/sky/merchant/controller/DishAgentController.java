package com.sky.merchant.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import com.sky.merchant.domain.OrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.common.core.redis.RedisCache;
import com.sky.merchant.agent.DishTools;

/**
 * 自然语言点单 Agent 入口。
 * 用法：GET /merchant/agent/chat?message=宫保鸡丁多少钱
 * 链路：用户自然语言 -> 模型理解意图 -> 调 DishTools 查真库 -> 模型组织成人话返回。
 *
 * Day9：/chat 加了答案缓存。同一个问题第二次问直接返回 Redis 里的答案，
 * 不再走模型。这一步同时省掉钱和等待时间，是 Agent 上线必须做的一件事。
 */
@RestController
@RequestMapping("/merchant/agent")
@Slf4j
public class DishAgentController
{
    /** 问答缓存前缀 + 问题原文的 md5 */
    private static final String CHAT_CACHE_KEY = "merchant:agent:chat:";

    private final ChatClient chatClient;
    private final ChatClient parseClient;
    private final DishTools dishTools;

    @Autowired
    private RedisCache redisCache;

    /** Day9 排障用：把当前 ChatClient 实际挂载的工具清单打出来 */
    @Autowired(required = false)
    private org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider;

    @GetMapping("/diagnose/tools")
    public AjaxResult diagnoseTools()
    {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();

        // 第一路：Spring Boot 自动配置收集到的 @Tool
        out.put("autoProviderBean", toolCallbackProvider == null ? "bean 不存在" : toolCallbackProvider.getClass().getSimpleName());
        int autoCount = toolCallbackProvider == null ? 0 : toolCallbackProvider.getToolCallbacks().length;
        out.put("autoProviderToolCount", autoCount);

        // 第二路：拿着 DishTools 这个对象现场解析 @Tool，验证注解本身有没有被识别
        org.springframework.ai.tool.method.MethodToolCallbackProvider manual =
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                        .toolObjects(dishTools)
                        .build();
        org.springframework.ai.tool.ToolCallback[] callbacks = manual.getToolCallbacks();
        out.put("manualToolCount", callbacks.length);

        java.util.List<String> names = new java.util.ArrayList<>();
        for (org.springframework.ai.tool.ToolCallback callback : callbacks)
        {
            names.add(callback.getToolDefinition().name());
        }
        out.put("manualToolNames", names);
        return AjaxResult.success("操作成功", out);
    }

    public DishAgentController(ChatClient.Builder builder, DishTools dishTools,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.model:qwen3.7-flash}") String chatModelName,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.temperature:0.2}") Double chatTemperature)
    {
        this.dishTools = dishTools;

        // 手动把 @Tool 方法解析成工具回调。
        // 若依这套工程里 Spring AI 的 tool 自动配置没生效，容器里没有 ToolCallbackProvider bean。
        org.springframework.ai.tool.ToolCallback[] toolCallbacks =
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                        .toolObjects(dishTools)
                        .build()
                        .getToolCallbacks();

        // 坑一：ChatClient.Builder 是可变对象。
        // 连续两次 defaultSystem 会互相覆盖 —— 后建的 parseClient 会把 chatClient 的 system
        // 和工具一起冲掉。Day8 遇到的「parse 多余调用工具、chat 反而像抽取器」根子就在这里,
        // 当时建了第二个客户端其实没起到隔离作用。两个客户端必须各自 clone 一份再配置。
        this.chatClient = builder.clone()
                .defaultSystem("你是外卖商家系统的点单助手，只负责回答菜品相关的问题。"
                        + "查询菜品时优先调用工具获取真实数据，不要凭空编造菜名和价格。"
                        + "回答简洁，直接给出菜品名、价格和在售状态。")
                // 坑二：OpenAiChatModel 内部会把 options 强转成它自己的 OpenAiChatOptions。
                // 用 Spring AI 默认的 DefaultToolCallingChatOptions 会直接抛 ClassCastException，
                // 结果就是工具一个都没发到模型那边，模型从头到尾不知道自己有工具可用。
                // 再有：这里的 options 会覆盖 starter 的默认配置，model 和 temperature 必须自己带上。
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .temperature(chatTemperature)
                        .toolCallbacks(java.util.Arrays.asList(toolCallbacks)))
                .build();

        this.parseClient = builder.clone()
                .defaultSystem("你是点单信息抽取器。从用户的话里提取菜名、份数、是否要辣、是否打包，"
                        + "未提及的字段填 null，不要调用任何工具，不要编造。")
                .build();
    }

    @GetMapping("/chat")
    public AjaxResult chat(@RequestParam String message)
    {
        String cacheKey = CHAT_CACHE_KEY + md5(message);

        String cached = redisCache.getCacheObject(cacheKey);
        if (cached != null)
        {
            // 这里必须写 success("操作成功", cached)。
            // 不能直接 success(cached)：AjaxResult 同时有 success(String msg) 和 success(Object data),
            // Java 重载会优先选更具体的 String 版本，回答会被当成 msg 塞进去，data 变成 null。
            AjaxResult hit = AjaxResult.success("操作成功", cached);
            hit.put("fromCache", true);
            return hit;
        }

        long start = System.currentTimeMillis();
        String reply = chatClient.prompt().user(message).call().content();
        long cost = System.currentTimeMillis() - start;
        System.out.println(">>> [Agent] 模型回答耗时 " + cost + " ms");

        // 只缓存非空回答。这版是朴素缓存，还有个未解的问题：
        // 模型这次答错了也会被原样缓存 30 分钟，直到过期才会重答。
        // 要彻底解决得做质量校验或人工反馈失效，留到 Day12 一致性那节再处理。
        if (reply != null && !reply.isBlank())
        {
            redisCache.setCacheObject(cacheKey, reply, 30, TimeUnit.MINUTES);
        }

        AjaxResult miss = AjaxResult.success("操作成功", reply);
        miss.put("fromCache", false);
        miss.put("costMs", cost);
        return miss;
    }

    /**
     * 给问题算 md5 当缓存 key。
     * 不用原文是因为中文长句直接当 Redis key 既占内存又不好看;
     * 同一个问题必须算出同一个 key，所以不能用带随机数的哈希。
     */
    private String md5(String text)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes)
            {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException("生成缓存 key 失败", e);
        }
    }

    @GetMapping("/parse")
    public AjaxResult parse1(@RequestParam String message)
    {
        OrderRequest request = parseClient.prompt()
                .user(message)
                .call()
                .entity(OrderRequest.class);
        return AjaxResult.success(request);
    }

//    @GetMapping("/parse")
//    public AjaxResult parse2(@RequestParam String message)
//    {
//        BeanOutputConverter<OrderRequest> converter =
//                new BeanOutputConverter<>(OrderRequest.class);
//
//        String text = parseClient.prompt()
//                .user(u -> u.text("{msg}\n{format}")
//                        .param("msg", message)
//                        .param("format", converter.getFormat()))
//                .call()
//                .content();
//
//        log.info("text={}", converter.getFormat());
//
//        if (text == null || text.isBlank()) {
//            throw new RuntimeException("大模型返回内容为空");
//        }
//
//        OrderRequest request = converter.convert(text);
//        return AjaxResult.success(request);
//    }
}
