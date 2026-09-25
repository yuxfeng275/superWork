# 路由、领域与权限基线

来源：`frontend/src/router/index.ts`、`frontend/src/layouts/MainLayout.vue`、`.trellis/spec/frontend/`。

| 路由 | 领域 | 主要页面 | 权限/边界 | 迁移批次 |
|---|---|---|---|---|
| `/login` | 认证 | LoginView | 未登录可访问 | 批次 1 |
| `/` | 工作台 | HomeView | 登录后 | 批次 1 |
| `/requirements` | 研发协同 | RequirementsView | 登录后；来源区分 | 批次 2 |
| `/requirements/:id` | 研发协同 | 路由重定向 | 重定向到独立详情 | 批次 2 |
| `/requirements-standalone/:id` | 研发协同 | RequirementDetailView | 登录后；按 ID | 批次 2 |
| `/tasks` | 研发协同 | TasksView | 登录后；云效任务只读 | 批次 2 |
| `/defects` | 研发协同 | DefectsView | 登录后；只读云效缺陷 | 批次 2 |
| `/projects` | 基础分类 | ProjectView | project 角色 | 批次 2 |
| `/business-lines` | 基础分类 | BusinessLineView | 登录后 | 批次 3 |
| `/organization` | 基础分类 | 重定向 | 重定向到业务线 | 批次 3 |
| `/customers` | 客户 | CustomerInfoView | customer 角色 | 批次 4 |
| `/opportunities` | 销售 | OpportunityView | 登录后 | 批次 4 |
| `/quotation-policies` | 报价 | QuotationPolicyView | 登录后 | 批次 4 |
| `/quotations` | 报价 | QuotationView | 登录后 | 批次 4 |
| `/quotations/:id` | 报价 | QuoteDetailView | 登录后；按 ID | 批次 4 |
| `/key-matters` | 协同管理 | KeyMattersView | requiresKeyMatterAccess | 批次 3 |
| `/key-matters-meeting` | 协同管理 | KeyMattersView | 独立页 + requiresKeyMatterAccess | 批次 3 |
| `/weekly-report` | 周报 | WeeklyReportView | 登录后 | 批次 3 |
| `/emails` | 邮件 | EmailManagementView | 个人邮箱边界 | 批次 3 |
| `/ai-assistant` | AI | AiAssistantView | 登录后；流式会话 | 批次 5 |
| `/statistics` | 经营分析 | StatisticsView | management | 批次 3 |
| `/revenue` | 经营分析 | RevenueView | management | 批次 3 |
| `/kpi-report` | 经营分析 | KpiReportView | management | 批次 3 |
| `/system/users` | 系统管理 | SystemUserView | management | 批次 4 |
| `/system/roles` | 系统管理 | SystemRoleView | management | 批次 4 |
| `/system/menus` | 系统管理 | SystemMenuView | management | 批次 4 |
| `/system/workflow` | 系统管理 | SystemWorkflowView | management | 批次 4 |
| `/system/configs` | 系统管理 | SystemConfigView | management；敏感值不回显 | 批次 4 |
| `/ai-connectors` | 系统管理 | AiConnectorManageView | management；凭证不回显 | 批次 5 |

## 通用权限规则

- 菜单可见性不是后端授权的替代，后端仍是最终权限边界。
- 动态菜单树为空时保留岗位默认菜单回退逻辑。
- 路由权限和侧边栏权限必须保持一致。
- 新前端迁移时不得把只读云效对象转成可编辑对象。
