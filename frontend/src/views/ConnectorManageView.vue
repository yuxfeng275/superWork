<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Document, Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import { splitHintLinks } from '@/utils/hint-links'
import type { AiConnectorAuthType, AiConnectorSavePayload, AiConnectorStatus, AiConnectorView } from '@/types/ai-agent'
import type { WecomCliAuthState, WecomCliCapability, WecomCliCapabilityState, WecomCliStatus } from '@/types/wecom-cli'

/** 连接器列表 */
const connectors = ref<AiConnectorView[]>([])
/** 状态摘要（GET /api/connectors/status，与 AI 助手面板同口径） */
const statuses = ref<AiConnectorStatus[]>([])
const loading = ref(false)
const testingId = ref<number | null>(null)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const router = useRouter()

type ConnectorStatus = 'READY' | 'NOT_CONFIGURED' | 'DISABLED'

type ExtraKind = 'text' | 'select' | 'number'

interface ExtraField {
  key: string
  label: string
  kind: ExtraKind
  placeholder?: string
  options?: Array<{ label: string; value: string }>
}

/**
 * 内置连接器的专属参数（extraConfig）；自建连接器只有通用字段。
 * 模型名 / 用途 / 默认模型由「模型管理」维护，不再作为连接器专属参数。
 */
const EXTRA_FIELDS: Record<string, ExtraField[]> = {
  yunxiao: [
    {
      key: 'edition',
      label: '云效版本',
      kind: 'select',
      options: [
        { label: '中心化版本', value: 'center' },
        { label: '专有云版本', value: 'region' }
      ]
    },
    { key: 'organizationId', label: '组织 ID', kind: 'text', placeholder: '云效企业组织 ID（中心化版本必填）' }
  ],
  oa: [
    {
      key: 'contractExportUrl',
      label: '销售合同导出地址',
      kind: 'text',
      placeholder: 'https://…（含查询参数的完整导出地址）'
    }
  ],
  yuque: [
    { key: 'repo', label: '知识库', kind: 'text', placeholder: 'login/repo' },
    { key: 'parentDir', label: '父目录', kind: 'text', placeholder: '部门会议' }
  ],
  mail: [{ key: 'searchDays', label: '检索回溯天数', kind: 'number', placeholder: '默认 90 天' }],
  wecom: [
    { key: 'corpId', label: 'CorpId', kind: 'text', placeholder: '企业 ID' },
    { key: 'agentId', label: 'AgentId', kind: 'text', placeholder: '内部应用 AgentId' }
  ]
}

/** 连接器关联的功能页面（连接器配置就绪后在此使用） */
const RELATED_PAGES: Record<string, { path: string; label: string }> = {
  yunxiao: { path: '/statistics', label: 'BU驾驶舱' },
  worktime: { path: '/kpi-report', label: 'KPI周报 · 数据同步' },
  oa: { path: '/oa-affairs', label: 'OA 待办审批台' },
  mail: { path: '/emails', label: '邮件管理' }
}

/** AI 模型提供方：模型名 / 用途 / 默认在「模型管理」维护，这里只指向过去 */
const MODEL_PROVIDER_CODES = ['deepseek', 'glm', 'typesafe']

function openModelManage() {
  void router.push('/system/models')
}

const AUTH_LABEL: Record<AiConnectorAuthType, string> = {
  BASIC: '账号密码',
  TOKEN: 'Token',
  MCP: 'MCP',
  SEEYON: '致远 OA',
  WECOM: '企业微信',
  MAIL: '企业邮箱'
}

const STATUS_LABEL: Record<ConnectorStatus, string> = {
  READY: '就绪',
  NOT_CONFIGURED: '待配置',
  DISABLED: '已停用'
}

const STATUS_TAG: Record<ConnectorStatus, 'success' | 'warning' | 'info'> = {
  READY: 'success',
  NOT_CONFIGURED: 'warning',
  DISABLED: 'info'
}

const CREDENTIAL_PLACEHOLDER = '已配置；留空保持不变'

/** 扫码授权轮询间隔与二维码倒计时刷新间隔（毫秒） */
const QR_POLL_INTERVAL = 3000
const QR_TICK_INTERVAL = 1000

const CAPABILITY_STATE_LABEL: Record<WecomCliCapabilityState, string> = {
  AVAILABLE: '可用',
  EXPIRED: '已过期',
  UNAUTHORIZED: '未授权',
  UNAVAILABLE: '未对企开放',
  ERROR: '异常'
}

const CAPABILITY_STATE_TAG: Record<WecomCliCapabilityState, 'success' | 'warning' | 'info' | 'danger'> = {
  AVAILABLE: 'success',
  EXPIRED: 'warning',
  UNAUTHORIZED: 'info',
  UNAVAILABLE: 'warning',
  ERROR: 'danger'
}

const QR_STATE_LABEL: Record<'loading' | WecomCliAuthState, string> = {
  loading: '二维码获取中',
  pending: '等待扫码',
  authorized: '授权成功',
  expired: '二维码已过期',
  failed: '授权失败'
}

