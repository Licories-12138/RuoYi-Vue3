package com.sky.merchant.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.merchant.agent.DishTools;

/**
 * 自然语言点单 Agent 入口。
 *
 * 用法：GET /merchant/agent/chat?message=宫保鸡丁多少钱
 * 链路：用户自然语言 -> 模型理解意图 -> 调 DishTools 查真库 -> 模型组织成人话返回。
 */
@RestController
@RequestMapping("/merchant/agent")
public class DishAgentController
{
    private final ChatClient chatClient;

    public DishAgentController(ChatClient.Builder builder, DishTools dishTools)
    {
        this.chatClient = builder
                .defaultSystem("你是外卖商家系统的点单助手，只负责回答菜品相关的问题。"
                        + "查询菜品时优先调用工具获取真实数据，不要凭空编造菜名和价格。"
                        + "回答简洁，直接给出菜品名、价格和在售状态。")
                .defaultTools(dishTools)
                .build();
    }

    @GetMapping("/chat")
    public AjaxResult chat(@RequestParam String message)
    {
        String reply = chatClient.prompt().user(message).call().content();
        return AjaxResult.success(reply);
    }
}
