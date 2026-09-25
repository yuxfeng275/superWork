# 项目利润（项目利润表）功能实施计划

## Context

依据 `docs/财报数据/项目利润表.xlsx`（表头与备注说明）开发新功能「项目利润」：按 月份 × 业务线 × 分类 × 项目 展示利润表，列固定为（备注3，不得变更）：

`月份 | 业务线 | 分类 | 项目 | 营业收入 | 短信成本 | 直接成本 | 平台佣金&手续费 | 赔付 | 协力&外包 | 软件赠送 | 工时 | 成本 | 考核毛利 | 考核毛利率(%)`

备注要求：支持 by月 / H1 / H2 / 全年查看与多选筛选（月份、H1/H2、全年、业务线、分类、项目）；销售分类行营收为 0 只有工时和成本；saas/定制的直接成本和协力支持手动分配到项目（本次扩展为 6 个成本列均可分配）；合计与《业务线营收利润月度汇总表》对齐（会员通对会员通、saas+定制对照、精准对精准）；「销售-业务线」行 = 投入到其他事项的工时成本，取自本系统 `revenue_cost_entry`（其数据本就来自工时系统 `/finance/analysis/cost/project` 同步）。

已确认的四项关键决策（用户拍板）：
1. 项目行营业收入 = OA 合同已交付金额（`revenue_contract_entry.receivable_amount` 按 `delivery_date` 落月，与「交付与利润」同口径）。**补充（用户拍板）：OA/销售合同金额为含税，财报为未税 —— 项目行营收按业务线 `tax_rate` ÷(1+taxRate/100) 换算为未税**（沿用 `RevenueDeliverySummaryService.excludeTax` 口径），与镜像合计（未税）同口径可比；手动分配的 6 成本列按财报未税口径录入，不换算。
2. 6 个成本列（短信/直接/佣金/赔付/协力/软件赠送）全部支持按 月×项目 手动分配；会员通「项目集」行、精准单行自动继承业务线镜像值。
3. 合计行取工时系统业务线利润镜像（`biz_line_profit_report`）保证与汇总表对齐；同时每线提供「差额」行 = 合计 − Σ(项目行+销售行)，使明细与合计的对齐情况可视、可通过分配消除。
4. 新增「月度一键同步」按钮：每个月工时系统的工时、成本、销售数据更新后，在页面选月点一次按钮，即按序完成 工时明细+成本分析（含销售工时，同一管道）重拉 → 业务线利润镜像刷新 → 当月利润计算与逐业务线对齐结果返回，无需分别操作多个同步入口。

数据基础（均已存在，本计划不新增工时系统接口）：
- `biz_line_profit_report`（V74）：工时系统业务线利润月报镜像，含全部指标列，业务线级权威合计来源；同步为手动（`BusinessLineProfitService.syncYear/syncMonth`）。
- `revenue_cost_entry`（V36）：月×项目 工时(人月)/成本(元)，work_type=project|sales，pending 标记；来自工时系统项目成本分析同步。
- `revenue_contract_entry`：OA 合同明细，receivable_amount / delivery_date / project_id / biz_line_id / pending。
- `business_line.revenue_mode`：full（云鹿Saas/云鹿定制，按项目拆行）/ aggregate（会员通，单「项目集」行）/ simple（精准，单行）。`kpi_report_group` 非空 + status=1 的 4 条业务线为管理范围（与业务线利润页一致）。

考核毛利公式（已用汇总表 1 月会员通数据验证：360520−281−92969=267270 ✓）：
`考核毛利 = 营业收入 − 短信成本 − 直接成本 − 平台佣金 − 赔付 − 协力外包 − 软件赠送 − 成本(人工)`；`考核毛利率 = 考核毛利/营业收入×100`（HALF_UP 保留 2 位，营收为 0/null 时率为 null）。

## Approach

### 0. 计划文档回写

将本计划内容整体覆盖写回仓库根 `PROJECT_PROFIT_PLAN.md`（用户要求补充到该文件），无其他改动。

