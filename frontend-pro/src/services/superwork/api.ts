export interface CurrentUser {
  id: number;
  username: string;
  realName: string;
  role: string;
  email?: string;
  phone?: string;
  avatar?: string;
}
export interface MenuTreeNode {
  id: number;
  name: string;
  icon?: string | null;
  path?: string | null;
  sortOrder?: number | null;
  children?: MenuTreeNode[];
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  userInfo: CurrentUser;
}

export interface Requirement {
  id: number | string;
  reqNo?: string;
  title: string;
  project?: string;
  projectName?: string;
  businessLine?: string;
  type?: string;
  status?: string;
  priority?: string;
  owner?: string;
  source?: string;
  createdAt?: string;
  updatedAt?: string;
  expectDate?: string;
  description?: string;
  [key: string]: unknown;
}

export interface RequirementList {
  records: Requirement[];
  total: number;
  current: number;
  size: number;
}

export interface WorkItemRecord {
  recordKey: string;
  dataSource: "LOCAL" | "YUNXIAO";
  readOnly: boolean;
  id?: number;
  title: string;
  projectName?: string;
  projectFullPath?: string;
  assigneeName?: string;
  status?: string;
  normalizedStatus?: string;
  priority?: string;
  dueDate?: string;
  overdueIncomplete?: boolean;
  overdueDays?: number;
  requirementNo?: string;
  requirementTitle?: string;
  updatedAt?: string;
  [key: string]: unknown;
}

export interface OverviewResponse {
  records: WorkItemRecord[];
  tasks?: WorkItemRecord[];
  total: number;
  current: number;
  size: number;
  summary?: {
    totalCount?: number;
    pendingCount?: number;
    inProgressCount?: number;
    completedCount?: number;
    testedCount?: number;
    otherCount?: number;
    [key: string]: unknown;
  };
  analysis?: Record<string, unknown>;
}

