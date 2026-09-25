# T02 React + Ant Design Pro 工程初始化

## 目标

建立与旧 Vue 应用并行运行的 React 工程，不接入具体业务页面，只完成可部署骨架。

## 依赖

- T01 已冻结路由、鉴权和 API 边界。

## 工作项

1. 初始化 React 19、Umi Max 4、Ant Design 6、ProComponents 3。
2. 锁定 Ant Design Pro、Ant Design X 和 React Query 的兼容版本。
3. 配置 TypeScript、路径别名、环境变量和构建输出目录。
4. 配置独立开发端口和反向代理，不能覆盖旧 Vue 的 5175。
5. 建立 `services/http`、`services/auth`、`types/contracts` 目录。
6. 增加基础 lint、typecheck、build 和 Playwright 命令。
7. 配置按路由分包和生产 source map 策略。
8. 添加最小健康页和错误页。

## 验收标准

- 新工程本地可启动、可构建、可预览。
- 旧工程继续可启动，不产生端口和产物覆盖。
- 新工程有独立 CI 命令。
- 首屏只加载 Shell，不加载全部业务域。

## 失败返回条件

- 依赖版本无法形成稳定兼容矩阵时，不进入页面迁移，先固定版本。