const QR_STATE_TAG: Record<'loading' | WecomCliAuthState, 'info' | 'warning' | 'success' | 'danger'> = {
  loading: 'info',
  pending: 'warning',
  authorized: 'success',
  expired: 'warning',
  failed: 'danger'
}

const statusSummary = computed(() => {
  const counts: Record<ConnectorStatus, number> = { READY: 0, NOT_CONFIGURED: 0, DISABLED: 0 }
  for (const item of statuses.value) counts[item.status] += 1
  return counts
})

/** 与后端 status() 同口径：停用优先，其余按就绪性判定 */
function connectorStatus(connector: AiConnectorView): ConnectorStatus {
  if (!connector.enabled) return 'DISABLED'
  return connector.ready ? 'READY' : 'NOT_CONFIGURED'
}

function authTagType(authType: AiConnectorAuthType): 'info' | 'warning' | 'success' {
  if (authType === 'TOKEN' || authType === 'SEEYON') return 'warning'
  if (authType === 'MCP' || authType === 'WECOM') return 'success'
  return 'info'
}

/** 表单模型（凭据字段留空 = 保持不变） */
const form = reactive({
  code: '',
  name: '',
  authType: 'BASIC' as AiConnectorAuthType,
  baseUrl: '',
  mcpUrl: '',
  testPath: '',
  queryPath: '',
  readPath: '',
  username: '',
  password: '',
  token: '',
  /** 机器人通道（wecom-cli）：Bot ID 走 extraConfig，Bot Secret 为写入型凭据 */
  botId: '',
  botSecret: '',
  enabled: true,
  sortOrder: 0
})

/** 专属字段输入（按类型分桶，便于 v-model 类型安全） */
const extraText = reactive<Record<string, string>>({})
const extraNumber = reactive<Record<string, number | undefined>>({})
/** 打开弹窗时的专属字段原始值：只提交改动键 */
let extraBaseline: Record<string, unknown> = {}

const extraFields = computed(() => EXTRA_FIELDS[form.code.trim()] ?? [])

/** 当前编辑目标的凭据配置状态（决定留空占位提示） */
const editing = computed(() => connectors.value.find(c => c.id === editingId.value) || null)

function extraCurrent(field: ExtraField): string | number {
  if (field.kind === 'number') return extraNumber[field.key] ?? ''
  return (extraText[field.key] ?? '').trim()
}

function extraStored(field: ExtraField): string | number {
  const raw = extraBaseline[field.key]
  if (field.kind === 'number') {
    const value = typeof raw === 'number' ? raw : Number(raw)
    return raw == null || raw === '' || !Number.isFinite(value) ? '' : value
  }
  return raw == null ? '' : String(raw)
}

/** 用连接器的 extraConfig 回填专属字段 */
function resetExtra(code: string, extraConfig: Record<string, unknown> = {}) {
  ;[extraText, extraNumber].forEach(record => {
    Object.keys(record).forEach(key => delete record[key])
  })
  extraBaseline = { ...extraConfig }
  for (const field of EXTRA_FIELDS[code] ?? []) {
    const value = extraBaseline[field.key]
    if (value == null) continue
    if (field.kind === 'number') {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) extraNumber[field.key] = parsed
    } else extraText[field.key] = String(value)
  }
}

/** 专属参数补丁：空值 = 删除该键（后端 mergeExtra 语义）；Bot ID 与 Bot Secret 同区展示，单独并入补丁 */
function buildExtraPayload(): Record<string, unknown> | undefined {
  const patch: Record<string, unknown> = {}
  for (const field of extraFields.value) {
    const current = extraCurrent(field)
    if (current === extraStored(field)) continue
    patch[field.key] = current
  }
  if (form.code.trim() === 'wecom') {
    const current = form.botId.trim()
    if (current !== String(extraBaseline.botId ?? '').trim()) patch.botId = current
  }
  return Object.keys(patch).length ? patch : undefined
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    code: '',
    name: '',
    authType: 'BASIC' as AiConnectorAuthType,
    baseUrl: '',
    mcpUrl: '',
    testPath: '',
    queryPath: '',
    readPath: '',
    username: '',
    password: '',
    token: '',
    botId: '',
    botSecret: '',
    enabled: true,
    sortOrder: connectors.value.length
  })
  resetExtra('')
  dialogVisible.value = true
}

function openEdit(connector: AiConnectorView) {
  editingId.value = connector.id
  Object.assign(form, {
    code: connector.code,
    name: connector.name,
    authType: connector.authType,
    baseUrl: connector.baseUrl,
    mcpUrl: connector.mcpUrl || '',
    testPath: connector.testPath || '',
    queryPath: connector.queryPath || '',
    readPath: connector.readPath || '',
    username: '',
    password: '',
    token: '',
    botId: connector.extraConfig?.botId == null ? '' : String(connector.extraConfig.botId),
    botSecret: '',
    enabled: connector.enabled,
    sortOrder: connector.sortOrder
  })
  resetExtra(connector.code, connector.extraConfig ?? {})
  dialogVisible.value = true
}

