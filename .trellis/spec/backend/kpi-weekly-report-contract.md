# KPI Weekly Report & Worktime Integration Contract

## Scope

KPI 经营周报（会员通/全渠道云鹿/全渠道精准三条业务线，云鹿=定制+SAAS 合并行）与
工时系统（worktime.lucidata.cn）数据自动同步。详细设计见 `docs/kpi-weekly-report-design.md`。

## Data Caliber

- 交付口径，不用财报考核口径。金额单位统一为元，前端展示换算万元。
- YTD 营收 = Σ `revenue_contract_entry.receivable_amount`（delivery_date 属于当年且 ≤ 快照日）。
- YTD 直接成本 = Σ 已交付合同行 sms_cost + direct_cost + third_party_cost（工时系统同步带入）。
- YTD 人工成本 = 已完结月（`revenue_month_close.closed_at` 非空）`revenue_cost_entry` 实际成本
  + 当月未完结估算：`work_log`（task → requirement.business_line_id）小时 ÷ 174 × 综合单价。
- 综合单价 = 当年已完结月成本合计 ÷ 已完结月工时合计；无完结月时当月人工估算为 0。
- YTD 毛利 = 营收 − 直接成本 − 人工 − 其他成本（`revenue_other_cost` 归属月 ≤ 当月）。
- 周环比增量 = 本快照 YTD − 上一快照 YTD；首个快照的增量 = YTD 本身。
- 前端月度矩阵取「该月最后一个周日快照」作为月末 YTD，月环比 = 月末 YTD 差。

## Business Line Grouping

- `business_line.kpi_report_group`：会员通 / 全渠道云鹿 / 全渠道精准；NULL 不参与 KPI 周报。
- KPI 模块（目标/快照/预警/备注）只按 report_group 聚合，不直接操作业务线行。

## Alert Rules

- 环比增量 ≤ 0 → red；0 < 增量 < 年度目标÷12÷weekly_divisor×yellow_ratio → yellow。
- 规则表 `kpi_alert_rule`：report_group NULL 为全局默认，分组级覆盖；enabled=0 停用该组预警。
- 快照重算时更新异常单元格级别；已填写的备注（status=done）不被删除；无异常时清理 pending 备注。

## Deviation Notes

- `kpi_deviation_note` 唯一键 (snapshot_id, metric)；异常单元格自动生成 pending 记录。
- 填写规则：deviationReason 必填；isAbnormal=1 时 countermeasure 必填；填写后 status=done。
- 三段式格式：偏差原因 / 是否异常 / 对策（正常免填对策）。

## Worktime Integration

- `worktime_integration_config` 单行配置（id=1），工号/密码 AES 加密存储
  （`WORKTIME_CONFIG_ENCRYPTION_KEY`，模式同 seeyon_oa）。页面保存后清空测试状态。
- `WorktimeApiClient`：POST /api/v1/auth/login → JWT 缓存 12h，401 自动重登一次；
  响应信封 code==200 才取 data。
- 合同同步（每日 06:30 + 手动）：按账号可见业务线（销售报表 filters 接口）逐线拉
  drilldown 分页明细，detail_record_id upsert 进 revenue_contract_entry；
  业务线归属优先 `worktime_business_line_mapping`，否则复用 RevenueContractAssignment 关键字判定；
  人工调整过归属的行（mapping_locked=1）不被同步/导入覆盖。
- 工时/成本同步（每日 07:00 + 手动）：available-months 状态 confirmed 且本地未完结且未成功同步过
  才拉取；工时明细下载 xlsx 走 RevenueImportService 现有管道（整月覆盖），
  成本分析 JSON 直接解析落 revenue_cost_entry（整月覆盖）。forceMonth 可手动强制重拉。
- 同步失败写 worktime_sync_log(status=failed)，不中断其他同步类型。

## API

- `GET /api/kpi/report?year`（kpi:view）：分组行（目标+全年快照+备注）+ 合计。
- `POST /api/kpi/snapshot/run?weekEndDate`（kpi:manage）：默认上周日，幂等覆盖同周日快照。
- `GET/PUT /api/kpi/targets`、`GET/PUT /api/kpi/alert-rules`、`PUT /api/kpi/notes/{id}`。
- `GET /api/kpi/report/export?year`：xlsx（KPI周报 + 偏差备注明细两个 sheet）。
- `GET /api/kpi/report/summary?year`：文字版周报摘要。
- `GET/PUT /api/worktime/status|config`、`POST /api/worktime/connection-test|sync/contracts|sync/monthly`、
  `GET /api/worktime/sync/logs`（全部 kpi:manage）。

## Scheduling

- `kpi.snapshot-cron`（默认周日 18:00）生成快照+预警扫描；
  有待填备注且配置 `kpi.notify-wecom-user` 时企业微信文本提醒。
- 每周日快照基于当周最新同步数据；合同每日同步（工时系统 02:00 从 OA 同步之后）。
