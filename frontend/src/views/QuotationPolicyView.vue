<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { QuotationPolicy, QuotationPolicyItem } from '@/types/quotation'

const rows = ref<QuotationPolicy[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)

const filters = reactive({ type: '', taxMode: '', status: '' })

const form = reactive({
  name: '',
  type: '',
  taxMode: '',
  effectiveDate: '',
  expiryDate: ''
})

const filteredRows = computed(() =>
  rows.value.filter(row =>
    (!filters.type || row.type === filters.type) &&
    (!filters.taxMode || row.taxMode === filters.taxMode) &&
    (!filters.status || row.status === filters.status)
  )
)

const typeLabel = (type: string) =>
  ({ SAAS: 'SaaS全渠道', PRIVATE_DEPLOYMENT: '私有化全渠道', MEMBERSHIP: '会员通' } as Record<string, string>)[type] || type

const taxModeLabel = (taxMode: string) =>
  ({ TAX_INCLUDED: '含税', TAX_EXCLUDED: '未税' } as Record<string, string>)[taxMode] || taxMode

const statusLabel = (status: string) =>
  ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' } as Record<string, string>)[status] || status

const statusTagType = (status: string) =>
  ({ DRAFT: 'info', PUBLISHED: 'success', ARCHIVED: 'warning' } as Record<string, string>)[status] || 'info'

const typeTagType = (type: string) =>
  ({ SAAS: '', PRIVATE_DEPLOYMENT: 'warning', MEMBERSHIP: 'success' } as Record<string, string>)[type] || ''

const formatDate = (value?: string) => {
  if (!value) return '-'
  return value.slice(0, 10)
}

const loadRows = async () => {
  loading.value = true
  try {
    rows.value = await api.getQuotationPolicies(filters)
  } catch {
    ElMessage.error('报价策略数据加载失败')
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  Object.assign(filters, { type: '', taxMode: '', status: '' })
  loadRows()
}

const openCreate = () => {
  editingId.value = null
  Object.assign(form, { name: '', type: '', taxMode: '', effectiveDate: '', expiryDate: '' })
  dialogVisible.value = true
}

const openEdit = (row: QuotationPolicy) => {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    type: row.type,
    taxMode: row.taxMode,
    effectiveDate: row.effectiveDate ? row.effectiveDate.slice(0, 10) : '',
    expiryDate: row.expiryDate ? row.expiryDate.slice(0, 10) : ''
  })
  dialogVisible.value = true
}

const save = async () => {
  if (!form.name.trim()) return ElMessage.warning('请填写策略名称')
  if (!form.type) return ElMessage.warning('请选择策略类型')
  if (!form.taxMode) return ElMessage.warning('请选择计税模式')
  const payload = {
    name: form.name.trim(),
    type: form.type,
    taxMode: form.taxMode,
    effectiveDate: form.effectiveDate || undefined,
    expiryDate: form.expiryDate || undefined
  }
  try {
    if (editingId.value) await api.updateQuotationPolicy(editingId.value, payload)
    else await api.createQuotationPolicy(payload)
    dialogVisible.value = false
    await loadRows()
    ElMessage.success(editingId.value ? '策略已更新' : '策略已创建')
  } catch {
    ElMessage.error('策略保存失败')
  }
}

const publish = async (row: QuotationPolicy) => {
  await ElMessageBox.confirm(`确定发布「${row.name}」吗？发布后旧同类型策略将被归档。`, '发布策略', { type: 'warning' })
  try {
    await api.publishPolicy(row.id)
    await loadRows()
    ElMessage.success('策略已发布')
  } catch {
    ElMessage.error('发布失败')
  }
}

const archivePolicy = async (row: QuotationPolicy) => {
  await ElMessageBox.confirm(`确定归档「${row.name}」吗？`, '归档策略', { type: 'warning' })
  try {
    await api.archivePolicy(row.id)
    await loadRows()
    ElMessage.success('策略已归档')
  } catch {
    ElMessage.error('归档失败')
  }
}

const remove = async (row: QuotationPolicy) => {
  await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '删除策略', { type: 'warning' })
  try {
    await api.deleteQuotationPolicy(row.id)
    await loadRows()
    ElMessage.success('策略已删除')
  } catch {
    ElMessage.error('删除失败')
  }
}

// ---------- Items management ----------

