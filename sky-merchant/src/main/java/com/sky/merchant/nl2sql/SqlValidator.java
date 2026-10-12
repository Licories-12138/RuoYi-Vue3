package com.sky.merchant.nl2sql;

/**
 * SQL 安全校验器：第 ③④ 段（解析 AST + 三层校验 + 重写）
 * 为什么单独一个接口：这是整条链路里唯一可以纯单元测试的部分
 * 不依赖 Spring 容器、不连数据库、不调模型。给它一堆 SQL 字符串就能验证。
 * 核心立场（面试必答）：安全边界在「执行前」，不在提示词里。
 * 模型是建议者不是执行者 —— 它被 prompt 注入绕过了也没关系，
 * 只要这里不放过，SQL 就执行不了。所以本接口是整条链路的安全闸门。
 * validate 需要依次完成（前三步只读不改，最后一步才重写）：
 *   1) 语句类型：必须是 SELECT。用 SQLUtils.parseSingleStatement(sql, DbType.mysql)
 *      拿到 Statement，判断是不是 SQLSelectStatement。
 *      ⚠️ 必须用 parseSingleStatement（单条），不能 parseStatements ——
 *      允许多语句就等于给 "select 1; drop table x" 开门。
 *   2) 表白名单：从 AST 的 from/join 里取全部表名，逐个比对白名单
 *      （tb_dish / tb_dish_flavor / tb_course）。
 *      注意表名可能带库名前缀或反引号，比对前要归一化。
 *   3) 危险模式：AST 里查 UNION、子查询（from 里嵌套 select）、注释、
 *      笛卡尔积（JOIN 缺连接条件）；另外还有列级的两道 —— SELECT * 通配符、列白名单
 *      （点名 stock / image 这类内部字段）。
 *      注意 —— 要在 AST 上查，不要正则匹配字符串（把注释塞进关键词中间就能绕正则）。
 *   4) 重写：给查询补上 LIMIT（见 Nl2SqlLimits.MAX_ROWS），
 *      并按 userId 补数据范围条件（越权防线，见 Day5 的 DataScope 思路）。
 *      ⚠️ 数据范围条件是框架补的，不是模型写的 —— 模型不知道「你是谁」。
 * ⚠️ 接口体里不能有可执行语句 —— 上面这四步都是实现，写在 impl.SqlValidatorImpl 里。
 *    接口只声明「要做什么」，不写「怎么做」。
 *
 * @see ValidationResult
 */
public interface SqlValidator
{
    /**
     * 校验并重写一条模型生成的 SQL。四步的详细说明见类注释。
     *
     * @param sql    模型生成的原始 SQL（可能带 markdown 代码围栏，建议调用前先剥掉）
     * @param userId 当前登录用户，用于数据范围重写；为 null 时按「只读公共数据」处理
     * @return 校验结果；通过时 rewrittenSql 才是最终拿去执行的 SQL
     */
    ValidationResult validate(String sql, Long userId);
}
