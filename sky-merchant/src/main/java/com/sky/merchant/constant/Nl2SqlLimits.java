package com.sky.merchant.constant;

/**
 * NL2SQL 的运行时限制。
 * 为什么单独一个类而不是塞进 RedisKeys：那个类的职责是「Redis key 及其 TTL」，
 * 而这两个是 JDBC 层的行为限制 ——
 *   MAX_ROWS              管"一次最多取多少行"：校验器用它补/压 LIMIT，执行层用它 setMaxRows；
 *   QUERY_TIMEOUT_SECONDS 管"一条 SQL 最多跑多久"：由 JdbcTemplate#setQueryTimeout 生效。
 * 混在一起会让"找 key 的人"翻到不相干的东西，也会让"调参数的人"和
 * "重构缓存 key 的人"改到同一个文件。
 */
public final class Nl2SqlLimits {
    /** 单次查询最多返回的行数。模型写超了会被压回这个值（防止大结果集打爆内存） */
    public static final int MAX_ROWS = 200;

    /** 单条 SQL 的执行超时（秒）。超时由驱动发 KILL QUERY 中断，连接随即归还连接池 */
    public static final int QUERY_TIMEOUT_SECONDS = 5;

    private Nl2SqlLimits() { }
}
