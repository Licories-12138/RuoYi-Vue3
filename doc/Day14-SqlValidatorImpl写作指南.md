# SqlValidatorImpl 写作指南

> 目标文件：`sky-merchant/src/main/java/com/sky/merchant/nl2sql/impl/SqlValidatorImpl.java`
> 接口定义见同目录 `SqlValidator.java`（那 4 步的 Javadoc 就是需求）。
> 本文所有 API 路径**已从 `druid-1.2.1.jar` 反查核实**，可以直接抄。

---

## 零、为什么先写这个类

整条 NL2SQL 链路里，**只有它是纯逻辑**：

| 类 | 依赖 | 能单测吗 |
|---|---|---|
| `SqlValidatorImpl` | 只依赖 Druid（纯内存计算） | ✅ **能** —— 给字符串就出结果 |
| `DbSchemaMetadataProvider` | 要连 MySQL | ❌ |
| `Nl2SqlServiceImpl` | 要连 MySQL + 调模型 | ❌ |

所以它是**唯一能拿一堆恶意 SQL 做严格验证**的部分。先写它，后面两个才能放心接。

---

## 一、Druid AST 怎么走（核心路径，已核实）

### 1.1 入口：字符串 → AST

```java
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;

// ⚠️ 必须是 parseSingleStatement，不能是 parseStatements
SQLStatement stmt = SQLUtils.parseSingleStatement(sql, DbType.mysql);
```

🔴 **变量作用域陷阱（实测踩过）**：如果把声明**放在 try 块里面**，出了 try 就用不了：

```java
// ❌ 编译不过：找不到符号 stmt
try {
    SQLStatement stmt = SQLUtils.parseSingleStatement(sql, DbType.mysql);   // 作用域仅限 try 块
} catch (Exception e) {
    return ValidationResult.fail(Reason.PARSE_ERROR, "SQL 解析失败");
}
if (!(stmt instanceof SQLSelectStatement s)) { ... }    // ← 这里访问不到 stmt

// ✅ 正确：声明在 try 外面，赋值在 try 里面
SQLStatement stmt;
try {
    stmt = SQLUtils.parseSingleStatement(sql, DbType.mysql);
} catch (Exception e) {
    return ValidationResult.fail(Reason.PARSE_ERROR, "SQL 解析失败");
}
if (!(stmt instanceof SQLSelectStatement s)) { ... }    // ✅ 能用
```

为什么"声明在外、赋值在内"编译器不会报"可能未初始化"：
因为 catch 分支**一定 return**，正常往下走的路径上 `stmt` 必然已被赋值 ——
Java 的**确定赋值分析（definite assignment）**能识别这一点。

| 方法 | 返回 | 该不该用 |
|---|---|---|
| `parseSingleStatement(sql, dbType)` | `SQLStatement`（**单条**） | ✅ **用这个** |
| `parseStatements(sql, dbType)` | `List<SQLStatement>`（**多条**） | ⛔ **不要用** |

**为什么这条是硬性的**：`parseStatements` 会把 `select 1; drop table tb_dish` 解析成**两个** statement，
你只校验第一个就执行 —— 等于给多语句注入直接开门。**用单条解析，多语句直接抛异常。**

⚠️ `DbType` 在 `com.alibaba.druid.DbType`（不在 `sql` 包下）。
⚠️ `SQLUtils` 在 `com.alibaba.druid.sql.SQLUtils`（不在 `ast` 包下）—— 容易找错。

### 1.2 判断是不是 SELECT

```java
if (!(stmt instanceof SQLSelectStatement selectStmt)) {
    return ValidationResult.fail(Reason.NOT_SELECT, "只支持查询语句");
}
```

用 `instanceof` 模式匹配 —— **已确认工程是 Java 17**（根 `pom.xml:19` `<java.version>17</java.version>`），可以直接写。
**这一步把 `SQLUpdateStatement` / `SQLDeleteStatement` / `SQLDropTableStatement` 全挡在外面。**

### 1.3 取查询块

⚠️ **这里是本指南唯一需要中转一跳的地方**（javap 核实过的真实签名）：

```
SQLSelectStatement.getSelect()   -> SQLSelect          ← 注意：不是 SQLSelectQuery！
SQLSelect.getQuery()             -> SQLSelectQuery     ← 真正的 SQLSelectQuery
SQLSelect.getQueryBlock()        -> SQLSelectQueryBlock（便捷方法）
```

```java
SQLSelect select = selectStmt.getSelect();   // SQLSelect
SQLSelectQuery query = select.getQuery();    // 再取一层
```

（import：`com.alibaba.druid.sql.ast.statement.SQLSelect`）

返回的 `SQLSelectQuery` 有**两种可能**：

