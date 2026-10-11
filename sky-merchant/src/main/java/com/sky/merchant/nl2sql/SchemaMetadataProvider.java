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
     * 某张表允许被查询的列（不带表名限定，全小写）。
     *
     * 为什么需要它（列白名单）：
     * 「禁止 SELECT *」只挡住了"偷懒写法"，挡不住"点单" ——
     * 模型可以一个个点名列出内部字段（实测两次分别挑了 stock 和 image）。
     * 要管住"选择"，就必须给列也定一份名单。
     *
     * 为什么必须人工声明、不能从库里读：
     * 数据库里没有「这一列能不能对外开放」这个信息 —— 列注释只写"库存数量"，
     * 不会写"别给顾客看"。这和"没有物理外键"是同一类问题：
     * 数据库只描述事实，不描述权限。
     *
     * ⚠️ 校验要覆盖 SELECT / WHERE / ORDER BY / JOIN ON 四处 ——
     * 只查 SELECT 列表是不够的：where stock > 0 虽然不返回 stock 列，
     * 但结果集本身就泄露了库存信息（返回 3 条 = 有 3 个菜有库存）。
     *
     * @param table 表名（小写、不带反引号和库名前缀）
     * @return 该表允许的列名；返回 null 表示「未声明」，校验器会跳过该表
     */
    List<String> getAllowedColumns(String table);

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
