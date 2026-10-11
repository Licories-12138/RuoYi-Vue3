package com.sky.merchant.nl2sql.impl;

import java.util.*;

import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.statement.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.sky.merchant.constant.RedisKeys;
import com.sky.merchant.nl2sql.SchemaMetadataProvider;
import com.sky.merchant.nl2sql.SqlValidator;
import com.sky.merchant.nl2sql.ValidationResult;
import com.sky.merchant.nl2sql.ValidationResult.Reason;

/**
 * SqlValidator 的实现 —— NL2SQL 链路的安全闸门（第 ③④ 段）。
 * 方法结构（本类的组织方式）
 * ------------------------------------------------------------------
 * 主方法 validate 只做编排，不写任何细节逻辑；每一步拆成一个私有方法。
 * 这样拆的两个理由：
 *   1) 每一步能单独读、单独验证 —— 安全逻辑最怕"揉成一大坨看不出漏了哪步"；
 *   2) 递归/归一化这类容易写错的逻辑独立成方法，便于单测逐个击破。
 * validate(sql, userId)                       ← 主方法：编排 4 步
 *   ├─ 步骤0  解析（try-catch 包住 parseSingleStatement）
 *   ├─ 步骤1  语句类型判定（instanceof，无需单独方法）
 *   ├─ 步骤3a UNION 判定（必须在强转 queryBlock 之前！见下方注释）
 *   ├─ 步骤2  collectTableNames() → collectTables() 递归 → normalizeTableName() 归一化
 *   ├─ 步骤3b hasSubquery()       → 递归 from 树
 *   ├─ 步骤3c hasSqlComment()     → 注意吃的是【原始 SQL 字符串】不是 AST
 *   └─ 步骤4  appendLimit() / appendDataScope()
 * ⚠️ 唯一的顺序陷阱
 * ------------------------------------------------------------------
 * UNION 判定必须放在"强转 SQLSelectQueryBlock"之前。
 * 因为 union 查询解析出来是 SQLUnionQuery，它不是 SQLSelectQueryBlock 的子类 ——
 * 直接强转会抛 ClassCastException。先判类型再强转，是这里的硬约束。
 */
@Component
public class SqlValidatorImpl implements SqlValidator
{
    /** 项目惯例：手写 logger（不依赖 Lombok @Slf4j，与 DishServiceImpl 保持一致） */
    private static final Logger log = LoggerFactory.getLogger(SqlValidatorImpl.class);

    /** 白名单来源。注意：不要在本类里硬编码表名 —— 白名单是元数据的职责 */
    private final SchemaMetadataProvider metadataProvider;

    public SqlValidatorImpl(SchemaMetadataProvider metadataProvider)
    {
        this.metadataProvider = metadataProvider;
    }

    // ==================================================================
    // 主方法：只做编排
    // ==================================================================
    @Override
    public ValidationResult validate(String sql, Long userId)
    {
        SQLStatement stmt;
        // ---- 步骤 0：解析成 AST ----
        try {
            stmt = SQLUtils.parseSingleStatement(sql, DbType.mysql);
        }catch (Exception e)
        {
            log.error("SQL 解析失败: {}", sql, e);
            return ValidationResult.fail(Reason.PARSE_ERROR, "SQL 解析失败");
        }

        // ---- 步骤 1：必须是 SELECT ----
        if (!(stmt instanceof SQLSelectStatement selectStmt)) {
            return ValidationResult.fail(Reason.NOT_SELECT, "只支持查询语句");
        }
        SQLSelect select = selectStmt.getSelect(); // 返回 SQLSelect
        SQLSelectQuery query = select.getQuery();  // 再取一层才是 SQLSelectQuery
        // ---- 步骤 3a：先挡 UNION（顺序陷阱！必须在强转 queryBlock 之前）----
        if (!(query instanceof SQLSelectQueryBlock block)) {
            return ValidationResult.fail(Reason.FORBIDDEN_PATTERN, "只支持普通查询");
        }
        // ---- 步骤 2：表白名单 ----
        Set<String> tables = collectTableNames(block);
        if (tables.isEmpty()){
            return ValidationResult.fail(Reason.PARSE_ERROR, "SQL解析失败");
        }
        List<String> allowedTables = metadataProvider.getAllowedTables();
        for (String t : tables) {
            if (!allowedTables.contains(t)) {
                return ValidationResult.fail(Reason.TABLE_NOT_ALLOWED, "涉及不开放的表：" + t);
            }
        }
        // ---- 步骤 3b/3c：子查询 + 注释 ----
        if (hasSubquery(block.getFrom()) || hasSqlComment(sql)) {
            return ValidationResult.fail(Reason.FORBIDDEN_PATTERN, "涉及子查询或注释");
        }
        // ---- 步骤 4：重写 ----
        appendLimit(block);
        appendDataScope(block, userId);
        String rewritten = SQLUtils.toSQLString(stmt, DbType.mysql);
        return ValidationResult.ok(rewritten, new ArrayList<>(tables));
    }

