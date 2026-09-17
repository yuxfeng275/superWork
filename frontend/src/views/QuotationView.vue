<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Download } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { api } from '@/utils/api'
import type {
  QuotationListVO,
  QuotationPolicy,
  QuotationPolicyItem,
  QuotationGenerateRequest,
  LineItemOverride
} from '@/types/quotation'

const router = useRouter()

// ── List state ──
const rows = ref<QuotationListVO[]>([])
const loading = ref(false)
const filters = reactive({ keyword: '', status: '', customerName: '' })

const statusOptions = ['DRAFT', 'INTERNAL_REVIEW', 'APPROVED', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED']

const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  INTERNAL_REVIEW: '内部审核',
  APPROVED: '已批准',
  SENT: '已发送',
  ACCEPTED: '已接受',
  REJECTED: '已拒绝',
  EXPIRED: '已过期'
}

// ── Wizard state ──
const dialogVisible = ref(false)
const currentStep = ref(0)
const submitting = ref(false)

const policies = ref<QuotationPolicy[]>([])
const selectedPolicy = ref<QuotationPolicy | null>(null)
const policyItems = ref<QuotationPolicyItem[]>([])

const customerForm = reactive({
  customerName: '',
  contactPerson: '',
  contactPhone: '',
  contactEmail: '',
  deliveryPeriod: '',
  opportunityId: undefined as number | undefined
})

interface ItemSelection {
  isSelected: boolean
  quantity: number
  discountRate: number
}

const lineItemSelections = ref<Record<number, ItemSelection>>({})

// ── Computed ──
const filteredRows = computed(() => rows.value)

const firstYearTotal = computed(() => {
  if (!policyItems.value.length) return 0
  return policyItems.value.reduce((sum, item) => {
    const sel = lineItemSelections.value[item.id]
    if (!sel || !sel.isSelected) return sum
    const unitPrice = Number(item.unitPrice || 0)
    const taxRate = Number(item.taxRate || 0)
    const subtotalExTax = unitPrice * sel.quantity * sel.discountRate
    const subtotalInclTax = subtotalExTax * (1 + taxRate)
    return sum + subtotalInclTax
  }, 0)
})

const subsequentYearTotal = computed(() => {
  if (!policyItems.value.length) return 0
  return policyItems.value.reduce((sum, item) => {
    const sel = lineItemSelections.value[item.id]
    if (!sel || !sel.isSelected) return sum
    if (item.section !== '运维服务') return sum
    const unitPrice = Number(item.unitPrice || 0)
    const taxRate = Number(item.taxRate || 0)
    const subtotalExTax = unitPrice * sel.quantity * sel.discountRate
    return sum + subtotalExTax * (1 + taxRate)
  }, 0)
})

const groupedItems = computed(() => {
  const groups: Record<string, QuotationPolicyItem[]> = {}
  policyItems.value.forEach(item => {
    const s = item.section || '其他'
    if (!groups[s]) groups[s] = []
    groups[s].push(item)
  })
  Object.values(groups).forEach(arr => arr.sort((a, b) => a.sortOrder - b.sortOrder))
  return groups
})

const sortedSections = computed(() => {
  const order = ['产品模块', '运维服务', '定制开发', '会员通对接']
  return Object.keys(groupedItems.value).sort((a, b) => {
    const ai = order.indexOf(a)
    const bi = order.indexOf(b)
    if (ai >= 0 && bi >= 0) return ai - bi
    if (ai >= 0) return -1
    if (bi >= 0) return 1
    return a.localeCompare(b)
  })
})

