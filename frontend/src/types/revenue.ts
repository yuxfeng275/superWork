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
