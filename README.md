> ## 📌 本分支说明（二次开发记录）
>
> 这是 **`RuoYi-Vue3` 仓库的后端分支（ruoyi-merchant）**，外卖商家系统后端，配套前端在 [`main` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/main)。
> 基于若依官方 `RuoYi-Vue v3.9.2` 二次开发，后端 JDK 17 + Spring Boot 4.1.0 + MyBatis。
>
> **我在官方基础上做的改造：**
>
> | 改造 | 说明 |
> |---|---|
> | 模块命名重构 | 若依 `ruoyi-*` 六模块全面改名 `sky-*`（admin / common / framework / generator / quartz / system） |
> | 新增商家模块 | `sky-merchant`，外卖商家业务 |
> | 自然语言点单 Agent | 接入 Spring AI，`@Tool` 查真实 `tb_dish` 表，老板用自然语言查菜品 |
> | JWT 密钥加固 | 官方默认密钥 `abcdefghijklmnopqrstuvwxyz` 为公开值，已替换为随机串 |
>
> **自然语言点单 Agent**（核心亮点）：
>
> `sky-merchant` 接入 Spring AI 2.0.1 + 阿里云百炼 `qwen3.7-flash`，商家老板直接说人话查菜品：
>
> ```
> GET /merchant/agent/chat?message=宫保鸡丁多少钱
> → 宫保鸡丁售价 28.00 元，目前在售。
> ```
>
> 实现方式：`DishTools` 里两个 `@Tool` 方法（按菜名模糊查 / 查在售菜品），内部调 `DishMapper` 查真实 MySQL。模型不执行代码，只返回"调哪个方法、传什么参数"，由 Spring AI 真正查库、把结果喂回模型组织成人话。停售菜品（`status=1`）会被正确过滤掉。
>
> **结构化输出：把人话转成 Java 对象**
>
> `/chat` 返回一句人话，程序拿不到字段。点单需要结构化数据，所以另开一个入口做纯抽取：
>
> ```
> GET /merchant/agent/parse?message=来两份宫保鸡丁不要辣
> → {"dishName":"宫保鸡丁","count":2,"spicy":false,"packed":null}
> ```
>
> 实现：`entity(OrderRequest.class)`，Spring AI 反射 DTO 生成 JSON Schema 塞进 prompt，模型按格式回答后自动转成 Java 对象。字段语义用 `@JsonPropertyDescription` 标注；`spicy` / `packed` 用 `Boolean` 包装类型，未提及时模型返回 null，才区分得开"没说"和"明确不要"。
>
> 抽取和决策刻意分开，parse 用的 `parseClient` 不挂任何工具。像"随便来个菜"这种没法提取的话，模型返回四个 null 而不替用户编一道菜，这类请求应该走 `/chat` 的 Agent 链路（查真实菜单后再决定）。
>
> 试过 `useProviderStructuredOutput()` 走模型原生 JSON Schema：`qwen3.7-flash` 不在百炼严格模式支持名单里（仅 qwen3.7-Plus / Max、qwen3.8-Max），请求返回 200 但字段全部丢失，已改回默认的 prompt 注入路线。
>
> 学习起步的最小 demo 见 [spring-ai-order-agent](https://github.com/Licories-12138/spring-ai-order-agent)。
>
> **本地启动**：导入 `sql/` 下两个脚本 → 改 `application-druid.yml` 的数据库连接 → 设环境变量 `DASHSCOPE_KEY` → 启动 `sky-admin` 的启动类 → 前端 `npm run dev`（代理指向 `localhost:8080`）
>
> ---

> ### 本仓库三分支的配套关系
>
> | 分支 | 内容 | 说明 |
> |---|---|---|
> | `main` | 外卖管理系统**前端** | 配套 `ruoyi-merchant` |
> | `ruoyi-merchant` | 外卖商家系统**后端**（`sky-*` 模块） | 本分支，与 `main` 配套 |
> | `backend` | 若依原版 + 课程管理模块 | 学习笔记，独立存在，不与前端配套 |


---

> 本项目基于若依官方 [RuoYi-Vue v3.9.2](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，框架本身的功能介绍、在线体验、演示图请看官方仓库。
