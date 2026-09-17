/** OA（致远）待办审批台类型。 */

/** 网页会话授权状态（REST 被网关拦截时的取数通道）。 */
export interface SeeyonOaSessionStatus {
  authorized: boolean
  hint: string
}

/** 验证码挑战：challengeId + base64 图片（PNG/JPEG 原始编码）+ 过期时间戳（5 分钟）。 */
export interface SeeyonOaCaptchaChallenge {
  challengeId: string
  imageBase64: string
  expireAt: number
}

/** 账号密码（连接器配置）+ 验证码自助授权入参。 */
export interface SeeyonOaLoginPayload {
  challengeId?: string
  captcha: string
}

/** 待办/已办事项（REST 与网页会话通道返回同一结构）。 */
export interface SeeyonOaAffair {
  id: string
  subject: string
  senderName: string
  createDate: string
  appName: string
  state: string
  flowId: string
  linkUrl?: string | null
}

/** 审批动作：approve=同意 / reject=驳回。 */
export type SeeyonOaApproveAction = 'approve' | 'reject'

/** 批量审批单项结果。 */
export interface SeeyonOaBatchApproveItem {
  affairId: string
  success: boolean
  result: string
}
