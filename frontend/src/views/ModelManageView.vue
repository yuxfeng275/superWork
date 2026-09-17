<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { AiConnectorView, AiModelSavePayload, AiModelView } from '@/types/ai-agent'

/** 模型清单（GET /api/ai/models，后端已附带提供方名称与连接就绪状态） */
const models = ref<AiModelView[]>([])
/** 提供方下拉的来源（GET /api/connectors：code/name/ready） */
const connectors = ref<AiConnectorView[]>([])
const loading = ref(false)
const saving = ref(false)
const updatingId = ref<number | null>(null)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const router = useRouter()

type FlagField = 'assistantEnabled' | 'digestEnabled' | 'enabled'

/** 开关改动后的提示文案 */
const FLAG_SUCCESS: Record<FlagField, { on: string; off: string }> = {
  assistantEnabled: { on: '助手可用已开启', off: '助手可用已关闭' },
  digestEnabled: { on: '摘要使用已开启', off: '摘要使用已关闭' },
  enabled: { on: '模型已启用', off: '模型已停用' }
}

const form = reactive({
  providerCode: '',
  model: '',
  displayName: '',
  assistantEnabled: true,
  digestEnabled: false,
  isDefault: false,
  enabled: true,
  sortOrder: 100
})

const editing = computed(() => models.value.find(item => item.id === editingId.value) || null)

const dialogTitle = computed(() => editing.value ? `编辑模型 · ${modelLabel(editing.value)}` : '新建模型')

/** 提供方选项：连接器编码 + 名称；连接未就绪只标注原因，不阻止配置 */
const providerOptions = computed(() => {
  const options = connectors.value.map(connector => ({
    value: connector.code,
    label: `${connector.name}（${connector.code}）${connector.ready ? '' : ' · 连接未就绪'}`
  }))
  // 提供方连接器已删除时仍要能打开编辑弹窗，避免下拉显示裸编码
  if (form.providerCode && !options.some(option => option.value === form.providerCode)) {
    options.unshift({ value: form.providerCode, label: `${form.providerCode}（连接器不存在）` })
  }
  return options
})

const modelSummary = computed(() => {
  const assistant = models.value.filter(item => item.assistantEnabled).length
  const digest = models.value.filter(item => item.digestEnabled).length
  return `共 ${models.value.length} 个模型 · 助手可用 ${assistant} · 摘要使用 ${digest}`
})

const notReadyProviders = computed(() => [...new Set(
  models.value.filter(item => !item.providerReady).map(item => item.providerName || item.providerCode)
)])

function modelLabel(model: AiModelView) {
  return model.displayName || model.model
}

function replaceModel(view: AiModelView) {
  const index = models.value.findIndex(item => item.id === view.id)
  if (index >= 0) models.value[index] = view
}

async function loadModels() {
  loading.value = true
  try {
    models.value = await api.getAiModels()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '模型列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadConnectors() {
  try {
    connectors.value = await api.getConnectors()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '连接器列表加载失败'))
  }
}

async function loadAll() {
  await Promise.all([loadModels(), loadConnectors()])
}

/** 用途 / 启停开关：单字段提交（乐观更新，失败回滚） */
async function toggleFlag(model: AiModelView, field: FlagField, value: boolean | string | number) {
  const next = Boolean(value)
  if (model[field] === next) return
  const previous = model[field]
  model[field] = next
  updatingId.value = model.id
  try {
    replaceModel(await api.updateAiModel(model.id, { [field]: next }))
    ElMessage.success(FLAG_SUCCESS[field][next ? 'on' : 'off'])
  } catch (err: unknown) {
    model[field] = previous
    ElMessage.error(errorText(err, '模型更新失败'))
  } finally {
    updatingId.value = null
  }
}