### 1. 迁移 V95__project_profit.sql（backend/src/main/resources/db/migration/）

最新迁移为 V94，本功能用 V95。内容：

a) 手动分配表（金额单位元）：
```sql
CREATE TABLE project_profit_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    `year_month` VARCHAR(7) NOT NULL COMMENT '归属月份 YYYY-MM',
    business_line_id BIGINT NOT NULL COMMENT '本系统业务线ID',
    project_id BIGINT NOT NULL COMMENT '本系统项目ID（主项目）',
    cost_type VARCHAR(20) NOT NULL COMMENT 'sms/direct/platform_fee/compensation/outsourcing/software_gift',
    amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '分配金额（元，允许负数作调整）',
    note VARCHAR(500) NULL,
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project_type (`year_month`, project_id, cost_type),
    INDEX idx_month_line (`year_month`, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目利润成本手动分配';
```

b) 菜单：「项目利润」作为「业务线利润」(/bl-profit) 的同级兄弟菜单，父节点用兄弟路径反查（避免 V92 那样硬编码 parent_id，生产库菜单可能漂移）：
```sql
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT parent_id AS id FROM sys_menu WHERE path = '/bl-profit') t),
       '项目利润', 'DataLine', '/project-profit', 'ProjectProfitView',
       (SELECT t.s + 1 FROM (SELECT sort_order AS s FROM sys_menu WHERE path = '/bl-profit') t), 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/project-profit');
```
插入前先把同父节点中 sort_order 大于 /bl-profit 的菜单 +1（同样用子查询取父 id 与 bl-profit 的 sort_order）。

c) 角色菜单授权：复制 /bl-profit 的授权（V74 同款 INSERT IGNORE ... sys_role_menu JOIN 模式，old_menu.path='/bl-profit'）。

d) 权限两行（V74 同款 ON DUPLICATE KEY UPDATE 幂等写法，menu_id 取 path='/project-profit'）：
- `project-profit:view`（type menu）查看项目利润
- `project-profit:manage`（type button）项目利润分配与同步

e) 角色权限：与 V74 相同的 5 个角色 DIRECTOR/BUSINESS_OWNER/DEPUTY_DIRECTOR/EFFECTIVENESS_OWNER/BU_ADMIN（INSERT IGNORE INTO sys_role_permission）。

### 2. 后端新文件（包 com.bu.management，全部沿用 MyBatis-Plus LambdaQueryWrapper、无 XML）

- `entity/ProjectProfitAllocation.java`：@TableName("project_profit_allocation")，字段与 DDL 一一对应（Long id/businessLineId/projectId/createdBy；String yearMonth/costType/note；BigDecimal amount；LocalDateTime createdAt/updatedAt）。
- `mapper/ProjectProfitAllocationMapper.java`：`@Mapper interface … extends BaseMapper<ProjectProfitAllocation>`（空接口，照抄 BizLineProfitReportMapper 模式）。
- `vo/ProjectProfitReportVO.java`：
  ```
  class ProjectProfitReportVO {
    Integer year; List<String> availableMonths;            // 镜像已同步月份 YYYY-MM 升序
    LocalDateTime lastSyncedAt;                            // 镜像行最大 synced_at
    List<LineOption> lineOptions;                          // 筛选项（不受当前过滤影响）
    List<Block> blocks;
  }
  class LineOption { Long businessLineId; String businessLineName; List<ProjectOption> projects; }
  class ProjectOption { Long projectId; String projectName; }
  class Block { String key; /* '2026-01' | 'H1' | 'H2' | 'YEAR' */ String label; /* '1月'|'H1'|'H2'|'全年' */ List<Line> lines; }
  class Line { Long businessLineId; String businessLineName; String revenueMode; List<Row> rows; Row total; Row residual; }
  class Row {
    String rowType;      // PROJECT | SALES | LINE_OTHER | TOTAL | RESIDUAL
    String category;     // '项目' | '销售'（TOTAL/RESIDUAL 为 null，RESIDUAL 展示为 '差额' 由前端处理）
    Long projectId; String projectName; Boolean editable;  // editable=true 仅 full 线真实项目行
    BigDecimal revenue, smsCost, directCost, platformFee, compensation, outsourcing, softwareGift;
    BigDecimal hours;           // 人月
    BigDecimal cost;            // 人工成本（元）
    BigDecimal grossProfit, grossProfitRate;
  }
  ```
