# 汉韵镜前端

Vue 3 / TypeScript / Vite / Pinia / Axios，完整说明以根目录README为准。

- npm run dev：前台5173，代理8082。
- npm run dev:admin：独立后台5174。
- npm run build / npm run build:admin：类型检查与分别构建。
- node scripts/verify-catalogue-ui.mjs：Vue编译器及非浏览器渲染器交互检查。

/guide 提供预算、尺码、颜色和数量约束，显示可买SKU与史料，二次确认后加购。/reception 显示持久选购记录及商家回复。后台新增AI接待与待办、执行步骤、指标及脱敏导出，独立评测不算用户接待。

前后台分别登录和构建。业务数据保存在SQL，对话草稿仍为页面内存。试穿以服务端实际结果为准，历史示例区别于实时生成。
