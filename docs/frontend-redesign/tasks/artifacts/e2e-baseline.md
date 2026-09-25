# E2E 基线清单

当前基线来源：`frontend/tests/`，共 25 个 spec 文件。迁移阶段不删除旧用例，先建立新旧路由映射，再逐项迁移。

| 测试域 | 代表用例 | 保护内容 |
|---|---|---|
| 认证 | `auth-expired.spec.ts` | 401 清理登录态并跳转登录 |
| 权限 | `navigation-permissions.spec.ts`、`role-auth*.spec.ts` | 菜单和直达路由权限 |
| 需求 | `requirements-modal-ui.spec.ts`、`requirements-detail-data.spec.ts` | 字段、富文本安全、状态流转、详情 |
| 云效工作项 | `yunxiao-workitems.spec.ts` | 来源标签、去重、只读任务和缺陷 |
| 任务 | `tasks-create.spec.ts` | 创建、详情和状态更新 |
| 大事儿 | `key-matters*.spec.ts` | 个人范围、竞态、参与人、周会、响应式 |
| 营收 | `revenue-*.spec.ts`、`prod-h1h2.spec.ts` | 口径切换、汇总、成本、工时、导入 |
| 邮件 | `email-management.spec.ts` | 绑定、纯文本正文、同步、分组、摘要闭环 |
| 驾驶舱 | `bu-dashboard.spec.ts` | 负荷、工时、云效配置和敏感字段 |
| 系统管理 | `system-config-management.spec.ts`、`system-user-card.spec.ts`、`workflow-canvas.spec.ts` | 配置、用户、工作流画布 |
| 响应式 | `responsive-list-layouts.spec.ts`、`workitem-risk-visual.spec.ts` | 320/390/768/1024 等尺寸 |

## 迁移要求

1. 先复制测试意图，再替换定位器，不以删除断言解决迁移失败。
2. 新前端必须继续断言请求参数和最终状态。
3. 视觉差异要单独记录，不得与功能回归混为一谈。
4. 生产验证用例必须在真实后端可用后执行，Mock 通过不能替代生产验证。