function buildPayload(): AiConnectorSavePayload {
  const payload: AiConnectorSavePayload = {
    code: form.code.trim(),
    name: form.name.trim(),
    authType: form.authType,
    baseUrl: form.baseUrl.trim(),
    mcpUrl: form.mcpUrl.trim() || undefined,
    testPath: form.testPath.trim() || undefined,
    queryPath: form.queryPath.trim() || undefined,
    readPath: form.readPath.trim() || undefined,
    extraConfig: buildExtraPayload(),
    enabled: form.enabled,
    sortOrder: form.sortOrder
  }
  // 凭据字段仅在有输入时提交；留空 = 后端保持原值
  if (form.username.trim()) payload.username = form.username.trim()
  if (form.password) payload.password = form.password
  if (form.token.trim()) payload.token = form.token.trim()
  if (form.botSecret) payload.botSecret = form.botSecret
  return payload
}

function replaceConnector(view: AiConnectorView) {
  const index = connectors.value.findIndex(c => c.id === view.id)
  if (index >= 0) connectors.value[index] = view
}

async function refreshConnector(id: number) {
  try {
    replaceConnector(await api.getConnector(id))
  } catch {
    // 结果回读失败不影响测试结论提示
  }
}

async function loadConnectors() {
  loading.value = true
  try {
    const [views, statusList] = await Promise.all([
      api.getConnectors(),
      api.getConnectorStatuses().catch(() => [] as AiConnectorStatus[])
    ])
    connectors.value = views
    statuses.value = statusList
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接器列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('请填写编码和名称')
    return
  }
  if (form.authType !== 'MAIL' && !form.baseUrl.trim()) {
    ElMessage.warning('请填写服务地址')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await api.createConnector(buildPayload())
      ElMessage.success('连接器已创建')
    } else {
      await api.updateConnector(editingId.value, buildPayload())
      ElMessage.success('连接器已保存')
    }
    dialogVisible.value = false
    await loadConnectors()
    // 企微卡片的机器人通道状态/品类矩阵随凭据变化，保存后静默对齐
    if (form.code.trim() === 'wecom') void refreshWecomCli(true)
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接器保存失败'))
  } finally {
    saving.value = false
  }
}

// ==================== 企业微信机器人通道（wecom-cli） ====================

/** 企微连接器：机器人通道分区只渲染在这张卡片里 */
const wecomConnector = computed(() => connectors.value.find(c => c.code === 'wecom') ?? null)
/** 已保存的 Bot ID（extraConfig.botId，非敏感） */
const botId = computed(() => {
  const raw = wecomConnector.value?.extraConfig?.botId
  return raw == null ? '' : String(raw).trim()
})
/** Bot 凭证齐备才允许「使用 Bot 凭证授权」；否则先保存凭证或走扫码 */
const botCredentialReady = computed(() => Boolean(botId.value && wecomConnector.value?.botSecretConfigured))

const wecomCliStatus = ref<WecomCliStatus | null>(null)
const wecomCliLoading = ref(false)
const wecomAuthorizing = ref(false)
const capabilities = ref<WecomCliCapability[]>([])
const capabilitiesLoading = ref(false)

/** 通道徽标：CLI 未安装 > 未授权 > 已授权 */
const wecomCliBadge = computed<{ label: string; type: 'success' | 'warning' | 'info' | 'danger' }>(() => {
  const status = wecomCliStatus.value
  if (!status) return { label: wecomCliLoading.value ? '检测中' : '状态未知', type: 'info' }
  if (!status.cliInstalled) return { label: 'CLI 未安装', type: 'danger' }
  if (!status.authorized) return { label: '未授权', type: 'warning' }
  return { label: '已授权', type: 'success' }
})

/** 通道说明：有后端 hint 用 hint，取不到时给引导文案（未配置 Bot 不报错） */
const wecomCliHint = computed(() => {
  const status = wecomCliStatus.value
  if (status) return status.hint
  if (wecomCliLoading.value) return '机器人通道状态检测中…'
  return botCredentialReady.value
    ? '机器人通道状态暂不可用，点右侧刷新重试。'
    : '尚未配置 Bot 凭证：点「编辑」填写 Bot ID 与 Bot Secret 并保存，或直接扫码授权。'
})

/** 展示用 Bot ID：已授权时以 CLI 实际绑定的为准 */
const botIdDisplay = computed(() => wecomCliStatus.value?.botId || botId.value)

const capabilitySummary = computed(() => {
  const counts: Record<WecomCliCapabilityState, number> = { AVAILABLE: 0, EXPIRED: 0, UNAUTHORIZED: 0, UNAVAILABLE: 0, ERROR: 0 }
  for (const item of capabilities.value) counts[item.state] += 1
  return counts
})

const capabilityRows = computed(() =>
  capabilities.value.map(capability => ({ ...capability, segments: splitHintLinks(capability.message) }))
)

