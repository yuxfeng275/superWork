# 数据流统一改造设计（data-flow）

> 分支：`data-flow-be`（后端）/ `data-flow`（前端）
> 目标：销售、工时、合同等外部数据彻底拉齐 —— 定时 + 手动从工时系统、OA 系统拉取，
> 业务功能基于拉取到的数据联动变化；Excel 导入降级为统一管道中的兜底适配器。

## 1. 现状与问题

| 数据域 | 现状 | 问题 |
|---|---|---|
| 合同明细 | Excel 手工导入 + 每日从工时系统拉取（工时系统 02:00 从 OA 同步） | 双入口；链路绕道，时效 T+1，受工时系统账号权限裁剪 |
| 工时/成本 | Excel 手工导入 + 月度确认后定时拉取（xlsx 灌回导入管道） | 双入口 |
| OA 组织/人员 | `SeeyonOaIntegrationService.syncAll()` 只查询不落库（TODO） | 半成品 |
| 销售商机 | 人工录入 | **保持人工**（已确认） |
| 同步编排 | 仅工时域有 `worktime_sync_log` + Scheduler | 各域割裂，无统一手动触发与日志 |

## 2. 目标架构

```
业务消费层   KPI周报 / 业务线利润 / BU驾驶舱 / 交付与利润 / 待映射
                ↑ 订阅 SyncCompletedEvent 自动联动
数据中心层   revenue_contract_entry / revenue_worklog_entry / revenue_cost_entry /
             oa_org_department / oa_org_member / revenue_sales_project
             （每行：source_system, source_id, sync_batch_id, mapping_locked 保护保留）
                ↑ 唯一写入入口：统一 Sync Pipeline（upsert + 归属判定 + 映射保护）
数据采集层   OaContractCollector(vReport 导出)  WorktimeCollector(工时/成本)
             OaOrgCollector(组织/人员)          ExcelImportAdapter(兜底，走同一管道)
                ↑ 统一编排
同步中枢     sync_task（定时 cron + 手动触发）+ data_sync_log（全域统一日志）
```

## 3. 数据源口径（权威源矩阵）

| 数据 | 权威源 | 采集方式 | 频率 | 兜底 |
|---|---|---|---|---|
| 合同明细 | **OA vReport「销售合同查询(全域)」** | 模拟导出拉取 xlsx → 复用解析管道 | 每日定时 + 手动 | Excel 导入 |
| 工时明细 | 工时系统 | 月度 confirmed 后拉取（现有逻辑） | 每日检查 + 手动 | Excel 导入 |
| 成本分析 | 工时系统 | 同上 | 每日检查 + 手动 | Excel 导入 |
| 组织/部门 | OA REST（orgDepartment） | 全量覆盖落库 | 每日 + 手动 | — |
| 人员 | OA REST（orgMember） | 全量覆盖落库 | 每日 + 手动 | — |
| 销售商机 | 本系统人工 | — | — | — |

合同从 OA 直取后，工时系统的合同链路保留为可配置开关（`sync_task.enabled`），
灰度期间两路并行对账，稳定后关闭工时链路。

## 4. 数据库设计（V75 迁移）

### 4.1 `sync_task` 同步任务表

| 列 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| task_code | varchar(64) unique | `oa-contract` / `oa-org` / `worktime-contract` / `worktime-worklog` / `worktime-cost` |
| task_name | varchar(128) | 展示名 |
| source_system | varchar(32) | `OA` / `WORKTIME` / `EXCEL` |
| domain | varchar(32) | `contract` / `worklog` / `cost` / `org` / `member` |
| cron | varchar(64) | 定时表达式（NULL=仅手动） |
| enabled | tinyint | 开关（灰度用） |
| last_status | varchar(16) | 冗余最近一次状态，便于列表展示 |
| last_run_at | datetime | |
| created_at / updated_at | datetime | |

初始数据：5 条内置任务，cron 取现有值（合同 06:30、月度 07:00），
OA 合同任务初始 `enabled=0`（灰度），OA 组织任务 `enabled=1`。

### 4.2 `data_sync_log` 统一同步日志

由 `worktime_sync_log` 泛化而来（保留原表不动，新表承接全部域）：

| 列 | 说明 |
|---|---|
| id | PK |
| task_code | 关联 sync_task |
| source_system / domain | 冗余便于查询 |
| scope | 同步范围：如 `2026` / `2026-08` / `all` |
| status | running / success / failed |
| total_count / upsert_count / pending_count | 统计 |
| message | 错误信息或摘要 |
| triggered_by | schedule / manual / fallback-import |
| operator_id | 手动触发人（定时为 NULL） |
| started_at / finished_at / created_at | |

### 4.3 OA 组织落库（V75 同时建）

- `oa_org_department`：oa_id(unique), name, parent_oa_id, sort_order, enabled, synced_at
- `oa_org_member`：oa_id(unique), name, login_name, department_oa_id, department_name,
  email, mobile, enabled, synced_at
- 全量覆盖语义：本次未出现的 oa_id 标记 `enabled=0`（不物理删除，保护映射数据）

### 4.4 明细表补充血缘列（V75 ALTER）

`revenue_contract_entry` / `revenue_worklog_entry` / `revenue_cost_entry` 增加：

