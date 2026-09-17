// BG 周报与周会纪要
export interface WeeklyReportSheetTargetInfo {
  dateRangeLabel: string
  teamName: string
  sheetName: string
  sheetUrl: string
  minutesUrl?: string | null
}

export interface WeeklyReportFactsPreviewItem {
  title: string
  status: string
}

export interface WeeklyReportVO {
  id: number
  weekStartDate: string
  periodEndDate: string
  wecomSummary?: string | null
  manualNotes?: string | null
  coreWork?: string | null
  kpiSection?: string | null
  risks?: string | null
  nextWeekPlan?: string | null
  minutesMarkdown?: string | null
  status: 'PENDING' | 'GENERATING' | 'DRAFT' | 'CONFIRMED' | 'PUBLISHED' | 'GENERATION_FAILED'
  generationModel?: string | null
  generationMode?: string | null
  generationError?: string | null
  yuqueDocId?: number | null
  yuqueDocSlug?: string | null
  yuqueDocUrl?: string | null
  yuqueTocStatus?: 'VERIFIED' | 'MOVED' | 'NOT_FOUND' | null
  sheetSyncStatus?: 'PENDING' | 'MANUAL_DONE' | 'API_FAILED' | null
  sheetSyncedAt?: string | null
  wecomPushStatus?: string | null
  wecomPushedAt?: string | null
  createdAt?: string
  updatedAt?: string
  editable: boolean
  factsPreview: WeeklyReportFactsPreviewItem[]
  sheetTargetInfo?: WeeklyReportSheetTargetInfo | null
}

export interface WeeklyReportKeyMatterFact {
  id: number
  title: string
  projectName?: string | null
  ownerName?: string | null
  priority?: string | null
  status?: string | null
  progress?: number | null
  progressSummary?: string | null
  issues?: string | null
  nextWeekPlan?: string | null
  supportNeeded?: string | null
}

export interface WeeklyReportFinanceFact {
  month: string
  newContractAmount: number
  deliveredAmount: number
  cumulativeReceivable: number
}

export interface WeeklyReportLastWeekFact {
  exists: boolean
  weekStart?: string
  status?: string
  nextWeekPlan?: string | null
  risks?: string | null
}

export interface WeeklyReportFacts {
  weekStart: string
  periodEnd: string
  keyMatters: WeeklyReportKeyMatterFact[]
  finance: WeeklyReportFinanceFact
  lastWeekReport: WeeklyReportLastWeekFact
}