/** 矩阵为空时的提示：未授权先引导授权，已授权/未知则引导体检 */
const capabilityEmptyHint = computed(() => {
  if (capabilitiesLoading.value) return '品类授权体检中…'
  const status = wecomCliStatus.value
  if (status && status.cliInstalled && !status.authorized) return '完成授权后点「刷新体检」查看各品类授权状态。'
  return '尚未体检：点「刷新体检」获取各品类授权状态。'
})

/** 通道状态；quiet = 自动加载，失败只留引导文案不弹错 */
async function loadWecomCliStatus(quiet = false) {
  if (!wecomConnector.value) return
  wecomCliLoading.value = true
  try {
    wecomCliStatus.value = await api.getWecomCliStatus()
  } catch (err: unknown) {
    wecomCliStatus.value = null
    if (!quiet) ElMessage.error(errorText(err, '机器人通道状态获取失败'))
  } finally {
    wecomCliLoading.value = false
  }
}

/** 品类授权矩阵；refresh=true 触发实时体检（逐品类探活，较慢） */
async function loadWecomCliCapabilities(refresh: boolean, quiet = false) {
  if (!wecomConnector.value) return
  capabilitiesLoading.value = true
  try {
    capabilities.value = await api.getWecomCliCapabilities(refresh)
  } catch (err: unknown) {
    if (!quiet) ElMessage.error(errorText(err, '品类授权体检失败'))
  } finally {
    capabilitiesLoading.value = false
  }
}

/** 状态 + 品类矩阵一起刷新（页面加载 / 保存凭证 / 授权完成后） */
async function refreshWecomCli(quiet = false) {
  await loadWecomCliStatus(quiet)
  if (wecomCliStatus.value?.cliInstalled) await loadWecomCliCapabilities(false, quiet)
}

/** 用已保存的 Bot 凭证授权（后端非 TTY 直连，失败透传 CLI 原始错误） */
async function authorizeWecomCli() {
  wecomAuthorizing.value = true
  try {
    const result = await api.authorizeWecomCli()
    if (result.authorized) {
      ElMessage.success(result.botId ? `机器人通道授权成功（Bot ${result.botId}）` : '机器人通道授权成功')
    } else {
      ElMessage.warning(result.hint || '授权未完成，请检查 Bot 凭证')
    }
    await loadWecomCliStatus(true)
    await loadWecomCliCapabilities(true, true)
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '机器人通道授权失败'))
    await loadWecomCliStatus(true)
  } finally {
    wecomAuthorizing.value = false
  }
}

// —— 扫码授权（拿不到 Bot Secret 时的兜底）：二维码 5 分钟有效，3 秒轮询一次 ——

const qrDialogVisible = ref(false)
const qrImage = ref('')
const qrState = ref<'loading' | WecomCliAuthState>('loading')
const qrHint = ref('')
const qrRemaining = ref(0)
let qrPollTimer: number | null = null
let qrTickTimer: number | null = null
let qrPollFailures = 0

const qrImageSrc = computed(() => (qrImage.value ? `data:image/png;base64,${qrImage.value}` : ''))
const qrCountdownText = computed(() => {
  const total = qrRemaining.value
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
})

function stopQrTimers() {
  if (qrPollTimer != null) {
    window.clearInterval(qrPollTimer)
    qrPollTimer = null
  }
  if (qrTickTimer != null) {
    window.clearInterval(qrTickTimer)
    qrTickTimer = null
  }
}

function openQrDialog() {
  qrDialogVisible.value = true
  void refreshQrcode()
}

/** 生成新二维码并重启倒计时/轮询 */
async function refreshQrcode() {
  stopQrTimers()
  qrPollFailures = 0
  qrImage.value = ''
  qrState.value = 'loading'
  qrHint.value = ''
  qrRemaining.value = 0
  try {
    const challenge = await api.createWecomCliQrcode()
    qrImage.value = challenge.imageBase64
    qrState.value = 'pending'
    startQrTimers(challenge.sessionId, challenge.expireAt)
  } catch (err: unknown) {
    qrState.value = 'failed'
    qrHint.value = errorText(err, '二维码获取失败，请重试')
  }
}

function startQrTimers(sessionId: string, expireAt: number) {
  updateQrCountdown(expireAt)
  qrTickTimer = window.setInterval(() => updateQrCountdown(expireAt), QR_TICK_INTERVAL)
  qrPollTimer = window.setInterval(() => void pollQrAuth(sessionId), QR_POLL_INTERVAL)
  void pollQrAuth(sessionId)
}

function updateQrCountdown(expireAt: number) {
  qrRemaining.value = Math.max(0, Math.ceil((expireAt - Date.now()) / 1000))
  if (qrRemaining.value === 0 && qrState.value === 'pending') {
    stopQrTimers()
    qrState.value = 'expired'
    qrHint.value = '二维码已过期，请点「重新获取」后再扫码。'
  }
}

