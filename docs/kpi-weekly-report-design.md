# KPI 经营周报功能设计（一期）

## 背景

原流程：每周手工维护 `2026KPI.xlsx`（BU × 业务线 × 月度 YTD 营收/毛利 + 达成率 + 环比增量），
对环比增量为 0 / 为负 / 明显偏小的单元格手工写偏差备注（偏差原因/是否异常/对策），
每周日前更新后向贾老师、轮子汇报。

目标：在工时管理系统内实现该周报，数据自动获取、毛利自动计算、异常自动标记，
用户只需每周填写偏差备注并导出汇报。

## 已确认的决策

| # | 决策 |
|---|------|
| 1 | 口径：**交付营收 + 成本，毛利自动计算**（系统口径，不用财报考核毛利），要求周更 |
| 2 | 范围：仅 **会员通、全渠道云鹿（定制+SAAS合并）、全渠道精准** 三条业务线 |
| 3 | "明显偏小"阈值：按**月均目标**（年度 KPI ÷ 12）换算周基准，系数可配置 |
| 4 | 偏差备注由用户（周报负责人）本人填写，不做多人协作流 |
| 5 | 数据不手工导入，全部通过 **工时系统（worktime.lucidata.cn）REST API** 自动同步（已实测验证） |

## 工时系统 API 集成（已实测，2026-09-09）

工时系统本身**每天 02:00 自动从 OA 同步合同明细**（记录含 `synced_at`/`last_sync_run_id`），
因此本系统只需对接工时系统一个数据源，无需任何 OA 接口。

**认证**：`POST /api/v1/auth/login` `{employee_no, password}` → JWT（Bearer）。
账号 00504 数据权限天然限定 4 条业务线（119 会员通 / 120 私域精准 / 121 云鹿Saas / 122 云鹿定制）= 汇报所需范围。

**销售/合同明细**（合同管理-销售报表模块）：
- `GET /api/v1/contracts/sales-report/drilldown?year=2026&drill_type=business_line&name=<业务线名>&page=&page_size=`
  分页返回合同明细行：`detail_record_id`（去重键）、`business_line_id`、`receivable_amount`、
  `project_delivery_date`、`payment_sales_date/month`、`payment_status`、`received_amount`、
  **`sms_cost` / `direct_cost` / `third_party_procurement_cost`（行级直接成本）**、合同/客户/品牌
- `GET /api/v1/contracts/sales-report/overview?year=` 年度汇总（校验用）
- `GET /api/v1/contracts/sales-report/delivery-trend?year=` 月度实际/预计交付（校验用）

**工时明细**（替代现有手工导入）：
- `POST /api/v1/worktime/export/bl-detail` `{business_line_ids:[...], year_month:"YYYY-MM"}`
  返回 xlsx，与现有「工时数据_业务线明细」导入格式一致，可直接复用现有导入管道
- `GET /api/v1/worktime/available-months` 各月工时确认状态（已确认=月结信号）

**成本分析**（替代现有手工导入，已实测）：
- `GET /api/v1/finance/analysis/cost/project?from_month=YYYY-MM&to_month=YYYY-MM`
  JSON 分页返回：year_month / business_line_id / project_name（含【销售】【项目】后缀）/
  employee_count / total_hours / total_labor_cost / hourly_cost，
  与「成本分析_项目」Excel 列一一对应，JSON 直连无需解析 xlsx

**KPI 目标**：工时系统已有 `/api/v1/business-lines/kpi-targets` 与 `/business-lines/kpi-report`
（当前账号 403 需管理员权限）。一期在本系统自维护 KPI 目标；如后续工时系统侧授权，可切换读取。

## 现有导入链路自动化改造（随本期一起落地）

将营收模块现有三条手工导入全部改为工时系统 API 自动同步，手工导入入口保留为降级通道：

| 现有手工导入 | 来源 Excel | 工时系统替代接口 | 说明 |
|---|---|---|---|
| 按月导入工时明细 | 工时数据_业务线明细_YYYY-MM.xlsx | `POST /worktime/export/bl-detail` | 返回同格式 xlsx，**复用现有解析/映射/pending 管道** |
| 导入成本分析 | 成本分析_项目_X至X.xlsx | `GET /finance/analysis/cost/project` | JSON，字段一一对应，新增轻量解析器 |
| 导入合同明细（销售/交付） | 本年销售/交付总额明细.xlsx | `GET /contracts/sales-report/drilldown` | JSON 分页，`detail_record_id` 去重 upsert |

