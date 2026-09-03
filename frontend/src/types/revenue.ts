// 营收管理（工时与成本）类型定义
// 金额单位：元；工时单位：人月。前端展示层负责换算为万元。

export type RevenueCellSource = 'actual' | 'estimate' | 'mixed' | null

export interface RevenueCell {
  hours: number
  cost: number
  source: RevenueCellSource
  estimateCount?: number | null
}

export interface RevenueMonthInfo {
  yearMonth: string
  closed: boolean
}

export type RevenueRowKind = 'project' | 'line_pool' | 'sales_specific' | 'pool' | 'other'
  | 'agg_project' | 'agg_sales' | 'simple'

export interface RevenueRow {
  rowKey: string
  name: string
  kind: RevenueRowKind
  projectId?: number | null
  salesProjectId?: number | null
  opportunityId?: number | null
  opportunityName?: string | null
  /** 累计完结人均成本（元/人月） */
  unitPrice?: number | null
  months: RevenueCell[]
  totals: RevenueCell
}

export interface RevenueSection {
  type: 'project' | 'sales'
  rows: RevenueRow[]
}

export interface RevenueLineBlock {
  businessLineId: number
  businessLineName: string
  /** full=项目+销售明细行 / aggregate=项目销售两行聚合 / simple=单行汇总 */
  mode: 'full' | 'aggregate' | 'simple'
  sections: RevenueSection[]
  monthTotals: RevenueCell[]
  totals: RevenueCell
}

export interface RevenueOverview {
  totalHours: number
  projectHours: number
  salesHours: number
  totalCost: number
  avgUnitPrice?: number | null
  closedMonthCount: number
}

export interface RevenueMatrix {
  year: number
  months: RevenueMonthInfo[]
  lines: RevenueLineBlock[]
  monthTotals: RevenueCell[]
  grandTotal: RevenueCell
  overview: RevenueOverview
}

export interface RevenueWorklogEntry {
  id: number
  yearMonth: string
  businessLineName: string
  businessLineId?: number | null
  projectNameRaw: string
  projectId?: number | null
  workType: string
  salesKind?: string | null
  salesProjectId?: number | null
  employeeNo?: string
  employeeName?: string
  department?: string
  hours: number
  workNote?: string
  specialNote?: string
  tags?: string
  pending: number
}

export interface RevenueCostEntry {
  id: number
  yearMonth: string
  businessLineName: string
  businessLineId?: number | null
  projectNameRaw: string
  projectId?: number | null
  workType: string
  salesKind?: string | null
  employeeCount?: number | null
  hours: number
  costAmount: number
  personMonthCost?: number | null
  pending: number
}

export interface RevenueCellDetail {
  closed: boolean
  worklogEntries?: RevenueWorklogEntry[]
  costEntries?: RevenueCostEntry[]
  estimates?: RevenueEstimateEntry[]
}

