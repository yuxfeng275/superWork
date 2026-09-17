# 全系统质量审计台账（第一轮）

## 基线证据

- 后端 Maven：151 tests pass（Java 17），但控制器 33 个，仅 4 个 `@WebMvcTest`；服务约 50 个，约 21 个直接测试。
- 前端生产构建通过；全量 Playwright 61 项中 60 通过、1 项失败（大事儿本周进展回填竞态）。
- Playwright 配置没有 `webServer`，裸执行会产生全量 `ERR_CONNECTION_REFUSED`。
- 前端最大文件：`KeyMattersView.vue` 6626 行、`RequirementsView.vue` 3334、`StatisticsView.vue` 2229、`TasksView.vue` 2029、`RequirementDetailView.vue` 1926、`api.ts` 1317。
- `frontend/src` 约 154 处 `any`；`api.ts` 内约 82 处。
- 生产容器健康，但后台每 5–10 分钟持续产生 scheduled task ERROR。

## P0 问题

### P0-1 生产调度持续失败
- 证据：生产日志从 2026-08-18 下午到 2026-08-19 持续报 `服务器未配置云效令牌加密密钥`。
- 根因：`yunxiao_integration_config.encrypted_token` 存在且启用，但 `YUNXIAO_CONFIG_ENCRYPTION_KEY` 为空；`YunxiaoConfigService#getRuntimeConfig` 直接解密并向调度器抛异常。
- 影响：云效同步/需求交接后台闭环失效，日志噪音掩盖其他故障。
- 修复：安全降级状态 + 调度跳过 + 页面明确恢复指引；重新录入 Token 后恢复。

### P0-2 操作者身份可由请求体伪造
- 证据：`IssueService` 使用 `dto.createdBy`，`AttachmentService` 使用 `dto.uploadedBy`，`RequirementConfirmationService` 使用 `dto.confirmedBy`，`WorkLogService` 使用 `dto.userId`，设计工作记录使用 DTO designerId。
- 影响：审计记录、工时、附件、确认人可能冒用其他用户，破坏追溯和权限边界。
- 修复：控制器统一从 JWT `@RequestAttribute("userId")` 注入 actor，DTO 删除/忽略 actor 字段，服务强制覆盖，补越权回归。

### P0-3 核心 E2E 不稳定
- 证据：`key-matters.spec.ts`“台账可查看状态、详情并维护本周进展”预期已有内容但读取为空；全量并发执行可复现。
- 影响：周进展编辑可能存在异步回填竞态，测试不能作为发布门禁。
- 修复：定位加载竞态，修正表单初始化/请求等待，确保全量并发稳定运行。

### P0-4 服务健康不等于业务健康
- 证据：`/actuator/health` 为 UP，但调度持续失败；仅暴露基础 health。
- 影响：生产发布验证产生假阳性。
- 修复：增加集成/调度状态摘要、最近失败时间与页面可观察状态，发布冒烟检查业务健康。

## P1 问题

### P1-1 UI 设计系统碎片化
- 各页面独立定义 header、summary、filter、card、drawer、dialog、empty/error/loading；圆角从 6px 到 16px，间距、字体、阴影不一致。
- 通用 spec（component/state/type/quality）仍为 `To fill`。
- 多数页面没有统一 `:focus-visible` 和 `prefers-reduced-motion`。

### P1-2 巨型单文件与 API 单体
- 6 个核心页面 >1000 行；大事儿 6626 行，难以隔离修改与测试。
- `api.ts` 1317 行混合全域 API 与大量 `any`，跨域变更冲突高。

### P1-3 数据加载假全量
- `RequirementsView` 请求 projects/business lines `size:999`、users 100、overview 200；项目/客户/用户等多页使用 100/200/999 固定上限且无完整分页。
- 数据增长后下拉项、统计与列表静默缺失。

### P1-4 关键闭环覆盖不完整
- 致远 OA 同步代码仍有“TODO 实际写入本地数据库”。
- 缺陷主要是只读查询，没有统一的来源/闭环动作说明。
- 多个 actor/状态推进依赖前端控制，服务层契约不完整。
- 需求评估/设计/确认/任务/交付存在多套状态动作入口，需要统一状态机验证。

### P1-5 质量门禁缺失
- 前端没有 lint、独立 typecheck、unit/component tests 脚本。
- Playwright 不自启 server；测试产物策略不统一。
- JaCoCo 输出显示 execution data missing，覆盖率 check 实际未生效。
- 没有真实 MySQL/Testcontainers 迁移回归；多数测试使用 H2 且 Flyway disabled。

### P1-6 部署与敏感配置治理不足
- compose 中存在硬编码 MySQL/MinIO 开发口令；镜像标签未固定；Docker buildx 缺失警告。
- 生产副本与仓库并非 Git checkout，只靠 rsync 与标记文件，可追溯性有限。

## P2 问题

- Vite 主包约 1.07MB，持续产生 chunk >500KB 警告。
- 多个后端 service 300–700 行，异常大量使用 `RuntimeException`，错误码和日志语义不统一。
- 部分查询存在 N+1、内存聚合或同步外部调用；调度器缺少统一锁、重试与运行记录。
- README、部署文档、规格和真实生产路径存在历史差异。
- 无统一视觉截图基线和跨页面可访问性门禁。

## 执行顺序

1. P0 生产调度与业务健康
2. P0 actor 身份与权限边界
3. P0 核心流程竞态与测试稳定
4. 工程质量门禁与可复现环境
5. 前端设计系统与页面壳
6. API 客户端模块化、类型与分页基础
7. 需求/任务/缺陷/交付核心闭环
8. 项目/客户/商机/大事儿/邮件体验收敛
9. 驾驶舱、系统管理、云效/OA 集成闭环
10. 性能、可观测性、文档与全量生产回归
