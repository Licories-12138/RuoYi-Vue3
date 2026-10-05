package com.sky.merchant.agent;

import java.math.BigDecimal;
import java.util.List;
import com.sky.merchant.service.IDishService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import com.sky.merchant.domain.Dish;

/**
 * 自然语言点单 Agent 的工具集：菜品查询
 * 和 llm-demo 的 OrderTools 是同一套机制，区别只有一个：
 * 数据从「内存假数据」换成了「真实 MySQL 的 tb_dish 表」。
 * 模型不执行代码，它只返回"我想调 searchDishByName，参数是 宫保鸡丁"，
 * Spring AI 真正去执行这里的方法，查完库把结果喂回模型，模型再组织成人话。
 */
@Component
public class DishTools
{
    @Autowired
    private IDishService dishService;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    /**
     * 按菜名模糊查询菜品。
     * 复用 DishMapper.selectDishList，SQL 里 name like concat('%', #{name}, '%')。
     */
    @Tool(description = "根据菜名关键词模糊查询菜品，返回菜名、售价和在售状态。例如用户问「宫保鸡丁多少钱」或「有哪些鱼香肉丝」时调用")
    public String searchDishByName(
            @ToolParam(description = "菜名关键词，例如「宫保鸡丁」或「鱼香肉丝」") String name)
    {
        System.out.println(">>> [Agent] 模型调用 searchDishByName，参数 name = " + name);

        Dish query = new Dish();
        query.setName(name);
        // 增加热门搜索次数
        redisTemplate.opsForZSet().incrementScore("merchant:dish:hot", name.trim(), 1);
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
     */
    @Tool(description = "查询当前在售的菜品列表，返回菜名和售价。例如用户问「现在有什么菜」或「在卖的菜有哪些」时调用")
    public String listOnSaleDishes()
    {
        System.out.println(">>> [Agent] 模型调用 listOnSaleDishes，查询在售菜品");

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