export interface BusinessLine {
  id: number;
  name: string;
  description?: string;
  status: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessLinePage {
  records: BusinessLine[];
  total: number;
  current: number;
  size: number;
}

export interface ProjectTreeNode {
  id: number;
  businessLineId: number;
  parentId: number | null;
  level: number;
  name: string;
  fullPath: string;
  code: string;
  managerId: number | null;
  managerName?: string;
  status: number;
  children?: ProjectTreeNode[];
}

export interface ProjectRecord {
  id: number;
  businessLineId: number;
  parentId: number | null;
  name: string;
  code: string;
  managerId: number | null;
  status: number;
  fullPath?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectMember {
  id: number;
  projectId: number;
  userId: number;
  username?: string;
  realName?: string;
  role: string;
}

export interface CustomerContact {
  id: number;
  projectId: number;
  name: string;
  company?: string;
  position?: string;
  phone?: string;
  email?: string;
  isActive: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface UserRecord {
  id: number;
  username?: string;
  realName: string;
  role?: string;
  email?: string;
  phone?: string;
  status?: string | number | null;
  createTime?: string;
  createdAt?: string;
}

export type SystemConfigValueType =
  | "STRING"
  | "PASSWORD"
  | "BOOLEAN"
  | "URL"
  | "NUMBER";
export interface SystemConfigGroupSummary {
  groupCode: string;
  groupName: string;
  description?: string;
  itemCount: number;
  configuredCount: number;
}
export interface SystemConfigItem {
  key: string;
  name: string;
  description?: string;
  valueType: SystemConfigValueType;
  value?: string;
  sensitive: boolean;
  configured: boolean;
  required: boolean;
  sortOrder: number;
}
export interface SystemConfigGroup {
  groupCode: string;
  groupName: string;
  description?: string;
  items: SystemConfigItem[];
}
export interface RoleRecord {
  id: number;
  code: string;
  name: string;
  description?: string;
  status: number;
  createdAt?: string;
}
export interface MenuRecord {
  id: number;
  parentId?: number | null;
  name: string;
  icon?: string;
  path?: string;
  component?: string;
  sortOrder?: number;
  visible?: number;
  status?: number;
  children?: MenuRecord[];
}
export interface PermissionRecord {
  id: number;
  code: string;
  name: string;
  description?: string;
  type?: string;
  menuId?: number | null;
}
export interface RoleAuthorization {
  menuIds: number[];
  permissionIds: number[];
  dataScope?: string;
  dataScopeValue?: string;
}
export interface WorkflowConfig {
  id: number;
  requirementType: string;
  currentStatus?: string;
  nextStatus?: string;
  fromStatus?: string;
  toStatus?: string;
  transitionName?: string;
  conditionType?: string;
  allowedRoles?: string | string[];
  isActive?: number;
  sortOrder?: number;
}
export type AiConnectorAuthType =
  | "BASIC"
  | "TOKEN"
  | "MCP"
  | "SEEYON"
  | "WECOM"
  | "MAIL";
export interface AiConnectorView {
  id: number;
  code: string;
  name: string;
  authType: AiConnectorAuthType;
  baseUrl: string;
  mcpUrl?: string;
  testPath?: string;
  queryPath?: string;
  readPath?: string;
  /** 系统专属参数（JSON），键值缺省即删除 */
  extraConfig: Record<string, unknown>;
  usernameConfigured: boolean;
  passwordConfigured: boolean;
  tokenConfigured: boolean;
  /** 机器人通道 Bot Secret 是否已配置（写入型字段不回显） */
  botSecretConfigured: boolean;
  enabled: boolean;
  ready: boolean;
  hint: string;
  lastTestStatus?: "SUCCESS" | "FAILED" | null;
  lastTestMessage?: string;
  lastTestedAt?: string;
  builtIn: boolean;
  sortOrder: number;
}
export interface AiConnectorSavePayload {
  code?: string;
  name?: string;
  authType?: AiConnectorAuthType;
  baseUrl?: string;
  mcpUrl?: string;
  testPath?: string;
  queryPath?: string;
  readPath?: string;
  extraConfig?: Record<string, unknown>;
  username?: string;
  password?: string;
  token?: string;
  /** 机器人通道 Bot Secret（写入型，留空表示不修改） */
  botSecret?: string;
  enabled?: boolean;
  sortOrder?: number;
}

/** wecom-cli 机器人通道状态（Bot ID + Secret 授权）。 */
export interface WecomCliStatus {
  cliInstalled: boolean;
  authorized: boolean;
  botId: string | null;
  hint: string;
}

/** 机器人授权结果：Bot 凭证授权与扫码授权共用口径。 */
export interface WecomCliAuthorizeResult {
  authorized: boolean;
  botId: string | null;
  hint: string;
}

/** 扫码授权会话：base64 PNG 二维码 + 过期时间戳（毫秒），5 分钟有效。 */
export interface WecomCliQrSession {
  sessionId: string;
  imageBase64: string;
  expireAt: number;
}

export type WecomCliQrPollStatus =
  | "pending"
  | "authorized"
  | "expired"
  | "failed";

/** 扫码轮询结果；authorized 后带 botId，expired/failed 带 hint。 */
export interface WecomCliQrPollResult {
  status: WecomCliQrPollStatus;
  botId?: string;
  hint?: string;
}

export type WecomCliCapabilityState =
  | "AVAILABLE"
  | "EXPIRED"
  | "UNAUTHORIZED"
  | "ERROR";

/** 品类授权矩阵项；message 为企微原文（未授权时含续期引导，可能带 markdown 链接）。 */
export interface WecomCliCapability {
  service: string;
  label: string;
  state: WecomCliCapabilityState;
  message: string;
}

export interface AiModelView {
  id: number;
  /** 提供方 = 连接器编码 */
  providerCode: string;
  providerName: string;
  /** 提供方连接器是否就绪（未就绪则模型不会生效） */
  providerReady: boolean;
  model: string;
  displayName: string;
  assistantEnabled: boolean;
  digestEnabled: boolean;
  isDefault: boolean;
  enabled: boolean;
  sortOrder: number;
}
export interface AiModelSavePayload {
  providerCode?: string;
  model?: string;
  displayName?: string;
  assistantEnabled?: boolean;
  digestEnabled?: boolean;
  isDefault?: boolean;
  enabled?: boolean;
  sortOrder?: number;
}
export interface AiAgentModelOption {
  provider: string;
  model: string;
  label: string;
}
export interface AiConnectorStatus {
  code: string;
  name: string;
  status: string;
  hint: string;
}
export interface AiAgentSessionSummary {
  id: number;
  title: string;
  provider: string;
  model: string;
  updatedAt: string;
  messageCount: number;
}
export interface AiAgentMessage {
  role?: string;
  content?: unknown;
  [key: string]: unknown;
}
export interface AiAgentSession {
  id: number;
  title: string;
  provider: string;
  model: string;
  updatedAt: string;
  messages: AiAgentMessage[];
}
export interface AiAgentStreamEvent {
  type: string;
  [key: string]: unknown;
}
export interface AiNotice {
  kind: string;
  date: string;
  title?: string;
  message?: string;
  body?: string;
  link?: string;
  read?: boolean;
  createdAt?: string;
  [key: string]: unknown;
}
export interface EmailAccount {
  configured: boolean;
  enabled: boolean;
  provider: string;
  emailAddress?: string;
  credentialConfigured: boolean;
  connectionStatus?: string;
  lastTestedAt?: string;
  lastSyncAt?: string;
  lastSyncStatus?: string;
  lastSyncMessage?: string;
}
export interface EmailMessageSummary {
  id: number;
  messageId: string;
  subject?: string;
  fromName?: string;
  fromAddress: string;
  receivedAt: string;
  preview?: string;
  projectId?: number;
  projectName?: string;
  projectFullPath?: string;
  hasAttachments: boolean;
  attachmentCount: number;
}
export interface EmailMessagePage {
  records: EmailMessageSummary[];
  total: number;
  size: number;
  current: number;
  pages?: number;
}
export interface EmailAttachment {
  fileName: string;
  contentType?: string;
  size: number;
}
export interface EmailInterpretation {
  status: string;
  disposition?: string;
  summary?: string;
  senderIntent?: string;
  keyPoints: string[];
  actionItems: Array<{
    content?: string;
    deadline?: string;
    priority?: string;
  }>;
  risks: string[];
  replySuggestion?: string;
  model?: string;
  errorMessage?: string;
  generatedAt?: string;
}
export interface EmailMessageDetail extends EmailMessageSummary {
  groupingReason?: string;
  toAddresses: string[];
  ccAddresses: string[];
  textBody: string;
  attachments: EmailAttachment[];
  interpretation: EmailInterpretation;
}
export interface EmailDigestItem {
  messageId: number;
  title?: string;
  subject?: string;
  sender?: string;
  content?: string;
  summary?: string;
  action?: string;
  deadline?: string;
}
export interface EmailDailyDigest {
  id?: number;
  businessDate: string;
  status: string;
  generationMode?: string;
  generatedModel?: string;
  overview?: string;
  mailCount: number;
  topics: Array<{
    title: string;
    summary: string;
    status: string;
    messageIds: number[];
  }>;
  progressItems: Array<{
    title: string;
    status: string;
    detail: string;
    messageIds: number[];
  }>;
  importantItems: EmailDigestItem[];
  todos: EmailDigestItem[];
  risks: EmailDigestItem[];
  replySuggestions: EmailDigestItem[];
  generatedAt?: string;
  pushStatus?: string;
  pushMessage?: string;
  feedback?: string | null;
}
export interface EmailSyncStatus {
  status: string;
  startedAt?: string;
  finishedAt?: string;
  completedAt?: string;
  syncedCount?: number;
  count?: number;
  message?: string;
  error?: string;
}
export interface EmailProjectGroup {
  projectId?: number;
  projectName: string;
  projectFullPath: string;
  mailCount: number;
}
export interface EmailSenderCompanyGroup {
  domain: string;
  companyName: string;
  mailCount: number;
}
export interface EmailWeComMapping {
  configured: boolean;
  enabled: boolean;
  weComUserId?: string;
}
export type EmailGroupingJobState = "IDLE" | "RUNNING" | "SUCCESS" | "FAILED";
export interface EmailGroupingJobStatus {
  status: EmailGroupingJobState;
  total: number;
  processed: number;
  grouped: number;
  ungrouped: number;
  message?: string;
  startedAt?: string;
  finishedAt?: string;
}
export interface EmailConvertResult {
  actionType: "TASK" | "ISSUE" | "KEY_MATTER";
  targetId: number;
  targetTitle: string;
  created: boolean;
}
export interface EmailActionLink {
  id: number;
  messageId: number;
  itemKind: string;
  itemTitle: string;
  actionType: string;
  targetId: number;
  targetTitle?: string;
  status: "OPEN" | "CLOSED";
  closedAt?: string;
  createdAt?: string;
}
export interface EmailReplyResult {
  replyId: number;
  status: "SENT" | "FAILED";
  errorMessage?: string;
}
export interface EmailValueMetrics {
  monthStart: string;
  converted: number;
  closed: number;
  closeRate?: number | null;
  digests: number;
  useful: number;
  useless: number;
  avgResponseMinutes?: number | null;
}

export type SalesOpportunityType = "线索" | "商机";
export type SalesOpportunityStatus =
  | "初步接触"
  | "需求确认"
  | "方案报价"
  | "商务谈判"
  | "已成交"
  | "已流失";

export interface SalesOpportunity {
  id: number;
  name: string;
  customer?: string;
  type: SalesOpportunityType;
  status: SalesOpportunityStatus;
  amount?: number;
  owner?: string;
  businessLine?: string;
  nextFollowUp?: string;
  createdAt?: string;
  probability?: number;
  expectedClose?: string;
  source?: string;
  note?: string;
}

export interface SalesOpportunityFollowUp {
  id: number;
  opportunityId: number;
  followUpAt: string;
  follower: string;
  content: string;
  status: SalesOpportunityStatus;
  probability: number;
  nextFollowUp?: string;
  createdAt?: string;
}

export interface SalesOpportunitySupportWorklog {
  id: number;
  opportunityId: number;
  supportDate: string;
  supporter: string;
  hours: number;
  supportType: string;
  content: string;
  createdAt?: string;
}

export interface QuotationPolicy {
  id: number;
  name: string;
  type: string;
  taxMode: string;
  version: number;
  status: string;
  effectiveDate?: string;
  expiryDate?: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface QuotationPolicyItem {
  id: number;
  policyId: number;
  section: string;
  category?: string;
  itemKey: string;
  itemName: string;
  description?: string;
  priceDescription?: string;
  isRequired: number;
  unitPrice?: number;
  taxRate?: number;
  chargeMethod?: string;
  chargeUnit?: string;
  remark?: string;
  sortOrder: number;
}

export interface QuotationLineItem {
  id: number;
  quotationId: number;
  policyItemId?: number;
  section: string;
  category?: string;
  itemName: string;
  description?: string;
  priceDescription?: string;
  isSelected: number;
  quantity: number;
  unitPriceExTax?: number;
  taxRate?: number;
  discountRate: number;
  subtotalExTax?: number;
  subtotalInclTax?: number;
  chargeMethod?: string;
  remark?: string;
  sortOrder: number;
}

export interface QuotationBrandScope {
  id: number;
  quotationId: number;
  brand?: string;
  store?: string;
  description?: string;
  target?: string;
  sortOrder: number;
}

export interface Quotation {
  id: number;
  quotationNo: string;
  policyId?: number;
  opportunityId?: number;
  opportunityName?: string;
  taxMode: string;
  customerName?: string;
  contactPerson?: string;
  contactPhone?: string;
  contactEmail?: string;
  deliveryPeriod?: string;
  quoteDate: string;
  validityDays?: number;
  currency?: string;
  invoiceType?: string;
  firstYearTotalExTax?: number;
  firstYearTotalInclTax?: number;
  subsequentYearTotalExTax?: number;
  subsequentYearTotalInclTax?: number;
  quotationNote?: string;
  status: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  lineItems?: QuotationLineItem[];
  brandScopes?: QuotationBrandScope[];
}

export interface QuotationListVO
  extends Pick<
    Quotation,
    | "id"
    | "quotationNo"
    | "customerName"
    | "firstYearTotalInclTax"
    | "status"
    | "quoteDate"
    | "opportunityName"
  > {
  [key: string]: unknown;
}

export interface QuotationLineItemOverride {
  policyItemId: number;
  isSelected: boolean;
  quantity?: number;
  discountRate?: number;
}

export interface QuotationGenerateRequest {
  policyId: number;
  opportunityId?: number;
  customerName: string;
  contactPerson?: string;
  contactPhone?: string;
  contactEmail?: string;
  deliveryPeriod?: string;
  lineItemOverrides: QuotationLineItemOverride[];
}

export type WeeklyReportStatus =
  | "PENDING"
  | "GENERATING"
  | "DRAFT"
  | "CONFIRMED"
  | "PUBLISHED"
  | "GENERATION_FAILED";

export interface WeeklyReportSheetTargetInfo {
  dateRangeLabel: string;
  teamName: string;
  sheetName: string;
  sheetUrl: string;
  minutesUrl?: string | null;
}

export interface WeeklyReportVO {
  id: number;
  weekStartDate: string;
  periodEndDate: string;
  wecomSummary?: string | null;
  manualNotes?: string | null;
  coreWork?: string | null;
  kpiSection?: string | null;
  risks?: string | null;
  nextWeekPlan?: string | null;
  minutesMarkdown?: string | null;
  status: WeeklyReportStatus;
  generationModel?: string | null;
  generationMode?: string | null;
  generationError?: string | null;
  yuqueDocUrl?: string | null;
  yuqueTocStatus?: "VERIFIED" | "MOVED" | "NOT_FOUND" | null;
  sheetSyncStatus?: "PENDING" | "MANUAL_DONE" | "API_FAILED" | null;
  sheetSyncedAt?: string | null;
  wecomPushStatus?: string | null;
  wecomPushedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
  editable: boolean;
  factsPreview: Array<{ title: string; status: string }>;
  sheetTargetInfo?: WeeklyReportSheetTargetInfo | null;
}

export interface WeeklyReportFacts {
  weekStart: string;
  periodEnd: string;
  keyMatters: Array<{
    id: number;
    title: string;
    projectName?: string | null;
    ownerName?: string | null;
    priority?: string | null;
    status?: string | null;
    progress?: number | null;
    progressSummary?: string | null;
    issues?: string | null;
    nextWeekPlan?: string | null;
    supportNeeded?: string | null;
  }>;
  finance: {
    month: string;
    newContractAmount: number;
    deliveredAmount: number;
    cumulativeReceivable: number;
  };
  lastWeekReport: {
    exists: boolean;
    weekStart?: string;
    status?: string;
    nextWeekPlan?: string | null;
    risks?: string | null;
  };
}

export interface WorktimeSyncLog {
  id: number;
  syncType: "contract" | "worklog" | "cost" | "bl_profit";
  scope: string | null;
  status: "running" | "success" | "failed";
  totalCount: number;
  upsertCount: number;
  pendingCount: number;
  message: string | null;
  triggeredBy: "schedule" | "manual";
  startedAt: string | null;
  finishedAt: string | null;
}

export interface BizLineProfitRow {
  id?: number;
  /** 归属月份 YYYY-MM；YTD 小计行为 "YTD"，总计行为 null */
  yearMonth: string | null;
  worktimeBusinessLineId?: number | null;
  worktimeBusinessLineName: string;
  businessLineId?: number | null;
  groupName?: string | null;
  revenue: number | null;
  smsCost?: number | null;
  directCost?: number | null;
  platformFee?: number | null;
  compensation?: number | null;
  outsourcing?: number | null;
  softwareGift?: number | null;
  totalHours: number | null;
  hoursRatio?: number | null;
  expense1?: number | null;
  laborCost1?: number | null;
  grossProfit: number | null;
  grossProfitRate: number | null;
  marketingCost?: number | null;
  laborCost2Sales?: number | null;
  laborCost3Backend?: number | null;
  laborCost3Tech?: number | null;
  laborCost3Rd?: number | null;
  expense2?: number | null;
  netProfit: number | null;
  netProfitRate: number | null;
  syncedAt?: string | null;
}

export interface BizLineProfitLineGroup {
  businessLineId: number | null;
  businessLineName: string;
  groupName: string | null;
  /** 月度行（按 yearMonth 升序） */
  months: BizLineProfitRow[];
  /** 该业务线 YTD 小计 */
  ytd: BizLineProfitRow;
}

export interface BizLineProfitReport {
  year: number;
  lines: BizLineProfitLineGroup[];
  /** 全业务线 YTD 总计 */
  totalYtd: BizLineProfitRow;
}

export type MeetingStatus =
  | "UPLOADED"
  | "TRANSCRIBING"
  | "SUMMARIZING"
  | "DRAFT"
  | "CONFIRMED"
  | "FAILED";

export interface MeetingSegment {
  seq: number;
  startMs: number;
  endMs: number;
  speaker: string;
  text: string;
  edited?: boolean;
}

export interface MeetingSpeaker {
  speakerLabel: string;
  displayName?: string | null;
  mappedUserId?: number | null;
}

export interface MeetingSummarySection {
  title: string;
  content: string;
  startMs: number | null;
  endMs: number | null;
  segmentRefs: number[];
}

export interface MeetingSpeakerPoint {
  speaker: string;
  points: string[];
  segmentRefs: number[];
}

export interface MeetingEvidenceItem {
  content: string;
  severity?: string | null;
  /** 服务端从转写段渲染的原文摘录 */
  excerpt?: string | null;
  startMs?: number | null;
  segmentRefs?: number[];
}

export interface MeetingSummary {
  summary: string;
  keywords?: string[];
  sections?: MeetingSummarySection[];
  speakerPoints?: MeetingSpeakerPoint[];
  decisions: MeetingEvidenceItem[];
  risks: MeetingEvidenceItem[];
}

export type MeetingTodoStatus = "DRAFT" | "CREATED" | "DISMISSED";

export interface MeetingTodo {
  id: number;
  title: string;
  description?: string | null;
  assigneeHint?: string | null;
  dueText?: string | null;
  dueDate?: string | null;
  sourceSegmentSeq?: number | null;
  sourceStartMs?: number | null;
  sourceEndMs?: number | null;
  sourceExcerpt?: string | null;
  status: MeetingTodoStatus;
  actionType?: "TASK" | "ISSUE" | null;
  targetId?: number | null;
  targetTitle?: string | null;
}

export interface MeetingListItem {
  id: number;
  title: string;
  meetingDate: string;
  durationSeconds?: number | null;
  status: MeetingStatus;
  generationError?: string | null;
}

export interface MeetingList {
  records: MeetingListItem[];
  total: number;
  size: number;
  current: number;
  pages?: number;
}

export interface MeetingDetail extends MeetingListItem {
  projectId?: number | null;
  generationModel?: string | null;
  /** 转写段（校正稿优先），seq 从 1 起 */
  segments: MeetingSegment[];
  speakers: MeetingSpeaker[];
  summary?: MeetingSummary | null;
  todos: MeetingTodo[];
}

export interface MeetingStatusSnapshot {
  status: MeetingStatus;
  generationError?: string | null;
}

export interface MeetingTodoUpdatePayload {
  title?: string;
  description?: string;
  dueText?: string;
  dueDate?: string | null;
  dismiss?: boolean;
}

export interface MeetingTodoConvertPayload {
  actionType: "TASK" | "ISSUE";
  requirementId?: number;
  assigneeId?: number;
  severity?: string;
  taskType?: string;
}

/** 致远 OA 网页会话授权状态（REST 被网关拦截时的取数通道）。 */
export interface OaSessionStatus {
  authorized: boolean;
  hint: string;
}

/** 自助授权用的登录验证码：图片 base64（PNG）+ 一次性 challengeId，约 5 分钟过期。 */
export interface OaCaptchaChallenge {
  challengeId: string;
  imageBase64: string;
  expireAt: number;
}

/** OA 待办/已办事项：REST 与网页会话通道返回同一结构。 */
export interface OaAffair {
  id: string;
  subject: string;
  senderName?: string;
  createDate?: string;
  appName?: string;
  state?: string;
  flowId?: string;
  linkUrl?: string;
}

export type OaAffairAction = "approve" | "reject";

/** 批量审批逐项结果（逐项执行，单项失败不影响其余）。 */
export interface OaBatchApproveResult {
  affairId: string;
  success: boolean;
  result?: string;
}

export class ApiRequestError extends Error {
  status: number;
  code?: number;

