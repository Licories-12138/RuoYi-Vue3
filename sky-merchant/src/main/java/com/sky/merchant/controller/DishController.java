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
        result.put("exists", redisCache.hasKey(key));
        result.put("ttlSeconds", redisCache.getExpire(key));
        return result;
    }

    @DeleteMapping("/cache/clear")
    public AjaxResult clearCache() {
        Collection<String> keys = redisCache.keys(RedisKeys.DISH_SCAN_PATTERN);
        if (keys == null || keys.isEmpty())
        {
            return success().put("cleared", 0);
        }
        List<String> toDelete = new ArrayList<>(keys);
        toDelete.remove(RedisKeys.DISH_HOT_KEY);      // ← 这一行是这次的重点
        redisCache.deleteObject(toDelete);
        return success().put("cleared", toDelete.size());
    }
}
