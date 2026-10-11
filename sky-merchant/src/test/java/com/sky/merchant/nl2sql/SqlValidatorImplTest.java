package com.sky.merchant.nl2sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sky.merchant.nl2sql.ValidationResult.Reason;
import com.sky.merchant.nl2sql.impl.SqlValidatorImpl;

/**
 * SqlValidator 的单元测试 —— 全工程唯一的测试类。
 *
 * 为什么只有它有测试：它是整条 NL2SQL 链路里唯一能纯单测的部分 ——
 * 不依赖 Spring 容器、不连数据库、不调模型。给 SQL 字符串就能验证。
 *
 * 两组用例，缺一不可：
 *   拒绝组：恶意 SQL 必须挡住 —— 这是安全闸门的底线，一条都不能漏；
 *   通过组：正常 SQL 必须放行 —— 防止校验过严，把合法查询也拒了。
 * 只测拒绝会让校验越写越严，只测通过会让闸门形同虚设 —— 两边都得守。
 *
 * 前置条件：SqlValidatorImpl 的 collectTables / hasSubquery / hasSqlComment /
 * appendLimit 四个方法实现完之前，本测试会大面积报红（UnsupportedOperationException）。
 * 先写测试、看它从红变绿，是检验实现是否完整最快的方式。
 */
class SqlValidatorImplTest
{
    /**
     * 手写一个假的白名单来源，不引入 Mockito。
     * 理由：这里只需要"固定返回三张表"，匿名内部类比 mock 框架更直观，也少一层依赖。
     */
    private static final SchemaMetadataProvider FAKE_PROVIDER = new SchemaMetadataProvider()
    {
        @Override
        public String getSchemaText()
        {
            return "";
        }

        @Override
        public List<String> getAllowedTables()
        {
            return List.of("tb_dish", "tb_dish_flavor", "tb_course");
        }

        @Override
        public List<String> getRelationHints()
        {
            return List.of();
        }
    };

    private final SqlValidator validator = new SqlValidatorImpl(FAKE_PROVIDER);

    /** 断言：必须被拒，且拒绝原因就是这个 */
    private void assertRejected(Reason expected, String sql)
    {
        ValidationResult r = validator.validate(sql, 1L);
        assertFalse(r.isPassed(), "本该拒绝却放行了：" + sql);
        assertEquals(expected, r.getReason(), "拒绝原因不符：" + sql);
    }

    /** 断言：只要被拒就行（具体原因可能有两个都合理，比如子查询会先被白名单拦） */
    private void assertRejectedAnyWay(String sql)
    {
        ValidationResult r = validator.validate(sql, 1L);
        assertFalse(r.isPassed(), "本该拒绝却放行了：" + sql);
    }

    // ==================================================================
    // 一、拒绝组 —— 恶意 SQL
    // ==================================================================

    @Test
    @DisplayName("多语句：Druid 主动抛 ParserException，被转成 PARSE_ERROR")
    void rejectMultiStatement()
    {
        assertRejected(Reason.PARSE_ERROR, "select 1; drop table tb_dish");
        assertRejected(Reason.PARSE_ERROR, "select * from tb_dish; drop table tb_dish");
    }

    @Test
    @DisplayName("非 SELECT：UPDATE / DELETE / DROP 一律拒绝")
    void rejectNonSelect()
    {
        assertRejected(Reason.NOT_SELECT, "update tb_dish set price = 0");
        assertRejected(Reason.NOT_SELECT, "delete from tb_dish");
        assertRejected(Reason.NOT_SELECT, "drop table tb_dish");
    }