  constructor(message: string, status: number, code?: number) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
  }
}

type Envelope<T> = { code: number; message?: string; data: T };

const isEnvelope = <T>(value: unknown): value is Envelope<T> =>
  Boolean(
    value && typeof value === "object" && "code" in value && "data" in value
  );

const getStoredUser = (): CurrentUser | undefined => {
  try {
    const raw = localStorage.getItem("user");
    return raw ? (JSON.parse(raw) as CurrentUser) : undefined;
  } catch {
    return undefined;
  }
};

const clearAuthAndRedirect = () => {
  localStorage.removeItem("token");
  localStorage.removeItem("user");
  localStorage.removeItem("refreshToken");
  if (window.location.pathname !== "/user/login")
    window.location.href = "/user/login";
};

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (
    !headers.has("Content-Type") &&
    init?.body &&
    !(init.body instanceof FormData)
  )
    headers.set("Content-Type", "application/json");
  const token = localStorage.getItem("token");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });
  const text = await response.text();
  let payload: unknown;
  try {
    payload = text ? JSON.parse(text) : undefined;
  } catch {
    payload = text;
  }

  if (!response.ok) {
    if (response.status === 401) clearAuthAndRedirect();
    const message = isEnvelope<unknown>(payload) ? payload.message : undefined;
    throw new ApiRequestError(
      message || `请求失败（${response.status}）`,
      response.status
    );
  }
  if (isEnvelope<T>(payload)) {
    if (payload.code !== 200) {
      if (payload.code === 401) clearAuthAndRedirect();
      throw new ApiRequestError(
        payload.message || "请求失败",
        response.status,
        payload.code
      );
    }
    return payload.data;
  }
  return payload as T;
}

const query = (params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") search.set(key, String(value));
  });
  const result = search.toString();
  return result ? `?${result}` : "";
};

export interface SyncTask {
  id: number;
  taskCode: string;
  taskName: string;
  /** OA / WORKTIME / EXCEL */
  sourceSystem: string;
  /** contract / worklog / cost / org / member */
  domain: string;
  cron: string | null;
  enabled: number;
  lastStatus: "running" | "success" | "failed" | null;
  lastRunAt: string | null;
}

