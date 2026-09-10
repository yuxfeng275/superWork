// KPI 周报与工时系统集成类型

export interface KpiNote {
  id: number
  metric: 'revenue' | 'profit'
  alertLevel: 'red' | 'yellow'
  deviationReason: string | null
  isAbnormal: number | null
  countermeasure: string | null
  status: 'pending' | 'done'
  updatedAt: string | null
}

export interface KpiSnapshotRow {
  snapshotId: number
  weekEndDate: string
  ytdRevenue: number
  ytdDirectCost: number
  ytdLaborCost: number
  ytdOtherCost: number
  ytdProfit: number
  weekDeltaRevenue: number
  weekDeltaProfit: number
  estimated: number
  revenueRate: number | null
  profitRate: number | null
  notes: KpiNote[]
}

export interface KpiGroupRow {
  reportGroup: string
  revenueTarget: number | null
  profitTarget: number | null
  latest: KpiSnapshotRow | null
  snapshots: KpiSnapshotRow[]
}

export interface KpiReportTotal {
  revenueTarget: number
  profitTarget: number
  ytdRevenue: number
  ytdProfit: number
  weekDeltaRevenue: number
  weekDeltaProfit: number
  revenueRate: number | null
  profitRate: number | null
}

export interface KpiReport {
  year: number
  groups: KpiGroupRow[]
  total: KpiReportTotal
}

export interface KpiTarget {
  id: number
  year: number
  reportGroup: string
  revenueTarget: number
  profitTarget: number
  remark: string | null
}

export interface KpiAlertRule {
  id: number
  reportGroup: string | null
  weeklyDivisor: number
  yellowRatio: number
  enabled: number
}

export interface WorktimeSyncLog {
  id: number
  syncType: 'contract' | 'worklog' | 'cost'
  scope: string | null
  status: 'running' | 'success' | 'failed'
  totalCount: number
  upsertCount: number
  pendingCount: number
  message: string | null
  triggeredBy: 'schedule' | 'manual'
  startedAt: string | null
  finishedAt: string | null
}

export interface WorktimeStatus {
  enabled: boolean
  baseUrl: string | null
  credentialConfigured: boolean
  credentialSource: string
  lastTestedAt: string | null
  lastTestStatus: string | null
  lastTestMessage: string | null
  lastContractSync: WorktimeSyncLog | null
  lastWorklogSync: WorktimeSyncLog | null
  lastCostSync: WorktimeSyncLog | null
}

export interface WorktimeTestResult {
  success: boolean
  message?: string
  visibleBusinessLines?: Array<{ id: number; name: string }>
  dataCutoffDate?: string
}

export interface MenuTreeNode {
  id: number
  name: string
  icon: string | null
  path: string | null
  sortOrder: number | null
  children: MenuTreeNode[]
}
