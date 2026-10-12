# 餐饮商家智能经营助手 · 前端

给餐饮商家用的管理界面，对接同仓库 `ruoyi-merchant` 分支的后端。

> 这个分支只放前端。配套后端在 [`ruoyi-merchant` 分支](https://github.com/Licories-12138/merchant-ai-assistant/tree/ruoyi-merchant)
> （JDK 17 + Spring Boot 4.1.0）。
> 另有 [`backend` 分支](https://github.com/Licories-12138/merchant-ai-assistant/tree/backend)（课程管理模块），和这套界面不配套。

## 用了什么

- Vue 3.4 + Element Plus 2.4 + Vite 5
- Pinia 管状态，vue-router 4 管路由
- axios 在 `src/utils/request.js` 做了统一封装：自动带 token、统一错误提示

## 解决什么问题

框架给的是通用后台：用户、角色、菜单这些系统管理。商家要的是业务界面 ——
菜品怎么上架、价格怎么改、有没有在卖。

要做的就两件：把通用后台的皮换成商家的，再给业务数据接上真实的后端接口。

## 实现了什么

- **品牌替换**：标题、logo、登录背景、favicon 全部换掉
  （`.env.development`、`.env.production`、`public/`）
- **菜品管理模块**：列表页 `src/views/merchant/dish/index.vue`，接口封装在
  `src/api/merchant/dish.js`，对接后端 `/merchant/dish`，能看价格与售卖状态并直接改

## 怎么跑

```bash
npm install
npm run dev
```

接口代理指向 `http://localhost:8080`，需要先把 `ruoyi-merchant` 分支的后端跑起来。

---

本项目基于 MIT 许可的开源后台框架构建，原始版权声明见 `LICENSE`。