const itemDialogVisible = ref(false)
const currentPolicyId = ref<number>(0)
const currentPolicyName = ref('')
const policyItems = ref<QuotationPolicyItem[]>([])
const itemLoading = ref(false)
const itemFormVisible = ref(false)
const editingItemId = ref<number | null>(null)
const itemSaving = ref(false)

const itemForm = reactive({
  section: '',
  category: '',
  itemKey: '',
  itemName: '',
  description: '',
  priceDescription: '',
  isRequired: 0,
  unitPrice: undefined as number | undefined,
  taxRate: undefined as number | undefined,
  chargeMethod: '',
  chargeUnit: '',
  remark: '',
  sortOrder: 0
})

const sectionOptions = ['产品模块', '运维服务', '定制开发', '会员通对接']

const openItems = async (policyId: number, policyName: string) => {
  currentPolicyId.value = policyId
  currentPolicyName.value = policyName
  itemDialogVisible.value = true
  await loadItems()
}

const loadItems = async () => {
  itemLoading.value = true
  try {
    const result = await api.getPolicyItems(currentPolicyId.value)
    policyItems.value = Array.isArray(result) ? result : []
  } catch {
    ElMessage.error('明细项加载失败')
  } finally {
    itemLoading.value = false
  }
}

const resetItemForm = () => {
  editingItemId.value = null
  Object.assign(itemForm, {
    section: '',
    category: '',
    itemKey: '',
    itemName: '',
    description: '',
    priceDescription: '',
    isRequired: 0,
    unitPrice: undefined,
    taxRate: undefined,
    chargeMethod: '',
    chargeUnit: '',
    remark: '',
    sortOrder: 0
  })
}

const openAddItem = () => {
  resetItemForm()
  itemFormVisible.value = true
}

const editItem = (item: QuotationPolicyItem) => {
  editingItemId.value = item.id
  Object.assign(itemForm, {
    section: item.section,
    category: item.category || '',
    itemKey: item.itemKey,
    itemName: item.itemName,
    description: item.description || '',
    priceDescription: item.priceDescription || '',
    isRequired: item.isRequired,
    unitPrice: item.unitPrice,
    taxRate: item.taxRate,
    chargeMethod: item.chargeMethod || '',
    chargeUnit: item.chargeUnit || '',
    remark: item.remark || '',
    sortOrder: item.sortOrder
  })
  itemFormVisible.value = true
}

const closeItemForm = () => {
  itemFormVisible.value = false
  editingItemId.value = null
}

const saveItem = async () => {
  if (!itemForm.section) return ElMessage.warning('请选择报价分组')
  if (!itemForm.itemKey.trim()) return ElMessage.warning('请填写明细项标识')
  if (!itemForm.itemName.trim()) return ElMessage.warning('请填写明细项名称')

  const payload = {
    section: itemForm.section,
    category: itemForm.category || undefined,
    itemKey: itemForm.itemKey.trim(),
    itemName: itemForm.itemName.trim(),
    description: itemForm.description || undefined,
    priceDescription: itemForm.priceDescription || undefined,
    isRequired: itemForm.isRequired,
    unitPrice: itemForm.unitPrice,
    taxRate: itemForm.taxRate,
    chargeMethod: itemForm.chargeMethod || undefined,
    chargeUnit: itemForm.chargeUnit || undefined,
    remark: itemForm.remark || undefined,
    sortOrder: itemForm.sortOrder
  }

  itemSaving.value = true
  try {
    if (editingItemId.value) {
      await api.updatePolicyItem(editingItemId.value, payload)
    } else {
      await api.addPolicyItems(currentPolicyId.value, [payload])
    }
    itemFormVisible.value = false
    await loadItems()
    ElMessage.success(editingItemId.value ? '明细项已更新' : '明细项已添加')
  } catch {
    ElMessage.error('明细项保存失败')
  } finally {
    itemSaving.value = false
  }
}

const deleteItem = async (item: QuotationPolicyItem) => {
  await ElMessageBox.confirm(`确定删除明细项「${item.itemName}」吗？`, '删除明细项', { type: 'warning' })
  try {
    await api.deletePolicyItem(item.id)
    await loadItems()
    ElMessage.success('明细项已删除')
  } catch {
    ElMessage.error('删除失败')
  }
}

