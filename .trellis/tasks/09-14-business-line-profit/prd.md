# 业务线月度利润报表（工时系统同步）

## Goal

在「数据分析」下新增二级菜单「业务线利润」（/bl-profit）：以 业务线 × 月 的列表展示
真实营收与利润数据（数据权威源为工时系统 `/api/v1/finance/report/business-line/{month}`），
每个业务线的各月合计即 YTD；支持按年/业务线查询、手动从工时系统同步（整月覆盖）。

## 数据源（已核实，2026-09-14 从工时系统前端 bundle 提取）

- 月度：`GET /api/v1/finance/report/business-line/{yearMonth}`，data: `{ items: [...], groups, summary, year_month }`
- YTD：`GET /api/v1/finance/report/business-line/ytd/{year}`（本功能自行按月求和，不依赖该接口）
- item 字段：business_line_id / business_line_name / group_name / revenue / sms_cost / direct_cost /
  platform_fee / compensation / outsourcing / software_gift / total_hours / hours_ratio / expense_1 /
  labor_cost_1 / gross_profit / gross_profit_rate / marketing_cost / labor_cost_2_sales /
  labor_cost_3_backend / labor_cost_3_tech / labor_cost_3_rd / expense_2 / net_profit / net_profit_rate

## Definition of Done

### 后端

- [ ] V74 迁移：`biz_line_profit_report` 表（year_month + worktime_business_line_id 唯一键，
      整月覆盖 upsert）；「业务线利润」菜单（数据分析，/bl-profit）+ 复制 /revenue/worktime 角色授权
- [ ] `WorktimeApiClient.fetchBusinessLineMonthlyReport(yearMonth)`
- [ ] `BusinessLineProfitService`：syncMonth / syncYear（year→latest-month 逐月），写
      `worktime_sync_log`（sync_type='bl_profit'）；业务线映射优先 worktime_business_line_id →
      `worktime_business_line_mapping`，兜底名称匹配
- [ ] API：`GET /api/finance/bl-profit?year=`（月行 + 每业务线 YTD + 总计，比率按合计重算）、
      `POST /api/finance/bl-profit/sync {year|month}`、`GET /api/finance/bl-profit/sync-logs`
- [ ] 展示列：营业收入 / 考核毛利 / 考核毛利率 / 净利润 / 净利率 / 工时（其余字段存库备用）

### 前端

- [ ] `BusinessLineProfitView`：年份切换、业务线 pill 筛选、同步按钮 + 最近同步状态，
      业务线分组列表（月行 + YTD 小计行 + 底部总计行）
- [ ] 路由 `/bl-profit`（roleAccess: management）+ MainLayout 回退默认菜单

### 验收

- [ ] `cd backend && mvn test` 通过；`cd frontend && npm run build`（含 vue-tsc）通过
- [ ] 前端 mock E2E（列表渲染/筛选/同步触发）
- [ ] 部署 241 后：手动同步当年数据，与工时系统业务线利润报表页面对账一致
