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
    /** 菜品缓存前缀，后面直接拼菜品 id。例：merchant:dish:80 */
    public static final String DISH_DETAIL_PREFIX = "merchant:dish:";

    /** 在售菜品列表（全量、不带分页），Agent 和客户端用 */
    public static final String DISH_ONSALE_KEY = "merchant:dish:onsale:list";

    /** 热搜菜名 ZSet：member 是菜名，score 是被问次数。不设过期，长期累积 */
    public static final String DISH_HOT_KEY = "merchant:dish:hot";

    /** 扫菜品缓存用的通配模式。注意它会一并命中 DISH_HOT_KEY，清理时必须排除 */
    public static final String DISH_SCAN_PATTERN = "merchant:dish:*";

    /** 菜品对象与在售列表的 TTL（秒）：30 分钟 */
    public static final int DISH_TTL_SECONDS = 30 * 60;

    /** 问答答案缓存前缀，后面拼 md5(问题)。例：merchant:agent:chat:3fbca053... */
    public static final String CHAT_CACHE_PREFIX = "merchant:agent:chat:";

    /** 问答答案缓存的 TTL（秒）：30 分钟 */
    public static final int CHAT_CACHE_TTL_SECONDS = 30 * 60;

    private RedisKeys()
    {
    }
}