async function pollQrAuth(sessionId: string) {
  if (qrState.value !== 'pending') return
  try {
    const result = await api.pollWecomCliAuth(sessionId)
    qrPollFailures = 0
    if (result.status === 'pending') return
    stopQrTimers()
    qrState.value = result.status
    if (result.status === 'authorized') {
      qrHint.value = result.hint || '扫码授权成功，机器人通道已就绪。'
      ElMessage.success(result.botId ? `机器人通道授权成功（Bot ${result.botId}）` : '机器人通道授权成功')
      await loadWecomCliStatus(true)
      await loadWecomCliCapabilities(true, true)
    } else if (result.status === 'expired') {
      qrHint.value = result.hint || '二维码已过期，请点「重新获取」后再扫码。'
    } else {
      qrHint.value = result.hint || '扫码授权失败，请重试。'
    }
  } catch (err: unknown) {
    // 单次网络抖动继续轮询，连续失败才终止，避免弹窗永远转圈
    qrPollFailures += 1
    if (qrPollFailures < 3) return
    stopQrTimers()
    qrState.value = 'failed'
    qrHint.value = errorText(err, '授权状态查询失败，请点「重新获取」重试')
  }
}

/** 弹窗关闭：清定时器，避免轮询泄漏 */
function resetQrDialog() {
  stopQrTimers()
  qrPollFailures = 0
  qrImage.value = ''
  qrState.value = 'loading'
  qrHint.value = ''
  qrRemaining.value = 0
}

onUnmounted(stopQrTimers)

/** 启用/停用（乐观更新，失败回滚并提示） */
async function toggleEnabled(connector: AiConnectorView, value: boolean | string | number) {
  const previous = connector.enabled
  connector.enabled = Boolean(value)
  try {
    replaceConnector(await api.updateConnector(connector.id, { enabled: connector.enabled }))
    ElMessage.success(connector.enabled ? '已启用' : '已停用')
  } catch (err: unknown) {
    connector.enabled = previous
    ElMessage.error(errorText(err, '状态更新失败'))
  }
}

async function testConnector(connector: AiConnectorView) {
  testingId.value = connector.id
  try {
    const view = await api.testConnector(connector.id)
    replaceConnector(view)
    ElMessage.success(view.lastTestMessage || '连接测试通过')
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接测试失败'))
    await refreshConnector(connector.id)
  } finally {
    testingId.value = null
  }
}

async function removeConnector(connector: AiConnectorView) {
  try {
    await ElMessageBox.confirm(
      `确定要删除连接器「${connector.name}」吗？删除后不可恢复。`,
      '删除连接器',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.deleteConnector(connector.id)
    ElMessage.success('连接器已删除')
    await loadConnectors()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '删除连接器失败'))
  }
}

/** 跳转到该连接器的使用页面；目标页面不在当前前端时给出提示而非白屏 */
function openRelated(code: string) {
  const page = RELATED_PAGES[code]
  if (!page) return
  if (!router.resolve(page.path).matched.length) {
    ElMessage.info(`当前前端未提供「${page.label}」页面，请在连接器管理内维护配置`)
    return
  }
  void router.push(page.path)
}

function connectorUrl(connector: AiConnectorView): string {
  return (connector.authType === 'MCP' ? connector.mcpUrl || connector.baseUrl : connector.baseUrl) || '—'
}

function formatTestTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function truncate(text: string, max = 60): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

onMounted(async () => {
  await loadConnectors()
  // 机器人通道分区仅在有企微连接器时加载（静默，未配置 Bot 时只显示引导文案）
  if (wecomConnector.value) void refreshWecomCli(true)
})
</script>

