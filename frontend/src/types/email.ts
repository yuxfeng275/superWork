export type EmailProvider = 'ALIBABA_CLOUD_ENTERPRISE_MAIL'
export type EmailConnectionStatus = 'UNCONFIGURED' | 'UNTESTED' | 'CONNECTED' | 'FAILED' | 'DISCONNECTED'
export type EmailSyncState = 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'
export type EmailDigestStatus = 'READY' | 'SUCCESS' | 'DEGRADED' | 'PENDING' | 'EMPTY' | 'FAILED'
export type EmailDigestMode = 'AI' | 'RULES' | 'NONE'
export type EmailPushStatus = 'SUCCESS' | 'PENDING' | 'NOT_REQUIRED' | 'NOT_CONFIGURED' | 'UNMAPPED' | 'FAILED'

export interface EmailAccount {
  configured: boolean
  enabled: boolean
  provider: EmailProvider
  emailAddress?: string
  credentialConfigured: boolean
  connectionStatus?: EmailConnectionStatus
  lastTestedAt?: string
  lastSyncAt?: string
  lastSyncStatus?: EmailSyncState
  lastSyncMessage?: string
}

export interface EmailAccountPayload {
  emailAddress: string
  appPassword: string
}

export interface EmailConnectionTestResult {
  success: boolean
  message: string
  testedAt: string
}

export interface EmailSyncStatus {
  status: EmailSyncState
  startedAt?: string
  finishedAt?: string
  completedAt?: string
  syncedCount?: number
  count?: number
  message?: string
  error?: string
}

export interface EmailAttachment {
  fileName: string
  contentType?: string
  size: number
}

export interface EmailMessageSummary {
  id: number
  messageId: string
  subject?: string
  fromName?: string
  fromAddress: string
  receivedAt: string
  preview?: string
  projectId?: number
  projectName?: string
  projectFullPath?: string
  groupingStatus?: string
  groupingConfidence?: number
  hasAttachments: boolean
  attachmentCount: number
}

export interface EmailInterpretationActionItem {
  content?: string
  deadline?: string
  priority?: string
}

export type EmailInterpretationStatus = 'NOT_GENERATED' | 'GENERATING' | 'SUCCESS' | 'FAILED'

export interface EmailInterpretation {
  status: EmailInterpretationStatus
  summary?: string
  senderIntent?: string
  keyPoints: string[]
  actionItems: EmailInterpretationActionItem[]
  risks: string[]
  replySuggestion?: string
  model?: string
  errorMessage?: string
  generatedAt?: string
}

export interface EmailMessageDetail extends EmailMessageSummary {
  groupingReason?: string
  toAddresses: string[]
  ccAddresses: string[]
  textBody: string
  attachments: EmailAttachment[]
  interpretation: EmailInterpretation
}

export interface EmailMessagePage {
  records: EmailMessageSummary[]
  total: number
  size: number
  current: number
  pages?: number
}


export interface EmailSenderCompanyGroup {
  domain: string
  companyName: string
  mailCount: number
}

export interface EmailProjectGroup {
  projectId?: number
  projectName: string
  projectFullPath: string
  mailCount: number
}

export type EmailGroupingJobState = 'IDLE' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface EmailGroupingJobStatus {
  status: EmailGroupingJobState
  total: number
  processed: number
  grouped: number
  ungrouped: number
  message?: string
  startedAt?: string
  finishedAt?: string
}

export interface EmailMessageQuery {
  page?: number
  size?: number
  date?: string
  keyword?: string
  projectId?: number
  ungrouped?: boolean
  senderDomain?: string
}

export interface EmailDigestItem {
  messageId: number
  title?: string
  subject?: string
  sender?: string
  content?: string
  summary?: string
  action?: string
  deadline?: string
}

export interface EmailDailyDigest {
  id?: number
  businessDate: string
  status: EmailDigestStatus
  generationMode?: EmailDigestMode
  generatedModel?: string
  overview?: string
  mailCount: number
  importantItems: EmailDigestItem[]
  todos: EmailDigestItem[]
  risks: EmailDigestItem[]
  replySuggestions: EmailDigestItem[]
  generatedAt?: string
  pushStatus?: EmailPushStatus
  pushMessage?: string
}
export interface EmailWeComMapping {
  configured: boolean
  enabled: boolean
  weComUserId?: string
}
