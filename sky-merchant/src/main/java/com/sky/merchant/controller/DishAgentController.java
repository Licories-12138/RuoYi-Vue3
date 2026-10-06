package com.sky.merchant.controller;

import com.sky.common.core.redis.RedisCache;
import com.sky.merchant.domain.OrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.merchant.agent.DishTools;
import com.sky.merchant.constant.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 自然语言点单 Agent 入口。
 * 用法：GET /merchant/agent/chat?message=宫保鸡丁多少钱
 * 链路：用户自然语言 -> 模型理解意图 -> 调 DishTools 查真库 -> 模型组织成人话返回。
 */
@RestController
@RequestMapping("/merchant/agent")
@Slf4j
public class DishAgentController
{
    @Autowired
    private RedisCache redisCache; // Fixed: Added missing semicolon
    private final ChatClient chatClient;
    private final ChatClient parseClient;

    public DishAgentController(ChatClient.Builder builder,
                               DishTools dishTools,
                               @Value("${spring.ai.openai.chat.model:qwen3.7-flash}") String model,
                               @Value("${spring.ai.openai.chat.temperature:0.2}") Double temperature)
    {
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(dishTools)
                .build()
                .getToolCallbacks();

        this.chatClient = builder.clone()
                .defaultSystem("你是外卖商家系统的点单助手，只负责回答菜品相关的问题。"
                        + "查询菜品时优先调用工具获取真实数据，不要凭空编造菜名和价格。"
                        + "回答简洁，直接给出菜品名、价格和在售状态。")
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .model(model)                // ← 见第 3 点
                                .temperature(temperature)
                                .toolCallbacks(List.of(toolCallbacks))
                )
                .build();
        this.parseClient = builder.clone()
                .defaultSystem("你是点单信息抽取器。从用户的话里提取菜名、份数、是否要辣、是否打包，"
                        + "未提及的字段填 null，不要调用任何工具，不要编造。")
                .build();
    }

    @GetMapping("/chat")
    public AjaxResult chat(@RequestParam String message)
    {
        // 为什么用 md5 不用原文 —— 中文长句当 Redis key 又长又占内存；
        // 为什么必须是确定性哈希 —— 同一个问题每次得算出同一个 key，所以不能带随机盐（加密哈希、UUID 都不行）。
        String key = RedisKeys.CHAT_CACHE_PREFIX + DigestUtils.md5DigestAsHex(message.getBytes(StandardCharsets.UTF_8));
        String reply = redisCache.getCacheObject(key);
        if (reply != null && !reply.isBlank())
        {
            log.info("命中直接返回: {} <- {}", key, message);
            return AjaxResult.success("操作成功", reply).put("fromCache", true);
        }
        log.info("未命中调用模型: {} <- {}", key, message);

        // costMs 测的是「start 到 costMs 这两行之间」的时间
        long start = System.nanoTime();
        reply = chatClient.prompt().user(message).call().content();
        long costMs = (System.nanoTime() - start) / 1_000_000;

        if (reply != null && !reply.isBlank())
        {
            redisCache.setCacheObject(key, reply, RedisKeys.CHAT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        AjaxResult miss = AjaxResult.success("操作成功", reply);
        miss.put("fromCache", false);
        miss.put("costMs", costMs);
        return miss;
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
}