    @Test
    @DisplayName("越权表：sys_user / qrtz_* / gen_* 必须挡掉")
    void rejectTableOutsideWhitelist()
    {
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from sys_user");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select password, salt from sys_user");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from qrtz_triggers");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from gen_table");
    }

    @Test
    @DisplayName("越权表 + 库名前缀 / 反引号 / 大写：归一化必须生效")
    void rejectTableWithPrefixAndCase()
    {
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from `ry-vue`.sys_user");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from skydb.sys_user");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from `SYS_USER`");
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from SYS_USER");
    }

    @Test
    @DisplayName("边界：库名带连字符时，不加反引号是非法 SQL（Druid 把 - 当减号）")
    void rejectHyphenDatabaseWithoutBacktick()
    {
        // 实测：Druid 报 "Invalid from clause : ry - vue.sys_user" —— 解析阶段就失败，
        // 根本走不到白名单校验。这不是缺陷，是 SQL 语法本身的要求：
        // 库名含连字符时必须用反引号包起来。
        assertRejected(Reason.PARSE_ERROR, "select * from ry-vue.sys_user");
        // 加上反引号就是合法 SQL，能解析 → 走到白名单校验才被拦
        assertRejected(Reason.TABLE_NOT_ALLOWED, "select * from `ry-vue`.sys_user");
    }

    @Test
    @DisplayName("UNION：挡在 SQLSelectQueryBlock 强转之前，否则会 ClassCastException")
    void rejectUnion()
    {
        assertRejected(Reason.FORBIDDEN_PATTERN,
                "select a.id from tb_dish a union select b.id from tb_course b");
        assertRejected(Reason.FORBIDDEN_PATTERN,
                "select id from tb_dish union select id from sys_user");
    }

    @Test
    @DisplayName("子查询：外层别名骗过白名单，必须靠 hasSubquery 单独拦")
    void rejectSubquery()
    {
        // 只断言"被拒" —— 具体原因取决于白名单检查与子查询检查的先后顺序
        assertRejectedAnyWay("select * from (select id from tb_dish) t");
        assertRejectedAnyWay("select * from (select id from sys_user) t");
    }

    @Test
    @DisplayName("注释：在原始字符串上查，AST 里查不到")
    void rejectComment()
    {
        assertRejected(Reason.FORBIDDEN_PATTERN, "select * from tb_dish -- tail comment");
        assertRejected(Reason.FORBIDDEN_PATTERN, "select * from tb_dish /* block */");
    }

    @Test
    @DisplayName("拿不到表名（没有 FROM）也必须拒绝，不能放行")
    void rejectNoTable()
    {
        assertRejectedAnyWay("select 1");
        assertRejectedAnyWay("select 1 + 1");
    }

    // ==================================================================
    // 二、通过组 —— 正常 SQL 必须放行
    // ==================================================================

    @Test
    @DisplayName("单表查询：通过，且重写后的 SQL 被补上了 LIMIT")
    void acceptSimpleSelectAndAppendLimit()
    {
        ValidationResult r = validator.validate("select id, name, price from tb_dish", 1L);
        assertTrue(r.isPassed(), "正常查询被拒了，原因：" + r.getMessage());
        assertTrue(r.getRewrittenSql().toLowerCase().contains("limit"),
                "没有补上 LIMIT：" + r.getRewrittenSql());
    }

    @Test
    @DisplayName("聚合 / 条件 / 已带 LIMIT 的查询都通过")
    void acceptVariousValidQueries()
    {
        assertTrue(validator.validate("select count(*) from tb_dish", 1L).isPassed());
        assertTrue(validator.validate("select * from tb_dish where price > 30", 1L).isPassed());
        assertTrue(validator.validate("select * from tb_course", 1L).isPassed());
        assertTrue(validator.validate("select * from tb_dish limit 5", 1L).isPassed());
    }

    @Test
    @DisplayName("白名单表带反引号 / 库名前缀也要通过 —— 归一化是双向的，不能只拦不放")
    void acceptWhitelistedTableWithPrefix()
    {
        assertTrue(validator.validate("select * from `tb_dish`", 1L).isPassed());
        assertTrue(validator.validate("select * from `ry-vue`.tb_dish", 1L).isPassed());
        assertTrue(validator.validate("select * from TB_DISH", 1L).isPassed());
    }

    @Test
    @DisplayName("两表 JOIN：递归收表后都在白名单内，通过")
    void acceptTwoTableJoin()
    {
        ValidationResult r = validator.validate(
                "select d.name, f.name from tb_dish d join tb_dish_flavor f on d.id = f.dish_id", 1L);
        assertTrue(r.isPassed(), "JOIN 查询被拒了，原因：" + r.getMessage());
    }

    @Test
    @DisplayName("三表 JOIN：((A join B) join C) 的嵌套结构也要收全表名")
    void acceptThreeTableJoin()
    {
        ValidationResult r = validator.validate(
                "select a.id from tb_dish a "
                        + "join tb_dish_flavor b on a.id = b.dish_id "
                        + "join tb_course c on c.id = a.id", 1L);
        assertTrue(r.isPassed(), "三表 JOIN 被拒了，原因：" + r.getMessage());
    }
}
