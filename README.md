# 外卖管理系统前端

给外卖商家用的管理界面。基于若依 RuoYi-Vue3（Vue3 版）二次开发。

> 这个分支只放前端。配套后端在同仓库的 [`ruoyi-merchant` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/ruoyi-merchant)（JDK 17 + Spring Boot 4.1.0）。
> 另有一个 [`backend` 分支](https://github.com/Licories-12138/RuoYi-Vue3/tree/backend)是学若依时的练习产物（课程管理模块），和这套界面不配套。

## 用了什么

- Vue 3.4 + Element Plus 2.4 + Vite 5
- Pinia 管状态，vue-router 4 管路由
- axios 在 `src/utils/request.js` 里做了统一封装：自动带 token、统一错误提示（若依自带）

## 解决什么问题

若依给的是通用后台：用户、角色、菜单这些系统管理。商家要的是业务界面——菜品怎么上架、价格怎么改、卖没卖停。

所以要做的事就两件：把"若依管理系统"这张皮换成外卖商家的，再给业务数据接上真实的后端接口。

## 实现了什么

- **品牌替换**：标题改成"外卖管理系统"，logo、登录背景、favicon 全部换掉（`.env.development`、`.env.production`、`public/`）
- **菜品管理模块**：商家端菜品列表页 `src/views/merchant/dish/index.vue`，接口封装在 `src/api/merchant/dish.js`，对接后端的 `/merchant/dish`，能看价格、售卖状态，直接改

## 怎么跑

```bash
npm install
npm run dev
```

接口代理指向 `http://localhost:8080`，需要先把 `ruoyi-merchant` 分支的后端跑起来。

---

基于若依官方 [RuoYi-Vue3](https://gitcode.com/yangzongzhuan/RuoYi-Vue3)（MIT License）二次开发，框架本身的功能介绍看官方仓库。