    // ==================================================================
    // 第 2 步：表名收集
    // ==================================================================

    /**
     * 从查询块收集所有被引用的表名（已归一化）。
     * 为什么要单独一层：getFrom() 返回的是一棵树，递归逻辑在 collectTables 里；
     * 本方法只负责准备容器。
     * 用 LinkedHashSet 而不是 HashSet —— 保持出现顺序，报错时日志更好读。
     */
    private Set<String> collectTableNames(SQLSelectQueryBlock block)
    {
        Set<String> out = new LinkedHashSet<>();
        collectTables(block.getFrom(), out);
        return out;
    }

    /**
     * 递归收集 TableSource 树里的表名。
     * 三种分支，各自对应一个 Druid 类型：
     *   SQLExprTableSource       单表    → out.add(normalizeTableName(t.getName()))
     *   SQLJoinTableSource       JOIN    → 递归 getLeft() + getRight()
     *   SQLSubqueryTableSource   子查询  → 递归进去（同时 hasSubquery 会单独拦）
     *  JOIN 必须递归左右两边：三表 JOIN 的实际结构是 ((A join B) join C) ——
     * 左边本身又是一个 JOIN。只取一层会漏表。
     * ⚠️ 遇到不认识的 TableSource 类型（如 SQLValuesTableSource）不要静默忽略 ——
     * 至少 log.warn 记一笔，否则新增的 SQL 结构会静默绕过白名单。
     */
    private void collectTables(SQLTableSource source, Set<String> out)
    {
        // 递归
        if (source == null){
            return;
        }
        if (source instanceof SQLExprTableSource t){
            // 单表: 递归的终点，真正干活的地方
            SQLName n = t.getName();  // 返回 SQLName 不是 String
            if (n != null){
                out.add(normalizeTableName(n.getSimpleName()));
            }
        } else if (source instanceof SQLJoinTableSource j) {
            // 中间层:JOIN 只是个"壳"，自己没表名，拆成左右两个子问题
            collectTables(j.getLeft(), out);
            collectTables(j.getRight(), out);
        } else{
            // 兜底:不认识的类型别静默放过 —— 否则新 SQL 结构会绕过白名单
            log.warn("未识别的 TableSource，可能漏检表名：{}", source.getClass().getName());
        }
    }

    /**
     * 表名归一化：把模型可能输出的各种写法统一成本类比对用的形态。
     *   带反引号      `tb_dish`          → tb_dish
     *   带库名前缀    `ry-vue`.tb_dish   → tb_dish
     *   大写          TB_DISH            → tb_dish
     *  不做这一步，白名单形同虚设 —— 模型只要把表名写成大写或加个反引号，
     * 字符串比对就放过去了。这是"看着做了校验、其实没做"的典型。
     */
    private String normalizeTableName(String raw)
    {
        // 1. 防御
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 2. 去所有反引号 + 去首尾空白
        String s = raw.replace("`", "").strip();
        // 3. 有库名前缀就取最后一段
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        // 4. 转小写
        return s.toLowerCase(Locale.ROOT);
    }

