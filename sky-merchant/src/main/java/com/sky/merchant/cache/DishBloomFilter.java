package com.sky.merchant.cache;

import com.sky.merchant.constant.RedisKeys;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.mapper.DishMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@Slf4j
public class DishBloomFilter implements ApplicationRunner{

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /*
    m 决定误判率，k 决定每次查询的成本；调 m 是白拿，调 k 是要还的
     */
    private static final String BLOOM_KEY = RedisKeys.DISH_BLOOM_KEY;
    private static final int EXPECTED_INSERTIONS = 100_000;   // 预期最大菜品数
    private static final double FALSE_POSITIVE_RATE = 0.01;    // 目标误判率 1%

    private static final int BIT_SIZE = (int) Math.ceil(
            -EXPECTED_INSERTIONS * Math.log(FALSE_POSITIVE_RATE)
                    / (Math.log(2) * Math.log(2)));            // ≈ 958,506

    private static final int HASH_COUNT = Math.max(1, (int) Math.round(
            (double) BIT_SIZE / EXPECTED_INSERTIONS * Math.log(2))); // ≈ 7
    /**
     * 将id添加到位图中
     * @param id 菜品id
     */
    public void add(Long id) {
        int[] pos = positions(id);
        for (int p : pos) {
            stringRedisTemplate.opsForValue().setBit(BLOOM_KEY, p, true);
        }
    }

    /**
     * 判断id是否可能在位图中
      * @param id 菜品id
     * @return 布尔值，表示菜品是否可能在位图中
     */
    public boolean mightContain(Long id) {
        int[] positions = positions(id);
        // 获取 k 个位置 → 逐个 getBit
        // 一遇到 false 立刻 return false（提前退出，这是性能关键）
        // k 个都通过才 return true
        for (int i = 0; i < HASH_COUNT; i++) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(BLOOM_KEY, positions[i]);
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /** MurmurHash3 的 fmix32：把低位信息扩散到高位，消除输入的线性相关性 */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * 根据 id 计算它在位图中对应的所有位下标（同一个 id 的多个哈希位置）
     *
     * @param id 菜品id
     * @return 位下标数组，长度为 HASH_COUNT，每个元素落在 [0, BIT_SIZE)
     */
    private int[] positions(Long id) {
        int[] pos = new int[HASH_COUNT];
        String s    = String.valueOf(id);
        int h1 = mix(Objects.hash(s));                 // ← 加一层混淆
        int h2 = mix(Objects.hash(s, "bloom-salt"));   // ← 两处都要
        int step = Math.floorMod(h2, BIT_SIZE - 1) + 1;   // 恒定落在 [1, BIT_SIZE-1]，永不为 0
        for (int i = 0; i < HASH_COUNT; i++) {
            pos[i] = Math.floorMod(h1 + i * step, BIT_SIZE);
        }
        return pos;
    }
    @Override
    public void run(@NonNull ApplicationArguments args) {
        List<Dish> all = dishMapper.selectDishList(new Dish());   // 传空条件 = 全量
        for (Dish d : all) {
            add(d.getId());
        }
        log.info("布隆过滤器预热完成，共 {} 个菜品", all.size());
    }
}
