# 外卖商家系统后端

给外卖商家用的管理后端。基于若依 RuoYi-Vue v3.9.2 二次开发，六个模块从 `ruoyi-*` 整体改名 `sky-*`，业务模块是新长的 `sky-merchant`。

> 配套前端在 [`main` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/main)。学若依时的练习产物在 [`backend` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/backend)（课程管理模块），和这套业务不配套。若依原版代码在 `master`。

## 用了什么

- JDK 17 + Spring Boot 4.1.0 + MyBatis + Redis
- Spring AI 2.0.1（`spring-ai-starter-model-openai`），模型走阿里云百炼 `qwen3.7-flash`，API Key 从环境变量 `DASHSCOPE_KEY` 读
- JWT 鉴权是若依自带的，默认密钥是公开值，已换成随机串

## 解决什么问题

查个菜品价格还要登录后台点界面，太重了。接入自然语言 Agent 后，老板直接说话就行。

但大模型只会生成文本，执行不了代码也拿不到业务数据，直接问"宫保鸡丁多少钱"它就编一个数。Function Calling 把这件事拆成两段：模型负责判断该调哪个方法、参数是什么；Spring AI 负责真正执行——`@Tool` 方法查真实的 `tb_dish` 表，结果喂回模型组织成人话。模型全程不碰代码也不碰数据库。

## 实现了什么

- **自然语言点单 Agent**：`GET /merchant/agent/chat?message=宫保鸡丁多少钱` → 「宫保鸡丁售价 28.00 元，目前在售」，停售菜品自动过滤
- **结构化输出**：`GET /merchant/agent/parse?message=来两份宫保鸡丁不要辣` → `{dishName, count, spicy, packed}`，未提及的字段返回 null 而不是瞎编。抽取和决策分成两个入口，`/parse` 不挂任何工具
- **Redis 缓存**：菜品查询 Cache Aside（先改库再删缓存，TTL 加随机抖动）；问答结果缓存，同一问题实测 **65.5 秒 → 2 秒**；ZSet 热榜记录模型最常查的菜名
- **观测接口**：`/merchant/dish/hot`（热搜菜名）、`/merchant/dish/cache/ttl`（缓存剩余时间）、`/merchant/agent/diagnose/tools`（Agent 挂载的工具清单）

## 怎么跑

导入 `sql/` 下脚本 → 改 `application-druid.yml` 的数据库连接 → 设环境变量 `DASHSCOPE_KEY` → 启动 `sky-admin` 的启动类 → 前端 `npm run dev`。

集成 Spring AI 踩的坑和排障流程记录在 `doc/` 下。

---

基于若依官方 [RuoYi-Vue v3.9.2](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，框架本身的功能介绍看官方仓库。
