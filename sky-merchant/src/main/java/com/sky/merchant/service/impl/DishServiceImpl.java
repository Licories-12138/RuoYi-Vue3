package com.sky.merchant.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.TypeReference;
import com.sky.common.core.redis.RedisCache;
import com.sky.common.exception.ServiceException;
import com.sky.common.utils.DateUtils;
import com.sky.merchant.cache.DishBloomFilter;
import com.sky.merchant.cache.RedisData;
import com.sky.merchant.config.CacheRebuildConfig;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
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
 * <h3>缓存读写姿势：本类里同时存在三套，加新 key 前先看清用哪套</h3>
 * <table border="1">
 *   <tr><th>注入的模板</th><th>序列化器</th><th>适用 key</th></tr>
 *   <tr><td>{@code redisCache}（若依封装）</td><td>FastJson2</td>
 *       <td>{@code DISH_ONSALE_KEY}（在售列表）</td></tr>
 *   <tr><td>{@code stringRedisTemplate}</td><td>纯字符串</td>
 *       <td>{@code DISH_DETAIL_PREFIX} / {@code DISH_LOGICAL_PREFIX}（详情两条路径）</td></tr>
 *   <tr><td>{@code redisTemplate<Object,Object>}</td><td>泛型对象</td>
 *       <td>{@code DISH_HOT_KEY}（ZSet 热榜）</td></tr>
 * </table>
 *
 * ⚠️ 三套写出来的字节形态互不兼容（读的时候必须用同一个模板），
 * 所以 key 之间不能串用。详情两条路径统一走 StringRedisTemplate + RedisData 包装，
 * 是为了让"空值标记"和"真数据"落在同一个类型上 —— 见 {@link #readCacheEntry}。
 *
 * @author ruoyi
 * @date 2026-10-04
 */
@Service
@Slf4j
public class DishServiceImpl implements IDishService {
    @Autowired
    private DishBloomFilter dishBloomFilter;
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    RedisTemplate<Object, Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    @Qualifier(CacheRebuildConfig.EXECUTOR_BEAN_NAME)
    private ThreadPoolExecutor rebuildExecutor;
    @Autowired
    private RedissonClient redissonClient;
    /**
     * 菜品缓存条目的类型令牌，两条路径共用。
     * 泛型必须显式写死：TypeReference 靠匿名子类的 getGenericSuperclass() 拿类型信息，
     * 一旦上下文没有明确目标类型（如 var / 赋给 Object），菱形推断会退化成 Object，
     * 解析出来是 JSONObject 而不是 Dish —— 且编译通过，属于静默失败。
     */
    @SuppressWarnings("Convert2Diamond")
    private static final TypeReference<RedisData<Dish>> DISH_CACHE_TYPE =
            new TypeReference<RedisData<Dish>>() {};

    /**
     * 查询菜品管理
     *
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    @SuppressWarnings("BusyWait")
    @Override
    public Dish selectDishById(Long id) {
        if (!dishBloomFilter.mightContain(id)) {
            log.info("布隆过滤器拦截：id = {}", id);
            return null;      // 一定不存在，缓存和数据库都不用碰
        }
        // 缓存空值方案，避免缓存穿透
        String key = RedisKeys.DISH_DETAIL_PREFIX + id;
        RedisData<Dish> rd = readCacheEntry(key);
        if (rd != null) {
            Dish cached = rd.getData();          // ← 只取一次
            if (cached == null) {
                log.info("命中空值标记，直接返回 null：id = {}", id);
            } else {
                log.info("缓存命中真数据：id = {}", id);
            }
            return cached;
        }

        // 1.缓存未命中，进入重建流程
        log.info("缓存未命中，开始重建：id = {}", id);
        String lockKey = RedisKeys.DISH_LOCK_PREFIX + id;
        // 2.抢锁: 带 TTL 的 SETNX（SET IF NOT EXIST + EXPIRE，一条命令原子完成
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(RedisKeys.DISH_LOCK_TTL_SECONDS));
        if (Boolean.TRUE.equals(locked)) {
            // 抢到锁,A线程
            try {
                log.info("拿到锁，重建缓存：id = {}", id);
                return loadAndCache(id, key);
            } finally {
                // 3) 无论成功、失败、异常，都必须把锁还回去
                stringRedisTemplate.delete(lockKey);
            }
        }
        // 3. 没抢到锁：在预算内轮询缓存。预算与锁 TTL 同源（预算 ≤ TTL），
        // 保证"只要锁还在有效期内，我就还有机会等到结果"
        long deadline = System.currentTimeMillis() + RedisKeys.DISH_LOCK_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(RedisKeys.DISH_LOCK_RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // 恢复中断标志，别吞掉
                return null;
            }
            // 等一下再读缓存：读的是和 loadAndCache 同一种格式（RedisData 包装）
            RedisData<Dish> retry = readCacheEntry(key);
            if (retry != null) {
                Dish cached = retry.getData();
                log.info(cached == null ? "等锁期间命中空值标记：id = {}" : "等锁期间命中缓存：id = {}", id);
                return cached;          // data 为 null 就是空值标记
            }
        }
        // 4. 预算用尽：兜底也要抢锁，抢到才查库。
        // 若无条件查库，所有等超时的线程会在同一瞬间一起砸向数据库，比不加锁更尖。
        Boolean relocked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(RedisKeys.DISH_LOCK_TTL_SECONDS));
        if (Boolean.TRUE.equals(relocked)) {
            try {
                log.info("等锁超时后抢到锁，重建缓存：id = {}", id);
                return loadAndCache(id, key);
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        }
        // 5. 抛明确异常
        log.warn("等锁预算用尽且未抢到兜底锁：id = {}", id);
        throw new ServiceException("服务繁忙，请稍后重试");
    }

    /**
     * 根据id查询菜品管理
     * 现在手上有没有旧数据，可以先给
     * 有（热 key 过期）→ 一个线程去后台重建，其余线程立刻拿走旧数据
     * 没有（冷启动 / 刚清过缓存）→ 没东西可给，只能同步重建
     */
    @Override
    public Dish selectDishByIdLogical(Long id) {
        // 1. 布隆过滤器
        if (!dishBloomFilter.mightContain(id)) {
            log.info("布隆过滤器拦截02：id = {}", id);
            return null;
        }

        // 2. 读缓存 + 反序列化（和另外两条路径共用同一个读法）
        String key = RedisKeys.DISH_LOGICAL_PREFIX + id;
        RedisData<Dish> rd = readCacheEntry(key);           // null = 缓存里没记录
        Dish old = (rd == null) ? null : rd.getData();      // 直接判 rd

        // 3. 快路径: 没过期
        if (rd != null && rd.getExpireTime() != null && rd.getExpireTime().isAfter(LocalDateTime.now())) {
            log.info("逻辑未过期，直接返回：id = {}", id);
            return old;   // old 为 null 就代表"不存在"，语义自洽
        }
        // 4. 重建 -- 抢锁
        String lockKey = RedisKeys.DISH_LOGICAL_LOCK_PREFIX + id;
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(RedisKeys.DISH_LOGICAL_LOCK_TTL_SECONDS));
        if (Boolean.TRUE.equals(locked)) {
            // 4.1 没有旧数据 -> 同步重建
            if (old == null) {
                try {
                    log.info("没有旧数据，同步重建：id = {}", id);
                    return rebuildLogical(id, key);
                } finally {
                    stringRedisTemplate.delete(lockKey); // 释放锁
                }
            }
            // 4.2 有旧数据 -> 异步重建
            log.info("有旧数据，异步重建：id = {}", id);
            /*
               关键：submit() 只是把任务丢进队列就返回，不等它跑完 —
               所以下面这行 return 是"立刻"执行的，请求总耗时 ≈ 无锁水平。
             */
            try {
                rebuildExecutor.submit(() -> {
                    try {
                        rebuildLogical(id, key);
                    } finally {
                        stringRedisTemplate.delete(lockKey);   // 重建完（或失败）放锁
                    }
                });
            } catch (RejectedExecutionException e) {
                stringRedisTemplate.delete(lockKey);           // 提交失败，锁得自己还
                log.warn("重建任务被拒绝，锁已归还：id = {}", id);
            }
            return old;
        }
        // 5. 没抢到锁
        if (rd != null) {
            log.info("未抢到重建锁，直接返回旧数据：id = {}", id);
            return old;   // 有记录就直接给（null 也行）
        }
        // 冷启动 + 别人正在重建: 兜底自己查一次，保证功能可用
        log.warn("冷启动且未抢到锁，兜底直接查库：id = {}", id);
        return dishMapper.selectDishById(id);  // 只有真的没记录才兜底查库
    }

    /**
     * 查库 + 回写逻辑缓存
     *
     * @param id  菜品 id
     * @param key 菜品详情缓存 key（RedisKeys.DISH_LOGICAL_PREFIX + id）
     * @return 菜品的真实数据；确实不存在时返回 null（此时已写好空值标记）
     */
    private Dish rebuildLogical(Long id, String key) {
        Dish dish = dishMapper.selectDishById(id); // 可能就是 null
        RedisData<Dish> redisData = new RedisData<>();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(
                RedisKeys.DISH_LOGICAL_EXPIRE_SECONDS
                        + ThreadLocalRandom.current().nextInt(RedisKeys.DISH_LOGICAL_TTL_JITTER_SECONDS)));
        redisData.setData(dish); // ★ 查不到就存 null，不再造空壳
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(redisData),
                Duration.ofSeconds(RedisKeys.DISH_PHYSICAL_TTL_SECONDS));
        log.info("逻辑缓存重建完成：id = {}, 下次过期于 = {}", id, redisData.getExpireTime());
        return dish;  // null 就是"不存在"
    }

    /**
     * 持锁期间重建缓存：双检 → 查库 → 回填。
     * 查不到时写"空值标记"（data 为 null 的 RedisData），后续请求命中它即可直接返回 null，不再打库。
     * 调用方必须已持有 lockKey 对应的锁，本方法只负责重建，不负责加锁 / 放锁。
     *
     * @param id  菜品 id
     * @param key 菜品详情缓存 key（RedisKeys.DISH_DETAIL_PREFIX + id）
     * @return 菜品的真实数据；确实不存在时返回 null（此时已写好空值标记）
     */
    private Dish loadAndCache(Long id, String key) {
        // 双检：等锁这段时间，前一个持锁者可能已经把缓存建好了
        // 读的是同一种格式，才能直接判 data 是否为 null
        RedisData<Dish> rd = readCacheEntry(key);
        if (rd != null) {
            log.info("双检命中：id = {}", id);
            return rd.getData();
        }
        // 真查库
        log.info("查库 {}", id);
        Dish dish = dishMapper.selectDishById(id);
        RedisData<Dish> entry = new RedisData<>();
        entry.setData(dish);  // 查不到就是 null
        /*
           两条分支的 TTL 策略不同，理由不一样：
           - 命中空值（dish == null）：用固定的 DISH_EMPTY_TTL_SECONDS（60 秒），不加随机。
             "不存在"是一个短时效结论，60 秒后自然重查，能立刻感知到新建的菜品；
             且扫不存在 id 的请求会被布隆过滤器拦在前面，根本走不到这里，所以不需要靠随机抖散。
           - 命中真数据：用 DISH_TTL_SECONDS + 随机抖动。
             加随机是为了防雪崩 —— 批量写入的 key 不能在同一秒集体失效，
             否则请求会在那一瞬间全压到 MySQL 上。
         */
        int ttl = (dish == null)
                ? RedisKeys.DISH_EMPTY_TTL_SECONDS
                : RedisKeys.DISH_TTL_SECONDS
                  + ThreadLocalRandom.current().nextInt(RedisKeys.DISH_TTL_JITTER_SECONDS);
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(entry), Duration.ofSeconds(ttl));
        return dish;
    }

    /**
     * 读缓存条目。
     * 返回 null = 缓存里没有记录；返回的对象里 data 为 null = 空值标记（查过、不存在）。
     */
    private RedisData<Dish> readCacheEntry(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isEmpty(json)) {
            return null;
        }
        return JSON.parseObject(json, DISH_CACHE_TYPE);
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
    public List<Dish> selectDishList(Dish dish) {
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
    public int insertDish(Dish dish) {
        dish.setCreateTime(DateUtils.getNowDate());
        int rows = dishMapper.insertDish(dish);
        insertDishFlavor(dish);
        dishBloomFilter.add(dish.getId()); // ← 新增的 id 必须同步进过滤器
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
    public int updateDish(Dish dish) {
        dish.setUpdateTime(DateUtils.getNowDate());
        dishMapper.deleteDishFlavorByDishId(dish.getId());
        insertDishFlavor(dish);
        // 先改库，再删缓存
        int rows = dishMapper.updateDish(dish);
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);
        clearDishCache(dish.getId());
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
    public int deleteDishByIds(Long[] ids) {
        int rows = dishMapper.deleteDishByIds(ids);
        dishMapper.deleteDishFlavorByDishIds(ids);
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);
        for (Long id : ids) {
            clearDishCache(id);
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
    public int deleteDishById(Long id) {
        dishMapper.deleteDishFlavorByDishId(id);
        int rows = dishMapper.deleteDishById(id);      // 先改库
        redisCache.deleteObject(RedisKeys.DISH_ONSALE_KEY);      // 再删缓存
        clearDishCache(id);
        return rows;
    }

    /**
     * 清掉一个菜品的所有缓存（互斥锁路径 + 逻辑过期路径）。
     * 为什么必须一起删：两条路写的是不同的 key，
     * 只删一条会让另一条继续返回旧菜名，直到它自己过期（最长 30 分钟）。
     */
    private void clearDishCache(Long id) {
        redisCache.deleteObject(RedisKeys.DISH_DETAIL_PREFIX + id);    // 互斥锁路径
        redisCache.deleteObject(RedisKeys.DISH_LOGICAL_PREFIX + id);   // 逻辑过期路径
    }

    /**
     * 新增菜品口味关系信息
     *
     * @param dish 菜品管理对象
     */
    public void insertDishFlavor(Dish dish) {
        List<DishFlavor> dishFlavorList = dish.getDishFlavorList();
        Long id = dish.getId();
        if (StringUtils.isNotNull(dishFlavorList)) {
            List<DishFlavor> list = new ArrayList<>();
            for (DishFlavor dishFlavor : dishFlavorList) {
                dishFlavor.setDishId(id);
                list.add(dishFlavor);
            }
            if (!list.isEmpty()) {
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
            map.put("rank", rank++);
            // 获取菜品信息
            map.put("name", tuple.getValue());
            // 获取热度分数（注意判空，虽然 WithScores 一般不会为空，但严谨起见）
            map.put("count", tuple.getScore() == null ? 0 : tuple.getScore().intValue());
            resultList.add(map);
        }
        return resultList;
    }
}