// ---------- Lifecycle ----------

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="policy-page">
    <!-- Filter panel -->
    <section class="policy-filter-panel">
      <div class="filter-grid">
        <el-select v-model="filters.type" placeholder="策略类型" clearable @change="loadRows">
          <el-option label="全部类型" value="" />
          <el-option label="SaaS全渠道" value="SAAS" />
          <el-option label="私有化全渠道" value="PRIVATE_DEPLOYMENT" />
          <el-option label="会员通" value="MEMBERSHIP" />
        </el-select>
        <el-select v-model="filters.taxMode" placeholder="计税模式" clearable @change="loadRows">
          <el-option label="全部模式" value="" />
          <el-option label="含税" value="TAX_INCLUDED" />
          <el-option label="未税" value="TAX_EXCLUDED" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable @change="loadRows">
          <el-option label="全部状态" value="" />
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
      </div>
      <div class="filter-actions">
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <el-button type="primary" :icon="Search" @click="loadRows">查询</el-button>
      </div>
    </section>

    <!-- Action bar -->
    <div class="policy-actions">
      <el-button type="primary" :icon="Plus" @click="openCreate">创建策略</el-button>
    </div>

    <!-- Table -->
    <section class="policy-table-panel">
      <el-table v-loading="loading" :data="filteredRows" class="policy-table" scrollbar-always-on>
        <el-table-column prop="name" label="策略名称" min-width="200">
          <template #default="{ row }">
            <div class="policy-name">
              <strong>{{ row.name }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="策略类型" width="140">
          <template #default="{ row }">
            <el-tag :type="typeTagType(row.type)" effect="light">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计税模式" width="100">
          <template #default="{ row }">
            <el-tag effect="light">{{ taxModeLabel(row.taxMode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80" prop="version" align="center" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生效日期" width="130">
          <template #default="{ row }">
            {{ formatDate(row.effectiveDate) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <div class="desktop-row-actions">
              <el-button link type="primary" :disabled="row.status !== 'DRAFT'" :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" :icon="Setting" @click="openItems(row.id, row.name)">明细</el-button>
              <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publish(row)">发布</el-button>
              <el-button v-if="row.status === 'PUBLISHED'" link type="warning" @click="archivePolicy(row)">归档</el-button>
              <el-button v-if="row.status === 'DRAFT'" link type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <footer class="table-footer">共 {{ filteredRows.length }} 条记录</footer>
    </section>

    <!-- Create/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑策略' : '创建策略'" width="560px" class="policy-dialog">
      <el-form label-position="top" class="policy-form">
        <el-form-item label="策略名称" required>
          <el-input v-model="form.name" placeholder="请输入策略名称" />
        </el-form-item>
        <div class="form-two">
          <el-form-item label="策略类型" required>
            <el-select v-model="form.type" placeholder="请选择策略类型">
              <el-option label="SaaS全渠道" value="SAAS" />
              <el-option label="私有化全渠道" value="PRIVATE_DEPLOYMENT" />
              <el-option label="会员通" value="MEMBERSHIP" />
            </el-select>
          </el-form-item>
          <el-form-item label="计税模式" required>
            <el-select v-model="form.taxMode" placeholder="请选择计税模式">
              <el-option label="含税" value="TAX_INCLUDED" />
              <el-option label="未税" value="TAX_EXCLUDED" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-two">
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
          <el-form-item label="失效日期">
            <el-date-picker v-model="form.expiryDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">{{ editingId ? '保存修改' : '创建策略' }}</el-button>
      </template>
    </el-dialog>

    <!-- Items Dialog -->
    <el-dialog v-model="itemDialogVisible" :title="`策略明细 — ${currentPolicyName}`" width="860px" class="policy-dialog" @closed="policyItems = []">
      <div class="item-toolbar">
        <el-button type="primary" :icon="Plus" size="small" @click="openAddItem">添加明细项</el-button>
        <span class="item-count">{{ policyItems.length }} 条明细</span>
      </div>
      <el-table v-loading="itemLoading" :data="policyItems" class="item-table" max-height="420" scrollbar-always-on>
        <el-table-column prop="section" label="分组" width="120" />
        <el-table-column prop="itemName" label="明细项名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="必选" width="70" align="center">
          <template #default="{ row }">
            <el-switch :model-value="!!row.isRequired" size="small" disabled />
          </template>
        </el-table-column>
        <el-table-column label="未税单价" width="110" align="right">
          <template #default="{ row }">
            {{ row.unitPrice != null ? row.unitPrice.toLocaleString() : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="税率" width="70" align="center">
          <template #default="{ row }">
            {{ row.taxRate != null ? `${(row.taxRate * 100).toFixed(1)}%` : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="chargeMethod" label="收费方式" width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="editItem(row)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="deleteItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- Item Edit Sub-Dialog -->
    <el-dialog v-model="itemFormVisible" :title="editingItemId ? '编辑明细项' : '添加明细项'" width="600px" class="policy-dialog" append-to-body>
      <el-form label-position="top" class="policy-form">
        <div class="form-two">
          <el-form-item label="报价分组" required>
            <el-select v-model="itemForm.section" placeholder="请选择">
              <el-option v-for="s in sectionOptions" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="itemForm.sortOrder" :min="0" :controls="false" style="width:100%" placeholder="0" />
          </el-form-item>
        </div>
        <div class="form-two">
          <el-form-item label="明细项标识" required>
            <el-input v-model="itemForm.itemKey" placeholder="如：cdp-core" />
          </el-form-item>
          <el-form-item label="明细项名称" required>
            <el-input v-model="itemForm.itemName" placeholder="如：CDP核心模块" />
          </el-form-item>
        </div>
        <el-form-item label="分类">
          <el-input v-model="itemForm.category" placeholder="报价项目分类" />
        </el-form-item>
        <el-form-item label="功能描述">
          <el-input v-model="itemForm.description" type="textarea" :rows="2" placeholder="功能描述" />
        </el-form-item>
        <el-form-item label="价格说明">
          <el-input v-model="itemForm.priceDescription" placeholder="价格说明" />
        </el-form-item>
        <div class="form-two">
          <el-form-item label="未税单价">
            <el-input-number v-model="itemForm.unitPrice" :min="0" :controls="false" style="width:100%" placeholder="0" />
          </el-form-item>
          <el-form-item label="税率">
            <el-input-number v-model="itemForm.taxRate" :min="0" :max="1" :precision="4" :step="0.01" :controls="false" style="width:100%" placeholder="0.06" />
          </el-form-item>
        </div>
        <div class="form-two">
          <el-form-item label="收费方式">
            <el-input v-model="itemForm.chargeMethod" placeholder="如：按年收取" />
          </el-form-item>
          <el-form-item label="计费单位">
            <el-input v-model="itemForm.chargeUnit" placeholder="如：年" />
          </el-form-item>
        </div>
        <el-form-item label="是否必选">
          <el-switch :model-value="!!itemForm.isRequired" @update:model-value="val => itemForm.isRequired = val ? 1 : 0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="itemForm.remark" type="textarea" :rows="2" placeholder="备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeItemForm">取消</el-button>
        <el-button type="primary" :loading="itemSaving" @click="saveItem">{{ editingItemId ? '保存修改' : '添加' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.policy-page {
  width: 100%;
  min-width: 0;
  color: #25324b;
}

.policy-filter-panel,
.policy-table-panel {
  min-width: 0;
  max-width: 100%;
  padding: 18px;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(30 41 80 / 4%);
}

.policy-filter-panel {
  position: relative;
  margin-bottom: 16px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  padding-right: 180px;
}

.filter-actions {
  position: absolute;
  right: 18px;
  top: 18px;
}

.policy-actions {
  margin-bottom: 16px;
}

.policy-table-panel {
  padding: 0;
  overflow: hidden;
}

.policy-table {
  width: 100%;
  max-width: 100%;
}

.policy-name strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.desktop-row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.table-footer {
  display: flex;
  justify-content: space-between;
  padding: 15px 18px;
  color: #657691;
  font-size: 12px;
}

.policy-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.policy-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.policy-form :deep(.el-date-editor),
.policy-form :deep(.el-select) {
  width: 100%;
}

.form-two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.item-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.item-count {
  color: #8391a7;
  font-size: 12px;
}

.item-table {
  width: 100%;
}

@media (max-width: 1100px) {
  .filter-grid {
    grid-template-columns: repeat(3, 1fr);
    padding-right: 0;
  }
  .filter-actions {
    position: static;
    margin-top: 12px;
  }
}

@media (max-width: 680px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-grid > :first-child {
    grid-column: 1 / -1;
  }
  .form-two {
    grid-template-columns: 1fr;
  }
}
</style>