- `source_system` varchar(16)：`OA` / `WORKTIME` / `EXCEL`
- `sync_log_id` bigint：写入该行的同步日志 id（手工导入对应 import_batch）

## 5. 后端模块设计

### 5.1 包结构（新增 `sync` 子包，不动现有包）

```
com.bu.management.sync/
├── SyncTaskService.java          # 任务 CRUD、启用/禁用、cron 动态注册
├── SyncOrchestrator.java         # 统一执行入口：手动/定时都走 run(taskCode, scope, triggeredBy, operatorId)
├── SyncScheduler.java            # 替代 WorktimeSyncScheduler，按 sync_task 表动态调度
├── SyncCompletedEvent.java       # 领域事件(domain, scope, logId)
└── collector/
    ├── DataCollector.java        # 接口：collect(SyncContext) → SyncOutcome
    ├── OaContractCollector.java  # vReport 导出 → xlsx → RevenueContractImportService 管道
    ├── OaOrgCollector.java       # 组织/人员落库
    ├── WorktimeContractCollector.java  # 包装现有 WorktimeContractSyncService
    └── WorktimeMonthlyCollector.java   # 包装现有 WorktimeMonthlySyncService
```

原则：**采集器只负责"取数 + 交给统一管道"**，upsert/归属判定/mapping_locked
保护继续复用 `RevenueContractImportService` / `RevenueImportService` /
`RevenueContractAssignment`，不重写解析逻辑。

### 5.2 OA vReport 合同采集器

- 入口 URL 可配置（默认用户提供的「销售合同查询(全域-云鹿and会员通and微信定制)」报表地址），
  存 `seeyon_oa_integration_config` 扩展列或 `system_config`：`oa.vreport.sales-contract-url`
- 认证：vReport 走 `/seeyon/` servlet 会话（JSESSIONID），与 REST token 不同；
  `SeeyonOaClient` 增加 `loginForSession()`（用户名/密码表单登录，配置里已有
  encryptedUsername/encryptedPassword），失败时回退尝试携带 REST token 调用
- 导出调用：`vReport.do?method=export&...`（具体 method/参数需在联调时通过浏览器
  抓包确认；采集器把"导出请求模板"做成配置项，避免硬编码）
- 产出 xlsx 流 → 直接调 `RevenueContractImportService` 现有解析（**该报表导出件
  与目前人工导入的 Excel 同源**，列结构一致，风险低）

### 5.3 统一触发 API（新控制器 `SyncController` → `/api/sync`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sync/tasks` | 任务列表（含最近状态） |
| PUT | `/api/sync/tasks/{taskCode}` | 改 cron / enabled |
| POST | `/api/sync/tasks/{taskCode}/run` | 手动触发，body: `{scope?}` |
| GET | `/api/sync/logs` | 日志分页（按 domain/状态/时间过滤） |
| GET | `/api/sync/overview` | 各域"数据截至"摘要（供前端报表页头展示） |

权限：挂 `RequirePermission("sync:manage")`，菜单挂到系统管理下。

### 5.4 事件驱动联动（P3）

`SyncOrchestrator` 在日志落 success 后发布 `SyncCompletedEvent`：

- domain=contract → 触发归属重扫（未锁定行）+ 刷新 `revenue_sales_project`
- domain=worklog/cost → 失效对应月 KPI 快照、利润矩阵按查询现算（现状已是现算，无需改）
- domain=org/member → 刷新名称映射候选清单

用 Spring `ApplicationEventPublisher`，不上 MQ。

## 6. 前端配套（data-flow 分支，P4）

1. 新增「数据集成中心」（`/integration`）：任务卡片（来源/域/cron/最近状态/手动触发按钮）、
   同步日志表格、失败重试
2. 「数据导入」页改造：顶部展示同步任务状态，导入区标注"兜底补录"
3. 「待映射与销售项目」、报表页头部：展示"数据截至：scope（来源 sourceSystem，同步于 xx:xx）"
   （来自 `/api/sync/overview`）

## 7. 分期与验证

| 阶段 | 内容 | 验证 |
|---|---|---|
| P0 | V75 迁移 + sync 包骨架 + SyncController + 迁移两个现有定时任务 | 原定时/手动行为不变；日志写入 data_sync_log |
| P1 | OaOrgCollector 落库；OaContractCollector（联调抓包确认导出参数） | 组织人员每日落库；OA 合同与工时链路对账一致 |
| P2 | Excel 导入走统一管道（记录 source_system=EXCEL + sync_log）；灰度切换合同源 | 双源对账一致后关闭 worktime-contract |
| P3 | SyncCompletedEvent 联动 | 同步后待映射/销售项目/快照自动刷新 |
| P4 | 前端集成中心与页面改造 | 页面上完成全部手动触发与状态查看 |

## 8. 已确认决策

1. 合同权威源 = OA vReport「销售合同查询(全域)」导出（用户确认，2026-××）
2. 销售商机保持人工维护
3. Excel 导入保留为兜底
4. 后端在 `data-flow-be` worktree 开发

## 9. 开放风险

- vReport 导出的具体 HTTP 参数/method 未确认 → P1 第一步用浏览器抓包确定，
  采集器把请求模板配置化以吸收差异
- OA 会话登录方式（验证码/SSO）可能影响自动登录 → 若表单登录受阻，
  备选：人工定期导出上传（即现有兜底），或申请 OA 侧开放接口账号
