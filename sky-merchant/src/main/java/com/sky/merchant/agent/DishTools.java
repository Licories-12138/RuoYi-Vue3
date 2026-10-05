package com.sky.merchant.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.service.IDishService;

/**
 * 自然语言点单 Agent 的工具集：菜品查询
 *
 * 和 llm-demo 的 OrderTools 是同一套机制，区别只有一个：
 * 数据从「内存假数据」换成了「真实 MySQL 的 tb_dish 表」。
 * 模型不执行代码，它只返回"我想调 searchDishByName，参数是 宫保鸡丁"，
 * Spring AI 真正去执行这里的方法，查完库把结果喂回模型，模型再组织成人话。
 *
 * Day9 改造了两点：
 *   1. 注入的从 DishMapper 换成了 IDishService —— 只有走 Service 才能吃到刚加的 Redis 缓存。
 *      这一行改动很关键：工具层直接捅 Mapper 的话，你在 Service 上加的缓存、日志、鉴权全都绕过。
 *   2. 每次被调用都往 Redis 的 ZSet 里给这个菜名加一分，沉淀出「顾客最常问的菜」热榜。
 *      这份数据后续可以直接喂回 Prompt 做推荐，也可以给老板看哪些菜被问得多却不见了。
 */
@Component
public class DishTools
{
    @Autowired
    private IDishService dishService;

    /** ZSet 没有封装进 RedisCache，直接用若依注册的 RedisTemplate。泛型必须是 Object,Object */
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    /** 顾客常问菜名的排行榜 key */
    public static final String HOT_KEY = "merchant:dish:hot";

    /**
     * 按菜名模糊查询菜品。
     * 复用 DishService.selectDishList，SQL 里 name like concat('%', #{name}, '%')。
     */
    @Tool(description = "根据菜名关键词模糊查询菜品，返回菜名、售价和在售状态。例如用户问「宫保鸡丁多少钱」或「有哪些鱼香肉丝」时调用")
    public String searchDishByName(
            @ToolParam(description = "菜名关键词，例如「宫保鸡丁」或「鱼香肉丝」") String name)
    {
        System.out.println(">>> [Agent] 模型调用 searchDishByName，参数 name = " + name);

        // 模型查过一次就计一次，热榜里累积的是真实顾客意图
        recordHotKeyword(name);

        Dish query = new Dish();
        query.setName(name);
        List<Dish> list = dishService.selectDishList(query);

        if (list == null || list.isEmpty())
        {
            return "没有找到名字包含「" + name + "」的菜品";
        }
        return list.stream()
                .map(this::formatDish)
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    /**
     * 查在售菜品列表。
     * tb_dish.status = 0 表示在售（沿用苍穹外卖的约定，0 正常 / 1 停售）。
     * 这个调用会命中 DISH_ONSALE_KEY 的列表缓存。
     */
    @Tool(description = "查询当前在售的菜品列表，返回菜名和售价。例如用户问「现在有什么菜」或「在卖的菜有哪些」时调用")
    public String listOnSaleDishes()
    {
        System.out.println(">>> [Agent] 模型调用 listOnSaleDishes，查询在售菜品");

        // 走的是带缓存的专用方法，不带分页，拿到的是完整的在售列表
        List<Dish> list = dishService.listOnSaleDishes();

        if (list == null || list.isEmpty())
        {
            return "当前没有在售的菜品";
        }
        return list.stream()
                .map(this::formatDish)
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    /**
     * 往 ZSet 里累加一次关键词。
     * 用 incrementScore 而不是先取再加：Redis 端是原子的，并发下不会丢计数。
     */
    private void recordHotKeyword(String name)
    {
        if (name == null || name.trim().isEmpty())
        {
            return;
        }
        redisTemplate.opsForZSet().incrementScore(HOT_KEY, name.trim(), 1);
    }

    /**
     * 把 Dish 对象转成模型能读懂的字符串。
     * 返回对象也行，但返回字符串最稳，模型组织语言时信息不丢。
     */
    private String formatDish(Dish d)
    {
        String statusText = "1".equals(String.valueOf(d.getStatus())) ? "停售" : "在售";
        BigDecimal price = d.getPrice() == null ? BigDecimal.ZERO : d.getPrice();
        String desc = d.getDescription() == null ? "" : ("，简介：" + d.getDescription());
        return "「" + d.getName() + "」售价 " + price + " 元，状态" + statusText + desc;
    }
}
