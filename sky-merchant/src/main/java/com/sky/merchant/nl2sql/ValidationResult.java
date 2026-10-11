package com.sky.merchant.nl2sql;

import java.util.List;

/**
 * 安全校验的结果。
 * 为什么不抛异常、不用 boolean：校验失败时要告诉调用方（和用户）为什么失败 ——
 * 「你的问题涉及了不开放的表」和「模型生成的 SQL 语法错误」是完全不同的两回事，
 * 前端提示、日志排查、灰度观察指标都要区分。一个 reason 字符串不够，需要分类码。
 */
public class ValidationResult
{
    /** 校验不通过的原因分类。新增分类时记得同步前端文案 */
    public enum Reason
    {
        /** 通过 */
        OK,
        /** SQL 解析失败（模型输出的不是合法 SQL） */
        PARSE_ERROR,
        /** 不是 SELECT（出现了 DML/DDL） */
        NOT_SELECT,
        /** 引用了白名单之外的表 */
        TABLE_NOT_ALLOWED,
        /** 出现了禁止的结构：UNION / 子查询 / 注释符 / 多语句 */
        FORBIDDEN_PATTERN,
        /**
         * 使用了列通配符（SELECT * 或 SELECT d.*）。
         * 单独一类而不是并进 FORBIDDEN_PATTERN —— 因为它的处置方式不同：
         * 前者是"有人在攻击你"，这条只是"模型偷懒"，提示用户补字段即可。
         */
        SELECT_STAR_NOT_ALLOWED,
        /**
         * 引用了列白名单之外的列。
         * 和 SELECT_STAR_NOT_ALLOWED 的分工：
         *   那条拦「偷懒」—— 用通配符一次拿到全部列；
         *   这条拦「点名」—— 逐个列出内部字段（如 stock / version / image）。
         * 只做前者挡不住后者，两条是互补的。
         */
        COLUMN_NOT_ALLOWED,
        /** 笛卡尔积：JOIN 没有 ON 条件 */
        CARTESIAN_JOIN
    }

    private final boolean passed;
    private final Reason reason;
    private final String message;

    /** 校验通过时，把解析后的表名带回给调用方（越权检查、日志都要用） */
    private List<String> referencedTables;

    /** 校验通过时，把「重写后」的 SQL 带回（可能追加了 LIMIT / 数据范围条件） */
    private String rewrittenSql;

    private ValidationResult(boolean passed, Reason reason, String message)
    {
        this.passed = passed;
        this.reason = reason;
        this.message = message;
    }

    public static ValidationResult ok(String rewrittenSql, List<String> tables)
    {
        ValidationResult r = new ValidationResult(true, Reason.OK, null);
        r.rewrittenSql = rewrittenSql;
        r.referencedTables = tables;
        return r;
    }

    public static ValidationResult fail(Reason reason, String message)
    {
        return new ValidationResult(false, reason, message);
    }

    public boolean isPassed()
    {
        return passed;
    }

    public Reason getReason()
    {
        return reason;
    }

    public String getMessage()
    {
        return message;
    }

    public List<String> getReferencedTables()
    {
        return referencedTables;
    }

    public String getRewrittenSql()
    {
        return rewrittenSql;
    }
}