**同步节奏**：
- 合同明细：每日定时全量 upsert（当年 4 条业务线约数百行，规模小无需增量）
- 工时明细 + 成本分析：每日检查 `available-months`，某月变「已确认」→ 自动拉取该月两份数据
  走现有导入管道落库 → 自动执行月结（可配置是否自动月结）
- 每次同步写 `worktime_sync_log`，失败告警（邮件）

**业务线/项目映射**：工时系统业务线名与本系统业务线名一致（如「全域-全渠道-会员通」），
一期按名称匹配 + 现有 `revenue_name_mapping` 记忆机制；新增 `worktime_business_line_mapping`
（工时系统 business_line_id ↔ 本系统 business_line_id）兜底。云鹿合并行 = 121+122。

**项目交付营收（利润）页同步受益**：合同明细与成本数据自动化后，
`RevenueDeliverySummaryService` 的毛利计算输入全部实时化，无需任何手工维护。

## 数据来源与可行性验证

| 数据 | 来源 | 粒度 | 现状 |
|------|------|------|------|
| 交付营收 | `revenue_contract_entry`（OA 合同明细，delivery_date） | 日 | 表已有；需 OA 接口自动同步替代手工导入 |
| 已完结月工时成本 | `revenue_worklog_entry` + `revenue_cost_entry` + `revenue_month_close` | 月（完结口径） | 已有，月结后锁定 |
| 当月未完结工时 | `work_log`（work_date 日粒度，task→project→business_line 可挂接） | 日 | 已有，实时 |
| 工时单价 | 行级/业务线综合单价 = 累计完结月成本 ÷ 累计完结人月（RevenueMatrixService 已有此逻辑） | 快照 | 已有算法，需落快照 |
| 其他成本（协力/服务器/其他） | `revenue_other_cost`（手动维护） | 月 | 已有 |
| KPI 年度目标 | **新增** `kpi_target` | 年 | 无，需新建维护页 |

周更毛利公式：

```
周快照 YTD 营收 = Σ 已完结月实际交付 + 当月 delivery_date ≤ 快照日 的合同应收（工时系统日更）
周快照 YTD 直接成本 = Σ 交付月 ≤ 当月 的合同行 sms_cost/direct_cost/third_party_procurement_cost
周快照 YTD 人工成本 = Σ 已完结月实际成本 + 当月 work_log 工时 × 业务线综合单价快照（实时估算）
周快照 YTD 毛利 = YTD 营收 − YTD 直接成本 − YTD 人工成本 − Σ 其他成本（归属月 ≤ 当月）
周环比增量 = 本周 YTD − 上周快照 YTD
```

注：当月成本为估算值（单价快照），月结后被实际值替换，历史快照不重算但保留月结修正标记。

## 数据模型（新增）

```sql
-- 年度 KPI 目标
kpi_target(id, year, business_line_id, revenue_target DECIMAL(16,2),
           profit_target DECIMAL(16,2), remark, created_by, created_at, updated_at,
           UNIQUE KEY uk_year_line(year, business_line_id))

-- 周快照（每周日定时生成，冻结）
kpi_weekly_snapshot(id, week_end_date DATE, business_line_id,
                    ytd_revenue DECIMAL(16,2), ytd_labor_cost DECIMAL(16,2),
                    ytd_other_cost DECIMAL(16,2), ytd_profit DECIMAL(16,2),
                    week_delta_revenue DECIMAL(16,2), week_delta_profit DECIMAL(16,2),
                    unit_price_snapshot DECIMAL(14,2), is_estimated TINYINT,
                    created_at, UNIQUE KEY uk_week_line(week_end_date, business_line_id))

-- 偏差备注（一个异常单元格一条）
kpi_deviation_note(id, snapshot_id, metric VARCHAR(20) COMMENT 'revenue/profit',
                   alert_level VARCHAR(10) COMMENT 'red/yellow',
                   deviation_reason VARCHAR(500), is_abnormal TINYINT,
                   countermeasure VARCHAR(500), status VARCHAR(20) COMMENT 'pending/done',
                   created_by, created_at, updated_at)

-- 预警规则配置（可按业务线覆盖）
kpi_alert_rule(id, business_line_id NULL COMMENT 'NULL=全局默认',
               monthly_base_coef DECIMAL(4,2) COMMENT '周基准=月均目标÷4.33×系数，默认1.0',
               yellow_ratio DECIMAL(4,2) COMMENT '低于周基准×该比例判偏小，默认0.5',
               enabled TINYINT, updated_by, updated_at)

-- 工时系统同步记录
worktime_sync_log(id, sync_type VARCHAR(20) COMMENT 'contract/worklog',
                  scope VARCHAR(100), sync_started_at, sync_finished_at,
                  total_count, upsert_count, status, message)

-- 合同明细扩展行级直接成本（来源工时系统同步）
ALTER TABLE revenue_contract_entry
  ADD COLUMN sms_cost DECIMAL(16,2) NULL COMMENT '短信成本',
  ADD COLUMN direct_cost DECIMAL(16,2) NULL COMMENT '直接成本',
  ADD COLUMN third_party_cost DECIMAL(16,2) NULL COMMENT '三方采购成本';
```