```
SQLSelectStatement.getSelect()  →  SQLSelect.getQuery()
   ├── SQLSelectQueryBlock        ← 普通 select（包含 from / where / limit）
   └── SQLUnionQuery              ← union 查询（包含 left / right 两个子查询）
```

**这正好是第 3 步要查的东西** —— 如果解析出来是 `SQLUnionQuery`，直接判定危险模式。

### 1.4 取表名（第 2 步的核心）

`SQLSelectQueryBlock.getFrom()` 返回的是**一棵 TableSource 树**，不是扁平列表：

```
getFrom()  ->  SQLTableSource（接口）
   ├── SQLExprTableSource        → 单个表      getName() 返回 SQLName，不是 String！
   ├── SQLJoinTableSource        → JOIN       getLeft() / getRight() / getCondition()
   ├── SQLSubqueryTableSource    → 子查询     getSelect() 返回 SQLSelect
   └── (可能嵌套，JOIN 的 left 又是一个 JOIN)
```

⚠️ **`SQLExprTableSource.getName()` 返回的是 `SQLName`（接口），不是 `String`** ——
直接当成字符串用会编译不过。取字符串要用：

```java
SQLName name = t.getName();
String raw = (name == null) ? null : name.getSimpleName();   // SQLName.getSimpleName() -> String
```

（`SQLName` 在 `com.alibaba.druid.sql.ast.SQLName`；`getSimpleName()` 已核实存在）

**所以要写递归**：

```java
private void collectTables(SQLTableSource source, Set<String> out) {
    if (source == null) return;
    if (source instanceof SQLExprTableSource t) {
        SQLName n = t.getName();
        if (n != null) {
            out.add(normalizeTableName(n.getSimpleName()));   // 表名可能带库名/反引号，要归一化
        }
    } else if (source instanceof SQLJoinTableSource j) {
        collectTables(j.getLeft(), out);          // 递归左
        collectTables(j.getRight(), out);         // 递归右
    } else if (source instanceof SQLSubqueryTableSource s) {
        // 子查询 → 危险模式，可直接标记（或递归进去继续收集）
        markForbidden(Reason.FORBIDDEN_PATTERN);
    }
    // 还有 SQLUnionQueryTableSource / SQLValuesTableSource 等，至少加个日志
}
```

⚠️ **`SQLJoinTableSource` 必须递归** —— 三表 JOIN 是 `((A join B) join C)`，左边还是个 JOIN。

### 1.5 归一化表名（**这步最容易漏，会漏判**）

模型可能输出这几种写法，**都指向同一张表**：

| 模型输出 | 期望比对 |
|---|---|
| `` `tb_dish` `` | `tb_dish` |
| `tb_dish` | `tb_dish` |
| `` `ry-vue`.tb_dish `` | `tb_dish` |
| `ry-vue.tb_dish` | `tb_dish` |

**归一化 = 去掉反引号 → 有 `.` 就取最后一段 → 转小写**。

⚠️ **不做归一化 = 白名单形同虚设** —— 模型写 `` `Tb_Dish` `` 你就放过去了。这是最典型的"看着做了其实没做"。

---

## 二、四步的具体判断条件

### 第 1 步 · 语句类型
```java
stmt instanceof SQLSelectStatement     // 否则 fail(NOT_SELECT)
```

### 第 2 步 · 表白名单
```java
Set<String> tables = collect(src);                       // 递归收集 + 归一化
for (String t : tables) {
    if (!allowedTables.contains(t)) {
        return ValidationResult.fail(TABLE_NOT_ALLOWED, "涉及不开放的表：" + t);
    }
}
```
`allowedTables` 从 `SchemaMetadataProvider.getAllowedTables()` 注入 —— **别在这里硬编码**。

**为什么必须白名单而不是黑名单**：库里有 34 张表，你只想开 3 张。
**黑名单要挡 31 张，漏一个就出事；白名单只要能数到 3 张。**

### 第 3 步 · 危险模式

在 AST 上查，**不要在字符串上做正则**：

| 危险 | AST 上怎么查 |
|---|---|
| UNION | `select.getQuery() instanceof SQLUnionQuery`（注意先过 `getSelect()`） |
| 子查询 | from 树里出现 `SQLSubqueryTableSource`；或 select 列表/where 里嵌 `SQLSelectStatement` |
| 注释 | Druid 解析**会丢掉**注释，所以要**在原始字符串上**查 `--`、`/**/`、`#`（⚠️ 这是唯一要用字符串的地方） |
| 多语句 | `parseSingleStatement` 自己会抛异常 —— 用 try-catch 转成 `PARSE_ERROR` |

⚠️ **"注释在 AST 上查不到"是个反直觉的点**：Druid 把 `select /*x*/ 1` 解析后注释就没了。
所以注释检测**必须回到原始 SQL 字符串**。这也解释了为什么 `UN/**/ION` 能绕正则 ——
但 Druid **能正确解析** `UN/**/ION` 成为 UNION，所以**先解析再判类型，免疫这类绕过**。

