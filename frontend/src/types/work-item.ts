export type WorkItemDataSource = 'LOCAL' | 'YUNXIAO'
export type NormalizedWorkItemStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'OTHER'

export interface WorkItemOverviewItem {
  recordKey: string
  dataSource: WorkItemDataSource
  readOnly: boolean
  id?: number
  yunxiaoWorkitemId?: string
  serialNumber?: string
  category?: 'Req' | 'Task' | 'Bug'
  title: string
  description?: string
  projectIds: number[]
  projectNames: string[]
  projectId?: number
  projectName?: string
  projectFullPath?: string
  assigneeId?: number
  assigneeKey?: string
  assigneeName?: string
  assigneeUsername?: string
  status?: string
  normalizedStatus: NormalizedWorkItemStatus
  estimatedHours?: number
  actualHours?: number
  createdAt?: string
  updatedAt?: string
  lastSyncedAt?: string
  dueDate?: string
  overdueIncomplete?: boolean
  overdueDays?: number
  requirementId?: number
  requirementNo?: string
  requirementTitle?: string
  businessLineId?: number
  type?: string
  priority?: string
  businessSource?: string
  customerContactId?: number
  creatorId?: number
  expectedOnlineDate?: string
  startDate?: string
  endDate?: string
  taskType?: string
  linkedYunxiaoWorkitemId?: string
  linkedYunxiaoSerialNumber?: string
  linkedYunxiaoStatus?: string
  linkedYunxiaoLastSyncedAt?: string
}

export interface WorkItemDistributionItem {
  key: string
  label: string
  count: number
  percentage: number
}

export interface WorkItemAnalysis {
  statusDistribution: WorkItemDistributionItem[]
  projectDistribution: WorkItemDistributionItem[]
  ownerDistribution: WorkItemDistributionItem[]
  sourceDistribution: WorkItemDistributionItem[]
  priorityDistribution: WorkItemDistributionItem[]
  overdueProjectDistribution: WorkItemDistributionItem[]
  overdueOwnerDistribution: WorkItemDistributionItem[]
  overdueAgeDistribution: WorkItemDistributionItem[]
  totalEstimatedHours: number
  totalActualHours: number
  completionRate: number
  unassignedCount: number
  overdueIncompleteCount: number
  missingDueDateCount: number
}

export interface WorkItemOverviewSummary {
  totalCount: number
  localCount: number
  yunxiaoCount: number
  pendingCount: number
  inProgressCount: number
  completedCount: number
  otherCount: number
}

export interface WorkItemOverviewResponse {
  records: WorkItemOverviewItem[]
  total: number
  current: number
  size: number
  summary: WorkItemOverviewSummary
  analysis: WorkItemAnalysis
  lastSyncedAt?: string
}

export interface WorkItemOverviewParams {
  page?: number
  size?: number
  businessLineId?: number
  projectId?: number
  assigneeId?: number
  dataSource?: WorkItemDataSource | ''
  normalizedStatus?: NormalizedWorkItemStatus | ''
  type?: string
  priority?: string
  keyword?: string
}
