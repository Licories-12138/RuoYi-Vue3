# 若依学习分支：课程管理模块实践

学若依时留下的练习产物，完整跑了一遍「代码生成器生成 → 手写改造」的二次开发流程。

> 这个分支是学习笔记，业务和外卖无关。实战项目在 [`ruoyi-merchant` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/ruoyi-merchant)（外卖商家系统，接了 Spring AI），前端在 [`main` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/main)。
> 若依原版代码在 `master` 分支，未改动。

## 用了什么

- JDK 17 + Spring Boot 4.1.0 + MyBatis + Redis（若依单体版，未拆微服务）
- 若依自带的代码生成器、Quartz 定时任务模块

## 解决什么问题

看懂若依的分层和权限体系，跟自己动手做一个完整模块，是两回事。生成器能吐出 CRUD 代码，吐不出「为什么这么分」。

所以这个分支做的就是拿课程管理这个最普通的业务，把 Controller / Service / Mapper / XML 的完整链路亲手走一遍：生成器给的代码逐个拆开看，该改的改，该重写的重写。

## 实现了什么

- **课程管理模块**：完整 CRUD（Controller / Service / Mapper / XML），先生成后手改，位置 `ruoyi-admin` 下的 `course/` 包
- **Quartz 定时任务实践**：自定义任务 `MyTask`，在 `ruoyi-quartz` 的 `task/` 包
- **JWT 密钥加固**：官方默认密钥 `abcdefghijklmnopqrstuvwxyz` 是公开值，已换成随机串（`application.yml`）

## 怎么跑

导入 `sql/` 下脚本 → 改 `application-druid.yml` 的数据库连接 → 启动 `ruoyi-admin` 的 `RuoYiApplication`。

---

基于若依官方 [RuoYi-Vue v3.9.2](https://gitee.com/y_project/RuoYi-Vue)（MIT License）二次开发，框架本身的功能介绍看官方仓库。