- `vo/ProjectProfitMonthSyncVO.java`（月度一键同步结果）：
  ```
  class ProjectProfitMonthSyncVO {
    String yearMonth;
    Boolean monthClosed;              // 该月已完结：工时/成本重拉被跳过，仅刷新利润镜像
    List<WorktimeSyncLog> logs;       // worklog / cost / bl_profit 各步骤日志（含失败原因）
    List<LineAlignment> lines;        // 每条 managed 业务线的当月对齐结果
  }
  class LineAlignment {               // static nested
    Long businessLineId; String businessLineName;
    BigDecimal revenueResidual;       // 差额行.营业收入（合计镜像 − Σ明细）
    BigDecimal costResidual;          // 差额行.成本(人工)
    BigDecimal hoursResidual;         // 差额行.工时
    Boolean aligned;                  // |revenue|<0.01 且 |cost|<0.01 且 |hours|<0.0001（null 按 0）
  }
  ```
- `service/ProjectProfitService.java`（组装逻辑见第 3 节，月度同步编排见第 4 节）
- `controller/ProjectProfitController.java`：`@RestController @RequestMapping("/api/finance/project-profit")`，endpoints：
  | 方法 | 路径 | 权限 | 说明 |
  |---|---|---|---|
  | GET | (根路径) | project-profit:view | 参数：year(默认当年)、months(CSV List<Integer>)、periods(CSV List<String> H1/H2/YEAR)、businessLineIds(CSV List<Long>)、categories(CSV)、projectIds(CSV List<Long>) → Result<ProjectProfitReportVO> |
  | POST | /sync-month | project-profit:manage | body `{yearMonth}`：月度一键同步（决策4），编排见第 4 节 → Result<ProjectProfitMonthSyncVO> |
  | POST | /sync | project-profit:manage | body {month?year}，直接委托 BusinessLineProfitService.syncMonth/syncYear(…, "manual")，返回 Result<List<WorktimeSyncLog>>（整年回填用，保留） |
  | GET | /sync-logs | project-profit:view | 委托 BusinessLineProfitService.recentSyncLogs(limit) |
  | GET | /allocations | project-profit:view | 参数 yearMonth、businessLineId → Result<List<ProjectProfitAllocation>> |
  | POST | /allocations/batch | project-profit:manage | body `{yearMonth, businessLineId, projectId, items:[{costType, amount, note}]}`：按 (year_month, project_id, cost_type) 唯一键 upsert（先按唯一键查，存在则 update amount/note，不存在 insert） |
  | DELETE | /allocations/{id} | project-profit:manage | removeById |

  校验（batch）：yearMonth 必须匹配 `\d{4}-\d{2}`；projectId 存在且属于 businessLineId；该业务线 revenueMode='full'；costType ∈ 6 枚举；金额允许负（调整项）。校验失败抛 IllegalArgumentException → Result.error(msg)。

### 3. ProjectProfitService 组装口径（query 方法，内存聚合，不写库）

数据加载（均为 selectList + LambdaQueryWrapper）：
1. managedLines = business_line where status=1 AND kpi_report_group IS NOT NULL，按 id 升序（与 BusinessLineProfitService.queryYear 同一过滤，保证两页业务线集合一致）。
2. mirrorRows = biz_line_profit_report where year_month like 'year-%' AND business_line_id ∈ managed。
3. availableMonths = mirrorRows 的 year_month 去重升序 —— **报表月份轴只由镜像驱动**（合计权威来源；未同步月份不出现在报表中）。
4. costRows = revenue_cost_entry where year_month ∈ availableMonths AND business_line_id ∈ managed AND pending=0。
5. contracts = revenue_contract_entry where pending=0 AND biz_line_id ∈ managed AND delivery_date 非空且年份=year（仅用 delivery_date 落月，不用 receivable_date/service_end_date）。
6. allocations = project_profit_allocation where year_month ∈ availableMonths。
7. projects = project 全量中属于 managed 的部分；rootIdOf 沿 parentId 走到根。

