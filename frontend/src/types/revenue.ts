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
  deliveryHours: number | null
  deliveryCost: number
  salesCost: number
  partnerCost: number
  serverCost: number
  otherCost: number
  totalCost: number
  profit: number
  profitRate: number
  h2Estimate: number | null
  months: RevenueMonthlyData[]
}

export interface RevenueBusinessLineSummary {
  businessLineId: number
  businessLineName: string
  type: 'project_breakdown' | 'business_line_summary' | string
  totalReceivable: number
  totalCost: number
  totalProfit: number
  profitRate: number
  projects: RevenueProjectSummary[]
  months: RevenueMonthlyData[]
}

export interface RevenueSummary {
  year: number
  totalReceivable: number
  totalCost: number
  totalProfit: number
  profitRate: number
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

export interface RevenueManualEntryDTO {
  id: number
  yearMonth: string
  projectId: number | null
  businessLineId: number
  entryType: RevenueEntryType
  amount: number
  remark: string | null
}
