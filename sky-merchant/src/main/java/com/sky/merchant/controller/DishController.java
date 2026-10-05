package com.sky.merchant.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sky.common.annotation.Log;
import com.sky.common.core.controller.BaseController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.common.core.redis.RedisCache;
import com.sky.common.enums.BusinessType;
import com.sky.merchant.agent.DishTools;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.service.IDishService;
import com.sky.common.utils.poi.ExcelUtil;
import com.sky.common.core.page.TableDataInfo;

/**
 * 菜品管理Controller
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
@RestController
@RequestMapping("/merchant/dish")
public class DishController extends BaseController
{
    @Autowired
    private IDishService dishService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private RedisCache redisCache;

    /**
     * 顾客最常问的菜名排行榜。
     * 数据来自 Agent 每次调用工具时用 ZSet 累加的计数，取前 top 个，分数从高到低。
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:list')")
    @GetMapping("/hot")
    public AjaxResult hot(@RequestParam(value = "top", defaultValue = "5") int top)
    {
        Set<ZSetOperations.TypedTuple<Object>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(DishTools.HOT_KEY, 0, (long) top - 1);

        List<Map<String, Object>> rank = new ArrayList<>();
        if (tuples != null)
        {
            int no = 1;
            for (ZSetOperations.TypedTuple<Object> tuple : tuples)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", no++);
                row.put("name", tuple.getValue());
                row.put("count", tuple.getScore() == null ? 0L : tuple.getScore().longValue());
                rank.add(row);
            }
        }
        return AjaxResult.success(rank);
    }

    /**
     * 查看一个缓存 key 还剩多久过期。
     * 返回 -2 表示 key 不存在，-1 表示没设过期时间，其余是剩余秒数。
     * 这个接口纯粹是为了验证缓存改造成效：先查一次菜，再来看 ttl。
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:list')")
    @GetMapping("/cache/ttl")
    public AjaxResult cacheTtl(@RequestParam String key)
    {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("key", key);
        info.put("ttlSeconds", redisCache.getExpire(key));
        info.put("exists", redisCache.hasKey(key));
        return AjaxResult.success(info);
    }

    /**
     * 手动清空菜品相关缓存，用来验证「改数据 -> 缓存失效 -> 重新查库」这条链路。
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:list')")
    @GetMapping("/cache/clear")
    public AjaxResult clearCache()
    {
        java.util.Collection<String> keys = redisCache.keys("merchant:dish:*");
        // 热榜是长期累积的数据，不能被"清菜品缓存"误删，这里单独排除
        keys.remove(DishTools.HOT_KEY);
        int size = keys.size();
        if (size > 0)
        {
            redisCache.deleteObject(keys);
        }
        return AjaxResult.success("已清理 " + size + " 个菜品缓存 key");
    }

    /**
     * 查询菜品管理列表
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:list')")
    @GetMapping("/list")
    public TableDataInfo list(Dish dish)
    {
        startPage();
        List<Dish> list = dishService.selectDishList(dish);
        return getDataTable(list);
    }

    /**
     * 导出菜品管理列表
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:export')")
    @Log(title = "菜品管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Dish dish)
    {
        List<Dish> list = dishService.selectDishList(dish);
        ExcelUtil<Dish> util = new ExcelUtil<Dish>(Dish.class);
        util.exportExcel(response, list, "菜品管理数据");
    }

    /**
     * 获取菜品管理详细信息
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(dishService.selectDishById(id));
    }

    /**
     * 新增菜品管理
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:add')")
    @Log(title = "菜品管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Dish dish)
    {
        return toAjax(dishService.insertDish(dish));
    }

    /**
     * 修改菜品管理
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:edit')")
    @Log(title = "菜品管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Dish dish)
    {
        return toAjax(dishService.updateDish(dish));
    }

    /**
     * 删除菜品管理
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:remove')")
    @Log(title = "菜品管理", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(dishService.deleteDishByIds(ids));
    }
}
