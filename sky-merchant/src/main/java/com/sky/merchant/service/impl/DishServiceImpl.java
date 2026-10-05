package com.sky.merchant.service.impl;

import java.util.List;
import java.util.concurrent.TimeUnit;
import com.sky.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import com.sky.common.utils.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import com.sky.merchant.domain.DishFlavor;
import com.sky.merchant.mapper.DishMapper;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.service.IDishService;
import com.alibaba.fastjson2.JSONArray;
import com.sky.common.core.redis.RedisCache;

/**
 * 菜品管理Service业务层处理
 * 
 * Day9 改造：给菜品查询加了 Redis 缓存，用的是 Cache Aside 模式。
 * 三条规则必须记住：
 *   1. 读：先查缓存，命中直接返回；没命中才查库，然后把结果写回缓存。
 *   2. 写：先更新数据库，再删缓存（不是更新缓存）。
 *   3. TTL 加了随机抖动，避免大批 key 在同一秒集体过期（雪崩，Day10 展开）。
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
@Service
public class DishServiceImpl implements IDishService 
{
    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private RedisCache redisCache;

    /** 单个菜品的缓存前缀：merchant:dish:{id} */
    private static final String DISH_KEY = "merchant:dish:";

    /** 在售菜品列表的缓存 key，整个商家端最高频的一次查询 */
    private static final String DISH_ONSALE_KEY = "merchant:dish:onsale:list";

    /** 基准过期时间 30 分钟 */
    private static final long BASE_TTL_SECONDS = 30 * 60L;

    /** 在基准上随机加 0-300 秒，防止集中过期 */
    private static final long TTL_JITTER_SECONDS = 300L;

    /**
     * 查询菜品管理
     * 
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    @Override
    public Dish selectDishById(Long id)
    {
        String cacheKey = DISH_KEY + id;

        // 注意这里能直接强转 Dish：序列化时开了 WriteClassName，值里带 @type
        // 且 Constants.JSON_WHITELIST_STR 里有 com.sky 白名单，反序列化会还原成 Dish
        Dish cached = redisCache.getCacheObject(cacheKey);
        if (cached != null)
        {
            System.out.println(">>> [缓存命中] dish id = " + id);
            return cached;
        }

        System.out.println(">>> [缓存未命中] 查库 dish id = " + id);
        Dish dish = dishMapper.selectDishById(id);
        if (dish != null)
        {
            redisCache.setCacheObject(cacheKey, dish, randomTtl(), TimeUnit.SECONDS);
        }
        return dish;
    }

    /**
     * 查询菜品管理列表。
     * 
     * 这里刻意不加缓存。原因见下面 listOnSaleDishes 的注释：
     * 后台列表接口调用前会先跑 PageHelper.startPage，查出来的是「某一页」的结果，
     * 如果把一个分页结果塞进不带页码的缓存 key 里，其它调用方拿到的就是被截断的半份数据。
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
     * 查询全部在售菜品，带缓存。
     * 
     * 只有这一个入口做列表缓存，前提是它不带分页、结果完整。
     * 调用方：Agent 的 listOnSaleDishes 工具。
     */
    @Override
    public List<Dish> listOnSaleDishes()
    {
        // 坑点：List 从 Redis 取回来不是 List<Dish>，而是 JSONArray。
        // 因为 RedisCache 反序列化时 clazz 是 Object，集合还原时没有目标泛型信息。
        // 若依自己的 DictUtils.getDictCache 也是这么处理的，写法一样。
        JSONArray cached = redisCache.getCacheObject(DISH_ONSALE_KEY);
        if (cached != null)
        {
            System.out.println(">>> [缓存命中] 在售菜品列表，共 " + cached.size() + " 条");
            return cached.toList(Dish.class);
        }

        System.out.println(">>> [缓存未命中] 查库获取在售菜品列表");
        Dish query = new Dish();
        query.setStatus(0L);
        List<Dish> list = dishMapper.selectDishList(query);
        if (list != null && !list.isEmpty())
        {
            redisCache.setCacheObject(DISH_ONSALE_KEY, list, randomTtl(), TimeUnit.SECONDS);
        }
        return list;
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
        // 新增的菜可能立刻就在售，列表缓存必须失效
        evictCache(null);
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
        int rows = dishMapper.updateDish(dish);
        evictCache(dish.getId());
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
        dishMapper.deleteDishFlavorByDishIds(ids);
        int rows = dishMapper.deleteDishByIds(ids);
        if (ids != null)
        {
            for (Long id : ids)
            {
                evictCache(id);
            }
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
        int rows = dishMapper.deleteDishById(id);
        evictCache(id);
        return rows;
    }

    /**
     * 使缓存失效。
     * 为什么是删缓存而不是更新缓存：更新要重新组装对象、保证字段一致，
     * 而这个接口本来就不常被写，删掉让它下次自己重建最省事，也不会出现脏写。
     * 
     * @param id 菜品主键，新增时还没有 id，传 null
     */
    private void evictCache(Long id)
    {
        if (id != null)
        {
            redisCache.deleteObject(DISH_KEY + id);
        }
        redisCache.deleteObject(DISH_ONSALE_KEY);
    }

    /**
     * 基准 TTL 加随机抖动。
     * 所有 key 用同一个固定 TTL 的话，它们会在同一秒一起失效，请求瞬间全打到数据库上，这就是雪崩。
     */
    private int randomTtl()
    {
        return (int) (BASE_TTL_SECONDS + (long) (Math.random() * TTL_JITTER_SECONDS));
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
            List<DishFlavor> list = new ArrayList<DishFlavor>();
            for (DishFlavor dishFlavor : dishFlavorList)
            {
                dishFlavor.setDishId(id);
                list.add(dishFlavor);
            }
            if (list.size() > 0)
            {
                dishMapper.batchDishFlavor(list);
            }
        }
    }
}