行构建（先按 月×业务线 构建，再做期间聚合）——按 revenueMode：

- **full 线（云鹿Saas/云鹿定制）**：
  - 项目行 ×N（每个根项目一行；别名合并 佳贝艾特/海普诺凯 → 澳优：复制 RevenueDeliverySummaryService.PROJECT_ALIASES 常量与按名折叠逻辑到新 service，注明来源；不动原类）：
    - hours/cost = Σ revenue_cost_entry（work_type='project'、projectId 经 rootIdOf 归并到该根项目）的 hours / cost_amount。
    - revenue = Σ contracts 中 projectId 归并到该根项目、delivery_date 落当月 的 receivable_amount。
    - 6 成本列 = Σ project_profit_allocation（当月×该项目×类型）。editable=true。
  - 销售行「销售」（rowType=SALES, category='销售', projectName='销售'）：hours/cost = Σ work_type='sales'（全部 salesKind 合并）；revenue 与 6 成本列 = 0（备注4）。
  - 销售行「业务线」（rowType=LINE_OTHER, category='销售', projectName='业务线'）：hours/cost = Σ work_type='project' 且 project_id IS NULL；其余列 = 0（备注7）。
- **aggregate 线（会员通）**：
  - 项目行「项目集」（projectId=null, projectName='项目集', editable=false）：hours/cost = Σ 该线全部 work_type='project'（含 project_id null）；**revenue 与 6 成本列 = 镜像线级值**（该行即整线，直接对齐汇总表）。
  - 销售行「销售」：同上 full 线。
  - 无「业务线」行（线级其他工时并入项目集，与矩阵 agg-project 口径一致）。
- **simple 线（精准）**：
  - 单个项目行（projectName=业务线名, editable=false）：hours/cost = Σ 该线全部成本行（project+sales 合并，沿用交付与利润 simple 口径）；**revenue 与 6 成本列 = 镜像线级值**。
  - 无销售行。

合计行（rowType=TOTAL）：整行取镜像值：revenue/smsCost/directCost/platformFee/compensation/outsourcing/softwareGift ← 同名镜像列；hours ← total_hours；cost ← labor_cost_1；grossProfit/rate 重算（不按镜像存储值，保证聚合视图一致）。

差额行（rowType=RESIDUAL, category='差额', projectName='未分配'）：每列 = 合计 − Σ(当前返回的项目行+销售行)。某列全 0 时该行仍返回，**前端仅当任一数值列 |值| ≥ 0.01（金额）或 ≥ 0.0001（工时）时渲染**。projectIds/categories 过滤改变行集时差额按显示行重算（在 VO 注释写明）。

每行 grossProfit 按公式重算；rate = grossProfit/revenue×100（revenue 0/null → null）。null 列一律按 0 参与运算，输出保留 null 语义仅用于率。

块（Block）组成：
- periods 非空 → 每个 period 一个聚合块：H1=1-6月、H2=7-12月、YEAR=1-12月，与 availableMonths 取交集，空则省略该块；行按 (业务线, rowType, projectId/projectName) 跨月求和后重算毛利/率（合计行 = 镜像跨月求和，与汇总表年度口径一致）。
- 否则 months 非空 → 每个选中月份 ∩ availableMonths 一个月度块（升序）。
- 否则 → 全部 availableMonths 各一个月度块（默认月度视图）。

过滤：businessLineIds 过滤 Line；categories（'项目'/'销售'）过滤行类型；projectIds 仅过滤 PROJECT 行（销售/合计/差额保留）。lineOptions 始终在全部 managed 线上构建（含各线根项目列表），不受过滤影响。