### 第 4 步 · 重写

```java
// 4.1 补 LIMIT
if (queryBlock.getLimit() == null) {
    // 没有 limit → 加一个 NL2SQL_MAX_ROWS
    // ⚠️ 别自己拼字符串！用 AST 改完再 SQLUtils.toSQLString()
}
// 4.2 补数据范围条件（越权防线）
// 按 userId 追加 where 条件 —— 与 Day5 @DataScope 同一思路
```

⚠️ **重写要用 AST 而不是字符串拼接** —— 字符串拼 `sql + " limit 200"` 在 SQL 已带
`order by` 或分号时都会出错。Druid 有 `SQLSelectQueryBlock.setLimit()`（**已核实存在**），
改完用 `SQLUtils.toSQLString(stmt, DbType.mysql)` 输出。

**给 LIMIT 传值**：
```java
import com.alibaba.druid.sql.ast.SQLLimit;                        // ← 在 ast 包，不在 statement
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;             // ← 在 ast.expr 包
queryBlock.setLimit(new SQLLimit(new SQLIntegerExpr(RedisKeys.NL2SQL_MAX_ROWS)));
```
（两个包路径均已核实。⚠️ `SQLLimit` 容易找错到 `statement` 包 —— 实际在 `ast`。）

（若嫌麻烦，退一步也可以在**校验通过后**用字符串拼 —— 但那就必须保证 SQL 是单条且无分号，
且这个"退一步"要在注释里写明原因，别让后来人以为是随手写的。）

---

## 三、异常处理

```java
SQLStatement stmt;                                // ← 声明放 try 外面（否则后面用不了）
try {
    stmt = SQLUtils.parseSingleStatement(sql, DbType.mysql);
    ...
} catch (Exception e) {
    // 解析失败 = 模型输出垃圾 或 有人塞多语句
    log.warn("SQL 解析失败: {}", sql, e);
    return ValidationResult.fail(Reason.PARSE_ERROR, "SQL 解析失败");
}
```

⚠️ **不要把 `e.getMessage()` 直接返回给前端** —— 可能泄露库表结构信息。日志里记全，返回给前端记简略。

---

## 四、单测清单（恶意 SQL，写完拿这个验）

**能挡住的（应返回 fail）**：

```sql
select 1; drop table tb_dish                              -- 多语句
select * from sys_user                                    -- 越权表
select * from `ry-vue`.sys_user                           -- 带库名前缀
select password, salt from sys_user                       -- 越权表
UN/**/ION select id from tb_dish union select id from sys_user   -- 注释绕过
select * from tb_dish union select * from qrtz_triggers    -- UNION
select * from tb_dish where id in (select id from sys_user) -- 子查询
update tb_dish set price = 0                              -- DML
delete from tb_dish                                       -- DML
drop table tb_dish                                        -- DDL
select * from tb_dish, sys_user                           -- 笛卡尔积
```

**必须放过的（应返回 ok，且 SQL 被补上 LIMIT）**：

```sql
select id, name, price from tb_dish
select d.name, f.name from tb_dish d join tb_dish_flavor f on d.id = f.dish_id
select count(*) from tb_dish
select * from tb_dish where price > 30
select * from tb_course
```

⚠️ **测试里最容易漏的两个**：
1. `` `ry-vue`.sys_user `` —— 带库名前缀的越权表（不做归一化就漏了）
2. `((A join B) join C)` —— 三层 JOIN 递归（只处理一层就漏了）

---

## 五、写完自检

- [ ] 用的是 `parseSingleStatement` 而非 `parseStatements`
- [ ] `stmt` 声明在 **try 块外面**（否则 `找不到符号`）
- [ ] 取查询块走的是 **两跳**：`getSelect()` → `SQLSelect` → `getQuery()` → `SQLSelectQuery`
- [ ] 表名用 `getName().getSimpleName()` 拿字符串（`getName()` 返回的是 `SQLName` 不是 `String`）
- [ ] 表名做了归一化（反引号 + 库名前缀 + 大小写）
- [ ] JOIN 是递归收集的，不是只取一层
- [ ] 注释检测回到原始字符串（AST 里没有注释）
- [ ] 重写用 AST 不是字符串拼接
- [ ] 白名单来自 `SchemaMetadataProvider`，没有硬编码
- [ ] 解析异常没把原始 `e.getMessage()` 透给前端
- [ ] 单测跑过上面**全部** 11 条恶意 SQL + 5 条正常 SQL

---

## 附：编译命令

```bash
cd D:/code/sky
mvn -q -pl sky-merchant -am -o compile -DskipTests
```

⚠️ 首次可能因为本地没 `druid-1.2.28` 而失败（本地只有 1.2.1）——
去掉 `-o` 让它联网下载一次，之后就能离线编译了。
