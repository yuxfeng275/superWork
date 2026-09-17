<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Document, Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { AiConnectorAuthType, AiConnectorSavePayload, AiConnectorStatus, AiConnectorView } from '@/types/ai-agent'

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
  oa: { path: '/system/sync', label: '数据集成中心' },
  mail: { path: '/emails', label: '邮件管理' }
}

/** AI 模型提供方：模型名 / 用途 / 默认在「模型管理」维护，这里只指向过去 */
const MODEL_PROVIDER_CODES = ['deepseek', 'glm']

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

/** 专属参数补丁：空值 = 删除该键（后端 mergeExtra 语义） */
function buildExtraPayload(): Record<string, unknown> | undefined {
  const patch: Record<string, unknown> = {}
  for (const field of extraFields.value) {
    const current = extraCurrent(field)
    if (current === extraStored(field)) continue
    patch[field.key] = current
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
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接器保存失败'))
  } finally {
    saving.value = false
  }
}

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

onMounted(loadConnectors)
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
