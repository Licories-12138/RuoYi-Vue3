package com.sky.merchant.nl2sql.impl;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.sky.common.core.redis.RedisCache;
import com.sky.merchant.constant.RedisKeys;
import com.sky.merchant.nl2sql.SchemaMetadataProvider;

@Component
public class DbSchemaMetadataProvider implements SchemaMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(DbSchemaMetadataProvider.class);

    /** 白名单：库里 34 张表，只开放这 3 张业务表 */
    private static final List<String> ALLOWED_TABLES =
            List.of("tb_dish", "tb_dish_flavor", "tb_course");

    /** 关联关系：库里没有物理外键（Day13 实测），只能人工声明 */
    private static final List<String> RELATION_HINTS =
            List.of("tb_dish.id = tb_dish_flavor.dish_id");

    /**
     * 字段注释校正表，key 是「表名.字段名」。
     * 为什么需要它：tb_dish.status 在数据库里的 COLUMN_COMMENT 是错的
     * （写的是"0 停售 1 起售"），实际以字典 dish_status 为准。
     * 不校正 → 模型生成的 WHERE 方向相反 → 查出来的数据全错。
     */
    private static final Map<String, String> COMMENT_OVERRIDES =
            Map.of("tb_dish.status", "0 停售 1 启用");

    /**
     * 列白名单：每张表允许被查询的列。
     * ⚠️ 这是「权限声明」，不是「表结构描述」—— 所以只能写在这里，
     * 不能从 information_schema 自动生成：库里只有"stock 是库存数量"这个事实，
     * 没有"stock 不能给顾客看"这个策略。数据库只描述事实，不描述权限。
     * 被排除的内部字段及理由：
     *   tb_dish.image          OSS 存储地址，纯查询场景用不到，还暴露存储桶
     *   tb_dish.stock          库存量 —— 属经营数据，能反推销量
     *   tb_dish.version        乐观锁版本号，纯内部字段
     *   create_time/update_time  审计字段，模型不需要
     *   tb_course 同类字段同理
     *
     * ⚠️ 这份名单「只用于校验」，**不用来过滤喂给模型的 schema 文本** ——
     * buildSchemaText() 会把 information_schema 里的【全部列】都写进提示词，
     * 包括上面这些被排除的。这是**故意的**，原因：
     *   Day14 验收清单第 7 条要验的是「模型点名叫内部字段时拦得住」。
     *   模型必须先看得见 stock，才可能生成 select stock，才验得出白名单生效；
     *   如果提示词里也不给，模型压根不会写，这条验收就等于没测。
     * 代价（知情取舍）：用户问"库存"会拿到 COLUMN_NOT_ALLOWED 而不是模型的 NO_QUERY，
     *   提示语是"不允许查询该字段：stock"，对普通用户偏费解。
     * 若将来决定过滤（真要对外时更该这么做：不把模型用不了的东西喂给它），
     *   要同步改 Day14 那三条用例的预期：COLUMN_NOT_ALLOWED → NO_QUERY。
     */
    private static final Map<String, List<String>> ALLOWED_COLUMNS = Map.of(
            "tb_dish", List.of("id", "name", "price", "description", "status"),
            "tb_dish_flavor", List.of("id", "dish_id", "name", "value"),
            "tb_course", List.of("id", "code", "subject", "name", "price",
                    "applicable_person", "info"));

    /** 表级元数据。注意 ORDER BY 保证表顺序与建表一致 用 AS 别名固定 Map 的 key  */
    private static final String SQL_TABLES = """
            SELECT TABLE_NAME AS tblName, TABLE_COMMENT AS tblComment
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME IN ('tb_dish', 'tb_dish_flavor', 'tb_course')
              ORDER BY FIELD(TABLE_NAME, 'tb_dish', 'tb_dish_flavor', 'tb_course')
            """;

    /** 列级元数据。注意 ORDER BY 保证字段顺序与建表一致 */
    private static final String SQL_COLUMNS = """
            SELECT TABLE_NAME AS tblName, COLUMN_NAME AS colName, COLUMN_TYPE AS colType,
                   COLUMN_KEY AS colKey, COLUMN_COMMENT AS colComment
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME IN ('tb_dish', 'tb_dish_flavor', 'tb_course')
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

    private final JdbcTemplate jdbcTemplate;

    private final RedisCache redisCache;

    public DbSchemaMetadataProvider(JdbcTemplate jdbcTemplate, RedisCache redisCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisCache = redisCache;
    }

    @Override
    public List<String> getAllowedTables() {
        return ALLOWED_TABLES;
    }

    @Override
    public List<String> getAllowedColumns(String table) {
        // 显式判 null：ALLOWED_COLUMNS 是 Map.of 造的不可变 Map，get(null) 会抛 NPE
        return table == null ? null : ALLOWED_COLUMNS.get(table);
    }

    @Override
    public List<String> getRelationHints() {
        return RELATION_HINTS;
    }

    @Override
    public String getSchemaText() {
        // 1. 先查缓存
        String cached = redisCache.getCacheObject(RedisKeys.NL2SQL_SCHEMA_KEY);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        // 2. 未命中 → 查两条 SQL → 拼文本
        String text = buildSchemaText(jdbcTemplate.queryForList(SQL_TABLES),
                jdbcTemplate.queryForList(SQL_COLUMNS));

        // 2.1. 如果没有 "【"，说明表都没拼出来
        if (!text.contains("【")) {   // 一张表都没拼出来
            log.warn("schema 文本生成异常，跳过缓存，下次会重试");
            return text;             // 返回但不缓存
        }

        // 3. 写缓存（key/TTL 常量都在 RedisKeys 里了）
        redisCache.setCacheObject(RedisKeys.NL2SQL_SCHEMA_KEY, text,
                RedisKeys.NL2SQL_SCHEMA_TTL_SECONDS, TimeUnit.SECONDS);
        return text;
    }

    /**
     * 根据表结构元数据，拼接成「表名：表注释」、「字段名：字段注释」的文本\
     * @param tables  表结构元数据
     * @param columns 字段结构元数据
     * @return 拼接成的文本
     */
    private String buildSchemaText(List<Map<String, Object>> tables,
                                   List<Map<String, Object>> columns) {
        StringBuilder sb = new StringBuilder();
        // 1. 把 columns 按 tblName 分组，LinkedHashMap 保持顺序
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) {
            String tblName = str(column, "tblName");
            columnsByTable
                    .computeIfAbsent(tblName, k -> new ArrayList<>()) // 如果不存在则创建一个空列表
                    .add(column); // 添加列数据
        }

        // 2 & 3. 逐张表拼表头，再拼它下面的列
        for (Map<String, Object> table : tables) {
            String tblName = str(table, "tblName");
            String tblComment = str(table, "tblComment");
            sb.append("【").append(tblName).append("】").append(tblComment).append("\n");
            sb.append("\n"); // 在表和表之间拼一个空行

            List<Map<String, Object>> cols = columnsByTable.get(tblName); // 获取该表的列数据
            if (cols == null) {
                continue;   // 该表没查到列，跳过
            }
            // ⚠️ 这里【故意】不按 ALLOWED_COLUMNS 过滤，把全部列都喂给模型。
            //    原因与代价见 ALLOWED_COLUMNS 的注释（Day14 验收第 7 条要验"点名也拦得住"）——
            //    想改成过滤的话，先读完那段注释再动手。
            for (Map<String, Object> column : cols) {
                String colName = str(column, "colName");
                String colType = str(column, "colType");
                // 4. 注释：人工声明优先，取不到才用数据库的 colComment
                String override = COMMENT_OVERRIDES.get(tblName + "." + colName);
                String colComment = (override != null) ? override : str(column, "colComment");

                // 攒成一行再拼：注释为空时行尾不会留多余空格
                StringBuilder line = new StringBuilder("  ").append(colName).append("  ").append(colType);
                if (!colComment.isEmpty()) {
                    line.append("  ").append(colComment);
                }
                sb.append(line).append("\n");
            }
        }
        //  5. 末尾追加 "关联关系（人工声明）：" + RELATION_HINTS
        sb.append("关联关系（人工声明）：")
                .append(String.join("；", RELATION_HINTS))
                .append("\n");
        return sb.toString();
    }

    /**
     * 小工具：Map 里存的是 Object，统一取字符串表示，免得处处写 String.valueOf
     * @param row 一行数据
     * @param key 列名
     * @return 列值
     */
    private static String str(Map<String, Object> row, String key)
    {
        return Objects.toString(row.get(key), "");
    }
}
