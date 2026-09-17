/**
 * 企业微信官方 CLI（wecom-cli）机器人通道类型。
 *
 * 与应用通道（corpId/agentId/应用 Secret，应用消息推送）是两个授权域：
 * 机器人通道走 Bot ID + Bot Secret（或扫码绑定），操作待办/会议/文档等品类，
 * 且每个品类需在企微侧单独授权、会过期，故有独立的品类授权矩阵。
 */

/** 通道状态（GET /api/wecom-cli/status）。 */
export interface WecomCliStatus {
  cliInstalled: boolean
  authorized: boolean
  botId: string | null
  hint: string
}

/** 用 Bot 凭证授权的执行结果（POST /api/wecom-cli/authorize）。 */
export interface WecomCliAuthResult {
  authorized: boolean
  botId: string | null
  hint: string
}

/** 扫码授权二维码（POST /api/wecom-cli/auth/qrcode）：base64 PNG，5 分钟有效。 */
export interface WecomCliQrcode {
  sessionId: string
  imageBase64: string
  expireAt: number
}

/** 扫码轮询状态：等待扫码 / 已授权 / 已过期 / 失败。 */
export type WecomCliAuthState = 'pending' | 'authorized' | 'expired' | 'failed'

/** 扫码轮询结果（GET /api/wecom-cli/auth/poll）。 */
export interface WecomCliPollResult {
  status: WecomCliAuthState
  botId?: string
  hint?: string
}

/** 品类授权状态：可用 / 已过期 / 未授权 / 异常。 */
export type WecomCliCapabilityState = 'AVAILABLE' | 'EXPIRED' | 'UNAUTHORIZED' | 'ERROR'

/**
 * 品类授权矩阵项（GET /api/wecom-cli/capabilities）。
 * message 未授权时是企微的续期引导文案，可能含 Markdown 链接，需原样展示。
 */
export interface WecomCliCapability {
  service: string
  label: string
  state: WecomCliCapabilityState
  message: string
}
