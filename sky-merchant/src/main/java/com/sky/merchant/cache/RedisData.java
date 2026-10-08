package com.sky.merchant.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 带「逻辑过期时间」的缓存包装。
 *
 * <p>和普通缓存的区别：普通缓存靠 Redis 的物理 TTL，到期后 key 直接消失 ——
 * 于是所有并发请求同时发现"缓存没了"，一起冲进数据库，这就是缓存击穿。
 * 本类把"过期"变成一个可以自己判断的字段，key 本身不消失：
 * 发现逻辑过期后，先返回旧数据，再让一个线程去异步重建，没人需要等待。
 * 代价是可能读到旧值 —— 拿一致性换可用性。
 *
 * <p><b>写入 / 读取约定</b>（必须成对使用，否则拿去反序列化会得到 JSONObject 而不是 Dish）：
 * <pre>
 *   写：RedisData&lt;Dish&gt; rd = new RedisData&lt;&gt;(dish, LocalDateTime.now().plusSeconds(...));
 *       stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(rd), 物理TTL, TimeUnit.SECONDS);
 *   读：String json = stringRedisTemplate.opsForValue().get(key);
 *       RedisData&lt;Dish&gt; rd = JSON.parseObject(json, new TypeReference&lt;RedisData&lt;Dish&gt;&gt;() {});
 * </pre>
 *
 * <p><b>不要用 RedisCache 存取本类。</b>RedisCache.getCacheObject() 返回的 T 在编译期就被擦除了，
 * FastJson 无从得知内层是 Dish，会把 data 还原成 JSONObject —— 和 Day9 缓存集合时
 * 「取回来是 JSONArray 而不是 List&lt;T&gt;」是同一个坑。用 StringRedisTemplate + TypeReference
 * 把类型写死在代码里，这条路最可控。
 *
 * @param <T> 真正的业务数据类型，例如 Dish
 * @author ruoyi
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisData<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 逻辑过期时间：到点后 key 依然存在，只是业务上认定它"过期了"，需要异步重建 */
    private LocalDateTime expireTime;

    /** 真正的业务数据 */
    private T data;
}