/** 默认模型唯一：设默认后整表重载，对齐后端自动取消的其他默认 */
async function setDefault(model: AiModelView) {
  if (model.isDefault) return
  updatingId.value = model.id
  try {
    replaceModel(await api.updateAiModel(model.id, { isDefault: true }))
    await loadModels()
    ElMessage.success(`已将「${modelLabel(model)}」设为默认模型`)
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '默认模型设置失败'))
  } finally {
    updatingId.value = null
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    providerCode: '',
    model: '',
    displayName: '',
    assistantEnabled: true,
    digestEnabled: false,
    isDefault: false,
    enabled: true,
    sortOrder: models.value.reduce((max, item) => Math.max(max, item.sortOrder), 0) + 10
  })
  dialogVisible.value = true
}

function openEdit(model: AiModelView) {
  editingId.value = model.id
  Object.assign(form, {
    providerCode: model.providerCode,
    model: model.model,
    displayName: model.displayName,
    assistantEnabled: model.assistantEnabled,
    digestEnabled: model.digestEnabled,
    isDefault: model.isDefault,
    enabled: model.enabled,
    sortOrder: model.sortOrder
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.providerCode) {
    ElMessage.warning('请选择提供方')
    return
  }
  if (!form.model.trim()) {
    ElMessage.warning('请填写模型名')
    return
  }
  saving.value = true
  try {
    const payload: AiModelSavePayload = {
      providerCode: form.providerCode,
      model: form.model.trim(),
      displayName: form.displayName.trim(),
      assistantEnabled: form.assistantEnabled,
      digestEnabled: form.digestEnabled,
      isDefault: form.isDefault,
      enabled: form.enabled,
      sortOrder: form.sortOrder
    }
    if (editingId.value == null) {
      await api.createAiModel(payload)
      ElMessage.success('模型已创建')
    } else {
      await api.updateAiModel(editingId.value, payload)
      ElMessage.success('模型已保存')
    }
    dialogVisible.value = false
    await loadModels()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '模型保存失败'))
  } finally {
    saving.value = false
  }
}

