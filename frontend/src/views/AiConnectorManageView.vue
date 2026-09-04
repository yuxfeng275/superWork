<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Document, Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { AiConnectorAuthType, AiConnectorSavePayload, AiConnectorView } from '@/types/ai-agent'

/** 连接器列表 */
const connectors = ref<AiConnectorView[]>([])
const loading = ref(false)
const testingId = ref<number | null>(null)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)

/** 表单模型（凭据字段留空 = 保持不变） */
const form = reactive<{
  code: string
  name: string
  authType: AiConnectorAuthType
  baseUrl: string
  mcpUrl: string
  testPath: string
  queryPath: string
  readPath: string
  username: string
  password: string
  token: string
  enabled: boolean
  sortOrder: number
}>({
  code: '',
  name: '',
  authType: 'BASIC',
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

/** 当前编辑目标的凭据配置状态（决定留空占位提示） */
const editing = computed(() => connectors.value.find(c => c.id === editingId.value) || null)

const CREDENTIAL_PLACEHOLDER = '已配置；留空保持不变'

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    code: '',
    name: '',
    authType: 'BASIC',
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
    enabled: form.enabled,
    sortOrder: form.sortOrder
  }
  // 凭据字段仅在有输入时提交；留空 = 后端保持原值
  if (form.username.trim()) payload.username = form.username.trim()
  if (form.password) payload.password = form.password
  if (form.token.trim()) payload.token = form.token.trim()
  return payload
}

async function loadConnectors() {
  loading.value = true
  try {
    connectors.value = await api.getAiConnectors()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接器列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.baseUrl.trim()) {
    ElMessage.warning('请填写编码、名称和服务地址')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await api.createAiConnector(buildPayload())
      ElMessage.success('连接器已创建')
    } else {
      await api.updateAiConnector(editingId.value, buildPayload())
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
    await api.updateAiConnector(connector.id, { enabled: connector.enabled })
    ElMessage.success(connector.enabled ? '已启用' : '已停用')
  } catch (err: unknown) {
    connector.enabled = previous
    ElMessage.error(errorText(err, '状态更新失败'))
  }
}

async function testConnector(connector: AiConnectorView) {
  testingId.value = connector.id
  try {
    const view = await api.testAiConnector(connector.id)
    const index = connectors.value.findIndex(c => c.id === connector.id)
    if (index >= 0) connectors.value[index] = view
    if (view.lastTestStatus === 'SUCCESS') {
      ElMessage.success('测试通过')
    } else {
      ElMessage.error(view.lastTestMessage || '测试失败')
    }
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接测试失败'))
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
    await api.deleteAiConnector(connector.id)
    ElMessage.success('连接器已删除')
    await loadConnectors()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '删除连接器失败'))
  }
}

/** 认证类型 → 标签类型 */
function authTagType(authType: AiConnectorAuthType): 'info' | 'warning' | 'success' {
  if (authType === 'TOKEN') return 'warning'
  if (authType === 'MCP') return 'success'
  return 'info'
}

const AUTH_LABEL: Record<AiConnectorAuthType, string> = {
  BASIC: 'BASIC',
  TOKEN: 'TOKEN',
  MCP: 'MCP'
}

function formatTestTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
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
        <span class="eyebrow">AI CONNECTORS</span>
        <h2>AI 连接器</h2>
        <p>管理 AI 助手可调用的外部服务连接器：认证方式、服务地址、工具路径与凭据。</p>
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
          </div>
          <div class="card-url" :title="connector.baseUrl">{{ connector.baseUrl }}</div>

          <div class="card-status">
            <span class="status-label">启用</span>
            <el-switch
              :model-value="connector.enabled"
              @update:model-value="(value: boolean | string | number) => toggleEnabled(connector, value)"
            />
          </div>

          <div v-if="connector.lastTestStatus === 'SUCCESS'" class="test-line success">
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
          </div>
        </div>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && !connectors.length" description="暂无连接器，点击右上角新建" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新建连接器' : `编辑连接器 · ${form.code}`"
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
          </el-radio-group>
        </el-form-item>
        <el-form-item label="服务地址" required>
          <el-input v-model="form.baseUrl" placeholder="https://host" />
        </el-form-item>
        <el-form-item v-if="form.authType === 'MCP'" label="MCP 服务地址">
          <el-input v-model="form.mcpUrl" placeholder="https://host/mcp" />
        </el-form-item>
        <el-form-item v-if="form.authType === 'BASIC'" label="测试路径">
          <el-input v-model="form.testPath" placeholder="/api/v1/auth/login" />
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

        <template v-if="form.authType === 'BASIC'">
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
        <el-form-item v-if="form.authType === 'TOKEN' || form.authType === 'MCP'" label="Token">
          <el-input
            v-model="form.token"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editing?.tokenConfigured ? CREDENTIAL_PLACEHOLDER : 'Token'"
          />
        </el-form-item>

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
  padding-top: 4px;
  border-top: 1px solid var(--gray-100);
}

.delete-wrap {
  display: inline-flex;
  margin-left: 8px;
}

.connector-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.field-help {
  display: block;
  margin-top: 5px;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.45;
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