    // ==================================================================
    // 第 3 步：危险模式
    // ==================================================================

    /**
     * 判断 from 树里有没有子查询。
     * 为什么要拦：select * from (select ... from sys_user) t ——
     * 外层表名是别名 t，看着人畜无害，真正的越权表藏在子查询里。
     * 用白名单校验外层根本挡不住，所以必须单独拦子查询。
     * ⚠️ 同样要递归 JOIN 的左右。
     */
    private boolean hasSubquery(SQLTableSource source)
    {
        if (source == null) {
            return false;   // 终止：空
        }
        if (source instanceof SQLSubqueryTableSource) {
            return true;  // 命中：就是它
        }
        if (source instanceof SQLJoinTableSource j) {
            return hasSubquery(j.getLeft()) || hasSubquery(j.getRight()); // 递归：左右都要看
        }
        return false;  // 其它类型：不是子查询
    }

    /**
     * 判断原始 SQL 里有没有注释符（双减号 / 斜杠星号 / 井号）。
     *  为什么吃的是原始字符串而不是 AST：Druid 解析时会把注释丢掉 ——
     * 在 select 和列名之间插一段注释，解析完 AST 里已经找不到那段注释了。
     * 所以注释检测只能回到原始字符串做。这也正是为什么
     * "先 parse、再判类型" 能免疫"把注释塞进关键词中间"这类绕法：
     * Druid 能正确解析出 UNION，类型判定天然生效。
     * ⚠️ 已知局限（要写进注释别隐瞒）：字符串字面量里的双减号会误判，
     * 例如 where name = 'a--b'。简单版先接受这个误判 ——
     * 方向是 fail-closed（宁可错杀），比漏放一个注入安全。
     */
    private boolean hasSqlComment(String rawSql)
    {
        if (rawSql == null){
            return false; // 终止：空
        }
        return rawSql.contains("--") || rawSql.contains("/*") || rawSql.contains("#"); // 命中：就是它
    }

    // ==================================================================
    // 第 4 步：重写
    // ==================================================================

    /**
     * 没有 LIMIT 就补一个，防止模型生成无上限的大结果集。
     * 用 AST 改（setLimit）而不是字符串拼接 ——
     * 字符串拼在 SQL 已带 order by 或结尾分号时会拼出语法错。
     */
    private void appendLimit(SQLSelectQueryBlock block)
    {
        if (block.getLimit() == null) {
            block.setLimit(new SQLLimit(new SQLIntegerExpr(RedisKeys.NL2SQL_MAX_ROWS)));
        }
    }

    /**
     * 按 userId 补数据范围条件 —— 横向越权的防线。
     * 本期的诚实状态：这个方法大概率是空实现，但要留着并写明原因。
     * 当前白名单三张表（tb_dish / tb_dish_flavor / tb_course）没有"归属人"字段 ——
     * 菜品是全店共享的，不存在"商户 A 不能看商户 B 的菜"这个面，所以谈不上横向越权。
     * ⚠️ 但方法不能删：一旦白名单里加进带归属字段的表（例如按门店隔离的库存表），
     * 这里就是补条件的唯一落点。注释里必须写明"当前无归属字段故为空"，
     * 否则后来人会以为作者忘了做越权 —— 空实现和"忘了做"必须能区分开。
     * 参考实现思路（对齐 Day5 的 DataScope）：从 AST 的 where 上拼一个
     * and owner_id = #{userId} 之类的条件。
     */
    private void appendDataScope(SQLSelectQueryBlock block, Long userId)
    {
        // TODO 当前阶段：留空实现 + 上面的说明注释
        //      （接口里 getAllowedTables 的竖切白名单已经是本期的纵向越权防线）
    }
}
