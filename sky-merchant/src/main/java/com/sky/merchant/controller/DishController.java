package com.sky.merchant.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.sky.common.core.redis.RedisCache;
import com.sky.merchant.constant.RedisKeys;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.sky.common.annotation.Log;
import com.sky.common.core.controller.BaseController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.common.enums.BusinessType;
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
    private RedisCache redisCache;

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
        ExcelUtil<Dish> util = new ExcelUtil<>(Dish.class);
        util.exportExcel(response, list, "菜品管理数据");
    }

    /**
     * 获取菜品管理详细信息
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:query')")
    @GetMapping(value = "/{id:\\d+}") // /merchant/dish/abc 直接 404，/merchant/dish/80 正常走
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(dishService.selectDishById(id));
    }

    /**
     * 逻辑过期版详情接口。
     * 与 getInfo 返回同一个 Dish，差别只在缓存策略：
     * 读到逻辑过期值时先返回旧值、后台线程异步重建，换取"任何请求都不等锁"。
     */
    @PreAuthorize("@ss.hasPermi('merchant:dish:query')")
    @GetMapping("/logical/{id:\\d+}")
    public AjaxResult getInfoLogical(@PathVariable("id") Long id)
    {
        return success(dishService.selectDishByIdLogical(id));
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


    /**
     * 【Day11】扣库存 —— 四版对照入口。
     * <p>
     * 刻意做成"同一条业务、四个 URL"，是为了双实例压测时能逐个对照，
     * 而不是靠改代码来回切 —— 改代码切会让机器状态、JIT 状态都不可比。
     * <pre>
     *   POST /merchant/dish/80/deduct?n=1         → v0 裸写（必超卖）
     *   POST /merchant/dish/80/deduct/lock?n=1    → v1 Redisson 分布式锁
     *   POST /merchant/dish/80/deduct/atomic?n=1  → v2 SQL 原子更新（推荐）
     *   POST /merchant/dish/80/deduct/version?n=1 → v3 乐观锁（备选）
     * </pre>
     * 注意这里用的是 {@code {id:\d+}} 而不是 {@code {id}}，
     * 否则 /deduct 这些子路径会和 /{id} 抢匹配（正则约束见 getInfo 处的说明）。
     */
    @PostMapping("/{id:\\d+}/deduct")
    public AjaxResult deduct(@PathVariable("id") Long id,
                             @RequestParam(name = "n", defaultValue = "1") Integer n)
    {
        dishService.deductStock(id, n);
        // ⚠️ 若依只有 success() / success(String msg) / success(Object data) 三个重载，
        //    没有 success(msg, data)。要同时带消息和数据必须自己 put。
        return success().put("msg", "扣减成功")
                        .put("stock", dishService.selectDishById(id).getStock());
    }

    /** 【Day11 · v0】裸写扣库存，用于复现超卖。 */
    @PostMapping("/{id:\\d+}/deduct/nolock")
    public AjaxResult deductNoLock(@PathVariable("id") Long id,
                                   @RequestParam(name = "n", defaultValue = "1") Integer n)
    {
        return success().put("mode", "v0 裸写")
                        .put("stock", dishService.deductStockNoLock(id, n));
    }

    /** 【Day11 · v1】Redisson 分布式锁扣库存。 */
    @PostMapping("/{id:\\d+}/deduct/lock")
    public AjaxResult deductWithLock(@PathVariable("id") Long id,
                                     @RequestParam(name = "n", defaultValue = "1") Integer n)
    {
        return success().put("mode", "v1 Redisson 锁")
                        .put("stock", dishService.deductStockWithLock(id, n));
    }

    /** 【Day11 · v2】数据库原子更新扣库存（不依赖锁）。 */
    @PostMapping("/{id:\\d+}/deduct/atomic")
    public AjaxResult deductAtomic(@PathVariable("id") Long id,
                                   @RequestParam(name = "n", defaultValue = "1") Integer n)
    {
        return success().put("mode", "v2 原子更新")
                        .put("stock", dishService.deductStockAtomic(id, n));
    }

    /** 【Day11 · v3】乐观锁扣库存（备用对照）。 */
    @PostMapping("/{id:\\d+}/deduct/version")
    public AjaxResult deductOptimistic(@PathVariable("id") Long id,
                                       @RequestParam(name = "n", defaultValue = "1") Integer n)
    {
        return success().put("mode", "v3 乐观锁")
                        .put("stock", dishService.deductStockOptimistic(id, n));
    }

    /**
     * 获取热门菜品列表
     * @param top 热门菜品数量
     * @return 热门菜品列表
     */
    @GetMapping("/hot")
    public List<Map<String,Object>> getHotDishes(
            @RequestParam(name = "top", defaultValue = "5")int top )
    {
        return dishService.getHotDishes(top);
    }

    /**
     * 获取缓存数据
     */
    @GetMapping("/cache/ttl")
    public AjaxResult getCache(@RequestParam(name = "key") String key) {
        if (!key.startsWith("merchant:"))
        {
            return error("key 必须以 merchant: 开头");
        }
        AjaxResult result = success();
        result.put("key", key);
        // 判断这个 key 在 Redis 中是否存在（返回 boolean）
        result.put("exists", redisCache.hasKey(key));
        // 获取该 key 的剩余存活时间（秒）
        result.put("ttlSeconds", redisCache.getExpire(key));
        return result;
    }

    /**
     * 清空"菜品缓存"。
     * 只清真正属于缓存的两类 key：
     *   - 在售列表 merchant:dish:onsale:list
     *   - 菜品详情 merchant:dish:detail:{id}
     * 布隆位图(merchant:dish:bloom)、重建锁(merchant:dish:lock:*)、热榜(merchant:dish:hot)
     * 都不是缓存数据，绝不能删：
     *   位图被删 → 所有 id 都被判成"不存在" → 全站菜品查询返回 null，必须重启才恢复；
     *   锁被删   → 正在重建的请求互斥失效。
     * 详情 key 有独立前缀 merchant:dish:detail:，扫出来就是干净的详情集合，
     * 不需要再按"前缀 + 纯数字"过滤 —— 这正是把前缀从 merchant:dish: 拆开的原因。
     */
    @DeleteMapping("/cache/clear")
    public AjaxResult clearCache() {
        List<String> toDelete = new ArrayList<>();

        // 1. 在售列表：固定 key，存在才加（不存在也加会让 cleared 虚高）
        if (Boolean.TRUE.equals(redisCache.hasKey(RedisKeys.DISH_ONSALE_KEY)))
        {
            toDelete.add(RedisKeys.DISH_ONSALE_KEY);
        }
        // 2. 菜品详情：merchant:dish:detail:{id}
        Collection<String> keys = redisCache.keys(RedisKeys.DISH_DETAIL_PREFIX + "*");
        if (keys != null) {
            toDelete.addAll(keys);
        }

        // 3. 批量删除
        redisCache.deleteObject(toDelete);
        return success().put("cleared", toDelete.size());
    }
}
