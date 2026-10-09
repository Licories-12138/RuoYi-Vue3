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
import org.redisson.api.RLock;
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

    private static final int MAX_RETRY = 10;
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
                log.info("等锁期间命中缓存：id = {}, 空值标记 = {}", id, cached == null);
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

    // ================================================================================
    // Day11 · 扣库存四版对照
    // --------------------------------------------------------------------------------
    // 四版跑完要回答的问题：
    //   v0 裸写    —— 不加任何保护，真的会超卖吗？（现象：最终库存 < 0，或卖出去的份数 > 初始库存）
    //   v1 Redisson—— 加锁能治；但锁释放 & 事务提交的顺序是个坑（见 deductStockWithLock 注释）
    //   v2 原子更新—— 不加锁也能治，而且代码最短
    //   v3 乐观锁  —— 也能治，但高冲突下重试率上升，作为"另一种正确姿势"对照
    //
    // 结论 ：能用一条带条件的 UPDATE 解决，就不要上分布式锁。
    // 锁的适用场景是"多步写、跨资源"，不是"单表单行减法"。
    // ================================================================================

    /**
     * 统一入口（走 v2 原子更新）。
     * <p>
     * 为什么不把四个都暴露成默认？因为真实业务里只能有一个正确答案，
     * 多路径并存是演示需要，不是设计需要。
     */
    @Override
    public void deductStock(Long dishId, Integer count) {
        // TODO 用户实现：调 deductStockAtomic(dishId, count) 即可
        throw new UnsupportedOperationException("TODO: deductStock");
    }

    /**
     * 【v0 · 裸写】故意不加保护的扣库存 —— 用来复现超卖。
     * <p>
     * 实现要点（三步，不要合并）：
     * <ol>
     *   <li>先校验参数：dishId / count 非空，count &gt; 0（count = -5 会变成"加库存"）</li>
     *   <li>读：{@code dishMapper.selectStockById(dishId)}</li>
     *   <li>判断并写：{@code if (current >= count) dishMapper.updateStockById(dishId, current - count)}</li>
     * </ol>
     * 这三步之间没有任何互斥 —— 两个线程同时读到 100，各自算成 99 写回，
     * 结果卖了 2 份、库存只掉了 1。这就是超卖。
     * <p>
     * ⚠️ 压测时一定要先把库存调到一个较小的值（如 50），否则跑不满差异。
     *
     * @return 写入后的库存值（注意：这个返回值本身就不可信）
     */
    @Override
    public Integer deductStockNoLock(Long dishId, Integer count) {
        // 1. 参数校验
        if (dishId == null || count == null || count <= 0) {
            throw new ServiceException("扣减数量必须大于 0");
        }
        Integer current = dishMapper.selectStockById(dishId);
        if (current == null) throw new ServiceException("菜品不存在");
        if (current < count) throw new ServiceException("库存不足");
        return dishMapper.updateStockById(dishId, current - count);
    }

    /**
     * 【v1 · Redisson 分布式锁】
     * <p>
     * 加锁用哪种 API，直接决定看门狗开不开（这是本日最核心的知识点）：
     * <pre>
     *   lock.lock()                              ✅ 看门狗生效，TTL 30s，每 10s 续
     *   lock.lock(10, TimeUnit.SECONDS)          ❌ 显式传 leaseTime = 关闭看门狗
     *   lock.tryLock(3, 10, TimeUnit.SECONDS)    ❌ 显式传 leaseTime = 关闭看门狗
     *   lock.tryLock(3, TimeUnit.SECONDS)        ✅ 只传等待时间，看门狗生效
     * </pre>
     * 扣库存是"业务级、时长不可预测"的操作 —— 万一和外部支付网关交互卡住 40 秒，
     * 传了 leaseTime=10s 的锁早就自动过期了，第二个线程直接进来，锁形同虚设。
     * 所以这里要用 {@code tryLock(等待时间)} 并让看门狗续期。
     * <p>
     * 🔴 finally 里解锁必须判空 + 判断持锁：
     * <pre>
     *   boolean locked = false;
     *   RLock lock = redissonClient.getLock(RedisKeys.DISH_STOCK_LOCK_PREFIX + dishId);
     *   try {
     *       locked = lock.tryLock(3, TimeUnit.SECONDS);
     *       if (!locked) { throw ... }   // 拿不到锁 = 直接拒绝，不是等待
     *       ...业务...
     *   } finally {
     *       if (locked &amp;&amp; lock.isHeldByCurrentThread()) { lock.unlock(); }
     *   }
     * </pre>
     * 不判 {@code isHeldByCurrentThread()} 会抛 {@code IllegalMonitorStateException}：
     * tryLock 超时返回 false 时你根本没持锁，unlock 一个不属于自己的锁必然报错。
     * <p>
     * 🔴 事务顺序坑（双实例才暴露）：
     * 如果这个方法上加了 {@code @Transactional}，锁在方法体结束就释放了，
     * 但事务是在方法返回后才提交 —— 第二线程拿到锁进去查库，读到的还是旧库存，
     * 照样超卖。<b>结论：锁必须包在事务外面</b>（分层：外层加锁，内层事务方法）。
     * <p>
     * 内层还是用"读-判断-写"三步（和 v0 一样），因为 v1 要证明的是
     * "锁能把 v0 的问题治住"，而不是靠 SQL 兜底。
     *
     * @return 扣减后的库存
     */
    @Override
    public Integer deductStockWithLock(Long dishId, Integer count) {
        // 1. 参数校验
        if (dishId == null || count == null || count <= 0) {
            throw new ServiceException("扣减数量必须大于 0");
        }
        // 2. 取锁对象（key 用常量收口）
        RLock lock = redissonClient.getLock(RedisKeys.DISH_STOCK_LOCK_PREFIX + dishId);

        // locked 必须声明在 try 外面 —— finally 里要用它判断
        boolean locked = false;
        try {
            // 3. 加锁：只传「等待时间」，不传 leaseTime
            // 传了 leaseTime 就关闭看门狗，业务没跑完锁就自动过期，等于没锁
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                // // 扣库存是「长临界区」—— 拿不到锁就直接拒绝，不能无限等
                throw new ServiceException("扣减库存失败");
            }
            // 4. 扣库存
            Integer current = dishMapper.selectStockById(dishId);
            if (current == null) throw new ServiceException("菜品不存在");
            if (current < count) throw new ServiceException("库存不足");
            dishMapper.updateStockById(dishId, current - count);
            return current - count;
        } catch (InterruptedException e) {
            // tryLock(带超时) 会抛这个
            Thread.currentThread().interrupt();   // 恢复中断标志，别吞掉
            throw new ServiceException("获取锁被中断");
        } finally {
            // 5. 解锁：必须满足「拿到了锁」+「锁是我的」两个条件
            // 少了 isHeldByCurrentThread()，tryLock 超时时会抛 IllegalMonitorStateException
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 【v2 · 数据库原子更新】推荐方案。
     * <p>
     * 一句话：把"够不够"和"扣多少"塞进同一条 SQL 的 WHERE 里，
     * 由 InnoDB 的行锁保证原子性 —— 这才是最朴素也最可靠的解法。
     * <pre>
     *   update tb_dish set stock = stock - #{count}
     *   where id = #{id} and stock >= #{count}
     * </pre>
     * 实现要点：
     * <ol>
     *   <li>参数校验（同上，重点防 count 为负）</li>
     *   <li>{@code int rows = dishMapper.deductStock(dishId, count);}</li>
     *   <li>{@code rows == 0} → 库存不足，抛 ServiceException（这是正常业务失败，不是异常）</li>
     *   <li>返回 {@code dishMapper.selectStockById(dishId)} 作为最终库存</li>
     * </ol>
     * ⚠️ 注意：这里读回来的库存是"另一个时刻"的值，只用于展示，不要拿它做判断。
     * <p>
     * ⚠️ 但 v2 不是万能的：它只能保证<b>单条 UPDATE</b> 的原子性。
     * 如果业务是"扣库存 + 写订单 + 写流水"三步，仍然需要锁或事务 +
     * 事务内的行锁顺序一致来避免死锁。
     * <p>
     * ⚠️ 缓存一致性：扣完库存要清 detail 缓存，否则查详情看到的还是旧库存。
     * 调 {@link #clearDishCache(Long)}。
     *
     * @return 扣减后的库存
     */
    @Override
    public Integer deductStockAtomic(Long dishId, Integer count) {
        // 1. 参数校验
        if (dishId == null || count == null || count <= 0) {
            throw new ServiceException("扣减数量必须大于 0");
        }
        // 2. 扣库存
        int rows = dishMapper.deductStock(dishId,count);
        // 3. 判断结果
        if (rows == 0) {
            throw new ServiceException("库存不足");
        }
        // 4. 清空缓存
        clearDishCache(dishId);
        // 5. 返回最终库存
        return dishMapper.selectStockById(dishId);
    }

    /**
     * 【v3 · 乐观锁】备用对照。
     * <p>
     * 实现要点（必须带重试循环）：
     * <pre>
     *   for (int i = 0; i &lt; MAX_RETRY; i++) {
     *       Dish dish = dishMapper.selectDishById(dishId);     // 读 stock + version
     *       if (dish.getStock() &lt; count) { throw ... }
     *       int rows = dishMapper.deductStockOptimistic(
     *               dishId, dish.getStock() - count, dish.getVersion());
     *       if (rows == 1) { return dish.getStock() - count; } // 成功
     *       // rows == 0 → 有人抢先改了，重读重试
     *   }
     *   throw new ServiceException("并发冲突，请重试");
     * </pre>
     * 对比结论：
     * <ul>
     *   <li>v2 一步到位、零重试；v3 是"读-改-写"，重试是常态</li>
     *   <li>高并发下 v3 的重试会放大数据库压力，v2 不会</li>
     *   <li>v3 的价值在"读多写少 + 冲突极低"场景，比如配置项更新</li>
     * </ul>
     * 注意 {@code getStock()} 是 Integer 包装类型，可能为 null（菜品不存在），
     * 直接参与 {@code <} 比较会 NPE —— 先判 null。
     *
     * @return 扣减后的库存
     */
    @Override
    public Integer deductStockOptimistic(Long dishId, Integer count) {
        // 1. 参数校验
        if (dishId == null || count == null || count <= 0) {
            throw new ServiceException("扣减数量必须大于 0");
        }
        // 2. 重试循环,最多 MAX_RETRY 次
        for (int i = 0; i < MAX_RETRY; i++) {
            // 重试前退避：让先成功的请求先落库，避免所有线程在同一时刻反复碰撞
            if (i > 0) {
                try {
                    Thread.sleep(10L * i);   // 10ms, 20ms 递增
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ServiceException("系统繁忙，请稍后再试");
                }
            }
            // 2.1 每轮都要重新查最新值 —— 不能把上一轮的值带进来
            Dish dish = dishMapper.selectDishById(dishId);
            // 2.2 提前退出：菜品不存在 / 库存不足 —— 这两种重试也没用，直接抛异常
            if (dish == null) throw new ServiceException("菜品不存在");
            if (dish.getStock() == null || dish.getStock() < count) {
                throw new ServiceException("库存不足");
            }
            // 2.3 CAS 尝试：带 version 条件的 UPDATE
            int rows = dishMapper.deductStockOptimistic(dishId, count, dish.getVersion());
            // 2.4 判断结果
            if (rows == 1) {
                clearDishCache(dishId);
                return dish.getStock() - count;
            } else if (rows == 0) {
                //noinspection UnnecessaryContinue
                continue; // 重试
            }
        }
        throw new ServiceException("系统繁忙，请稍后再试");
    }
}