// ── Methods ──
const loadRows = async () => {
  loading.value = true
  try {
    const result = await api.getQuotations({
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      customerName: filters.customerName || undefined
    })
    rows.value = Array.isArray(result) ? result : (result?.records || [])
  } catch {
    ElMessage.error('报价单数据加载失败')
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  filters.keyword = ''
  filters.status = ''
  filters.customerName = ''
  loadRows()
}

const loadPublishedPolicies = async () => {
  try {
    policies.value = await api.getQuotationPolicies({ status: 'PUBLISHED' })
  } catch {
    ElMessage.error('报价策略加载失败')
  }
}

const selectPolicy = async (policy: QuotationPolicy) => {
  selectedPolicy.value = policy
  try {
    const detail = await api.getQuotationPolicyDetail(policy.id)
    policyItems.value = detail?.items || []
    const map: Record<number, ItemSelection> = {}
    policyItems.value.forEach(item => {
      map[item.id] = {
        isSelected: item.isRequired === 1,
        quantity: 1,
        discountRate: 1
      }
    })
    lineItemSelections.value = map
  } catch {
    ElMessage.error('策略明细加载失败')
  }
}

const toggleItem = (policyItemId: number) => {
  const sel = lineItemSelections.value[policyItemId]
  if (!sel) return
  sel.isSelected = !sel.isSelected
}

const nextStep = () => {
  if (currentStep.value === 0 && !selectedPolicy.value) {
    ElMessage.warning('请先选择报价策略')
    return
  }
  if (currentStep.value === 1 && !customerForm.customerName.trim()) {
    ElMessage.warning('请填写委托方/品牌公司名称')
    return
  }
  if (currentStep.value < 2) {
    currentStep.value++
  }
}

const prevStep = () => {
  if (currentStep.value > 0) currentStep.value--
}

const submitQuotation = async () => {
  if (!selectedPolicy.value) return

  const overrides: LineItemOverride[] = policyItems.value.map(item => {
    const sel = lineItemSelections.value[item.id]
    return {
      policyItemId: item.id,
      isSelected: sel?.isSelected ?? (item.isRequired === 1),
      quantity: sel?.quantity ?? 1,
      discountRate: sel?.discountRate ?? 1
    }
  })

  const payload: QuotationGenerateRequest = {
    policyId: selectedPolicy.value.id,
    customerName: customerForm.customerName.trim(),
    contactPerson: customerForm.contactPerson || undefined,
    contactPhone: customerForm.contactPhone || undefined,
    contactEmail: customerForm.contactEmail || undefined,
    deliveryPeriod: customerForm.deliveryPeriod || undefined,
    opportunityId: customerForm.opportunityId,
    lineItemOverrides: overrides
  }

  submitting.value = true
  try {
    await api.generateQuotation(payload)
    ElMessage.success('报价单已生成')
    dialogVisible.value = false
    await loadRows()
  } catch {
    ElMessage.error('报价单生成失败')
  } finally {
    submitting.value = false
  }
}

const openCreate = () => {
  currentStep.value = 0
  selectedPolicy.value = null
  policyItems.value = []
  lineItemSelections.value = {}
  Object.assign(customerForm, {
    customerName: '',
    contactPerson: '',
    contactPhone: '',
    contactEmail: '',
    deliveryPeriod: '',
    opportunityId: undefined
  })
  loadPublishedPolicies()
  dialogVisible.value = true
}

const exportQuotation = async (id: number) => {
  try {
    const blob = await api.exportQuotation(id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `报价单_${id}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

const remove = async (row: QuotationListVO) => {
  await ElMessageBox.confirm(`确定删除报价单「${row.quotationNo}」吗？`, '删除报价单', { type: 'warning' })
  try {
    await api.deleteQuotation(row.id)
    await loadRows()
    ElMessage.success('报价单已删除')
  } catch {
    ElMessage.error('报价单删除失败')
  }
}

const updateStatus = async (row: QuotationListVO, status: string) => {
  const label = statusLabel[status] || status
  await ElMessageBox.confirm(`确定将报价单状态变更为「${label}」吗？`, '状态变更', { type: 'warning' })
  try {
    await api.updateQuotationStatus(row.id, status)
    await loadRows()
    ElMessage.success('状态已更新')
  } catch {
    ElMessage.error('状态更新失败')
  }
}

const formatMoney = (value?: number) => {
  if (value == null) return '--'
  return '¥' + value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const getStatusType = (status: string): 'info' | 'warning' | 'primary' | 'success' | 'danger' => {
  const map: Record<string, 'info' | 'warning' | 'primary' | 'success' | 'danger'> = {
    DRAFT: 'info',
    INTERNAL_REVIEW: 'warning',
    APPROVED: 'primary',
    SENT: 'success',
    ACCEPTED: 'success',
    REJECTED: 'danger',
    EXPIRED: 'info'
  }
  return map[status] || 'info'
}

const viewDetail = (row: QuotationListVO) => {
  router.push(`/quotations/${row.id}`)
}

const policyTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    SAAS: 'SaaS全渠道',
    PRIVATE_DEPLOYMENT: '私有化',
    MEMBERSHIP: '会员通'
  }
  return map[type] || type
}

const taxModeLabel = (mode: string) => mode === 'TAX_INCLUDED' ? '含税' : '未税'

const itemSubtotalExTax = (item: QuotationPolicyItem) => {
  const sel = lineItemSelections.value[item.id]
  if (!sel) return 0
  return (Number(item.unitPrice || 0)) * sel.quantity * sel.discountRate
}

const itemSubtotalInclTax = (item: QuotationPolicyItem) => {
  const exTax = itemSubtotalExTax(item)
  return exTax * (1 + Number(item.taxRate || 0))
}

// ── Lifecycle ──
onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="quotation-page">
    <!-- ── Filter bar ── -->
    <section class="quotation-filter-panel">
      <div class="filter-grid">
        <el-input
          v-model="filters.keyword"
          placeholder="搜索报价单号、客户名称"
          :prefix-icon="Search"
          clearable
          @clear="loadRows"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable @change="loadRows">
          <el-option label="全部状态" value="" />
          <el-option v-for="s in statusOptions" :key="s" :label="statusLabel[s]" :value="s" />
        </el-select>
        <el-input
          v-model="filters.customerName"
          placeholder="客户名称"
          clearable
          @clear="loadRows"
        />
      </div>
      <div class="filter-actions">
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <el-button type="primary" :icon="Search" @click="loadRows">查询</el-button>
      </div>
      <div class="view-actions">
        <span class="spacer" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建报价单</el-button>
      </div>
    </section>

    <!-- ── Table ── -->
    <section class="quotation-table-panel" aria-label="报价单列表">
      <el-table v-loading="loading" :data="filteredRows" class="quotation-table" scrollbar-always-on>
        <el-table-column prop="quotationNo" label="报价单号" min-width="180" />
        <el-table-column prop="customerName" label="客户名称" min-width="160" />
        <el-table-column label="首年含税总价" width="150">
          <template #default="{ row }">
            <strong>{{ formatMoney(row.firstYearTotalInclTax) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" effect="light">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="opportunityName" label="关联商机" min-width="140">
          <template #default="{ row }">
            <span v-if="row.opportunityName" class="quotation-opp-link">{{ row.opportunityName }}</span>
            <span v-else class="quotation-empty-cell">--</span>
          </template>
        </el-table-column>
        <el-table-column label="报价日期" width="120" prop="quoteDate">
          <template #default="{ row }">
            <time :datetime="row.quoteDate">{{ row.quoteDate }}</time>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <div class="desktop-row-actions">
              <el-button link type="primary" @click="viewDetail(row)">详情</el-button>
              <el-button link type="primary" :icon="Download" @click="exportQuotation(row.id)">导出</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => { if (cmd === 'remove') remove(row); else updateStatus(row, cmd) }">
                <el-button link type="primary">操作</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-if="row.status === 'DRAFT'">
                      <el-dropdown-item command="INTERNAL_REVIEW">提交审核</el-dropdown-item>
                      <el-dropdown-item command="remove" divided>删除</el-dropdown-item>
                    </template>
                    <template v-else-if="row.status === 'INTERNAL_REVIEW'">
                      <el-dropdown-item command="APPROVED">批准</el-dropdown-item>
                    </template>
                    <template v-else-if="row.status === 'APPROVED'">
                      <el-dropdown-item command="SENT">标记已发送</el-dropdown-item>
                    </template>
                    <template v-else-if="row.status === 'SENT'">
                      <el-dropdown-item command="ACCEPTED">标记已接受</el-dropdown-item>
                      <el-dropdown-item command="REJECTED" divided>标记已拒绝</el-dropdown-item>
                    </template>
                    <template v-else>
                      <el-dropdown-item disabled>无可用操作</el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <footer class="table-footer">共 {{ filteredRows.length }} 条记录</footer>
    </section>

    <!-- ── Create Wizard Dialog ── -->
    <el-dialog
      v-model="dialogVisible"
      title="新建报价单"
      width="900px"
      class="quotation-dialog"
      :close-on-click-modal="false"
    >
      <el-steps :active="currentStep" align-center finish-status="success">
        <el-step title="选择报价策略" />
        <el-step title="客户信息" />
        <el-step title="配置明细" />
      </el-steps>

      <!-- Step 0: Policy selection -->
      <div v-show="currentStep === 0" class="wizard-step">
        <div class="policy-grid">
          <div
            v-for="policy in policies"
            :key="policy.id"
            class="policy-card"
            :class="{ active: selectedPolicy?.id === policy.id }"
            @click="selectPolicy(policy)"
          >
            <div class="policy-card-header">
              <span class="policy-type-tag">{{ policyTypeLabel(policy.type) }}</span>
              <span class="policy-tax-tag">{{ taxModeLabel(policy.taxMode) }}</span>
            </div>
            <div class="policy-card-body">
              <strong>{{ policy.name }}</strong>
              <small>版本 v{{ policy.version }}</small>
            </div>
            <div class="policy-card-footer">
              <span v-if="policy.effectiveDate">生效: {{ policy.effectiveDate }}</span>
              <span v-if="policy.expiryDate">至: {{ policy.expiryDate }}</span>
            </div>
          </div>
        </div>
        <div v-if="selectedPolicy" class="policy-preview">
          <p>
            已选策略：
            <strong>{{ selectedPolicy.name }}</strong>
            · {{ policyTypeLabel(selectedPolicy.type) }}
            · {{ taxModeLabel(selectedPolicy.taxMode) }}
          </p>
        </div>
      </div>

      <!-- Step 1: Customer info -->
      <div v-show="currentStep === 1" class="wizard-step">
        <el-form label-position="top" class="quotation-form">
          <el-form-item label="委托方/品牌公司名称" required>
            <el-input v-model="customerForm.customerName" placeholder="请输入委托方或品牌公司全称" />
          </el-form-item>
          <div class="form-two">
            <el-form-item label="项目负责人">
              <el-input v-model="customerForm.contactPerson" placeholder="请输入负责人姓名" />
            </el-form-item>
            <el-form-item label="电话">
              <el-input v-model="customerForm.contactPhone" placeholder="请输入联系电话" />
            </el-form-item>
          </div>
          <div class="form-two">
            <el-form-item label="邮件">
              <el-input v-model="customerForm.contactEmail" placeholder="请输入邮箱地址" />
            </el-form-item>
            <el-form-item label="交货期">
              <el-input v-model="customerForm.deliveryPeriod" placeholder="如: 30个工作日" />
            </el-form-item>
          </div>
        </el-form>
      </div>

      <!-- Step 2: Configure items -->
      <div v-show="currentStep === 2" class="wizard-step wizard-configure">
        <div class="configure-sections">
          <div
            v-for="section in sortedSections"
            :key="section"
            class="configure-section"
          >
            <h4 class="section-header">{{ section }}</h4>
            <el-table :data="groupedItems[section]" class="configure-table" size="small">
              <el-table-column width="50">
                <template #default="{ row: item }">
                  <el-checkbox
                    :model-value="lineItemSelections[item.id]?.isSelected ?? false"
                    :disabled="item.isRequired === 1"
                    @change="toggleItem(item.id)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="明细项" min-width="160">
                <template #default="{ row: item }">
                  <div class="configure-item-name">
                    <strong>{{ item.itemName }}</strong>
                    <small v-if="item.description">{{ item.description }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="价格说明" width="140">
                <template #default="{ row: item }">
                  <span class="configure-price-desc">{{ item.priceDescription || formatMoney(item.unitPrice) + (item.chargeUnit ? '/' + item.chargeUnit : '') }}</span>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="100">
                <template #default="{ row: item }">
                  <el-input-number
                    v-model="lineItemSelections[item.id].quantity"
                    :min="1"
                    :max="9999"
                    :controls="false"
                    size="small"
                    style="width: 80px"
                  />
                </template>
              </el-table-column>
              <el-table-column label="折扣率" width="110">
                <template #default="{ row: item }">
                  <el-input-number
                    v-model="lineItemSelections[item.id].discountRate"
                    :min="0"
                    :max="1"
                    :step="0.05"
                    :precision="2"
                    :controls="false"
                    size="small"
                    style="width: 90px"
                  />
                </template>
              </el-table-column>
              <el-table-column label="含税小计" width="130" align="right">
                <template #default="{ row: item }">
                  <strong v-if="lineItemSelections[item.id]?.isSelected">
                    {{ formatMoney(itemSubtotalInclTax(item)) }}
                  </strong>
                  <span v-else class="quotation-empty-cell">--</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="configure-summary">
          <div class="summary-row">
            <span>首年含税总价</span>
            <strong>{{ formatMoney(firstYearTotal) }}</strong>
          </div>
          <div v-if="subsequentYearTotal" class="summary-row">
            <span>次年及以后含税总价</span>
            <strong>{{ formatMoney(subsequentYearTotal) }}</strong>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="wizard-footer">
          <div class="wizard-footer-left">
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button v-if="currentStep > 0" @click="prevStep">上一步</el-button>
          </div>
          <div class="wizard-footer-right">
            <el-button v-if="currentStep < 2" type="primary" @click="nextStep">下一步</el-button>
            <el-button v-else type="primary" :loading="submitting" @click="submitQuotation">生成报价单</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.quotation-page { width: 100%; min-width: 0; color: #25324b; }

/* ── Filter ── */
.quotation-filter-panel {
  position: relative;
  min-width: 0;
  max-width: 100%;
  padding: 18px;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(30 41 80 / 4%);
  margin-bottom: 16px;
}

.filter-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 12px;
  padding-right: 180px;
}

.filter-actions {
  position: absolute;
  right: 18px;
  top: 18px;
}

.view-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #eff2f7;
}

.view-actions .spacer { flex: 1; }

/* ── Table ── */
.quotation-table-panel {
  padding: 0;
  overflow: hidden;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(30 41 80 / 4%);
}

.quotation-table { width: 100%; max-width: 100%; }

.desktop-row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
}

.quotation-opp-link { color: #4f46e5; cursor: pointer; }
.quotation-opp-link:hover { text-decoration: underline; }

.quotation-empty-cell { color: #c0c4cc; }

.table-footer {
  display: flex;
  justify-content: space-between;
  padding: 15px 18px;
  color: #657691;
  font-size: 12px;
}

/* ── Dialog ── */
.quotation-dialog :deep(.el-dialog__body) { padding-top: 8px; }

/* ── Wizard ── */
.wizard-step {
  padding: 20px 0 10px;
  min-height: 280px;
  max-height: 520px;
  overflow-y: auto;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

.policy-card {
  padding: 16px;
  border: 2px solid #e7ebf3;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color .15s ease, box-shadow .15s ease;
}

.policy-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 4px 14px rgb(79 70 229 / 10%);
}

.policy-card.active {
  border-color: #4f46e5;
  box-shadow: 0 4px 18px rgb(79 70 229 / 18%);
  background: #fafaff;
}

.policy-card-header {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.policy-type-tag,
.policy-tax-tag {
  padding: 3px 8px;
  border-radius: 5px;
  font-size: 11px;
}

.policy-type-tag {
  color: #4f46e5;
  background: #eef2ff;
}

.policy-tax-tag {
  color: #059669;
  background: #ecfdf5;
}

.policy-card-body strong {
  display: block;
  font-size: 14px;
  color: #25324b;
}

.policy-card-body small {
  display: block;
  margin-top: 4px;
  color: #8391a7;
  font-size: 12px;
}

.policy-card-footer {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #eff2f7;
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #8391a7;
}

.policy-preview {
  margin-top: 18px;
  padding: 12px 16px;
  border: 1px solid #c7d2fe;
  border-radius: 8px;
  background: #eef2ff;
  color: #4f46e5;
  font-size: 13px;
}

.policy-preview strong { color: #3730a3; }

/* ── Customer form ── */
.quotation-form :deep(.el-form-item) { margin-bottom: 14px; }

.form-two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

/* ── Configure step ── */
.wizard-configure { max-height: 460px; }

.configure-sections { display: grid; gap: 16px; }

.configure-section { border: 1px solid #e7ebf3; border-radius: 10px; overflow: hidden; }

.section-header {
  margin: 0;
  padding: 12px 16px;
  background: #f8fafc;
  color: #25324b;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid #e7ebf3;
}

.configure-table { width: 100%; }

.configure-item-name strong {
  display: block;
  font-size: 13px;
  color: #25324b;
}

.configure-item-name small {
  display: block;
  margin-top: 2px;
  color: #8391a7;
  font-size: 11px;
}

.configure-price-desc {
  font-size: 12px;
  color: #64748b;
}

.configure-summary {
  margin-top: 20px;
  padding: 14px 18px;
  border: 1px solid #e7ebf3;
  border-radius: 10px;
  background: #fafafa;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.summary-row + .summary-row {
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid #e7ebf3;
}

.summary-row span {
  color: #64748b;
  font-size: 13px;
}

.summary-row strong {
  font-size: 16px;
  color: #25324b;
}

/* ── Wizard footer ── */
.wizard-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.wizard-footer-left,
.wizard-footer-right {
  display: flex;
  gap: 8px;
}

/* ── Responsive ── */
@media (max-width: 1100px) {
  .filter-grid { grid-template-columns: repeat(2, 1fr); padding-right: 0; }
  .filter-actions { position: static; margin-top: 12px; }
}

@media (max-width: 680px) {
  .filter-grid { grid-template-columns: 1fr; }
  .form-two { grid-template-columns: 1fr; }
  .policy-grid { grid-template-columns: 1fr; }
}
</style>