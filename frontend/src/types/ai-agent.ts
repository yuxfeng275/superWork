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