<template>
  <div class="connector-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">CONNECTOR HUB</span>
        <h2>连接器管理</h2>
        <p>统一维护各外部系统的服务地址、专属参数与凭据：AI 助手、云效同步、工时同步、邮件摘要等共用这份连接配置。</p>
        <p v-if="statuses.length" class="head-summary">
          就绪 {{ statusSummary.READY }} · 待配置 {{ statusSummary.NOT_CONFIGURED }} · 已停用 {{ statusSummary.DISABLED }}
        </p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="loadConnectors">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建连接器</el-button>
      </div>
    </header>

    <el-row :gutter="16">
      <el-col v-for="connector in connectors" :key="connector.id" :xs="24" :sm="12" :md="8">
        <div class="connector-card">
          <div class="card-head">
            <span class="card-name">{{ connector.name }}</span>
            <span class="card-code">{{ connector.code }}</span>
            <el-tag size="small" :type="authTagType(connector.authType)">{{ AUTH_LABEL[connector.authType] }}</el-tag>
            <el-tag v-if="connector.builtIn" size="small" effect="plain">内置</el-tag>
            <el-tooltip :content="connector.hint" placement="top" :disabled="!connector.hint">
              <el-tag size="small" effect="light" :type="STATUS_TAG[connectorStatus(connector)]">
                {{ STATUS_LABEL[connectorStatus(connector)] }}
              </el-tag>
            </el-tooltip>
          </div>
          <div class="card-url" :title="connectorUrl(connector)">{{ connectorUrl(connector) }}</div>
          <div v-if="connector.hint" class="card-hint">{{ connector.hint }}</div>
          <div v-if="MODEL_PROVIDER_CODES.includes(connector.code)" class="card-hint">
            模型配置已移至
            <el-link type="primary" :underline="false" @click="openModelManage">「模型管理」</el-link>
          </div>

          <div class="card-status">
            <span class="status-label">启用</span>
            <el-switch
              :model-value="connector.enabled"
              @update:model-value="(value: boolean | string | number) => toggleEnabled(connector, value)"
            />
          </div>

          <div v-if="connector.lastTestStatus === 'SUCCESS'" class="test-line success" :title="connector.lastTestMessage">
            测试通过 {{ formatTestTime(connector.lastTestedAt) }}
          </div>
          <div v-else-if="connector.lastTestStatus === 'FAILED'" class="test-line failed" :title="connector.lastTestMessage">
            {{ truncate(connector.lastTestMessage || '测试失败') }}
          </div>
          <div v-else class="test-line idle">未测试</div>

          <!-- 机器人通道（wecom-cli）：仅企微卡片渲染，与应用通道并存 -->
          <div v-if="connector.code === 'wecom'" class="bot-channel">
            <div class="bot-head">
              <span class="bot-title">机器人通道</span>
              <el-tag size="small" effect="light" :type="wecomCliBadge.type">{{ wecomCliBadge.label }}</el-tag>
              <el-tooltip content="重新获取通道状态" placement="top">
                <el-button
                  class="bot-refresh"
                  size="small"
                  link
                  :icon="Refresh"
                  :loading="wecomCliLoading"
                  @click="loadWecomCliStatus(false)"
                />
              </el-tooltip>
            </div>
            <p class="bot-hint">{{ wecomCliHint }}</p>
            <p v-if="botIdDisplay" class="bot-id" :title="botIdDisplay">Bot ID：{{ truncate(botIdDisplay, 40) }}</p>

            <div class="bot-actions">
              <el-tooltip :disabled="botCredentialReady" content="先在「编辑」中保存 Bot ID 与 Bot Secret" placement="top">
                <span class="bot-action-wrap">
                  <el-button size="small" :loading="wecomAuthorizing" :disabled="!botCredentialReady" @click="authorizeWecomCli">
                    使用 Bot 凭证授权
                  </el-button>
                </span>
              </el-tooltip>
              <el-button size="small" @click="openQrDialog">扫码授权</el-button>
              <el-button size="small" :loading="capabilitiesLoading" @click="loadWecomCliCapabilities(true)">刷新体检</el-button>
            </div>

            <div v-if="capabilityRows.length" class="bot-matrix">
              <div class="bot-matrix-head">
                <span class="bot-matrix-title">品类授权矩阵</span>
                <span class="bot-matrix-summary">
                  可用 {{ capabilitySummary.AVAILABLE }} · 过期 {{ capabilitySummary.EXPIRED }} · 未授权
                  {{ capabilitySummary.UNAUTHORIZED }} · 未开放 {{ capabilitySummary.UNAVAILABLE }} · 异常
                  {{ capabilitySummary.ERROR }}
                </span>
              </div>
              <div v-for="row in capabilityRows" :key="row.service" class="bot-matrix-row">
                <div class="bot-matrix-line">
                  <span class="bot-matrix-label">{{ row.label }}</span>
                  <el-tag size="small" effect="light" :type="CAPABILITY_STATE_TAG[row.state]">
                    {{ CAPABILITY_STATE_LABEL[row.state] }}
                  </el-tag>
                </div>
                <p v-if="row.message" class="bot-matrix-message">
                  <template v-for="(segment, index) in row.segments" :key="index">
                    <a
                      v-if="segment.href"
                      class="bot-matrix-link"
                      :href="segment.href"
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ segment.text }}</a
                    >
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </p>
              </div>
            </div>
            <p v-else class="bot-hint">{{ capabilityEmptyHint }}</p>
          </div>

          <div class="card-actions">
            <el-button size="small" :icon="Connection" :loading="testingId === connector.id" @click="testConnector(connector)">测试</el-button>
            <el-button size="small" :icon="Document" @click="openEdit(connector)">编辑</el-button>
            <el-tooltip content="内置连接器不可删除" placement="top" :disabled="!connector.builtIn">
              <span class="delete-wrap">
                <el-button size="small" type="danger" plain :disabled="connector.builtIn" @click="removeConnector(connector)">删除</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-if="RELATED_PAGES[connector.code]"
              class="related-link"
              size="small"
              link
              type="primary"
              @click="openRelated(connector.code)"
            >
              {{ RELATED_PAGES[connector.code].label }}
            </el-button>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && !connectors.length" description="暂无连接器，点击右上角新建" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      class="connector-dialog"
      :title="editingId == null ? '新建连接器' : `编辑连接器 · ${form.name}`"
      width="640px"
      destroy-on-close
    >
      <el-form label-position="top" class="connector-form">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="editingId != null" placeholder="如 worktime（编码创建后不可修改）" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="认证类型">
          <el-radio-group v-model="form.authType">
            <el-radio-button value="BASIC">账号密码</el-radio-button>
            <el-radio-button value="TOKEN">Token</el-radio-button>
            <el-radio-button value="MCP">MCP 服务</el-radio-button>
            <el-radio-button value="SEEYON">致远 OA</el-radio-button>
            <el-radio-button value="WECOM">企业微信</el-radio-button>
            <el-radio-button value="MAIL">企业邮箱</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="服务地址" :required="form.authType !== 'MAIL'">
          <el-input v-model="form.baseUrl" placeholder="https://host" />
        </el-form-item>
        <el-form-item v-if="form.authType === 'MCP'" label="MCP 服务地址" required>
          <el-input v-model="form.mcpUrl" placeholder="https://host/mcp" />
        </el-form-item>
        <el-form-item v-if="form.authType === 'BASIC' || form.authType === 'TOKEN'" label="测试路径">
          <el-input v-model="form.testPath" placeholder="/api/v1/auth/login（留空使用默认值）" />
        </el-form-item>
        <el-form-item label="查询路径">
          <el-input v-model="form.queryPath" placeholder="/api/v1/xxx">
            <template #label>
              <span class="label-with-tip">
                查询路径
                <el-tooltip placement="top" content="配置后 AI 助手自动获得 query_{code} / read_{code} 两个只读工具">
                  <el-icon><Tickets /></el-icon>
                </el-tooltip>
              </span>
            </template>
          </el-input>
          <span class="field-help">配置后 AI 助手自动获得 query_{{ form.code || '{code}' }} / read_{{ form.code || '{code}' }} 两个只读工具</span>
        </el-form-item>
        <el-form-item label="读取路径">
          <el-input v-model="form.readPath" placeholder="/api/v1/xxx/{id}" />
        </el-form-item>

        <template v-if="form.authType === 'BASIC' || form.authType === 'SEEYON'">
          <el-form-item label="账号">
            <el-input v-model="form.username" :placeholder="editing?.usernameConfigured ? CREDENTIAL_PLACEHOLDER : '账号'" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="editing?.passwordConfigured ? CREDENTIAL_PLACEHOLDER : '密码'"
            />
          </el-form-item>
        </template>
        <el-form-item
          v-if="form.authType === 'TOKEN' || form.authType === 'MCP' || form.authType === 'WECOM'"
          :label="form.authType === 'WECOM' ? 'Secret' : 'Token'"
        >
          <el-input
            v-model="form.token"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editing?.tokenConfigured ? CREDENTIAL_PLACEHOLDER : form.authType === 'WECOM' ? '内部应用 Secret' : 'Token'"
          />
        </el-form-item>

        <template v-if="extraFields.length">
          <div class="form-divider">专属参数</div>
          <el-form-item v-for="field in extraFields" :key="field.key" :label="field.label">
            <el-select v-if="field.kind === 'select'" v-model="extraText[field.key]" placeholder="请选择">
              <el-option v-for="option in field.options" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-input-number
              v-else-if="field.kind === 'number'"
              v-model="extraNumber[field.key]"
              :min="1"
              :max="365"
              :controls="false"
              :placeholder="field.placeholder"
            />
            <el-input v-else v-model="extraText[field.key]" :placeholder="field.placeholder" />
            <span v-if="field.kind === 'number'" class="field-help">留空即删除该参数</span>
          </el-form-item>
        </template>
        <p v-else-if="MODEL_PROVIDER_CODES.includes(form.code.trim())" class="model-note">
          本连接器只维护服务地址与凭据；模型名、助手可用、邮件摘要与默认模型已移至
          <el-link type="primary" :underline="false" @click="openModelManage">「模型管理」</el-link>
        </p>

        <!-- 机器人通道（wecom-cli）：Bot ID + Bot Secret，与应用通道凭据并存 -->
        <template v-if="form.code.trim() === 'wecom'">
          <div class="form-divider">机器人通道（wecom-cli）</div>
          <el-form-item label="Bot ID">
            <el-input v-model="form.botId" placeholder="智能机器人 Bot ID（aib…）" />
          </el-form-item>
          <el-form-item label="Bot Secret">
            <el-input
              v-model="form.botSecret"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="editing?.botSecretConfigured ? CREDENTIAL_PLACEHOLDER : '智能机器人 Bot Secret'"
            />
          </el-form-item>
          <p class="field-help">
            机器人通道（待办/会议/文档等）与上方应用通道（CorpId/AgentId/Secret，应用消息推送）并存，凭证互不影响；
            保存后可在卡片内执行「使用 Bot 凭证授权」或「扫码授权」。
          </p>
        </template>

        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 扫码授权弹窗（机器人通道兜底路径）：二维码 5 分钟有效，3 秒轮询一次授权状态 -->
    <el-dialog
      v-model="qrDialogVisible"
      class="qr-dialog"
      title="扫码授权 · 企业微信机器人通道"
      width="420px"
      destroy-on-close
      @closed="resetQrDialog"
    >
      <div class="qr-body">
        <div class="qr-frame">
          <img v-if="qrImageSrc" class="qr-image" :src="qrImageSrc" alt="企业微信授权二维码" />
          <div v-else class="qr-image qr-blank">{{ qrState === 'loading' ? '二维码生成中…' : '二维码未生成' }}</div>
        </div>
        <div class="qr-meta">
          <el-tag size="small" effect="light" :type="QR_STATE_TAG[qrState]">{{ QR_STATE_LABEL[qrState] }}</el-tag>
          <span v-if="qrState === 'pending'" class="qr-countdown">剩余 {{ qrCountdownText }}</span>
        </div>
        <p class="qr-hint">{{ qrHint || '请使用企业微信扫描二维码，并在手机上确认授权。' }}</p>
      </div>
      <template #footer>
        <el-button @click="qrDialogVisible = false">{{ qrState === 'authorized' ? '完成' : '关闭' }}</el-button>
        <el-button v-if="qrState !== 'authorized'" type="primary" :loading="qrState === 'loading'" @click="refreshQrcode">
          {{ qrState === 'expired' || qrState === 'failed' ? '重新获取' : '刷新二维码' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.connector-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
  text-align: left;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.04);
}