## 预警规则

| 条件 | 级别 | 动作 |
|------|------|------|
| 周环比增量 < 0 | 红 | 生成待填备注 |
| 周环比增量 = 0 | 红 | 生成待填备注 |
| 0 < 周环比增量 < 月均目标 ÷ 4.33 × yellow_ratio | 黄 | 生成待填备注 |

备注内容格式（与现有要求一致）：偏差原因 / 是否异常（是|否）/ 对策（正常免填）。

## 工时系统同步（替代原 OA 同步方案）

- 新增 `worktime_integration_config`：base_url、加密的工号/密码、token 缓存（模式复用 seeyon_oa_integration_config）。
- `WorktimeContractSyncService`：每日定时 + 手动触发，按 4 条业务线 × 当年分页拉取 drilldown 明细，
  按 `detail_record_id` upsert 进 `revenue_contract_entry`（含行级直接成本字段，需扩表），
  同步后写 `worktime_sync_log`；年度汇总与 overview 接口对账。
- `WorktimeWorklogSyncService`：每日检查 `available-months`，某月状态变「已确认」后自动调
  `export/bl-detail` 拉取 xlsx 并走现有工时导入管道落库，替代手工导入。
- 风险：工时系统为内部系统无 SLA 承诺；同步失败告警 + 保留手工导入作为降级通道。

## 页面与输出

1. **KPI 周报页**（新菜单，权限 `kpi:view`/`kpi:manage`）：
   - 表格结构复刻现有 Excel：业务线行 ×（营收/毛利）列，按周/月切换视图，达成率行、环比增量行
   - 红/黄单元格标记，点击弹出备注编辑（偏差原因/是否异常/对策）
2. **KPI 目标维护**：年度目标录入/调整（`kpi:manage`）
3. **导出**：一键导出 Excel（保持现有汇报格式）+ 文字版周报摘要（异常项+对策列表，可粘贴汇报）
4. **提醒**：周日快照生成后，如有待填备注，邮件提醒用户本人（复用 EmailDigestService 能力）

## 任务拆解（一期）

1. DB migration：`kpi_target` / `kpi_weekly_snapshot` / `kpi_deviation_note` / `kpi_alert_rule` / `oa_contract_sync_log` + 权限菜单
2. 后端：工时系统连接器（认证/token 刷新/配置页 `worktime_integration_config`）+ 业务线映射表
3. 后端：三条自动同步服务（合同明细每日 / 工时明细+成本分析按月确认触发），复用现有导入管道，手工入口保留降级
4. 后端：KPI 目标 CRUD；周快照计算服务（复用 RevenueDeliverySummaryService 口径 + work_log 当月实时估算）；预警扫描；备注 CRUD；周报查询 API
5. 前端：KPI 周报表格页 + 备注弹窗 + 目标维护页；营收管理页加「自动同步状态」标识
6. 导出：Excel 导出 + 周报文字摘要
7. 定时任务：每日数据同步 + 失败告警；每周日快照 + 待填备注提醒

## 开放问题

- **账号权限范围**：00504 仅能拉取 4 条业务线（119-122）的合同/工时数据；现有营收模块覆盖
  全部 BU（二象限/数据等），若要把这些业务线的导入也自动化，需要工时系统管理员提供
  全量数据权限的服务账号（或给专用账号扩权）。一期先用现有账号覆盖电商 BU 范围
- 工时系统 API 无正式文档，以上接口均从前端 bundle + 实测还原；建议与工时系统维护者同步集成意向，避免对方改版导致中断
- 工时系统已有 KPI 目标/报表功能（需管理员权限），一期本系统自维护目标；若对方授权可改为直读，避免双头维护
- 全渠道云鹿 = 工时系统业务线 121(Saas) + 122(定制) 合并一行，KPI 目标按合并口径录入
- 月中单价快照在当月发生大额成本波动时会失真，月结后自动修正（页面标注"预估"）
- 账号 00504 密码硬编码风险：密码变更会导致同步中断，需在配置页可更新 + 失败告警
