package com.sky.merchant.constant;

/**
 * sky-merchant 模块的 Redis key 常量。
 * 为什么要收口到一个类：同一个 key 会被 Service / Agent 工具 / Controller 几处引用，
 * 分散硬编码时任何一处打错一个字都不会报错 ——
 * 表现是缓存各写各的：写进去了删不掉，删掉了还能读到。
 *
 * @author ruoyi
 */
public class RedisKeys
{
    /** 菜品详情缓存前缀，后面直接拼菜品 id。例：merchant:dish:detail:80 */
    public static final String DISH_DETAIL_PREFIX = "merchant:dish:detail:";

    /** 在售菜品列表（全量、不带分页），Agent 和客户端用 */
    public static final String DISH_ONSALE_KEY = "merchant:dish:onsale:list";

    /** 热搜菜名 ZSet：member 是菜名，score 是被问次数。不设过期，长期累积 */
    public static final String DISH_HOT_KEY = "merchant:dish:hot";

    /** 菜品对象与在售列表的 TTL（秒）：30 分钟 */
    public static final int DISH_TTL_SECONDS = 30 * 60;

    /** 问答答案缓存前缀，后面拼 md5(问题)。例：merchant:agent:chat:3fbca053... */
    public static final String CHAT_CACHE_PREFIX = "merchant:agent:chat:";

    /** 问答答案缓存的 TTL（秒）：30 分钟 */
    public static final int CHAT_CACHE_TTL_SECONDS = 30 * 60;

    /** 空值标记的 TTL（秒）：1 分钟。故意比正常缓存短得多 —— 见下方说明 */
    public static final int DISH_EMPTY_TTL_SECONDS = 60;

    /** 菜品 id 的布隆过滤器位图 */
    public static final String DISH_BLOOM_KEY = "merchant:dish:bloom";

    /** 菜品详情重建锁前缀 */
    public static final String DISH_LOCK_PREFIX = "merchant:dish:lock:";

    /** 重建锁的过期时间（秒）：必须明显大于一次查库的耗时，否则锁会提前失效 */
    public static final int DISH_LOCK_TTL_SECONDS = 10;

    /** 没抢到锁后最多等多久（毫秒）：必须 ≤ 锁 TTL×1000 */
    public static final long DISH_LOCK_WAIT_MILLIS = 2000L;

    /** 等锁轮询间隔（毫秒）：间隔 × 轮数 ≈ 等待预算 */
    public static final long DISH_LOCK_RETRY_INTERVAL_MILLIS = 50L;

    /**
     * 逻辑过期版详情缓存前缀。例：merchant:dish:detail:logical:80
     * 刻意拼在 DISH_DETAIL_PREFIX <b>之内</b> —— clearCache 按 DISH_DETAIL_PREFIX + "*" 扫描，
     * 这样它天然会被清掉；若另起一个 merchant:dish:logical: 前缀，这个 key 就永远清不掉了。
     */
    public static final String DISH_LOGICAL_PREFIX = DISH_DETAIL_PREFIX + "logical:";

    /**
     * 逻辑过期路径的重建锁前缀。例：merchant:dish:lock:logical:80
     * 为什么不和 Step 3 共用同一把锁：两条路径的产物不同（一条写裸 Dish，一条写 RedisData），
     * 共锁会让 Step 3 的等待者白等 2 秒后抛异常 —— 各锁各的，互不干扰。
     */
    public static final String DISH_LOGICAL_LOCK_PREFIX = DISH_LOCK_PREFIX + "logical:";

    /** 逻辑过期时长（秒）：30 分钟。到点后不删 key，由业务判断过期并异步重建 */
    public static final int DISH_LOGICAL_EXPIRE_SECONDS = 30 * 60;

    /**
     * 逻辑过期缓存的物理 TTL（秒）：24 小时。
     * 只作兜底 —— 正常情况下靠逻辑过期时间控制；万一重建一直失败，
     * 也不至于让一条脏数据永久驻留。
     */
    public static final int DISH_PHYSICAL_TTL_SECONDS = 24 * 60 * 60;

    private RedisKeys()
    {
    }
}
