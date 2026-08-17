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
  hasAttachments: boolean
  attachmentCount: number
}

export interface EmailMessageDetail extends EmailMessageSummary {
  toAddresses: string[]
  ccAddresses: string[]
  textBody: string
  attachments: EmailAttachment[]
}

export interface EmailMessagePage {
  records: EmailMessageSummary[]
  total: number
  size: number
  current: number
  pages?: number
}

export interface EmailMessageQuery {
  page?: number
  size?: number
  date?: string
  keyword?: string
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
export interface EmailIntegrationConfig {
  configured: boolean
  deepSeekEnabled: boolean
  deepSeekBaseUrl: string
  deepSeekModel: string
  deepSeekApiKeyConfigured: boolean
  deepSeekTestStatus?: 'SUCCESS' | 'FAILED'
  deepSeekTestMessage?: string
  deepSeekTestedAt?: string
  weComEnabled: boolean
  weComBaseUrl: string
  weComCorpId?: string
  weComAgentId?: string
  weComSecretConfigured: boolean
  publicBaseUrl?: string
  weComTestStatus?: 'SUCCESS' | 'FAILED'
  weComTestMessage?: string
  weComTestedAt?: string
}

export interface EmailIntegrationConfigPayload {
  deepSeekEnabled: boolean
  deepSeekBaseUrl: string
  deepSeekModel: string
  deepSeekApiKey?: string
  weComEnabled: boolean
  weComBaseUrl: string
  weComCorpId?: string
  weComAgentId?: string
  weComSecret?: string
  publicBaseUrl?: string
}

export interface EmailIntegrationTestResult {
  success: boolean
  message: string
  testedAt: string
}