**单月构建抽公**：月度块构建逻辑抽为私有方法 `Block buildMonthBlock(String yearMonth, 过滤条件)`，query 路径与第 4 节 syncMonth 的对齐计算共用同一份实现（禁止复制出第二份行构建逻辑）。

### 4. ProjectProfitService.syncMonth 月度一键同步编排（决策4）

注入依赖：在第 3 节所需 mapper 之外，额外注入 `WorktimeMonthlySyncService`、`BusinessLineProfitService`、`RevenueMonthCloseMapper`（三者均已存在，无循环依赖：两个 sync service 均不依赖 ProjectProfitService）。

`ProjectProfitMonthSyncVO syncMonth(String yearMonth)`，严格按序执行：

1. 校验：yearMonth 必须匹配 `\d{4}-\d{2}` 且不大于当前自然月（`LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))`），否则抛 IllegalArgumentException。
2. monthClosed = `RevenueMonthCloseMapper.selectCount(year_month = yearMonth AND closed_at IS NOT NULL) > 0`（与 WorktimeMonthlySyncService.isMonthClosed 同口径）。
3. `List<WorktimeSyncLog> logs = new ArrayList<>(worktimeMonthlySyncService.syncConfirmedMonths(yearMonth, "manual"))` —— 一步同时重拉当月工时明细（worklog）与成本分析（cost，含 work_type=sales 销售工时行，即"销售数据"），整月覆盖写入 revenue_cost_entry。既有行为：forceMonth 绕过工时系统「已确认」状态限制强制拉取；已完结月在 syncConfirmedMonths 内部被跳过、返回空 logs。异常已被各子步骤捕获为 failed 日志，不向上抛。
4. `logs.add(businessLineProfitService.syncMonth(yearMonth, "manual"))` —— 刷新当月 biz_line_profit_report 镜像（合计行权威来源，含 6 成本列与营收）。必须在第 3 步之后执行：镜像成本与明细成本同源同月刷新，差额行才是当期真实对齐状态。
5. 对齐计算：调用第 3 节抽公的 `buildMonthBlock(yearMonth, 无过滤)`，对返回的每条 Line 取其 residual 行映射为 LineAlignment（revenueResidual=residual.revenue、costResidual=residual.cost、hoursResidual=residual.hours；null 按 0；aligned 阈值见 VO 注释）。镜像无该月数据时 lines 为空列表（buildMonthBlock 返回的 lines 即空），不报错。
6. 组装 ProjectProfitMonthSyncVO 返回；任一步失败仅体现在对应 log.status='failed' + message，方法本身不再抛业务异常（仅参数校验抛 IllegalArgumentException）。

不在本编排内：OA 合同明细（revenue_contract_entry）由既有 WorktimeContractCollector 每日自动同步（按年粒度、耗时长），月度按钮不重复拉取；营收侧差额会随每日合同同步自动更新。

### 5. 前端（frontend-pro，React 19 + Umi Max + antd 6）

a) `src/services/superwork/api.ts`（在 bl-profit 函数块附近，约 3172 行后追加）：
- 类型 `ProjectProfitReport / ProjectProfitBlock / ProjectProfitLine / ProjectProfitRow / ProjectProfitLineOption / ProjectProfitAllocation / ProjectProfitMonthSyncResult / ProjectProfitLineAlignment`（字段与两个 VO 对应；WorktimeSyncLog 类型已存在则复用）。
- `getProjectProfitReport(params: {year:number; months?:number[]; periods?:string[]; businessLineIds?:number[]; categories?:string[]; projectIds?:number[]})` → GET /api/finance/project-profit（数组拼 CSV 查询串）。
- `syncProjectProfitMonth(yearMonth: string)` → POST /api/finance/project-profit/sync-month，body `{yearMonth}`。
- `syncProjectProfit(body:{year?:number;month?:string})` → POST /api/finance/project-profit/sync（整年镜像回填，保留）。
- `getProjectProfitSyncLogs(limit)` → GET /api/finance/project-profit/sync-logs。
- `getProjectProfitAllocations(yearMonth:string, businessLineId:number)`、`saveProjectProfitAllocations(body)`（POST /allocations/batch）、`deleteProjectProfitAllocation(id:number)`。