export interface DataSyncLog {
  id: number;
  taskCode: string;
  sourceSystem: string;
  domain: string;
  scope: string;
  status: string;
  totalCount: number | null;
  upsertCount: number | null;
  pendingCount: number | null;
  message: string | null;
  triggeredBy: string;
  operatorId: number | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface SyncOverviewItem {
  domain: string;
  lastSuccess: DataSyncLog | null;
}

export interface RevenueCell {
  /** 人月 */
  hours: number;
  /** 元 */
  cost: number;
  /** actual=完结实际 / estimate=预估 / mixed=混合 / null=无数据 */
  source: "actual" | "estimate" | "mixed" | null;
  estimateCount?: number | null;
}

export interface RevenueMonthInfo {
  yearMonth: string;
  closed: boolean;
}

export type RevenueRowKind =
  | "project"
  | "line_pool"
  | "sales_specific"
  | "pool"
  | "other"
  | "agg_project"
  | "agg_sales"
  | "simple";

export interface RevenueRow {
  rowKey: string;
  name: string;
  kind: RevenueRowKind;
  projectId?: number | null;
  salesProjectId?: number | null;
  opportunityId?: number | null;
  opportunityName?: string | null;
  /** 累计完结人均成本（元/人月），无完结历史为 null */
  unitPrice?: number | null;
  months: RevenueCell[];
  totals: RevenueCell;
}

export interface RevenueSection {
  type: "project" | "sales";
  rows: RevenueRow[];
}

export interface RevenueLineBlock {
  businessLineId: number;
  businessLineName: string;
  /** full=项目+销售明细行 / aggregate=项目销售两行聚合 / simple=单行汇总 */
  mode: "full" | "aggregate" | "simple";
  sections: RevenueSection[];
  monthTotals: RevenueCell[];
  totals: RevenueCell;
}

export interface RevenueOverview {
  totalHours: number;
  projectHours: number;
  salesHours: number;
  totalCost: number;
  /** 综合单价（元/人月） */
  avgUnitPrice?: number | null;
  closedMonthCount: number;
}

export interface RevenueMatrix {
  year: number;
  months: RevenueMonthInfo[];
  lines: RevenueLineBlock[];
  monthTotals: RevenueCell[];
  grandTotal: RevenueCell;
  overview: RevenueOverview;
}

export interface RevenueEstimateEntry {
  id: number;
  yearMonth: string;
  businessLineId: number;
  projectId?: number | null;
  workType: string;
  salesKind?: string | null;
  salesProjectId?: number | null;
  description: string;
  personMonths: number;
  unitPrice?: number | null;
  amount?: number | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface RevenueWorklogEntry {
  id: number;
  yearMonth: string;
  businessLineName: string;
  businessLineId?: number | null;
  projectNameRaw: string;
  projectId?: number | null;
  workType: string;
  salesKind?: string | null;
  salesProjectId?: number | null;
  employeeNo?: string;
  employeeName?: string;
  department?: string;
  hours: number;
  workNote?: string;
  specialNote?: string;
  tags?: string;
  pending: number;
}

export interface RevenueCostEntry {
  id: number;
  yearMonth: string;
  businessLineName: string;
  businessLineId?: number | null;
  projectNameRaw: string;
  projectId?: number | null;
  workType: string;
  salesKind?: string | null;
  employeeCount?: number | null;
  hours: number;
  costAmount: number;
  personMonthCost?: number | null;
  pending: number;
}

export interface RevenueCellDetail {
  /** 该单元格所属月份是否已完结：完结返回实际明细，未完结返回预估明细 */
  closed: boolean;
  worklogEntries?: RevenueWorklogEntry[];
  costEntries?: RevenueCostEntry[];
  estimates?: RevenueEstimateEntry[];
}

export const superworkApi = {
  login(username: string, password: string) {
    return requestJson<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },
  getCurrentUser() {
    return Promise.resolve(getStoredUser());
  },
  getRequirements(
    params: {
      page?: number;
      size?: number;
      status?: string;
      priority?: string;
      title?: string;
    } = {}
  ) {
    return requestJson<RequirementList>(`/api/requirements${query(params)}`);
  },
  getRequirementById(id: number | string) {
    return requestJson<Requirement>(
      `/api/requirements/${encodeURIComponent(String(id))}`
    );
  },
  createRequirement(payload: Record<string, unknown>) {
    return requestJson<Requirement>("/api/requirements", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateRequirement(id: number | string, payload: Record<string, unknown>) {
    return requestJson<Requirement>(
      `/api/requirements/${encodeURIComponent(String(id))}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteRequirement(id: number | string) {
    return requestJson<void>(
      `/api/requirements/${encodeURIComponent(String(id))}`,
      { method: "DELETE" }
    );
  },
  executeRequirementStageAction(id: number | string, action: string) {
    return requestJson<Requirement>(
      `/api/requirements/${encodeURIComponent(String(id))}/stage-actions`,
      { method: "POST", body: JSON.stringify({ action }) }
    );
  },
  getRequirementEvaluation(requirementId: number | string) {
    return requestJson<Record<string, unknown> | null>(
      `/api/requirement-evaluations/by-requirement/${requirementId}`
    );
  },
  submitRequirementEvaluation(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      "/api/requirement-evaluations",
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  submitBuDecision(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/bu-decisions", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  getRequirementConfirmation(requirementId: number | string) {
    return requestJson<Record<string, unknown> | null>(
      `/api/requirement-confirmations/${requirementId}`
    );
  },
  createRequirementConfirmation(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      "/api/requirement-confirmations",
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  getRequirementDelivery(requirementId: number | string) {
    return requestJson<Record<string, unknown> | null>(
      `/api/requirement-deliveries/${requirementId}`
    );
  },
  createRequirementDelivery(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/requirement-deliveries", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  acceptRequirementDelivery(
    requirementId: number | string,
    payload: Record<string, unknown>
  ) {
    return requestJson<Record<string, unknown>>(
      `/api/requirement-deliveries/${requirementId}/accept`,
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  getDesignWorkLogs(requirementId: number | string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/design-work-logs/requirement/${requirementId}`
    );
  },
  createDesignWorkLog(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/design-work-logs", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateDesignWorkLog(id: number | string, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(`/api/design-work-logs/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteDesignWorkLog(id: number | string) {
    return requestJson<void>(`/api/design-work-logs/${id}`, {
      method: "DELETE",
    });
  },
  getRequirementTasks(requirementId: number | string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/tasks/requirement/${requirementId}`
    );
  },
  getTaskOverview(
    params: {
      projectId?: number;
      assigneeId?: number;
      status?: string;
      keyword?: string;
    } = {}
  ) {
    return requestJson<OverviewResponse>(`/api/tasks/overview${query(params)}`);
  },
  getTask(id: number | string) {
    return requestJson<Record<string, unknown>>(`/api/tasks/${id}`);
  },
  createTask(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/tasks", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateTask(id: number | string, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(`/api/tasks/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  getDefectOverview(
    params: {
      page?: number;
      size?: number;
      projectId?: number;
      assigneeId?: number;
      normalizedStatus?: string;
      priority?: string;
      keyword?: string;
    } = {}
  ) {
    return requestJson<OverviewResponse>(
      `/api/defects/overview${query(params)}`
    );
  },
  getBusinessLines(
    params: {
      page?: number;
      size?: number;
      name?: string;
      status?: number;
    } = {}
  ) {
    return requestJson<BusinessLinePage>(`/api/business-lines${query(params)}`);
  },
  createBusinessLine(
    payload: Pick<BusinessLine, "name" | "status"> & { description?: string }
  ) {
    return requestJson<BusinessLine>("/api/business-lines", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateBusinessLine(
    id: number,
    payload: Pick<BusinessLine, "name" | "status"> & { description?: string }
  ) {
    return requestJson<BusinessLine>(`/api/business-lines/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteBusinessLine(id: number) {
    return requestJson<void>(`/api/business-lines/${id}`, { method: "DELETE" });
  },
  getProjectTree(businessLineId?: number) {
    return requestJson<ProjectTreeNode[]>(
      `/api/projects/tree${query({ businessLineId })}`
    );
  },
  getProjects(
    params: {
      page?: number;
      size?: number;
      businessLineId?: number;
      name?: string;
      status?: number;
    } = {}
  ) {
    return requestJson<{
      records: ProjectRecord[];
      total: number;
      current: number;
      size: number;
    }>(`/api/projects${query(params)}`);
  },
  getUsers(params: { page?: number; size?: number } = {}) {
    return requestJson<{
      records: UserRecord[];
      total: number;
      current: number;
      size: number;
    }>(`/api/users${query(params)}`);
  },
  getUserById(id: number) {
    return requestJson<UserRecord>(`/api/users/${id}`);
  },
  createUser(payload: {
    username: string;
    password: string;
    realName: string;
    email?: string;
    phone?: string;
    role: string;
  }) {
    return requestJson<UserRecord>("/api/users", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateUser(
    id: number,
    payload: Partial<{
      username: string;
      password: string;
      realName: string;
      email: string;
      phone: string;
      role: string;
    }>
  ) {
    return requestJson<UserRecord>(`/api/users/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteUser(id: number) {
    return requestJson<void>(`/api/users/${id}`, { method: "DELETE" });
  },
  getMenus() {
    return requestJson<MenuRecord[]>("/api/system/menus");
  },
  createMenu(payload: {
    parentId?: number | null;
    name: string;
    icon?: string;
    path?: string;
    component?: string;
    sortOrder?: number;
    visible?: number;
    status?: number;
  }) {
    return requestJson<MenuRecord>("/api/system/menus", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateMenu(
    id: number,
    payload: {
      parentId?: number | null;
      name: string;
      icon?: string;
      path?: string;
      component?: string;
      sortOrder?: number;
      visible?: number;
      status?: number;
    },
  ) {
    return requestJson<void>(`/api/system/menus/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteMenu(id: number) {
    return requestJson<void>(`/api/system/menus/${id}`, { method: "DELETE" });
  },
  reorderMenus(parentId: number, menuIds: number[]) {
    return requestJson<void>("/api/system/menus/reorder", {
      method: "PUT",
      body: JSON.stringify({ parentId, menuIds }),
    });
  },
  getPermissions() {
    return requestJson<PermissionRecord[]>("/api/system/permissions");
  },
  getMyMenus() {
    return requestJson<{ paths: string[]; managedPaths: string[] }>("/api/auth/my-menus");
  },
  getMyMenuTree() {
    return requestJson<MenuTreeNode[]>("/api/auth/my-menu-tree");
  },
  getRoles() {
    return requestJson<RoleRecord[]>("/api/system/roles");
  },
  createRole(payload: {
    code: string;
    name: string;
    description?: string;
    status?: number;
  }) {
    return requestJson<RoleRecord>("/api/system/roles", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateRole(
    id: number,
    payload: { name: string; description?: string; status?: number }
  ) {
    return requestJson<void>(`/api/system/roles/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteRole(id: number) {
    return requestJson<void>(`/api/system/roles/${id}`, { method: "DELETE" });
  },
  getRoleAuthorization(id: number) {
    return requestJson<RoleAuthorization>(
      `/api/system/roles/${id}/authorization`
    );
  },
  assignRoleAuthorization(
    id: number,
    menuIds: number[],
    permissionIds: number[],
    dataScope?: string,
    dataScopeValue?: string
  ) {
    return requestJson<void>("/api/system/roles/authorization/assign", {
      method: "POST",
      body: JSON.stringify({
        roleId: id,
        menuIds,
        permissionIds,
        dataScope,
        dataScopeValue,
      }),
    });
  },
  getWorkflowConfigs() {
    return requestJson<WorkflowConfig[]>("/api/workflow-configs");
  },
  getWorkflowStatusOptions() {
    return requestJson<Record<string, string[]>>(
      "/api/workflow-configs/meta/status-options"
    );
  },
  createWorkflowConfig(payload: Omit<WorkflowConfig, "id">) {
    return requestJson<WorkflowConfig>("/api/workflow-configs", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateWorkflowConfig(id: number, payload: Omit<WorkflowConfig, "id">) {
    return requestJson<WorkflowConfig>(`/api/workflow-configs/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteWorkflowConfig(id: number) {
    return requestJson<void>(`/api/workflow-configs/${id}`, {
      method: "DELETE",
    });
  },
  getAiConnectors() {
    return requestJson<AiConnectorView[]>("/api/connectors");
  },
  getConnectorStatuses() {
    return requestJson<AiConnectorStatus[]>("/api/connectors/status");
  },
  createAiConnector(payload: AiConnectorSavePayload) {
    return requestJson<AiConnectorView>("/api/connectors", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateAiConnector(id: number, payload: AiConnectorSavePayload) {
    return requestJson<AiConnectorView>(`/api/connectors/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteAiConnector(id: number) {
    return requestJson<void>(`/api/connectors/${id}`, { method: "DELETE" });
  },
  testAiConnector(id: number) {
    return requestJson<AiConnectorView>(`/api/connectors/${id}/test`, {
      method: "POST",
    });
  },
  /** 机器人通道（wecom-cli）授权状态。 */
  getWecomCliStatus() {
    return requestJson<WecomCliStatus>("/api/wecom-cli/status");
  },
  /** 用连接器已保存的 Bot ID + Secret 执行无人值守授权。 */
  authorizeWecomCli() {
    return requestJson<WecomCliAuthorizeResult>("/api/wecom-cli/authorize", {
      method: "POST",
    });
  },
  /** 获取扫码授权二维码（base64 PNG，5 分钟有效）。 */
  createWecomCliQrcode() {
    return requestJson<WecomCliQrSession>("/api/wecom-cli/auth/qrcode", {
      method: "POST",
    });
  },
  /** 轮询扫码授权结果。 */
  pollWecomCliAuth(sessionId: string) {
    return requestJson<WecomCliQrPollResult>(
      `/api/wecom-cli/auth/poll${query({ sessionId })}`
    );
  },
  /** 品类授权矩阵；refresh=true 触发一次实时体检。 */
  getWecomCliCapabilities(refresh = false) {
    return requestJson<WecomCliCapability[]>(
      `/api/wecom-cli/capabilities${query({
        refresh: refresh ? "true" : undefined,
      })}`
    );
  },
  getAiModels() {
    return requestJson<AiModelView[]>("/api/ai/models");
  },
  createAiModel(payload: AiModelSavePayload) {
    return requestJson<AiModelView>("/api/ai/models", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateAiModel(id: number, payload: AiModelSavePayload) {
    return requestJson<AiModelView>(`/api/ai/models/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteAiModel(id: number) {
    return requestJson<void>(`/api/ai/models/${id}`, { method: "DELETE" });
  },
  getAiNotices() {
    return requestJson<AiNotice[]>('/api/ai/notices');
  },
  getAiNoticeUnreadCount() {
    return requestJson<{ count: number }>('/api/ai/notices/unread-count');
  },
  markAiNoticeRead(kind: string, date: string) {
    return requestJson<void>('/api/ai/notices/read', { method: 'POST', body: JSON.stringify({ kind, date }) });
  },
  getAiAgentModels() {
    return requestJson<AiAgentModelOption[]>("/api/ai-agent/models");
  },
  getAiAgentConnectors() {
    return requestJson<AiConnectorStatus[]>("/api/ai-agent/connectors");
  },
  getAiAgentSessions() {
    return requestJson<AiAgentSessionSummary[]>("/api/ai-agent/sessions");
  },
  createAiAgentSession(
    payload: { title?: string; provider?: string; model?: string } = {}
  ) {
    return requestJson<AiAgentSession>("/api/ai-agent/sessions", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  getAiAgentSession(id: number) {
    return requestJson<AiAgentSession>(`/api/ai-agent/sessions/${id}`);
  },
  deleteAiAgentSession(id: number) {
    return requestJson<void>(`/api/ai-agent/sessions/${id}`, {
      method: "DELETE",
    });
  },
  async streamAiAgentRun(
    sessionId: number,
    content: string,
    onEvent: (event: AiAgentStreamEvent) => void,
    signal?: AbortSignal
  ) {
    const headers = new Headers({ "Content-Type": "application/json" });
    const token = localStorage.getItem("token");
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(
      `/api/ai-agent/sessions/${sessionId}/messages`,
      { method: "POST", headers, body: JSON.stringify({ content }), signal }
    );
    if (!response.ok)
      throw new ApiRequestError(
        `AI 请求失败（${response.status}）`,
        response.status
      );
    if (!response.body)
      throw new ApiRequestError("当前浏览器不支持流式响应", 0);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const dispatch = (block: string) => {
      const data = block
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
        .join("\n");
      if (!data) return;
      try {
        onEvent(JSON.parse(data) as AiAgentStreamEvent);
      } catch {
        onEvent({
          type: "message_delta",
          delta: { type: "text_delta", text: data },
        });
      }
    };
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index;
      while ((index = buffer.indexOf("\n\n")) >= 0) {
        dispatch(buffer.slice(0, index));
        buffer = buffer.slice(index + 2);
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) dispatch(buffer);
  },
  getEmailAccount() {
    return requestJson<EmailAccount>("/api/emails/account");
  },
  saveEmailAccount(payload: { emailAddress: string; appPassword: string }) {
    return requestJson<EmailAccount>("/api/emails/account", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  testEmailAccount() {
    return requestJson<{ success: boolean; message: string }>(
      "/api/emails/account/test",
      { method: "POST" }
    );
  },
  removeEmailAccount() {
    return requestJson<void>("/api/emails/account", { method: "DELETE" });
  },
  getEmailMessages(
    params: {
      page?: number;
      size?: number;
      date?: string;
      keyword?: string;
      projectId?: number;
      ungrouped?: boolean;
      senderDomain?: string;
    } = {}
  ) {
    return requestJson<EmailMessagePage>(
      `/api/emails/messages${query({
        ...params,
        ungrouped: params.ungrouped ? "true" : undefined,
      } as Record<string, string | number | undefined>)}`
    );
  },
  getEmailMessage(id: number) {
    return requestJson<EmailMessageDetail>(`/api/emails/messages/${id}`);
  },
  getEmailDigest(date: string) {
    return requestJson<EmailDailyDigest>(
      `/api/emails/digests?date=${encodeURIComponent(date)}`
    );
  },
  regenerateEmailDigest(date: string) {
    return requestJson<EmailDailyDigest>(
      `/api/emails/digests/${encodeURIComponent(date)}/regenerate`,
      { method: "POST" }
    );
  },
  startEmailSync() {
    return requestJson<EmailSyncStatus>("/api/emails/sync", { method: "POST" });
  },
  getEmailSyncStatus() {
    return requestJson<EmailSyncStatus>("/api/emails/sync/status");
  },
  getEmailProjectGroups() {
    return requestJson<EmailProjectGroup[]>("/api/emails/project-groups");
  },
  getEmailSenderCompanyGroups() {
    return requestJson<EmailSenderCompanyGroup[]>(
      "/api/emails/sender-company-groups"
    );
  },
  getEmailWeComMapping() {
    return requestJson<EmailWeComMapping>("/api/emails/wecom-mapping");
  },
  saveEmailWeComMapping(weComUserId: string, enabled = true) {
    return requestJson<EmailWeComMapping>("/api/emails/wecom-mapping", {
      method: "PUT",
      body: JSON.stringify({ weComUserId, enabled }),
    });
  },
  getEmailInterpretation(id: number) {
    return requestJson<EmailInterpretation>(
      `/api/emails/messages/${id}/interpretation`
    );
  },
  generateEmailInterpretation(id: number) {
    return requestJson<EmailInterpretation>(
      `/api/emails/messages/${id}/interpretation`,
      { method: "POST" }
    );
  },
  startEmailGrouping(regroupAll = false) {
    return requestJson<EmailGroupingJobStatus>(
      `/api/emails/grouping?regroupAll=${regroupAll}`,
      { method: "POST" }
    );
  },
  getEmailGroupingStatus() {
    return requestJson<EmailGroupingJobStatus>("/api/emails/grouping/status");
  },
  assignEmailProject(id: number, projectId: number) {
    return requestJson<void>(`/api/emails/messages/${id}/project`, {
      method: "PUT",
      body: JSON.stringify({ projectId }),
    });
  },
  convertEmailItem(payload: {
    messageId: number;
    itemKind: string;
    itemTitle: string;
    actionType: "TASK" | "ISSUE" | "KEY_MATTER";
    requirementId?: number;
    assigneeId?: number;
    severity?: string;
    projectId?: number;
  }) {
    return requestJson<EmailConvertResult>("/api/emails/actions/convert", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  getEmailActions(messageId: number) {
    return requestJson<EmailActionLink[]>(`/api/emails/actions/${messageId}`);
  },
  replyEmail(messageId: number, bodyText: string, subject?: string) {
    return requestJson<EmailReplyResult>(
      `/api/emails/messages/${messageId}/reply`,
      { method: "POST", body: JSON.stringify({ bodyText, subject }) }
    );
  },
  feedbackEmailDigest(date: string, feedback: "USEFUL" | "USELESS") {
    return requestJson<EmailDailyDigest>(
      `/api/emails/digests/${encodeURIComponent(date)}/feedback`,
      { method: "POST", body: JSON.stringify({ feedback }) }
    );
  },
  getEmailValueMetrics() {
    return requestJson<EmailValueMetrics>("/api/emails/metrics");
  },
  getStatistics() {
    return requestJson<Record<string, unknown>>("/api/bu-dashboard");
  },
  getBuDashboard<T = Record<string, unknown>>(
    params: {
      startDate?: string;
      endDate?: string;
      planWindowWorkdays?: number;
    } = {}
  ) {
    return requestJson<T>(`/api/bu-dashboard${query(params)}`);
  },
  createBuDirection(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/bu-directions", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateBuDirection(id: number | string, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(`/api/bu-directions/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteBuDirection(id: number | string) {
    return requestJson<void>(`/api/bu-directions/${id}`, { method: "DELETE" });
  },
  getYunxiaoStatus<T = Record<string, unknown>>() {
    return requestJson<T>("/api/yunxiao/status");
  },
  getYunxiaoAnalysis<T = Record<string, unknown>>() {
    return requestJson<T>("/api/yunxiao/analysis");
  },
  getYunxiaoProjectMappings() {
    return requestJson<Record<string, unknown>[]>(
      "/api/yunxiao/project-mappings"
    );
  },
  getYunxiaoProjects() {
    return requestJson<Record<string, unknown>[]>("/api/yunxiao/projects");
  },
  getYunxiaoMembers() {
    return requestJson<Record<string, unknown>[]>("/api/yunxiao/members");
  },
  saveYunxiaoProjectMapping(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      "/api/yunxiao/project-mappings",
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  deleteYunxiaoProjectMapping(id: number | string) {
    return requestJson<void>(`/api/yunxiao/project-mappings/${id}`, {
      method: "DELETE",
    });
  },
  getYunxiaoUserMappings() {
    return requestJson<Record<string, unknown>[]>("/api/yunxiao/user-mappings");
  },
  saveYunxiaoUserMapping(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/yunxiao/user-mappings", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  deleteYunxiaoUserMapping(id: number | string) {
    return requestJson<void>(`/api/yunxiao/user-mappings/${id}`, {
      method: "DELETE",
    });
  },
  syncYunxiao() {
    return requestJson<string[]>("/api/yunxiao/sync", { method: "POST" });
  },
  getKeyMatterAccess() {
    return requestJson<Record<string, unknown>>("/api/key-matters/access");
  },
  getKeyMatters(
    params: {
      keyword?: string;
      status?: string;
      priority?: string;
      ownerId?: number;
      projectId?: number;
    } = {}
  ) {
    return requestJson<Record<string, unknown>[]>(
      `/api/key-matters${query(params)}`
    );
  },
  getKeyMatter(id: number) {
    return requestJson<Record<string, unknown>>(`/api/key-matters/${id}`);
  },
  getKeyMatterMeeting(weekStartDate: string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/key-matters/meeting?weekStartDate=${encodeURIComponent(
        weekStartDate
      )}`
    );
  },
  createKeyMatter(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/key-matters", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateKeyMatter(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(`/api/key-matters/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteKeyMatter(id: number) {
    return requestJson<void>(`/api/key-matters/${id}`, { method: "DELETE" });
  },
  upsertKeyMatterWeeklyUpdate(
    id: number,
    weekStartDate: string,
    payload: Record<string, unknown>
  ) {
    return requestJson<Record<string, unknown>>(
      `/api/key-matters/${id}/weekly-updates/${weekStartDate}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteKeyMatterWeeklyUpdate(id: number, weekStartDate: string) {
    return requestJson<void>(
      `/api/key-matters/${id}/weekly-updates/${weekStartDate}`,
      { method: "DELETE" }
    );
  },
  getKpiReport(year: number) {
    return requestJson<Record<string, unknown>>(`/api/kpi/report?year=${year}`);
  },
  runKpiSnapshot(weekEndDate?: string) {
    return requestJson<unknown>(
      `/api/kpi/snapshot/run${
        weekEndDate ? `?weekEndDate=${weekEndDate}` : ""
      }`,
      { method: "POST" }
    );
  },
  getKpiTargets(year: number) {
    return requestJson<Record<string, unknown>[]>(
      `/api/kpi/targets?year=${year}`
    );
  },
  getKpiAlertRules() {
    return requestJson<Record<string, unknown>[]>("/api/kpi/alert-rules");
  },
  saveKpiTarget(payload: {
    year: number;
    reportGroup: string;
    revenueTarget: number;
    profitTarget: number;
    remark?: string;
  }) {
    return requestJson<Record<string, unknown>>("/api/kpi/targets", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  saveKpiNote(
    id: number,
    payload: {
      deviationReason: string;
      isAbnormal: number | null;
      countermeasure?: string;
    }
  ) {
    return requestJson<Record<string, unknown>>(`/api/kpi/notes/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  saveKpiAlertRule(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/kpi/alert-rules", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  getWorktimeStatus() {
    return requestJson<Record<string, unknown>>("/api/worktime/status");
  },
  syncWorktimeContracts(year?: number) {
    return requestJson<Record<string, unknown>>(
      `/api/worktime/sync/contracts${year ? `?year=${year}` : ""}`,
      { method: "POST" }
    );
  },
  syncWorktimeMonthly(forceMonth?: string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/worktime/sync/monthly${
        forceMonth ? `?forceMonth=${forceMonth}` : ""
      }`,
      { method: "POST" }
    );
  },
  getWorktimeSyncLogs(syncType?: string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/worktime/sync/logs${
        syncType ? `?syncType=${encodeURIComponent(syncType)}` : ""
      }`
    );
  },
  // ==================== 数据集成中心（统一同步 /api/sync） ====================
  listSyncTasks() {
    return requestJson<SyncTask[]>("/api/sync/tasks");
  },
  updateSyncTask(
    taskCode: string,
    payload: { cron?: string | null; enabled?: boolean }
  ) {
    return requestJson<SyncTask>(`/api/sync/tasks/${taskCode}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  runSyncTask(taskCode: string, scope?: string) {
    return requestJson<DataSyncLog[]>(`/api/sync/tasks/${taskCode}/run`, {
      method: "POST",
      body: JSON.stringify(scope ? { scope } : {}),
    });
  },
  getSyncLogs(params?: { domain?: string; status?: string; taskCode?: string }) {
    return requestJson<DataSyncLog[]>(
      `/api/sync/logs${query({
        domain: params?.domain,
        status: params?.status,
        taskCode: params?.taskCode,
      })}`
    );
  },
  getSyncOverview() {
    return requestJson<SyncOverviewItem[]>("/api/sync/overview");
  },
  async downloadKpiReport(year: number) {
    const response = await fetch(`/api/kpi/report/export?year=${year}`, {
      headers: localStorage.getItem("token")
        ? { Authorization: `Bearer ${localStorage.getItem("token")}` }
        : {},
    });
    if (!response.ok)
      throw new ApiRequestError(
        `导出失败（${response.status}）`,
        response.status
      );
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `KPI周报-${year}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
  },
  getRevenueMatrix(year: number) {
    return requestJson<RevenueMatrix>(`/api/revenue/matrix?year=${year}`);
  },
  getRevenueImportBatches(importType?: string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/revenue/imports${
        importType ? `?importType=${encodeURIComponent(importType)}` : ""
      }`
    );
  },
  getRevenueEstimates(yearMonth?: string) {
    return requestJson<Record<string, unknown>[]>(
      `/api/revenue/estimates${
        yearMonth ? `?yearMonth=${encodeURIComponent(yearMonth)}` : ""
      }`
    );
  },
  createRevenueEstimate(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/revenue/estimates", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateRevenueEstimate(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/estimates/${id}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteRevenueEstimate(id: number) {
    return requestJson<void>(`/api/revenue/estimates/${id}`, {
      method: "DELETE",
    });
  },
  getRevenuePending() {
    return requestJson<{
      worklog: Record<string, unknown>[];
      cost: Record<string, unknown>[];
    }>("/api/revenue/pending");
  },
  getRevenueSalesProjects() {
    return requestJson<Record<string, unknown>[]>(
      "/api/revenue/sales-projects"
    );
  },
  getRevenueOpportunityOptions() {
    return requestJson<Record<string, unknown>[]>(
      "/api/revenue/opportunity-options"
    );
  },
  closeRevenueMonth(yearMonth: string) {
    return requestJson<void>(`/api/revenue/months/${yearMonth}/close`, {
      method: "POST",
    });
  },
  reopenRevenueMonth(yearMonth: string) {
    return requestJson<void>(`/api/revenue/months/${yearMonth}/reopen`, {
      method: "POST",
    });
  },
  getRevenueCellDetail(
    yearMonth: string,
    businessLineId: number,
    rowKey: string
  ) {
    return requestJson<RevenueCellDetail>(
      `/api/revenue/cell-detail?yearMonth=${encodeURIComponent(
        yearMonth
      )}&businessLineId=${businessLineId}&rowKey=${encodeURIComponent(rowKey)}`
    );
  },
  resolveRevenuePending(
    type: "worklog" | "cost",
    id: number,
    businessLineId: number,
    projectId?: number
  ) {
    return requestJson<void>(`/api/revenue/pending/${type}/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ businessLineId, projectId: projectId ?? null }),
    });
  },
  bindRevenueSalesProject(id: number, opportunityId: number | null) {
    return requestJson<void>(`/api/revenue/sales-projects/${id}`, {
      method: "PUT",
      body: JSON.stringify({ opportunityId }),
    });
  },
  importRevenueWorklog(file: File, yearMonth: string) {
    const body = new FormData();
    body.append("file", file);
    return requestJson<Record<string, unknown>>(
      `/api/revenue/import/worklog?yearMonth=${encodeURIComponent(yearMonth)}`,
      { method: "POST", body }
    );
  },
  importRevenueCost(file: File) {
    const body = new FormData();
    body.append("file", file);
    return requestJson<Record<string, unknown>>("/api/revenue/import/cost", {
      method: "POST",
      body,
    });
  },
  createRevenueWorklogEntry(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      "/api/revenue/worklog-entries",
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  updateRevenueWorklogEntry(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/worklog-entries/${id}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteRevenueWorklogEntry(id: number) {
    return requestJson<void>(`/api/revenue/worklog-entries/${id}`, {
      method: "DELETE",
    });
  },
  createRevenueCostEntry(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/revenue/cost-entries", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateRevenueCostEntry(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/cost-entries/${id}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteRevenueCostEntry(id: number) {
    return requestJson<void>(`/api/revenue/cost-entries/${id}`, {
      method: "DELETE",
    });
  },
  getDeliverySummary(params: {
    year: number;
    includeEstimate?: boolean;
    excludeTax?: boolean;
  }) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/delivery/summary?year=${params.year}&includeEstimate=${
        params.includeEstimate !== false
      }&excludeTax=${Boolean(params.excludeTax)}`
    );
  },
  getDeliveryPlans(
    params: {
      year?: number;
      businessLineId?: number;
      projectId?: number | null;
    } = {}
  ) {
    return requestJson<Record<string, unknown>[]>(
      `/api/revenue/delivery-plans${query(
        params as Record<string, string | number | undefined>
      )}`
    );
  },
  createDeliveryPlansBatch(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>[]>(
      "/api/revenue/delivery-plans/batch",
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  updateDeliveryPlan(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/delivery-plans/${id}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteDeliveryPlan(id: number) {
    return requestJson<void>(`/api/revenue/delivery-plans/${id}`, {
      method: "DELETE",
    });
  },
  getOtherCosts(
    params: {
      year?: number;
      businessLineId?: number;
      projectId?: number | null;
    } = {}
  ) {
    return requestJson<Record<string, unknown>[]>(
      `/api/revenue/other-costs${query(
        params as Record<string, string | number | undefined>
      )}`
    );
  },
  createOtherCost(payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>("/api/revenue/other-costs", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateOtherCost(id: number, payload: Record<string, unknown>) {
    return requestJson<Record<string, unknown>>(
      `/api/revenue/other-costs/${id}`,
      { method: "PUT", body: JSON.stringify(payload) }
    );
  },
  deleteOtherCost(id: number) {
    return requestJson<void>(`/api/revenue/other-costs/${id}`, {
      method: "DELETE",
    });
  },
  importDeliveryContracts(file: File) {
    const body = new FormData();
    body.append("file", file);
    return requestJson<Record<string, unknown>>(
      "/api/revenue/contracts/import",
      { method: "POST", body }
    );
  },
  getPendingDeliveryContracts() {
    return requestJson<Record<string, unknown>[]>(
      "/api/revenue/contracts/pending"
    );
  },
  resolvePendingDeliveryContract(
    id: number,
    projectId?: number | null,
    businessLineId?: number | null
  ) {
    return requestJson<void>(`/api/revenue/contracts/pending/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({
        projectId: projectId ?? null,
        businessLineId: businessLineId ?? null,
      }),
    });
  },
  getDeliveryContractBatches() {
    return requestJson<Record<string, unknown>[]>(
      "/api/revenue/contracts/batches"
    );
  },
  getMappedDeliveryContracts(year: number) {
    return requestJson<Record<string, unknown>[]>(
      `/api/revenue/contracts/mapped?year=${year}`
    );
  },
  updateDeliveryContractMapping(id: number, payload: Record<string, unknown>) {
    return requestJson<void>(`/api/revenue/contracts/${id}/mapping`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  createProject(
    payload: Omit<ProjectRecord, "id" | "fullPath" | "createdAt" | "updatedAt">
  ) {
    return requestJson<ProjectRecord>("/api/projects", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateProject(
    id: number,
    payload: Omit<ProjectRecord, "id" | "fullPath" | "createdAt" | "updatedAt">
  ) {
    return requestJson<ProjectRecord>(`/api/projects/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteProject(id: number) {
    return requestJson<void>(`/api/projects/${id}`, { method: "DELETE" });
  },
  getProjectMembers(projectId: number) {
    return requestJson<ProjectMember[]>(
      `/api/project-members/by-project?projectId=${projectId}`
    );
  },
  addProjectMember(payload: {
    projectId: number;
    userId: number;
    role?: string;
  }) {
    return requestJson<ProjectMember>("/api/project-members", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  removeProjectMember(projectId: number, userId: number) {
    return requestJson<void>(
      `/api/project-members?projectId=${projectId}&userId=${userId}`,
      { method: "DELETE" }
    );
  },
  getCustomerContacts(
    params: {
      page?: number;
      size?: number;
      projectId?: number;
      name?: string;
      isActive?: number;
    } = {}
  ) {
    return requestJson<{
      records: CustomerContact[];
      total: number;
      current: number;
      size: number;
    }>(`/api/customer-contacts${query(params)}`);
  },
  createCustomerContact(
    payload: Omit<CustomerContact, "id" | "createdAt" | "updatedAt">
  ) {
    return requestJson<CustomerContact>("/api/customer-contacts", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateCustomerContact(
    id: number,
    payload: Omit<CustomerContact, "id" | "createdAt" | "updatedAt">
  ) {
    return requestJson<CustomerContact>(`/api/customer-contacts/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteCustomerContact(id: number) {
    return requestJson<void>(`/api/customer-contacts/${id}`, {
      method: "DELETE",
    });
  },
  getSalesOpportunities(
    params: {
      keyword?: string;
      type?: string;
      status?: string;
      owner?: string;
      businessLine?: string;
    } = {}
  ) {
    return requestJson<SalesOpportunity[]>(
      `/api/sales-opportunities${query(params)}`
    );
  },
  createSalesOpportunity(payload: Omit<SalesOpportunity, "id" | "createdAt">) {
    return requestJson<SalesOpportunity>("/api/sales-opportunities", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateSalesOpportunity(
    id: number,
    payload: Omit<SalesOpportunity, "id" | "createdAt">
  ) {
    return requestJson<SalesOpportunity>(`/api/sales-opportunities/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteSalesOpportunity(id: number) {
    return requestJson<void>(`/api/sales-opportunities/${id}`, {
      method: "DELETE",
    });
  },
  getSalesOpportunityFollowUps(id: number) {
    return requestJson<SalesOpportunityFollowUp[]>(
      `/api/sales-opportunities/${id}/follow-ups`
    );
  },
  createSalesOpportunityFollowUp(
    id: number,
    payload: Omit<
      SalesOpportunityFollowUp,
      "id" | "opportunityId" | "createdAt"
    >
  ) {
    return requestJson<SalesOpportunityFollowUp>(
      `/api/sales-opportunities/${id}/follow-ups`,
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  getSalesOpportunitySupportWorklogs(id: number) {
    return requestJson<SalesOpportunitySupportWorklog[]>(
      `/api/sales-opportunities/${id}/support-worklogs`
    );
  },
  createSalesOpportunitySupportWorklog(
    id: number,
    payload: Omit<
      SalesOpportunitySupportWorklog,
      "id" | "opportunityId" | "createdAt"
    >
  ) {
    return requestJson<SalesOpportunitySupportWorklog>(
      `/api/sales-opportunities/${id}/support-worklogs`,
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  getOpportunityQuotations(id: number) {
    return requestJson<QuotationListVO[]>(
      `/api/sales-opportunities/${id}/quotations`
    );
  },
  getQuotationPolicies(
    params: { type?: string; taxMode?: string; status?: string } = {}
  ) {
    return requestJson<QuotationPolicy[]>(
      `/api/quotation-policies${query(params)}`
    );
  },
  createQuotationPolicy(
    payload: Pick<QuotationPolicy, "name" | "type" | "taxMode"> & {
      effectiveDate?: string;
      expiryDate?: string;
    }
  ) {
    return requestJson<QuotationPolicy>("/api/quotation-policies", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateQuotationPolicy(
    id: number,
    payload: Pick<QuotationPolicy, "name" | "type" | "taxMode"> & {
      effectiveDate?: string;
      expiryDate?: string;
    }
  ) {
    return requestJson<QuotationPolicy>(`/api/quotation-policies/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  publishQuotationPolicy(id: number) {
    return requestJson<QuotationPolicy>(
      `/api/quotation-policies/${id}/publish`,
      { method: "PUT" }
    );
  },
  archiveQuotationPolicy(id: number) {
    return requestJson<void>(`/api/quotation-policies/${id}/archive`, {
      method: "PUT",
    });
  },
  deleteQuotationPolicy(id: number) {
    return requestJson<void>(`/api/quotation-policies/${id}`, {
      method: "DELETE",
    });
  },
  getQuotationPolicyItems(policyId: number) {
    return requestJson<QuotationPolicyItem[]>(
      `/api/quotation-policies/${policyId}/items`
    );
  },
  addQuotationPolicyItems(
    policyId: number,
    payload: Array<Omit<QuotationPolicyItem, "id" | "policyId">>
  ) {
    return requestJson<void>(`/api/quotation-policies/${policyId}/items`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateQuotationPolicyItem(
    id: number,
    payload: Omit<QuotationPolicyItem, "id" | "policyId">
  ) {
    return requestJson<void>(`/api/quotation-policies/items/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteQuotationPolicyItem(id: number) {
    return requestJson<void>(`/api/quotation-policies/items/${id}`, {
      method: "DELETE",
    });
  },
  getQuotations(
    params: {
      keyword?: string;
      status?: string;
      customerName?: string;
      page?: number;
      size?: number;
    } = {}
  ) {
    return requestJson<QuotationListVO[] | { records?: QuotationListVO[] }>(
      `/api/quotations${query(params)}`
    );
  },
  getQuotationDetail(id: number) {
    return requestJson<Quotation>(`/api/quotations/${id}`);
  },
  generateQuotation(payload: QuotationGenerateRequest) {
    return requestJson<Quotation>("/api/quotations/generate", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateQuotationStatus(id: number, status: string) {
    return requestJson<void>(`/api/quotations/${id}/status`, {
      method: "PUT",
      body: JSON.stringify({ status }),
    });
  },
  deleteQuotation(id: number) {
    return requestJson<void>(`/api/quotations/${id}`, { method: "DELETE" });
  },
  async exportQuotation(id: number) {
    const headers = new Headers();
    const token = localStorage.getItem("token");
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(`/api/quotations/${id}/export`, { headers });
    if (!response.ok)
      throw new ApiRequestError(
        `导出失败（${response.status}）`,
        response.status
      );
    return response.blob();
  },
  getSystemConfigGroups() {
    return requestJson<SystemConfigGroupSummary[]>("/api/system/configs");
  },
  getSystemConfigGroup(groupCode: string) {
    return requestJson<SystemConfigGroup>(
      `/api/system/configs/${encodeURIComponent(groupCode)}`
    );
  },
  saveSystemConfigGroup(groupCode: string, values: Record<string, string>) {
    return requestJson<SystemConfigGroup>(
      `/api/system/configs/${encodeURIComponent(groupCode)}`,
      { method: "PUT", body: JSON.stringify({ values }) }
    );
  },
  getWeeklyReport(weekStart?: string) {
    return requestJson<WeeklyReportVO>(
      `/api/weekly-reports${
        weekStart ? `?weekStart=${encodeURIComponent(weekStart)}` : ""
      }`
    );
  },
  getWeeklyFacts(weekStart?: string) {
    return requestJson<WeeklyReportFacts>(
      `/api/weekly-reports/facts${
        weekStart ? `?weekStart=${encodeURIComponent(weekStart)}` : ""
      }`
    );
  },
  getWeeklyHistory() {
    return requestJson<WeeklyReportVO[]>("/api/weekly-reports/history");
  },
  saveWeeklyInputs(
    id: number,
    payload: { wecomSummary?: string; manualNotes?: string }
  ) {
    return requestJson<WeeklyReportVO>(`/api/weekly-reports/${id}/inputs`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  generateWeeklyReport(id: number) {
    return requestJson<WeeklyReportVO>(`/api/weekly-reports/${id}/generate`, {
      method: "POST",
    });
  },
  saveWeeklyContent(
    id: number,
    payload: {
      coreWork?: string;
      kpiSection?: string;
      risks?: string;
      nextWeekPlan?: string;
      minutesMarkdown?: string;
    }
  ) {
    return requestJson<WeeklyReportVO>(`/api/weekly-reports/${id}/content`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  confirmWeeklyReport(id: number) {
    return requestJson<WeeklyReportVO>(`/api/weekly-reports/${id}/confirm`, {
      method: "PUT",
    });
  },
  publishWeeklyYuque(id: number) {
    return requestJson<WeeklyReportVO>(
      `/api/weekly-reports/${id}/publish-yuque`,
      { method: "POST" }
    );
  },
  publishWeeklySheet(id: number) {
    return requestJson<WeeklyReportVO>(
      `/api/weekly-reports/${id}/publish-sheet`,
      { method: "POST" }
    );
  },
  pushWeeklyWecom(id: number) {
    return requestJson<WeeklyReportVO>(`/api/weekly-reports/${id}/push-wecom`, {
      method: "POST",
    });
  },
  getRequirementOverview(
    params: {
      page?: number;
      size?: number;
      businessLineId?: number;
      projectId?: number;
      assigneeId?: number;
      dataSource?: string;
      normalizedStatus?: string;
      type?: string;
      status?: string;
      priority?: string;
      title?: string;
      keyword?: string;
    } = {}
  ) {
    const overviewParams = {
      ...params,
      keyword: params.keyword || params.title,
    };
    delete (overviewParams as Record<string, unknown>).title;
    delete (overviewParams as Record<string, unknown>).status;
    return requestJson<OverviewResponse>(
      `/api/requirements/overview${query(
        overviewParams as Record<string, string | number | undefined>
      )}`
    );
  },
  getCustomerContactPage(
    params: {
      page?: number;
      size?: number;
      projectId?: number;
      name?: string;
      isActive?: number;
    } = {}
  ) {
    return requestJson<Record<string, unknown>>(
      `/api/customer-contacts${query(
        params as Record<string, string | number | undefined>
      )}`
    );
  },
  getQuotationPolicyDetail(id: number) {
    return requestJson<Record<string, unknown>>(
      `/api/quotation-policies/${id}`
    );
  },
  publishPolicy(id: number) {
    return requestJson<Record<string, unknown>>(
      `/api/quotation-policies/${id}/publish`,
      { method: "PUT" }
    );
  },
  archivePolicy(id: number) {
    return requestJson<void>(`/api/quotation-policies/${id}/archive`, {
      method: "PUT",
    });
  },
  getPolicyItems(policyId: number) {
    return requestJson<Record<string, unknown>[]>(
      `/api/quotation-policies/${policyId}/items`
    );
  },
  addPolicyItems(policyId: number, items: Record<string, unknown>[]) {
    return requestJson<void>(`/api/quotation-policies/${policyId}/items`, {
      method: "POST",
      body: JSON.stringify(items),
    });
  },
  updatePolicyItem(itemId: number, payload: Record<string, unknown>) {
    return requestJson<void>(`/api/quotation-policies/items/${itemId}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deletePolicyItem(itemId: number) {
    return requestJson<void>(`/api/quotation-policies/items/${itemId}`, {
      method: "DELETE",
    });
  },
  getDeliveryUnitPrice(projectId: number) {
    return requestJson<unknown>(
      `/api/revenue/estimates/unit-price?projectId=${projectId}`
    );
  },
  getBlProfitReport(year: number) {
    return requestJson<BizLineProfitReport>(`/api/finance/bl-profit?year=${year}`);
  },
  syncBlProfit(body: { year?: number; month?: string }) {
    return requestJson<WorktimeSyncLog[]>("/api/finance/bl-profit/sync", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  getBlProfitSyncLogs(limit = 10) {
    return requestJson<WorktimeSyncLog[]>(
      `/api/finance/bl-profit/sync-logs?limit=${limit}`
    );
  },
  getMeetings(
    params: { page?: number; size?: number; status?: MeetingStatus } = {}
  ) {
    return requestJson<MeetingList>(`/api/meetings${query(params)}`);
  },
  getMeeting(id: number) {
    return requestJson<MeetingDetail>(`/api/meetings/${id}`);
  },
  getMeetingStatus(id: number) {
    return requestJson<MeetingStatusSnapshot>(`/api/meetings/${id}/status`);
  },
  uploadMeeting(payload: {
    file: File;
    title: string;
    meetingDate: string;
    projectId?: number;
  }) {
    const body = new FormData();
    body.append("file", payload.file);
    body.append("title", payload.title);
    body.append("meetingDate", payload.meetingDate);
    if (payload.projectId != null)
      body.append("projectId", String(payload.projectId));
    return requestJson<MeetingListItem>("/api/meetings", {
      method: "POST",
      body,
    });
  },
  updateMeetingTranscript(
    id: number,
    segments: Array<{ seq: number; speaker: string; text: string }>
  ) {
    return requestJson<MeetingSegment[]>(`/api/meetings/${id}/transcript`, {
      method: "PUT",
      body: JSON.stringify({ segments }),
    });
  },
  updateMeetingSpeakers(
    id: number,
    speakers: Array<{
      speakerLabel: string;
      displayName?: string | null;
      mappedUserId?: number | null;
    }>
  ) {
    return requestJson<MeetingSpeaker[]>(`/api/meetings/${id}/speakers`, {
      method: "PUT",
      body: JSON.stringify({ speakers }),
    });
  },
  summarizeMeeting(id: number) {
    return requestJson<MeetingStatusSnapshot>(
      `/api/meetings/${id}/summarize`,
      { method: "POST" }
    );
  },
  reprocessMeeting(id: number) {
    return requestJson<MeetingStatusSnapshot>(
      `/api/meetings/${id}/reprocess`,
      { method: "POST" }
    );
  },
  updateMeetingTodo(
    id: number,
    todoId: number,
    payload: MeetingTodoUpdatePayload
  ) {
    return requestJson<MeetingTodo>(`/api/meetings/${id}/todos/${todoId}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  convertMeetingTodo(
    id: number,
    todoId: number,
    payload: MeetingTodoConvertPayload
  ) {
    return requestJson<MeetingTodo>(
      `/api/meetings/${id}/todos/${todoId}/convert`,
      { method: "POST", body: JSON.stringify(payload) }
    );
  },
  confirmMeeting(id: number) {
    return requestJson<MeetingStatusSnapshot>(`/api/meetings/${id}/confirm`, {
      method: "POST",
    });
  },
  deleteMeeting(id: number) {
    return requestJson<void>(`/api/meetings/${id}`, { method: "DELETE" });
  },
  async getMeetingAudioBlob(id: number) {
    const headers = new Headers();
    const token = localStorage.getItem("token");
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(`/api/meetings/${id}/audio`, { headers });
    if (!response.ok)
      throw new ApiRequestError(
        `音频加载失败（${response.status}）`,
        response.status
      );
    return response.blob();
  },
  // ==================== 致远 OA 待办（网页会话通道 + 审批） ====================
  getOaSessionStatus() {
    return requestJson<OaSessionStatus>("/api/seeyon-oa/session");
  },
  authorizeOaSession(cookie: string) {
    return requestJson<OaSessionStatus>("/api/seeyon-oa/session", {
      method: "POST",
      body: JSON.stringify({ cookie }),
    });
  },
  /** 自动授权：OA 不强制验证码时直接成功；否则返回 authorized=false 与可读提示。 */
  autoOaLogin() {
    return requestJson<OaSessionStatus>("/api/seeyon-oa/session/auto", {
      method: "POST",
    });
  },
  /** 拉取登录验证码图片（含 challengeId，供账号密码 + 验证码登录使用）。 */
  getOaCaptcha() {
    return requestJson<OaCaptchaChallenge>("/api/seeyon-oa/session/captcha");
  },
  /** 账号密码（连接器配置）+ 验证码登录；challengeId 为空时尝试免验证码登录。 */
  loginOaSession(payload: { challengeId?: string; captcha?: string }) {
    return requestJson<OaSessionStatus>("/api/seeyon-oa/session/login", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  clearOaSession() {
    return requestJson<void>("/api/seeyon-oa/session", { method: "DELETE" });
  },
  getOaPendingAffairs() {
    return requestJson<OaAffair[]>("/api/seeyon-oa/affairs/pending");
  },
  getOaDoneAffairs() {
    return requestJson<OaAffair[]>("/api/seeyon-oa/affairs/done");
  },
  approveOaAffair(affairId: string, action: OaAffairAction) {
    return requestJson<string>(
      `/api/seeyon-oa/affairs/${encodeURIComponent(affairId)}/approve`,
      { method: "POST", body: JSON.stringify({ action }) }
    );
  },
  batchApproveOaAffairs(affairIds: string[], action: OaAffairAction) {
    return requestJson<OaBatchApproveResult[]>(
      "/api/seeyon-oa/affairs/batch-approve",
      { method: "POST", body: JSON.stringify({ affairIds, action }) }
    );
  },
};

export default superworkApi;