export interface RevenueEstimateEntry {
  id: number
  yearMonth: string
  businessLineId: number
  projectId?: number | null
  workType: string
  salesKind?: string | null
  salesProjectId?: number | null
  description: string
  personMonths: number
  unitPrice?: number | null
  amount?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface RevenueImportBatch {
  id: number
  importType: 'worklog' | 'cost'
  yearMonth: string
  fileName: string
  totalCount: number
  successCount: number
  pendingCount: number
  createdAt: string
}

export interface RevenueImportResult {
  batchId: number
  totalCount: number
  successCount: number
  pendingCount: number
}

export interface RevenueSalesProject {
  id: number
  businessLineId: number
  name: string
  opportunityId?: number | null
  opportunityName?: string | null
}

export interface RevenueOpportunityOption {
  id: number
  name: string
  customer?: string
}

// ---------------------------------------------------------------
// 交付与利润（项目交付营收看板）类型定义
// 金额单位：元；工时单位：人月。前端展示层换算为万元。
// 结构与后端 /api/revenue/delivery/summary 的 RevenueDeliverySummaryVO 对齐。
// ---------------------------------------------------------------

/** 手动维护的其他成本拆分（协力/服务器/其他） */
export interface DeliveryOtherCosts {
  partner?: number | null
  server?: number | null
  other?: number | null
  total?: number | null
}

/** 未分配销售成本原因（全年口径汇总） */
export interface DeliveryUnallocatedItem {
  /** NO_OPP_LINK / NO_MATCH_CONTRACT / MULTI_PROJECT / POOL_NO_EVIDENCE */
  reason: string
  label: string
  /** 全年金额（元） */
  cost: number
}

/**
 * 单一时段（h1=1-6月 / h2=7-12月 / ytd=全年）交付营收块。
 * 销售口径：项目行 allocatedSales*=成单（有明确成单证据才分配）；线/表汇总
 * unallocatedSales*=未分配剩余（只扣业务线利润，不做均摊）。行级 salesHours/salesCost
 * 为该窗口销售合计（仅业务线/汇总行有值，项目行=0）。
 * 利润口径：项目行 grossProfit 不减销售成本，trueProfit=grossProfit−成单销售成本；
 * 汇总行 trueProfit=grossProfit（销售成本全额已计入）。
 */
export interface DeliveryPeriodBlock {
  /** 实际已交付应收额（元） */
  delivered?: number | null
  /** 预估交付额（元） */
  estimated?: number | null
  /** 项目工时合计（人月，完结月实际） */
  projectHours?: number | null
  /** 项目工时成本（元，完结月实际） */
  projectLaborCost?: number | null
  /** 预估交付关联的预估工时成本（元） */
  estimatedLaborCost?: number | null
  /** 销售工时合计（人月；仅业务线级，项目行=0） */
  salesHours?: number | null
  /** 销售工时成本（元；仅业务线级，项目行=0） */
  salesCost?: number | null
  /** 已分配（成单→本行）销售工时（人月） */
  allocatedSalesHours?: number | null
  /** 已分配（成单→本行）销售工时成本（元） */
  allocatedSalesCost?: number | null
  /** 未分配销售工时（人月；项目行=0，仅线/表汇总） */
  unallocatedSalesHours?: number | null
  /** 未分配销售工时成本（元；项目行=0，仅线/表汇总） */
  unallocatedSalesCost?: number | null
  otherCosts?: DeliveryOtherCosts | null
  /** 人工成本后利润 = 营收 − 项目人工(±预估) − 销售成本（行级为 0） */
  laborProfit?: number | null
  /** 毛利 = laborProfit − 其他成本（历史口径：项目行不减销售成本；汇总行减全部销售成本） */
  grossProfit?: number | null
  /** 毛利率（%），营收为 0 时为 null */
  grossRate?: number | null
  /** 真实利润：项目行=毛利−已分配销售成本；线/表汇总=毛利（销售全额已计） */
  trueProfit?: number | null
  /** 真实利润率（%） */
  trueProfitRate?: number | null
}

/**
 * 业务线级未落具体项目的合同（如福田定制型业务线级合同）：不进 projects 项目行，
 * 只在业务线级汇总可追溯。金额单位元。后端尚未全部落地时字段整体缺失，按 0 兼容回退。
 * - lineUnallocated*：业务线级未落项目合同口径（总额/已交付/利润）。
 * totals 行同名字段为业务线级镜像，全表 overview 用 total* 前缀。
 */
export interface DeliveryLineLevelContract {
  /** 业务线级未落项目合同总额（元） */
  lineUnallocatedContract?: number | null
  /** 业务线级未落项目合同已交付金额（元） */
  lineUnallocatedDelivered?: number | null
  /** 业务线级未落项目合同利润（元） */
  lineUnallocatedProfit?: number | null
}

/** 营收行（真实项目、会员通聚合行，以及业务线 totals 汇总行共用此结构） */
export interface DeliveryProjectRow extends DeliveryLineLevelContract {
  /** 真实项目 id；业务线聚合行/汇总行无真实项目时为 null */
  projectId: number | null
  name: string
  /** true=业务线聚合行（会员通项目集等） */
  isAggregate?: boolean
  /** 全年 OA 合同总额（元） */
  oaContract?: number | null
  h1?: DeliveryPeriodBlock | null
  h2?: DeliveryPeriodBlock | null
  ytd?: DeliveryPeriodBlock | null
}

/** 业务线块：全年销售工时/成本（合计、已分配、未分配）+ 营收项目行 + 线 totals */
export interface DeliverySummaryLine extends DeliveryLineLevelContract {
  businessLineId: number
  businessLineName: string
  /** 该线全年销售工时合计（人月，完结月实际） */
  salesHours?: number | null
  /** 该线全年销售工时成本（元） */
  salesCost?: number | null
  /** 该线全年已分配（成单→项目）销售工时（人月） */
  salesAllocatedHours?: number | null
  /** 该线全年已分配销售工时成本（元） */
  salesAllocatedCost?: number | null
  /** 该线全年未分配销售工时（人月） */
  salesUnallocatedHours?: number | null
  /** 该线全年未分配销售工时成本（元） */
  salesUnallocatedCost?: number | null
  /** 未分配销售成本原因明细（按原因代码汇总，全年） */
  salesUnallocatedDetail?: DeliveryUnallocatedItem[]
  /** 交付日期为空、未计入任何年份窗口的合同金额（元） */
  noDeliveryDateContract?: number | null
  projects: DeliveryProjectRow[]
  /** 业务线汇总行（结构同项目行；窗口含线级销售拆分与重算后的利润） */
  totals: DeliveryProjectRow
}

/** 全表合计 */
export interface DeliveryOverview {
  includeEstimate?: boolean
  /** 全年 OA 合同总额（元） */
  totalOaContract?: number | null
  totalDelivered?: number | null
  totalEstimated?: number | null
  /** 总人工成本（项目工时成本 + 预估工时成本(含预估口径) + 销售工时成本） */
  totalLaborCost?: number | null
  /** 已分配销售工时成本合计（元） */
  totalAllocatedSalesCost?: number | null
  /** 未分配销售工时成本合计（元） */
  totalUnallocatedSalesCost?: number | null
  totalOtherCost?: number | null
  totalProfit?: number | null
  profitRate?: number | null
  /** 整表真实利润（= totalProfit，销售成本已全额计入） */
  totalTrueProfit?: number | null
  trueProfitRate?: number | null
  salesUnallocatedDetail?: DeliveryUnallocatedItem[]
  /** 全表业务线级未落项目合同总额（元，兼容后端未落地时缺失） */
  totalLineUnallocatedContract?: number | null
  /** 全表业务线级未落项目合同已交付金额（元） */
  totalLineUnallocatedDelivered?: number | null
  /** 全表业务线级未落项目合同利润（元） */
  totalLineUnallocatedProfit?: number | null
  /** 交付日期为空、未泄漏至任何年份汇总的合同金额（元） */
  totalNoDeliveryDateContract?: number | null
}

export interface DeliverySummary {
  year: number
  includeEstimate?: boolean
  lines: DeliverySummaryLine[]
  overview: DeliveryOverview
}

/** 预估交付计划记录 */
export interface DeliveryPlan {
  id: number
  yearMonth: string
  businessLineId: number
  projectId?: number | null
  projectName?: string | null
  /** 预估交付金额（元） */
  amountYuan: number
  personMonths: number
  /** 预估交付成本（元，服务端按单价快照计算） */
  laborCostYuan?: number | null
  unitPriceSnapshot?: number | null
  createdAt?: string
}

export type DeliveryCostType = 'partner' | 'server' | 'other'

/** 其他成本记录（协力/服务器/其他） */
export interface DeliveryOtherCost {
  id: number
  yearMonth: string
  businessLineId: number
  projectId?: number | null
  projectName?: string | null
  costType: DeliveryCostType
  amountYuan: number
  note?: string | null
  createdAt?: string
}

/** 合同导入记录；待映射与已映射共用字段 */
export interface DeliveryPendingContract {
  id: number
  contractNo?: string | null
  detailNo?: string | null
  contractName?: string | null
  brand?: string | null
  customer?: string | null
  itemDesc?: string | null
  bizLineRaw?: string | null
  bizLineId?: number | null
  projectId?: number | null
  businessLineName?: string | null
  projectName?: string | null
  receivableAmount?: number | null
  saleMonth?: string | null
  deliveryDate?: string | null
  pending?: number
}

/** 已映射合同归属调整记录 */
export type DeliveryMappedContract = DeliveryPendingContract

/** 合同导入批次 */
export interface DeliveryContractBatch {
  id: number
  fileName: string
  totalCount: number
  successCount: number
  pendingCount: number
  createdAt: string
}

/** 合同导入结果 */
export interface DeliveryContractImportResult {
  batchId: number
  total: number
  success: number
  pendingCount: number
}
