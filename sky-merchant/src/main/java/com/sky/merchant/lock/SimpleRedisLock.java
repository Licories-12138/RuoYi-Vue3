package com.sky.merchant.lock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 【Day12 · 手写锁】不依赖 Redisson 的 SET NX PX 锁。
 * 存在的意义是"看得见底层"。Day11 的 v1 直接用了 Redisson 的 RLock，
 * 能用但看不见里面发生了什么；这个类把 Redisson 替你做的三件事拆出来：
 *   互斥：{@code SET key token NX PX ttl} —— 一条命令同时完成"不存在才设置"和"设过期"
 *   防误删：value 存唯一 token，解锁时先比对，不是自己的锁不删
 *   原子释放：比对 + 删除交给 Lua，消除两步之间的空档
 *   这个类故意不做看门狗续期。续期不是锁的必需品，
 * 它是 Redisson 为了解决"业务没跑完锁就到期"额外加的一层；
 * 先跑通裸锁，才知道那一层在补什么。
 *
 * @author ruoyi
 */
@Component
public class SimpleRedisLock
{
    /**
     * 解锁脚本：token 一致才删除。
     * 脚本里 {@code KEYS} / {@code ARGV} 都是 1-based；
     * {@code redis.call} 是在 Redis 内部执行命令，不是从 Java 发命令。
     */
    private static final String LUA_UNLOCK =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    /**
     * 脚本对象是线程安全的，做成 static final 单例即可。
     * 别每次调用都 new —— 那会让客户端重复计算 SHA1 并多发一次 EVALSHA。
     * 返回值类型必须显式写 {@code Long.class}，否则 Spring Data Redis 拿不到正确类型。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(LUA_UNLOCK, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate)
    {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 加锁。
     * 用 {@code SET key value NX PX ttl}，不用老的 {@code SETNX} + {@code EXPIRE}：
     * 后者是两条命令，中间有空档 —— 进程若在两条命令之间挂掉，
     * key 就永远不会过期，直接变成死锁。合成一条才没有这个缝隙。
     *
     * @param key       完整锁 key
     * @param token     持有者标识（解锁时用来判断"这还是我的锁吗"）
     * @param ttlMillis 租期（毫秒）。到点自动释放，是防死锁的兜底
     * @return true 表示抢到了锁
     */
    public boolean tryLock(String key, String token, long ttlMillis)
    {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 解锁
     * 必须整体交给 Lua。不能写成"先 get 比对、再 delete"两步：
     * 两步之间有空档，如果锁恰好在此期间自动过期并被别人抢走，
     * 你的 delete 删掉的就是别人的锁，互斥随之失效.
     * @return true 表示锁确实是我的、已删除；false 表示 token 不匹配（什么都没删）
     */
    public boolean unlock(String key, String token)
    {
        Long result = stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        return result != null && result == 1L;
    }

    /**
     * 生成一个持有者标识。加锁前调一次，解锁时把同一个值传回来。
     * 之所以不把它做成类成员变量：本类是单例 Bean，
     * 成员变量会被所有线程共用 —— 那样任何线程都能通过比对，"防误删"就形同虚设。
     */
    public static String newToken()
    {
        return UUID.randomUUID().toString();
    }

    /**
     * 观察用：读当前锁的持有者。
     * @return null 表示锁不存在（已释放，或已因到期自动过期）
     */
    public String peekHolder(String key)
    {
        return stringRedisTemplate.opsForValue().get(key);
    }
}
