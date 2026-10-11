package com.sky.merchant.nl2sql;

import java.util.List;

/**
 * 元数据提供者：把 information_schema 里的机器可读结构，转成模型可读的文本。
 * 这是整条链路的 输入质量来源 —— 模型能不能写对 SQL，八成取决于这里喂了什么。
 */
public interface SchemaMetadataProvider
{
    /**
     * 读取白名单表的结构，拼成喂给模型的 schema 文本。建议带 Redis 缓存。
     * 产出形态参考（不要直接抛 CREATE TABLE，太啰嗦）：
     * 【tb_dish】菜品信息表
     *   id            bigint         主键
     *   name          varchar(32)    菜品名称
     *   price         decimal(10,2)  菜品价格（元）
     *   status        int            0 停售 1 启用
     *   ...
     * 关联关系（库里没有物理外键，人工声明）：
     *   tb_dish.id = tb_dish_flavor.dish_id
     * 数据来源两条 SQL（都在 information_schema）：
     * -- 表级
     * SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
     *  WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (...);
     * -- 列级
     * SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT
     *   FROM information_schema.COLUMNS
     *  WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION;
     * 缓存 key 见 {@code RedisKeys.NL2SQL_SCHEMA_KEY}，TTL {@code NL2SQL_SCHEMA_TTL_SECONDS}。
     *
     * @return 拼接好的 schema 文本
     */
    String getSchemaText();

    /**
     * 当前允许查询的白名单表名（不带库名前缀）。
     * 为什么和 schema 文本分开两个方法：{@link SqlValidator} 要用它做表白名单校验，
     * 而校验是纯逻辑、不该依赖「schema 文本」这个给模型看的产物。
     * 两者数据源相同（同一份白名单常量），但用途不同。
     *
     * @return 例如 {@code ["tb_dish", "tb_dish_flavor", "tb_course"]}
     */
    List<String> getAllowedTables();

    /**
     * 表之间的关联关系（人工声明，库里没有物理外键）。
     * 为什么必须人工声明：Day13 实测 {@code KEY_COLUMN_USAGE} 里 {@code tb_} 表全为空 ——
     * MySQL 层面根本没记录这些关系，information_schema 推不出来。
     * 但模型要做两表 JOIN 就必须知道 {@code tb_dish.id = tb_dish_flavor.dish_id}。
     *
     * @return 例如 {@code ["tb_dish.id = tb_dish_flavor.dish_id"]}
     */
    List<String> getRelationHints();
}
