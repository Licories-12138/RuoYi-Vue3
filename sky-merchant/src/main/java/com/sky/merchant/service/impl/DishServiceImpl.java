package com.sky.merchant.service.impl;

import java.util.*;

import com.alibaba.fastjson2.JSONArray;
import com.sky.common.core.redis.RedisCache;
import com.sky.common.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import com.sky.common.utils.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import com.sky.merchant.domain.DishFlavor;
import com.sky.merchant.mapper.DishMapper;
import com.sky.merchant.constant.RedisKeys;
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
    @Autowired
    RedisTemplate<Object, Object> redisTemplate;

    /**
     * 查询菜品管理
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    @Override
    public Dish selectDishById(Long id)
    {
        // 判断是否为NULL，如果为NULL则从数据库查询
        Dish dish = redisCache.getCacheObject(RedisKeys.DISH_DETAIL_PREFIX + id);
        if (dish == null)
        {
            log.info("从数据库查询菜品管理 {}", id);
            dish = dishMapper.selectDishById(id);
            redisCache.setCacheObject(RedisKeys.DISH_DETAIL_PREFIX + id, dish, RedisKeys.DISH_TTL_SECONDS, TimeUnit.SECONDS);
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
        JSONArray cached = redisCache.getCacheObject(RedisKeys.DISH_ONSALE_KEY); // 目标类型写 JSONArray
        if (cached != null && !cached.isEmpty()) {
            return cached.toJavaList(Dish.class); // 再转成 List<Dish>
        }
        List<Dish> list = dishMapper.selectDishList(dish);
        if (list != null && !list.isEmpty()) {
            redisCache.setCacheObject(RedisKeys.DISH_ONSALE_KEY, list, RedisKeys.DISH_TTL_SECONDS, TimeUnit.SECONDS);
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
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);
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
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);
        redisCache.deleteObject(RedisKeys.DISH_DETAIL_PREFIX + dish.getId());
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
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);
        for (Long id : ids) {
            redisCache.deleteObject(RedisKeys.DISH_DETAIL_PREFIX + id);
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
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);      // 再删缓存
        redisCache.deleteObject(RedisKeys.DISH_DETAIL_PREFIX + id);
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

    @Override
    public List<Map<String, Object>> getHotDishes(int top) {
        // 1. 参数校验（防止 top 为负数或 0 导致 Redis 报错）
        if (top <= 0) {
            return Collections.emptyList();
        }
        // 2. 从 Redis 获取降序排列的前 top 个元素及分数
        Set<ZSetOperations.TypedTuple<Object>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(RedisKeys.DISH_HOT_KEY, 0, top - 1);
        // 3. 判空，如果 Redis 里没有数据，返回空列表
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }
        // 4. 遍历 tuples，转换成 List<Map<String, Object>>
        List<Map<String, Object>> resultList = new ArrayList<>(tuples.size());
        int rank = 1;
        for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
            Map<String, Object> map = new LinkedHashMap<>();
            // 获取名次
            map.put("rank", rank++ );
            // 获取菜品信息
            map.put("name", tuple.getValue());
            // 获取热度分数（注意判空，虽然 WithScores 一般不会为空，但严谨起见）
            map.put("count", tuple.getScore() == null ? 0 : tuple.getScore().intValue());
            resultList.add(map);
        }
        return resultList;
    }
}
