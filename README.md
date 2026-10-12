# 餐饮商家智能经营助手 · 后端

给餐饮商家用的经营数据后端：**用中文提问，系统把它变成对数据库的查询并返回结果**。
在企业级权限框架之上开发，业务模块是新增的 `sky-merchant`。

> 配套前端在 [`main` 分支](https://github.com/Licories-12138/merchant-ai-assistant/tree/main)；
> 另有 `backend` 分支（权限框架的课程管理练习，与本套业务不配套）。

## 用了什么

- JDK 17 + Spring Boot 4.1.0 + MyBatis + MySQL + Redis
- Spring AI 2.0.1（`spring-ai-starter-model-openai`），模型走阿里云百炼 `qwen3.7-flash`，
  API Key 从环境变量 `DASHSCOPE_KEY` 读
- **Druid SQL Parser** —— 把 SQL 解析成 AST 做结构化校验（随框架传递依赖，未新增依赖）
- JWT 鉴权来自框架，默认密钥是公开值，已换成随机串

## 解决什么问题

查个菜品价格还要登录后台点界面，太重。接上自然语言之后，直接说话就行。

但大模型只会生成文本 —— 它执行不了代码，也拿不到业务数据。直接问「宫保鸡丁多少钱」，
它会编一个数给你。所以这里做了**两条查询路线**，各自的安全边界完全不同。

## 两条查询路线

**路线 A · 工具调用**
模型只判断「该调哪个方法、参数是什么」，Spring AI 负责真正执行。`@Tool` 方法查真实的
`tb_dish` 表，结果喂回模型组织成人话。**模型全程不碰代码、不碰数据库** ——
注入面在构造上就不存在。

**路线 B · NL2SQL**
模型直接生成 SQL。灵活度更高（没预定义过的问题也能问），但模型输出不可信，
所以**安全边界必须放在执行前**：用 Druid 解析成 AST，过四层校验才交给执行器。

| 层 | 拦什么 |
| --- | --- |
| ① 语句类型 | 只放行 SELECT |
| ② 表级白名单 | 库里 34 张表只开放 3 张业务表 |
| ③ 危险模式 | UNION / 子查询 / 注释符 |
| ④ 列级 | 禁止 `SELECT *`，且列必须在白名单内 |

> **核心立场：模型是建议者，不是执行者。**
> 它被 prompt 注入绕过了也没关系 —— 只要 AST 校验不放过，SQL 就执行不了。

## 接口

| 端点 | 说明 |
| --- | --- |
| `GET /merchant/agent/chat?message=宫保鸡丁多少钱` | 自然语言问答（工具调用路线） |
| `GET /merchant/agent/parse?message=来两份宫保鸡丁不要辣` | 结构化抽取 → `{dishName, count, spicy, packed}`，未提及的字段返回 null 而不是瞎编 |
| `GET /merchant/nl2sql/query?question=价格超过30的菜有哪些` | 自然语言 → SQL → 校验 → 执行（NL2SQL 路线） |

抽取与决策分成两个入口，`/parse` 不挂任何工具 —— 避免模型在纯抽取任务里误触发查库。
（曾实测到该场景耗时 81 秒。）

## 缓存与并发（实测结论）

- **缓存三件套**：布隆过滤器（按 1% 误判率反推位图约 95.8 万位）+ 空值标记防穿透；
  互斥锁 / 逻辑过期防击穿；TTL 抖动防雪崩。
  实测 20 并发打同一热点 key：无保护时 20 次请求**全部落库**（同一毫秒），加锁后**降为 1 次**。
- **写一致性**：发现「删缓存写在 `@Transactional` 方法内」会在**事务提交前**执行，
  并发读会把未提交的旧值固化进缓存；改用事务提交后回调（`afterCommit`）删除，并用回滚实验验证。
- **并发扣减对照压测**：20 并发下无保护产生 **17 份超卖**，分布式锁与条件原子更新均为 0，
  版本号乐观锁因冲突只成功 6 次。
  结论：**单行热点扣减用条件原子 UPDATE，不用乐观锁**（乐观锁的前提是冲突少，而扣库存是同行高频冲突，
  且 `stock >= n` 已经完整表达了约束）。

## 测试

```bash
mvn -pl sky-merchant -am test     # 18 个单测，覆盖安全校验器的全部规则
```

其中两条是回归用例。一条针对**藏在 WHERE 里的子查询**：

```sql
select id from tb_dish where id in (select id from sys_user)
```

最初的实现只检查 FROM 子树，这条 SQL 会被放行并**真的执行**；改用 AST visitor 遍历整条语句
（覆盖 `SQLSubqueryTableSource` / `SQLInSubQueryExpr` / `SQLQueryExpr` / `SQLExistsExpr` 四类节点）后修复，
并补上了用例。

## 文档

`doc/` 下是设计文档与验收清单（Day9~14）：缓存三件套、Redisson 分布式锁、双写一致性、
NL2SQL 方案设计、安全校验器写作指南。

## 怎么跑

1. 建库 `ry-vue`，导入 `sql/` 下脚本
2. 启动 Redis
3. 设环境变量 `DASHSCOPE_KEY`
4. 启动 `sky-admin` 的启动类；前端 `npm run dev`

---

本项目基于 MIT 许可的开源后台框架构建，原始版权声明见 `LICENSE`。
