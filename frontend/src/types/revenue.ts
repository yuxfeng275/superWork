export type RevenueEntryType = 'h2_estimate' | 'partner_cost' | 'server_cost' | 'other_cost'

export interface RevenueMonthlyTrendItem {
  month: string
  income: number
  cost: number
}

export interface RevenueMonthlyData {
  month: string
  income: number
  hours: number | null
  cost: number
}

export interface RevenueProjectSummary {
  projectId: number
  projectName: string
  receivable: number
  received: number
  deliveryHours: number | null
  deliveryCost: number
  salesCost: number
  partnerCost: number
  serverCost: number
  otherCost: number
  totalCost: number
  profit: number
  profitRate: number | null
  h2Estimate: number | null
  months: RevenueMonthlyData[]
}

export interface RevenueBusinessLineSummary {
  businessLineId: number
  businessLineName: string
  type: 'project_breakdown' | 'business_line_summary' | string
  totalReceivable: number
  totalReceived: number
  totalCost: number
  totalProfit: number
  profitRate: number | null
  projects: RevenueProjectSummary[]
  months: RevenueMonthlyData[]
}

export interface RevenueSummary {
  year: number
  totalReceivable: number
  totalReceived: number
  totalCost: number
  totalProfit: number
  profitRate: number | null
  monthlyTrend: RevenueMonthlyTrendItem[]
  businessLines: RevenueBusinessLineSummary[]
}

export interface RevenueImportResult {
  successCount: number
  newMappingCount: number
  pendingMappingCount: number
  errors: string[]
}

export interface RevenueMapping {
  id: number
  sourceType: string
  sourceName: string
  projectId: number | null
  businessLineId: number | null
  category: 'delivery' | 'sales' | 'product' | string
  status: number
}

export interface RevenueImportRecord {
  id: number
  importType: 'cost' | 'income' | string
  fileName: string
  successCount: number
  newMappingCount: number
  pendingMappingCount: number
  errorCount: number
  createdBy: number | null
  createdAt: string
}

export interface RevenueManualEntryDTO {
  id: number
  yearMonth: string
  projectId: number | null
  businessLineId: number
  entryType: RevenueEntryType
  amount: number
  remark: string | null
}
