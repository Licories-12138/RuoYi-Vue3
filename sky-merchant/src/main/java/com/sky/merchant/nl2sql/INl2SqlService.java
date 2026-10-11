package com.sky.merchant.nl2sql;

/**
 * NL2SQL 主流程接口。
 * 六段链路（见 doc/Day13-NL2SQL方案设计.md）：
 * 用户中文提问
 *   → ① Prompt 组装（schema 文本进 system prompt）
 *   → ② 模型生成 SQL（qwen3.7-flash）
 *   → ③ Parser 解析成 AST（Druid SQLUtils）
 *   → ④ 三层安全校验（SELECT 判定 / 表白名单 / 危险模式）+ 重写（补数据范围 + LIMIT）
 *   → ⑤ 执行（JdbcTemplate，maxRows + timeout）
 *   → ⑥ 渲染成 Nl2SqlResult
 * 分工：①②④⑤⑥ 在实现类里；③ 单独抽成 {@link SqlValidator}，因为它是唯一需要单测的部分。
 * 为什么把「解析 + 校验 + 重写」拆出去单独一个类：
 * 它是整条链路里唯一不需要起 Spring 上下文、不需要连数据库就能测的部分。
 * 其余五段（调模型、查库）都不好单测 —— 拆开后可以拿一堆恶意 SQL 做纯单元测试。
 */
public interface INl2SqlService
{
    /**
     * 把中文问题转成查询结果。
     *
     * @param question 用户的中文提问，例如「价格超过 30 的菜有哪些」
     * @param userId   当前登录用户 id，用于越权检查（补数据范围条件）
     * @return 查询结果；校验失败或执行异常时，结果里的 {@code error} 字段有值，不抛异常
     */
    Nl2SqlResult query(String question, Long userId);
}