async function removeModel(model: AiModelView) {
  try {
    await ElMessageBox.confirm(
      `确定要删除模型「${modelLabel(model)}」吗？删除后 AI 助手与邮件摘要都不再使用该模型。`,
      '删除模型',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.deleteAiModel(model.id)
    ElMessage.success('模型已删除')
    await loadModels()
  } catch (err: unknown) {
    ElMessage.error(errorText(err, '删除模型失败'))
  }
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

onMounted(loadAll)
</script>

<template>
  <div class="model-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">AI MODELS</span>
        <h2>模型管理</h2>
        <p>连接参数（服务地址、凭据、启停）在「连接器管理」维护；本页只维护模型：模型名、用途（助手可用 / 邮件摘要与周报纪要）与默认模型。</p>
        <p v-if="models.length" class="head-summary">{{ modelSummary }}</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        <el-button :icon="Connection" @click="router.push('/system/connectors')">连接器管理</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建模型</el-button>
      </div>
    </header>

    <el-alert v-if="notReadyProviders.length" type="warning" :closable="false" show-icon title="部分提供方连接未就绪">
      <div>
        {{ notReadyProviders.join('、') }} 的连接未就绪，对应模型保存后仍不可用；请先在
        <el-link type="primary" :underline="false" @click="router.push('/system/connectors')">「连接器管理」</el-link>
        补全服务地址与凭据。
      </div>
    </el-alert>

    <section class="table-card">
      <el-table :data="models" row-key="id" empty-text="暂无模型，点击右上角「新建模型」" scrollbar-always-on>
        <el-table-column label="提供方" min-width="200">
          <template #default="{ row }">
            <div class="provider-cell">
              <strong class="provider-name">{{ row.providerName || row.providerCode }}</strong>
              <span class="provider-code">{{ row.providerCode }}</span>
            </div>
            <div v-if="!row.providerReady" class="provider-warning">
              <el-tag size="small" type="warning" effect="light">连接未就绪</el-tag>
              <el-link type="primary" :underline="false" @click="router.push('/system/connectors')">去配置</el-link>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模型名" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.model }}</span></template>
        </el-table-column>
        <el-table-column label="展示名" min-width="140">
          <template #default="{ row }">{{ row.displayName || '—' }}</template>
        </el-table-column>
        <el-table-column label="助手可用" width="112" align="center">
          <template #header>
            <el-tooltip content="AI 助手下拉可选该模型" placement="top">
              <span class="header-tip">助手可用</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="row.assistantEnabled"
              :loading="updatingId === row.id"
              @update:model-value="(value: boolean | string | number) => toggleFlag(row, 'assistantEnabled', value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="摘要使用" width="112" align="center">
          <template #header>
            <el-tooltip content="邮件摘要与周报纪要使用该模型" placement="top">
              <span class="header-tip">摘要使用</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-switch
              :model-value="row.digestEnabled"
              :loading="updatingId === row.id"
              @update:model-value="(value: boolean | string | number) => toggleFlag(row, 'digestEnabled', value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="默认" width="96" align="center">
          <template #header>
            <el-tooltip content="同一时间只有一个默认模型；设为默认会自动取消其他模型" placement="top">
              <span class="header-tip">默认</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-radio
              :model-value="row.isDefault"
              :value="true"
              :disabled="updatingId === row.id"
              aria-label="设为默认模型"
              @change="() => setDefault(row)"
            >
              <span class="radio-text">{{ row.isDefault ? '默认' : '设为默认' }}</span>
            </el-radio>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="96" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="updatingId === row.id"
              @update:model-value="(value: boolean | string | number) => toggleFlag(row, 'enabled', value)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="84" align="center" />
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="removeModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" class="model-dialog" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form label-position="top" class="model-form">
        <el-form-item label="提供方" required>
          <el-select v-model="form.providerCode" placeholder="选择提供方（连接器）" style="width: 100%">
            <el-option v-for="option in providerOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <span class="field-help">提供方即连接器编码，服务地址与凭据在「连接器管理」维护</span>
        </el-form-item>
        <el-form-item label="模型名" required>
          <el-input v-model="form.model" placeholder="如 deepseek-v4-flash（同一提供方下不可重复）" />
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="form.displayName" :placeholder="editingId == null ? '留空使用模型名' : '留空保持不变'" />
          <span class="field-help">AI 助手下拉里显示的名称</span>
        </el-form-item>
        <el-form-item label="用途">
          <div class="switch-grid">
            <span class="switch-item"><el-switch v-model="form.assistantEnabled" /><span>助手可用</span></span>
            <span class="switch-item"><el-switch v-model="form.digestEnabled" /><span>摘要使用</span></span>
          </div>
          <span class="field-help">助手可用 = AI 助手下拉可选；摘要使用 = 邮件摘要与周报纪要</span>
        </el-form-item>
        <el-form-item label="默认模型">
          <el-switch v-model="form.isDefault" />
          <span class="field-help">开启后后端自动取消其他模型的默认</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="field-help">停用后 AI 助手与摘要都不再使用该模型</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
          <span class="field-help">数字越小越靠前</span>
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
.model-page {
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

.table-card {
  overflow: hidden;
  padding: 6px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.04);
}

.provider-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.provider-name {
  overflow: hidden;
  color: var(--gray-900);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-code {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  flex: 0 0 auto;
}

.provider-warning {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.mono {
  color: var(--gray-700);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
}

.header-tip {
  border-bottom: 1px dashed var(--gray-300);
  cursor: default;
}

.radio-text {
  color: var(--gray-600);
  font-size: 12.5px;
}

.model-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.switch-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.switch-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--gray-700);
  font-size: 13px;
}

.model-dialog :deep(.el-dialog__body) {
  max-height: 62vh;
  overflow-y: auto;
  padding-top: 8px;
}

.field-help {
  display: block;
  margin-top: 5px;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.45;
}

@media (max-width: 820px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
