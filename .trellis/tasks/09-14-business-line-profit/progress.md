# 业务线月度利润报表 — 进度

## 2026-09-14 实现完成（待验收部署）

- 后端：V74 迁移（biz_line_profit_report 表 + /bl-profit 菜单 + bl-profit:view/manage 权限）；
  WorktimeApiClient.fetchBusinessLineMonthlyReport；BusinessLineProfitService（整月覆盖同步 +
  YTD 聚合比率重算）；BusinessLineProfitController（query/sync/sync-logs）
- 后端单测 5/5；mvn test 377 全绿
- 前端：BusinessLineProfitView（年份/业务线筛选/同步按钮/月行+YTD+总计），路由 /bl-profit，
  MainLayout 回退菜单；E2E 4/4
- 待办：合并 master → 部署 241 → 生产手动同步对账（worktime /finance/report/business-line/{month} 接口
  形状取自工时系统前端 bundle，未经实测，首次同步若失败按 sync-log 报错修正）

## 2026-09-14 已部署 241 生产并对账通过

- 合并 master（601d0b2），部署后修复两个上线问题：V74 `year_month` 保留字反引号；
  数字结尾字段 @TableField 显式映射（expense_1/labor_cost_1/labor_cost_2_sales/...）
- 生产同步 2026 全年 8 个月 × 70 业务线行成功；与工时系统 7 月报表 xlsx 对账一致
  （合计营收 16,072,737 / 毛利 3,045,740 / 工时 271.82）
- 生产冒烟通过：侧边栏「数据分析→业务线利润」、月行/YTD/合计渲染
- 备份：241:/home/openclaw/deploy-backups/superwork-bl-profit-20260914-143030/
