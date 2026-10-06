# KPI 经营周报与工时系统数据自动同步

## Goal

替代手工维护的 `2026KPI.xlsx` 周报流程：数据从工时系统（worktime.lucidata.cn）API 自动获取，
毛利自动计算（交付营收 − 直接成本 − 人工成本 − 其他成本），环比异常自动标记，
用户每周仅需填写偏差备注（偏差原因/是否异常/对策）并导出汇报。
同时将营收模块现有三条手工导入链路（工时明细/成本分析/合同明细）全部改为自动同步。

## 范围

- 业务线范围：会员通、全渠道云鹿（工时系统 121 Saas + 122 定制合并）、全渠道精准（120）
- 详细设计：`docs/kpi-weekly-report-design.md`（接口已实测验证，2026-09-09）

## Definition of Done

### 数据同步（工时系统连接器）

- [ ] `worktime_integration_config` 配置页（base_url/工号/密码加密/token 缓存刷新），模式复用 seeyon 集成
- [ ] 合同明细每日自动同步 → `revenue_contract_entry` upsert（detail_record_id 去重），扩表存行级 sms_cost/direct_cost/third_party_cost
- [ ] 工时明细自动同步：available-months「已确认」触发 `export/bl-detail` 拉取，复用现有导入管道
- [ ] 成本分析自动同步：`/finance/analysis/cost/project` JSON 解析落 `revenue_cost_entry`
- [ ] `worktime_sync_log` + 同步失败邮件告警；手工导入入口保留为降级通道
- [ ] 工时系统业务线 ↔ 本系统业务线映射（名称匹配 + 映射表兜底）

### KPI 周报

- [ ] `kpi_target` 年度目标维护（营收/毛利，按业务线，云鹿合并口径）
- [ ] `kpi_weekly_snapshot` 每周日自动快照：YTD 营收/直接成本/人工成本/毛利 + 周环比增量；当月未完结用 work_log 实时工时 × 综合单价快照估算（标"预估"）
- [ ] `kpi_alert_rule` 预警规则：环比 ≤0 红、< 月均目标÷4.33×系数 黄（可按业务线配置）
- [ ] `kpi_deviation_note` 偏差备注（偏差原因/是否异常/对策），异常单元格自动生成待填
- [ ] 前端 KPI 周报页（复刻现有 Excel 结构）+ 备注弹窗 + 目标维护页
- [ ] 一键导出 Excel（保持现有汇报格式）+ 文字版周报摘要
- [ ] 周日快照后有待填备注 → 邮件提醒用户本人

### 验收

- [ ] `cd backend && mvn test` 通过；`cd frontend && npm run type-check && npm run build` 通过
- [ ] 部署后真实环境验证：同步落库数据与工时系统销售报表页面对账一致；周报表数字与手工 Excel 同口径核对
- [ ] 权限：`kpi:view` / `kpi:manage` 接入现有角色权限体系

## 风险

- 工时系统 API 无文档（前端 bundle 逆向 + 实测），对方改版可能中断 → 失败告警 + 手工降级
- 00504 账号仅覆盖 4 条业务线；全 BU 自动化需服务账号扩权（一期不阻塞）
- 当月成本为估算（单价快照），月结后修正