b) `src/pages/project-profit/index.tsx` + `style.less`（以 `src/pages/bl-profit/index.tsx` 为模板，复用其 formatWan/formatRate/formatHours/负数标红/SyncCutoff/同步按钮模式）：
- 页头：标题「项目利润」、年份 Select（复用 yearOptions 模式）、视图 Segmented（月度/H1/H2/全年）、月度视图下月份多选 Select（options=availableMonths）、业务线 pills 多选（照抄 bl-profit 的 pill 模式，数据源 lineOptions）、分类多选（项目/销售）、项目多选（options 随选中业务线联动）；SyncCutoff 组件 + 最近同步标签（lastSyncedAt / sync-logs）。
- 页头右侧同步区（主按钮为月度一键同步，决策4）：
  - 月份单选 Select（同步目标月）：options = 当前选中年的 `YYYY-01`..`YYYY-12` 且不大于当前自然月，降序；默认值 = `dayjs().subtract(1,'month').format('YYYY-MM')`，若该值不在 options 内（如选中往年）则取 options[0]。
  - 「同步月度数据」主按钮（type=primary，loading=syncing）：点击调 `syncProjectProfitMonth(选中月)`，完成后弹结果 Modal：
    - 顶部展示 monthClosed=true 时的 Alert「该月已完结，工时/成本未重拉，仅刷新了利润镜像」；
    - 同步步骤列表：logs 逐条展示 syncType（worklog/cost/bl_profit）× status（success 绿 / failed 红）× upsertCount 行数 × failed 时 message；
    - 对齐结果列表：lines 逐条展示业务线名 + aligned=true 显示「✓ 已对齐」，否则展示差额数值（营收/成本/工时差额，负数标红，金额 formatWan、工时 formatHours）；
    - 任一 log failed 时 Modal 顶部额外 warning 提示「部分同步失败，对齐结果基于现有数据计算」；
    - 关闭 Modal 后 `Promise.all([loadReport(year), loadSyncLogs()])` 刷新（照抄 bl-profit syncFromWorktime 的收尾模式）。
  - 原「同步工时系统」整年按钮保留为次要按钮（调 syncProjectProfit({year})，行为与 bl-profit 页一致），用于历史回填。
- 表格 15 列 = 月份(块 label) | 业务线 | 分类 | 项目 | 营业收入(万) | 短信成本 | 直接成本 | 平台佣金&手续费 | 赔付 | 协力&外包 | 软件赠送 | 工时(人月) | 成本(万) | 考核毛利(万) | 考核毛利率。平铺行（不做 rowSpan，照 bl-profit 重复标签的做法）。行序：项目行… → 销售行（销售、业务线） → 差额行（warning 色，仅非零渲染） → 合计行（沿用 sw-bl-profit-total-row 样式类名写法到本项目 style.less）。
- 分配入口：rowType=PROJECT 且 editable=true 且当前为月度块 的行尾「分配」按钮 → Drawer：展示该月该业务线 6 个成本列的 合计值 / 已分配(Σ 兄弟项目行) / 未分配余额，6 个 InputNumber 编辑当前项目值 + 备注；保存调 saveProjectProfitAllocations 后刷新报表。聚合块（H1/H2/全年）不出现分配按钮（分配是月度粒度）。
- 空态：availableMonths 为空 → Empty + 提示「请先用右上角「同步月度数据」选择月份拉取工时系统数据」。

c) `config/routes.ts`：在 /bl-profit 路由对象（247-252 行）后追加 `{ path: '/project-profit', name: '项目利润', icon: 'fund', component: './project-profit' }`。

d) `src/app.tsx`：accessForPath 的 management 路径数组（约 621-636 行，含 "/bl-profit"）中加入 "/project-profit"。菜单图标 'DataLine' 已在 menuIconByServerName（105 行）映射为 BarChartOutlined，无需改动。

