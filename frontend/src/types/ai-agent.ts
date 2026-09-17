/** AI 助手可选模型（后端按系统配置返回） */
export interface AiAgentModelOption {
  provider: string
  model: string
  label: string
}

/**
 * AI 助手（Pi Agent）类型定义。
 * 会话消息结构由服务端定义且较为宽松，这里提供基础可读字段 + 索引签名，
 * 避免后端调整字段时前端类型失效。
 */

/** 会话列表摘要项 */
export interface AiAgentSessionSummary {
  id: number
  title: string
  provider: string
  model: string
  updatedAt: string
  messageCount: number
}

/** 会话完整 DTO（含消息列表） */
export interface AiAgentSession {
  id: number
  title: string
  provider: string
  model: string
  updatedAt: string
  /** 消息数组；元素可为 user / assistant / toolResult 等，见 AiAgentMessage */
  messages: AiAgentMessage[]
  [key: string]: unknown
}

/** 会话中的单条消息（宽松结构，兼容服务端各类角色消息） */
export interface AiAgentMessage {
  role?: string
  content?: unknown
  [key: string]: unknown
}

/** 助手回复中的内容片段：文本 */
export interface AiAgentTextPart {
  type: 'text'
  text: string
}

/** 助手回复中的内容片段：思考过程 */
export interface AiAgentThinkingPart {
  type: 'thinking'
  thinking: string
}

export type AiAgentContentPart = AiAgentTextPart | AiAgentThinkingPart

/** 流式运行开始 */
export interface AiAgentRunStartEvent {
  type: 'run_start'
  runId: string
}

/** 助手消息开始（后续 text/thinking delta 以 index 定位） */
export interface AiAgentMessageStartEvent {
  type: 'message_start'
  index: number
}

/** 助手消息增量内容 */
export interface AiAgentMessageDeltaEvent {
  type: 'message_delta'
  index: number
  delta: { type: 'text_delta' | 'thinking_delta'; text: string }
}

/** 助手消息结束 */
export interface AiAgentMessageEndEvent {
  type: 'message_end'
  message: AiAgentMessage
}

/** 工具调用开始 */
export interface AiAgentToolExecutionStartEvent {
  type: 'tool_execution_start'
  toolCallId: string
  toolName: string
  args?: unknown
}

/** 工具调用结束 */
export interface AiAgentToolExecutionEndEvent {
  type: 'tool_execution_end'
  toolCallId: string
  result?: unknown
  isError: boolean
}

/** 整轮运行结束（可据此重新拉取会话以对齐最终消息） */
export interface AiAgentRunEndEvent {
  type: 'run_end'
  newMessages: AiAgentMessage[]
}

/** 流内错误（终止事件） */
export interface AiAgentErrorEvent {
  type: 'error'
  code?: number | string
  message: string
}

/** POST /api/ai-agent/sessions/{id}/messages 的 SSE 事件判别联合 */
export type AiAgentStreamEvent =
  | AiAgentRunStartEvent
  | AiAgentMessageStartEvent
  | AiAgentMessageDeltaEvent
  | AiAgentMessageEndEvent
  | AiAgentToolExecutionStartEvent
  | AiAgentToolExecutionEndEvent
  | AiAgentRunEndEvent
  | AiAgentErrorEvent

/** 连接器状态项（管理页 GET /api/connectors/status；AI 助手面板走免权限别名 GET /api/ai-agent/connectors） */
export interface AiConnectorStatus {
  code: string
  name: string
  status: 'READY' | 'DISABLED' | 'NOT_CONFIGURED'
  hint: string
}

/** 连接器认证类型 */
export type AiConnectorAuthType = 'BASIC' | 'TOKEN' | 'MCP' | 'SEEYON' | 'WECOM' | 'MAIL'

/** 连接器管理视图（GET /api/connectors） */
export interface AiConnectorView {
  id: number
  code: string
  name: string
  authType: AiConnectorAuthType
  baseUrl: string
  mcpUrl?: string
  testPath?: string
  queryPath?: string
  readPath?: string
  /** 系统专属扩展参数（如云效 edition/organizationId、语雀 repo、邮件 searchDays） */
  extraConfig: Record<string, unknown>
  usernameConfigured: boolean
  passwordConfigured: boolean
  tokenConfigured: boolean
  enabled: boolean
  /** 启用且必填项齐全 */
  ready: boolean
  /** 状态说明：未就绪原因或就绪后的用途提示 */
  hint: string
  lastTestStatus?: 'SUCCESS' | 'FAILED' | null
  lastTestMessage?: string
  lastTestedAt?: string
  builtIn: boolean
  sortOrder: number
}

/** 连接器创建/编辑请求体（凭据字段留空 = 保持不变；extraConfig 键缺省即删除） */
export interface AiConnectorSavePayload {
  code?: string
  name?: string
  authType?: AiConnectorAuthType
  baseUrl?: string
  mcpUrl?: string
  testPath?: string
  queryPath?: string
  readPath?: string
  extraConfig?: Record<string, unknown>
  username?: string
  password?: string
  token?: string
  enabled?: boolean
  sortOrder?: number
}

/** 模型管理视图（GET /api/ai/models）：provider 即连接器编码，连接就绪状态由后端附带 */
export interface AiModelView {
  id: number
  providerCode: string
  providerName: string
  /** 提供方连接器是否就绪（未就绪时模型不可用，需到连接器管理补全连接） */
  providerReady: boolean
  model: string
  displayName: string
  /** AI 助手可用 */
  assistantEnabled: boolean
  /** 用于邮件摘要与周报纪要 */
  digestEnabled: boolean
  /** 默认模型（同一时间只有一条，后端在设为 true 时自动取消其他行） */
  isDefault: boolean
  enabled: boolean
  sortOrder: number
}

/** 模型创建/编辑请求体（PUT 可只提交改动字段） */
export interface AiModelSavePayload {
  providerCode?: string
  model?: string
  displayName?: string
  assistantEnabled?: boolean
  digestEnabled?: boolean
  isDefault?: boolean
  enabled?: boolean
  sortOrder?: number
}

/** 站内通知（GET /api/ai/notices） */
export interface AiNotice {
  kind: string
  title: string
  body: string
  link?: string
  date: string
  read: boolean
}
