# SuperWork 前端迁移工程

这是从现有 Vue 前端拆出的独立 React 工程，基于 Ant Design Pro 6.0.3、Ant Design 6、ProComponents 与 Ant Design X 依赖体系创建。

## 迁移边界

- 只改前端的结构、布局、交互和视觉表达。
- 不修改后端服务、数据库、接口路径、权限语义和业务状态流转。
- 旧 Vue 工程继续作为生产兜底；本工程只注册已经完成真实接口接入的路由，不用示例页面占位。
- 菜单按业务域分组，不把所有页面平铺：工作台、基础分类、销售管理、系统管理、AI 与协作、数据分析六个父级。
- 迁移完成前不切换生产入口，避免出现功能少了或无意增加功能的情况。

## 当前已迁移

| 路由 | 功能 | 真实接口 | 迁移状态 |
| --- | --- | --- | --- |
| `/user/login` | 登录、记住账号、错误提示 | `POST /api/auth/login` | 已验证 |
| `/workbench` | 需求状态概览、最近需求、风险提醒 | `GET /api/requirements` | 已验证 |
| `/requirements`、`/requirements/:id`、`/requirements-standalone/:id` | 筛选、分页、卡片/表格、详情深链接、生命周期、评估、设计规划、关联任务、交付验收 | `GET/POST/PUT/DELETE /api/requirements/*` | 已验证 |
| `/tasks` | 任务检索、项目/负责人分组、执行分析、创建、详情和本地状态推进 | `GET/POST/PUT /api/tasks/*` | 已验证 |
| `/defects` | 缺陷检索、状态摘要、分页、看板/列表、结构分析和只读详情 | `GET /api/defects/overview`、`GET /api/defects/:id` | 已验证 |
| `/weekly-report` | 周报历史、事实采集、编辑、生成、确认、发布与同步 | `/api/weekly-reports/*` | 已验证 |
| `/business-lines` | 业务线筛选、状态、增删改 | `/api/business-lines/*` | 已验证 |
| `/projects` | 按业务线的项目/子项目、负责人、成员、相关邮件 | `/api/projects/*`、`/api/project-members/*`、`/api/emails/messages` | 已验证 |
| `/customers` | 客户联系人筛选、状态、增删改 | `/api/customer-contacts/*` | 已验证 |
| `/opportunities` | 线索商机筛选（含负责人/金额区间）、五类概览、列表/看板、跟进、售前支持工时 | `/api/sales-opportunities/*` | 已验证 |
| `/quotation-policies` | 策略筛选、版本状态、发布归档、报价明细维护 | `/api/quotation-policies/*` | 已验证 |
| `/quotations`、`/quotations/:id` | 报价单筛选、详情、导出、状态流转、策略生成向导 | `/api/quotations/*`、`/api/quotation-policies/*` | 已验证 |
| `/emails` | 邮箱绑定、同步、每日摘要、项目/公司分组、纯文本详情、AI 解读 | `/api/emails/*` | 已验证 |
| `/statistics` | BU 驾驶舱、容量与工时明细 | `/api/bu-dashboard` | 已验证 |
| `/revenue` | 营收矩阵、待处理工时/成本、营收估算 | `/api/revenue/*` | 已验证 |
| `/revenue/worktime`、`/revenue/delivery`、`/revenue/import`、`/revenue/pending` | 营收四个旧版深链接分栏（工时成本、交付利润、数据导入、待映射） | `/api/revenue/*` | 已映射，入口不平铺 |
| `/kpi-report` | KPI 年度报告、目标、周快照 | `/api/kpi/*` | 已验证 |
| `/key-matters`、`/key-matters-meeting` | 大事儿登记与会议视图、运行时权限 | `/api/key-matters/*` | 已验证路由与权限态 |
| `/system/users`、`/system/roles`、`/system/menus`、`/system/workflow`、`/system/configs` | 系统用户、角色、菜单、工作流、配置 | `/api/system/*`、`/api/workflow-configs/*` | 已验证 |
| `/system/connectors`、`/ai-assistant` | 连接器管理（内置 + 通用连接器配置/测试/跳转）、会话、模型与流式对话 | `/api/connectors/*`、`/api/ai-agent/*` | 已验证 |

需求、任务、缺陷页面均保留旧页面已有的读写边界：本地需求/任务允许原有创建、状态推进和关联动作，云效记录保持只读，缺陷详情保持只读。周报保留旧页面已有的保存、生成、确认、语雀发布、汇总表回填标记和企微推送动作。邮件凭据不回显，正文采用纯文本安全阅读；大事儿权限由后端运行时能力接口决定。所有新入口都按业务域分组，不平铺。

## 本地运行

```bash
npm ci
npm run dev
```

开发代理配置在 `config/proxy.ts`，当前指向家中网络可达的生产地址 `http://100.85.67.82:18080`（原内网地址 `192.168.1.241:18080` 作为环境基线保留在迁移记录中）。正式部署时需要由反向代理或同源网关提供 `/api` 转发，Umi 本地代理不会进入生产构建。

## 验证

```bash
npm run tsc
npm run build
```

当前已通过 TypeScript 检查、正式构建和浏览器真实访问验证。生产切换条件仍是：完成全量路由/权限/接口/操作闭环对照、回归测试和发布窗口验收。
