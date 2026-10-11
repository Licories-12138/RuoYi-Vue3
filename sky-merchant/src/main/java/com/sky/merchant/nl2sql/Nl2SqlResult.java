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
}