### 6. 测试（backend/src/test/java/com/bu/management/service/ProjectProfitServiceTest.java）

照 BusinessLineProfitServiceTest 的 Mockito 模式（mock 全部 mapper + WorktimeMonthlySyncService + BusinessLineProfitService；后两者仅供 syncMonth 编排使用，query 路径不依赖它们）。覆盖：
- 月份轴只来自镜像：revenue_cost_entry 有数据但镜像缺失的月份不出块。
- full 线组装：子项目工时归并根项目、别名（佳贝艾特→澳优）合并、销售行=全部 sales、业务线行=project_id 为 null 的 project 行。
- aggregate 线：项目集行继承镜像 revenue/6 成本列，销售行单列。
- simple 线：单行合并 project+sales 工时成本。
- 差额行 = 合计 − Σ明细（构造镜像 1000、项目行 600、销售行 0 → 差额 400）。
- 公式：行 grossProfit = revenue − 各成本列 − cost；rate 在 revenue=0 时为 null。
- H1 聚合：跨月求和且率重算；空交集块省略。
- projectIds 过滤只影响 PROJECT 行。
- batch upsert 校验：非 full 线 / 项目不属于业务线 / costType 非法 → IllegalArgumentException。
- syncMonth 编排（决策4）：
  - 顺序与委托：mock WorktimeMonthlySyncService.syncConfirmedMonths 返回 worklog/cost 两条 success 日志、BusinessLineProfitService.syncMonth 返回 bl_profit 日志，InOrder 验证 monthly 先于 bl_profit；返回 VO.logs 含 3 条、yearMonth 透传。
  - monthClosed：RevenueMonthCloseMapper.selectCount 返回 1 且 monthlySyncService 返回空列表 → VO.monthClosed=true 且 logs 仅含 bl_profit 一条。
  - 对齐结果：构造镜像合计 revenue=1000/cost=800、明细项目行 revenue=600/cost=800 → lines[0].revenueResidual=400、costResidual=0、aligned=false；再构造明细=合计 → aligned=true。
  - 镜像缺失月份：buildMonthBlock 无数据 → lines 为空列表，不抛异常。
  - 参数校验：yearMonth='2026-13' / 未来月份 → IllegalArgumentException。

## Critical files & anchors

- `backend/src/main/java/com/bu/management/service/BusinessLineProfitService.java` — syncMonth/syncYear/recentSyncLogs 委托对象（52-88、292-297 行）+ managed 线过滤范式（queryYear 192-205 行照搬到新 service）。
- `backend/src/main/java/com/bu/management/service/WorktimeMonthlySyncService.java` — 月度一键同步的第一步委托对象：`syncConfirmedMonths(forceMonth, triggeredBy)`（43-78 行）一步产出 worklog+cost 两个日志；forceMonth 绕过「已确认」限制、已完结月跳过。
- `backend/src/main/java/com/bu/management/service/RevenueDeliverySummaryService.java` — PROJECT_ALIASES（71-73 行）与 rootIdOf 归并、full/aggregate/simple 行模式的口径来源；只复制小工具，不改此类。
- `backend/src/main/resources/db/migration/V74__business_line_profit_report.sql` — 菜单/权限/角色授权 SQL 模板；biz_line_profit_report 列名（snake_case）对照。
- `frontend-pro/src/pages/bl-profit/index.tsx` + `frontend-pro/src/app.tsx`(617-640 行路由守卫) + `frontend-pro/config/routes.ts`(247-252 行) — 前端三处锚点；bl-profit 页 92-109 行 syncFromWorktime 为月度同步按钮的交互模板（loading/message/收尾刷新）。

## Verification

