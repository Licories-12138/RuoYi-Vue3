package com.sky.merchant.nl2sql;

import java.util.List;

/**
 * NL2SQL 一次查询的返回体。
 * 为什么不用 AjaxResult 直接包 Map：前端要拿「列名」和「行数据」分开渲染表格 ——
 * 只用 Map 的话列名得从第一行的 key 里猜，空结果集时连列名都没有。
 * 显式给出 columns，空结果也能渲染出表头。
 */
public class Nl2SqlResult
{
    /** 生成的 SQL（回显给用户/前端，便于排查"模型到底写了什么"） */
    private String sql;

    /** 列名，按 SELECT 顺序。用于渲染表头，空结果集时依然有值 */
    private List<String> columns;

    /** 行数据。每个 List 的长度与 columns 一致；值已转成字符串，前端不用管类型 */
    private List<List<String>> rows;

    /** 结果行数（= rows.size()，单列出来省得前端再算） */
    private int rowCount;

    /** 是否被截断（命中 LIMIT 上限 = 可能还有更多数据） */
    private boolean truncated;

    /** 校验/执行失败时的原因。非 null 表示这次没成功，sql 字段可能仍有用（说明模型写了啥） */
    private String error;

    public String getSql()
    {
        return sql;
    }

    public void setSql(String sql)
    {
        this.sql = sql;
    }

    public List<String> getColumns()
    {
        return columns;
    }

    public void setColumns(List<String> columns)
    {
        this.columns = columns;
    }

    public List<List<String>> getRows()
    {
        return rows;
    }

    public void setRows(List<List<String>> rows)
    {
        this.rows = rows;
    }

    public int getRowCount()
    {
        return rowCount;
    }

    public void setRowCount(int rowCount)
    {
        this.rowCount = rowCount;
    }

    public boolean isTruncated()
    {
        return truncated;
    }

    public void setTruncated(boolean truncated)
    {
        this.truncated = truncated;
    }

    public String getError()
    {
        return error;
    }

    public void setError(String error)
    {
        this.error = error;
    }

    // ==================================================================
    // 静态工厂：让调用方一行构造，不用 new 完再调一串 setter
    // （ValidationResult 也是这个风格，保持一致）
    // ==================================================================

    /** 失败：只有一句原因 */
    public static Nl2SqlResult fail(String error)
    {
        Nl2SqlResult r = new Nl2SqlResult();
        r.error = error;
        return r;
    }

    /** 失败：前缀 + 详情，例如 "SQL 执行失败" + 异常消息 */
    public static Nl2SqlResult fail(String prefix, String detail)
    {
        Nl2SqlResult r = new Nl2SqlResult();
        r.error = (detail == null || detail.isBlank()) ? prefix : prefix + "：" + detail;
        return r;
    }

    /**
     * 失败：校验器给的分类 + 消息。
     * 把 Reason 一并放进 error 字符串，是为了让前端和日志能区分
     * 「用户问错了」（PARSE_ERROR）和「有人在探测表结构」（TABLE_NOT_ALLOWED）。
     */
    public static Nl2SqlResult fail(ValidationResult.Reason reason, String message)
    {
        Nl2SqlResult r = new Nl2SqlResult();
        r.error = reason + "：" + message;
        return r;
    }

    /**
     * 补上「模型实际生成的 SQL」。
     * 为什么失败时也要带：排查失败时第一眼要看的就是「它到底写了什么」——
     * 是被拦在越权表，还是被拦在 SELECT *，全看这个字段。
     * 用链式调用而不是给每个 fail 工厂加参数，免得把工厂都撑成三参。
     */
    public Nl2SqlResult withSql(String sql)
    {
        this.sql = sql;
        return this;
    }

    /** 成功：一次填齐（truncated 由调用方按 LIMIT 上限判断后传入） */
    public static Nl2SqlResult success(String sql, List<String> columns,
                                       List<List<String>> rows, int rowCount, boolean truncated)
    {
        Nl2SqlResult r = new Nl2SqlResult();
        r.sql = sql;
        r.columns = columns;
        r.rows = rows;
        r.rowCount = rowCount;
        r.truncated = truncated;
        return r;
    }
}
