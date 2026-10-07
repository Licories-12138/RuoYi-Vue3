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

    private static final String BLOOM_KEY = RedisKeys.DISH_BLOOM_KEY; //
    private static final int BIT_SIZE = 10000;    // 位图总位数
    private static final int HASH_COUNT = 7;      // 哈希函数个数
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
        // 获取 7 个位置 → 逐个 getBit
        // 一遇到 false 立刻 return false（提前退出，这是性能关键）
        // 7 个都通过才 return true
        for (int i = 0; i < HASH_COUNT; i++) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(BLOOM_KEY, positions[i]);
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 根据id获取位图中对应的位数组，用于判断id是否可能在位图中
     * @param id 菜品id
     * @return 位数组
     */
    private int[] positions(Long id) {
        int[] pos = new int[HASH_COUNT];
        String s    = String.valueOf(id);
        int h1 = Objects.hash(s);              // 哈希一
        int h2 = Objects.hash(s, "bloom-salt"); // 哈希二：换个盐，和 h1 无关联
        for (int i = 0; i < HASH_COUNT; i++) {
            pos[i] = Math.floorMod(h1 + i * h2, BIT_SIZE);   // 用 h2 当步长，位置就散开了
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
