package com.sky.merchant.service.impl;

import java.util.List;
import com.alibaba.fastjson2.JSONArray;
import com.sky.common.core.redis.RedisCache;
import com.sky.common.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import com.sky.common.utils.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import com.sky.merchant.domain.DishFlavor;
import com.sky.merchant.mapper.DishMapper;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.service.IDishService;

/**
 * 菜品管理Service业务层处理
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
@Service
@Slf4j
public class DishServiceImpl implements IDishService 
{
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private DishMapper dishMapper;
    private static final String DISH_KEY = "merchant:dish:";
    private static final String DISH_ONSALE_KEY = "merchant:dish:onsale:list";
    private static final int DISH_TTL_SECONDS = 30 * 60;   // 30 分钟

    /**
     * 查询菜品管理
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    @Override
    public Dish selectDishById(Long id)
    {
        // 判断是否为NULL，如果为NULL则从数据库查询
        Dish dish = redisCache.getCacheObject(DISH_KEY + id);
        if (dish == null)
        {
            log.info("从数据库查询菜品管理 {}", id);
            dish = dishMapper.selectDishById(id);
            redisCache.setCacheObject(DISH_KEY + id, dish, DISH_TTL_SECONDS, TimeUnit.SECONDS);
        }else {
            log.info("缓存命中 {}", dish);
        }
        return dish;
    }

    /**
     * 后台分页走 selectDishList（不缓存），Agent 走 listOnSaleDishes()（带缓存）
     *
     * @return 菜品管理集合
     */
    @Override
    public List<Dish> listOnSaleDishes() {
        Dish dish = new Dish();
        dish.setStatus(0L);
        // RedisCache 反序列化时传的 clazz 是 Object，集合里没有泛型信息可还原，取回来运行时是 JSONArray
        JSONArray cached = redisCache.getCacheObject(DISH_ONSALE_KEY); // 目标类型写 JSONArray
        if (cached != null && !cached.isEmpty()) {
            return cached.toJavaList(Dish.class); // 再转成 List<Dish>
        }
        List<Dish> list = dishMapper.selectDishList(dish);
        if (list != null && !list.isEmpty()) {
            redisCache.setCacheObject(DISH_ONSALE_KEY, list, DISH_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return list;
    }

    /**
     * 查询菜品管理列表
     * 
     * @param dish 菜品管理
     * @return 菜品管理
     */
    @Override
    public List<Dish> selectDishList(Dish dish)
    {
        return dishMapper.selectDishList(dish);
    }

    /**
     * 新增菜品管理
     * 
     * @param dish 菜品管理
     * @return 结果
     */
    @Transactional
    @Override
    public int insertDish(Dish dish)
    {
        dish.setCreateTime(DateUtils.getNowDate());
        int rows = dishMapper.insertDish(dish);
        insertDishFlavor(dish);
        redisCache.deleteObject(DISH_ONSALE_KEY);
        return rows;
    }

    /**
     * 修改菜品管理
     * 
     * @param dish 菜品管理
     * @return 结果
     */
    @Transactional
    @Override
    public int updateDish(Dish dish)
    {
        dish.setUpdateTime(DateUtils.getNowDate());
        dishMapper.deleteDishFlavorByDishId(dish.getId());
        insertDishFlavor(dish);
        // 先改库，再删缓存
        int rows = dishMapper.updateDish(dish);
        redisCache.deleteObject(DISH_ONSALE_KEY);
        redisCache.deleteObject(DISH_KEY + dish.getId());
        return rows;
    }

    /**
     * 批量删除菜品管理
     * 
     * @param ids 需要删除的菜品管理主键
     * @return 结果
     */
    @Transactional
    @Override
    public int deleteDishByIds(Long[] ids)
    {
        int rows = dishMapper.deleteDishByIds(ids);
        dishMapper.deleteDishFlavorByDishIds(ids);
        redisCache.deleteObject(DISH_ONSALE_KEY);
        for (Long id : ids) {
            redisCache.deleteObject(DISH_KEY + id);
        }
        return rows;
    }

    /**
     * 删除菜品管理信息
     * 
     * @param id 菜品管理主键
     * @return 结果
     */
    @Transactional
    @Override
    public int deleteDishById(Long id)
    {
        dishMapper.deleteDishFlavorByDishId(id);
        int rows = dishMapper.deleteDishById(id);      // 先改库
        redisCache.deleteObject(DISH_ONSALE_KEY);      // 再删缓存
        redisCache.deleteObject(DISH_KEY + id);
        return rows;
    }

    /**
     * 新增菜品口味关系信息
     * 
     * @param dish 菜品管理对象
     */
    public void insertDishFlavor(Dish dish)
    {
        List<DishFlavor> dishFlavorList = dish.getDishFlavorList();
        Long id = dish.getId();
        if (StringUtils.isNotNull(dishFlavorList))
        {
            List<DishFlavor> list = new ArrayList<>();
            for (DishFlavor dishFlavor : dishFlavorList)
            {
                dishFlavor.setDishId(id);
                list.add(dishFlavor);
            }
            if (!list.isEmpty())
            {
                dishMapper.batchDishFlavor(list);
            }
        }
    }
}