.page-head h2 {
  margin: 4px 0 6px;
  color: var(--gray-900);
  font-size: 22px;
}

.page-head p {
  margin: 0;
  color: var(--gray-500);
}

.head-summary {
  margin-top: 8px !important;
  color: var(--gray-600) !important;
  font-size: 12.5px;
}

.eyebrow {
  color: var(--primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.connector-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.03);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.connector-card:hover {
  border-color: var(--primary-light);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.06);
}

.card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.card-name {
  overflow: hidden;
  color: var(--gray-900);
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-code {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  flex: 0 0 auto;
}

.card-url {
  overflow: hidden;
  color: var(--gray-500);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-hint {
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.5;
}

.card-status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-label {
  color: var(--gray-600);
  font-size: 12.5px;
}

.test-line {
  font-size: 12.5px;
  line-height: 1.5;
}

.test-line.success {
  color: var(--success, #16a34a);
}

.test-line.failed {
  color: var(--danger, #dc2626);
}

.test-line.idle {
  color: var(--gray-400);
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--gray-100);
}

.delete-wrap {
  display: inline-flex;
  margin-left: 8px;
}

.related-link {
  margin-left: auto;
}

/* 机器人通道（wecom-cli）分区 */
.bot-channel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px dashed var(--gray-200);
  border-radius: 10px;
  background: var(--gray-50);
}

.bot-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bot-title {
  color: var(--gray-700);
  font-size: 13px;
  font-weight: 600;
}

.bot-refresh {
  margin-left: auto;
}

.bot-hint {
  margin: 0;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.5;
}

.bot-id {
  overflow: hidden;
  margin: 0;
  color: var(--gray-500);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bot-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.bot-action-wrap {
  display: inline-flex;
}

.bot-matrix {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--gray-200);
}

.bot-matrix-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.bot-matrix-title {
  color: var(--gray-600);
  font-size: 12.5px;
  font-weight: 600;
}

.bot-matrix-summary {
  color: var(--gray-500);
  font-size: 11.5px;
}

.bot-matrix-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 6px 0 2px;
  border-top: 1px solid var(--gray-100);
}

.bot-matrix-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.bot-matrix-label {
  color: var(--gray-700);
  font-size: 12.5px;
}

.bot-matrix-message {
  margin: 0;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.bot-matrix-link {
  color: var(--primary);
  text-decoration: none;
}

.bot-matrix-link:hover {
  text-decoration: underline;
}

/* 扫码授权弹窗 */
.qr-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.qr-frame {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 220px;
  height: 220px;
  padding: 10px;
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  background: #fff;
}

.qr-image {
  width: 200px;
  height: 200px;
  object-fit: contain;
}

.qr-blank {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--gray-400);
  font-size: 12.5px;
}

.qr-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.qr-countdown {
  color: var(--gray-500);
  font-size: 12.5px;
}

.qr-hint {
  margin: 0;
  color: var(--gray-500);
  font-size: 12.5px;
  line-height: 1.6;
  text-align: center;
}

.connector-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.connector-dialog :deep(.el-dialog__body) {
  max-height: 62vh;
  overflow-y: auto;
  padding-top: 8px;
}

.form-divider {
  margin: 4px 0 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--gray-200);
  color: var(--gray-600);
  font-size: 13px;
  font-weight: 600;
}

.field-help {
  display: block;
  margin-top: 5px;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.45;
}

.model-note {
  margin-bottom: 16px;
  padding: 10px 12px;
  border: 1px dashed var(--gray-200);
  border-radius: 10px;
  background: var(--gray-50);
  color: var(--gray-500);
  font-size: 12.5px;
  line-height: 1.6;
}

.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

@media (max-width: 820px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