1. 后端单测：`cd backend && mvn -q test -Dtest=ProjectProfitServiceTest` 全绿。
2. 迁移验证：`docker compose -f docker/docker-compose.yml up -d mysql`（库 bu_management，账号 bu_admin/bu_admin123，application-dev.yml 默认指向）后 `cd backend && mvn spring-boot:run` 启动，Flyway 应用 V95 无错；`mysql -h127.0.0.1 -ubu_admin -pbu_admin123 bu_management -e "show tables like 'project_profit%'; select path from sys_menu where path='/project-profit'; select code from sys_permission where code like 'project-profit%'"` 确认表/菜单/权限落库。
3. 接口行为验证（新行为，非仅编译）：登录拿 JWT（POST /api/auth/login，dev 库管理员账号），`curl -H "Authorization: Bearer $T" "http://localhost:8080/api/finance/project-profit?year=2026"` → 返回 blocks，且任一业务线某月 total.revenue 与 `/api/finance/bl-profit?year=2026` 同线同月 revenue 相等（备注6 对齐验证）；再 POST /allocations/batch 给一个 full 线项目分配 direct_cost=100 后重查，该项目行 directCost=100 且 residual.directCost 减少 100。
4. 月度一键同步验证（决策4，新行为核心证据）：`curl -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"yearMonth":"<工时系统已有数据的最近月份>"}' http://localhost:8080/api/finance/project-profit/sync-month` → 响应 logs 含 syncType=worklog/cost/bl_profit 且 status=success；lines 每条含三列 residual 数值与 aligned 布尔；随后 `mysql … -e "select year_month, count(*) from revenue_cost_entry where year_month='<月>' group by 1; select year_month, max(synced_at) from biz_line_profit_report where year_month='<月>' group by 1"` 确认两表该月数据已刷新（synced_at 为点击时刻）；再 GET 报表确认该月出现/更新且差额行数值与响应 lines 一致。若本地无工时系统连通性，降级为单测证据（第 1 步编排用例）+ 在交付说明注明。
5. 前端：`cd frontend-pro && npm run tsc` 无错；`npm run dev` 启动后浏览器开 /project-profit：表格 15 列渲染、切换 H1/全年聚合正确、分配 Drawer 保存后差额行数值联动、选月点「同步月度数据」弹出步骤+对齐结果 Modal 且关闭后报表刷新。若本地库无财报数据导致页面全空，以第 3/4 步 curl 证据为准并在交付说明中注明 UI 仅验证到空态/渲染。

## Assumptions & contingencies

- 工时系统无项目级利润接口（现有客户端仅业务线级报告 + 项目级成本分析），故项目分解在本系统组装；若后续工时系统新增 `/finance/report/project/...`，本功能的镜像合计行与手动分配表可直接保留，仅需把项目行数据源替换为新同步镜像。
- 「都要对齐」的落地方式 = 合计行取镜像（天然对齐汇总表）+ 差额行暴露明细缺口 + 6 列手动分配可消除成本类差额；营收口径差（OA 交付 vs 财报营收）通过差额行可见，不做强制插值。
- 报表月份轴由镜像已同步月份驱动：成本/合同数据早于镜像就绪属正常，差额行会体现；镜像未同步的月份不显示。
- 会员通/精准的「项目」行营收取镜像而非 OA 交付（该行即整线，无分解意义；与备注6 对齐优先）。真实项目（saas/定制）营收取 OA 已交付（用户决策1）。
- 分配金额允许负数（调整场景）；不强制 Σ分配 ≤ 线合计，差额行承担提示职责。
- 月度一键同步（决策4）的「销售数据」= 工时系统成本分析中 work_type=sales 的销售工时成本行，与项目成本同一管道（syncConfirmedMonths）一次拉回；OA 合同/营收数据由既有每日自动同步覆盖，不纳入该按钮。
- 月度按钮用 forceMonth 强制拉取，工时系统该月未置「已确认」也会同步（沿用 WorktimeMonthlySyncService 既有设计，用于数据修正后重拉）；已完结月（revenue_month_close）工时/成本被跳过属预期，镜像仍刷新，前端以 monthClosed 提示。
- 若本地开发库无同步数据或工时系统不可达，第 5 步 UI 验证降级为空态验证 + 接口级/单测证据（第 1、3、4 步）。
