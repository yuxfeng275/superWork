<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, ArrowLeft, Edit, Delete, Check } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { Quotation, QuotationLineItem } from '@/types/quotation'

const route = useRoute()
const router = useRouter()

const quotation = ref<Quotation | null>(null)
const loading = ref(false)
const error = ref(false)

const lineItemsBySection = computed(() => {
  const map = new Map<string, QuotationLineItem[]>()
  if (!quotation.value?.lineItems) return map
  const sorted = [...quotation.value.lineItems].sort((a, b) => a.sortOrder - b.sortOrder)
  for (const item of sorted) {
    const section = item.section || '其他'
    if (!map.has(section)) map.set(section, [])
    map.get(section)!.push(item)
  }
  return map
})

const sectionNames = computed(() => [...lineItemsBySection.value.keys()])

const firstYearTotal = computed(() => {
  if (!quotation.value) return 0
  return quotation.value.taxMode === 'TAX_INCLUDED'
    ? (quotation.value.firstYearTotalInclTax ?? 0)
    : (quotation.value.firstYearTotalExTax ?? 0)
})

const subsequentYearTotal = computed(() => {
  if (!quotation.value) return 0
  return quotation.value.taxMode === 'TAX_INCLUDED'
    ? (quotation.value.subsequentYearTotalInclTax ?? 0)
    : (quotation.value.subsequentYearTotalExTax ?? 0)
})

const formatMoney = (value?: number) => {
  if (value == null || Number.isNaN(value)) return '-'
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatDate = (value?: string) => {
  if (!value) return '-'
  return value.slice(0, 10)
}

const unitPrice = (item: QuotationLineItem) => {
  if (quotation.value?.taxMode === 'TAX_INCLUDED') {
    const exTax = item.unitPriceExTax ?? 0
    const rate = item.taxRate ?? 0
    return exTax * (1 + rate)
  }
  return item.unitPriceExTax ?? 0
}

const subtotal = (item: QuotationLineItem) => {
  if (quotation.value?.taxMode === 'TAX_INCLUDED') {
    return item.subtotalInclTax ?? 0
  }
  return item.subtotalExTax ?? 0
}

const statusLabel = (status: string) =>
  ({
    DRAFT: '草稿',
    INTERNAL_REVIEW: '内部审核',
    APPROVED: '已批准',
    SENT: '已发送',
    ACCEPTED: '已接受',
    REJECTED: '已拒绝',
    EXPIRED: '已过期'
  } as Record<string, string>)[status] || status

const statusTagType = (status: string) =>
  ({
    DRAFT: 'info',
    INTERNAL_REVIEW: 'warning',
    APPROVED: 'success',
    SENT: '',
    ACCEPTED: 'success',
    REJECTED: 'danger',
    EXPIRED: 'info'
  } as Record<string, string>)[status] || 'info'

const taxModeLabel = (mode: string) =>
  mode === 'TAX_INCLUDED' ? '含税' : '未税'

const loadQuotation = async () => {
  loading.value = true
  error.value = false
  try {
    const id = Number(route.params.id)
    quotation.value = await api.getQuotationDetail(id)
  } catch {
    error.value = true
    ElMessage.error('报价单数据加载失败')
  } finally {
    loading.value = false
  }
}

const exportExcel = async () => {
  if (!quotation.value) return
  try {
    const blob = await api.exportQuotation(quotation.value.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `报价单_${quotation.value.quotationNo}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

const updateStatus = async (status: string) => {
  if (!quotation.value) return
  const label = statusLabel(status)
  try {
    await ElMessageBox.confirm(`确定将报价单状态变更为「${label}」吗？`, '更新状态', { type: 'warning' })
    await api.updateQuotationStatus(quotation.value.id, status)
    await loadQuotation()
    ElMessage.success('状态已更新')
  } catch {
    // cancelled or error
  }
}

const deleteQuotation = async () => {
  if (!quotation.value) return
  try {
    await ElMessageBox.confirm(`确定删除报价单「${quotation.value.quotationNo}」吗？此操作不可恢复。`, '删除报价单', { type: 'warning' })
    await api.deleteQuotation(quotation.value.id)
    ElMessage.success('报价单已删除')
    router.push('/quotations')
  } catch {
    // cancelled or error
  }
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/quotations')
  }
}

const goToEdit = () => {
  // FUTURE: navigate to edit mode
  ElMessage.info('编辑功能开发中')
}

const nextStatuses = computed(() => {
  if (!quotation.value) return []
  const flow: Record<string, string[]> = {
    DRAFT: ['INTERNAL_REVIEW'],
    INTERNAL_REVIEW: ['APPROVED'],
    APPROVED: ['SENT'],
    SENT: ['ACCEPTED', 'REJECTED'],
    ACCEPTED: [],
    REJECTED: [],
    EXPIRED: []
  }
  return flow[quotation.value.status] || []
})

onMounted(() => {
  loadQuotation()
})
</script>

<template>
  <div class="quote-detail-page" v-loading="loading">
    <!-- Error state -->
    <div v-if="error" class="error-state">
      <p>报价单加载失败</p>
      <el-button type="primary" @click="loadQuotation">重新加载</el-button>
    </div>

    <template v-else-if="quotation">
      <!-- Header bar -->
      <header class="detail-header">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="header-actions">
          <el-button type="primary" :icon="Download" @click="exportExcel">导出 Excel</el-button>
          <el-button :icon="Edit" @click="goToEdit" v-if="quotation.status === 'DRAFT'">修改</el-button>
          <el-button
            v-for="status in nextStatuses"
            :key="status"
            :icon="Check"
            :type="status === 'APPROVED' ? 'success' : 'primary'"
            @click="updateStatus(status)"
          >
            {{ statusLabel(status) }}
          </el-button>
          <el-button
            type="danger"
            :icon="Delete"
            v-if="quotation.status === 'DRAFT'"
            @click="deleteQuotation"
          >
            删除
          </el-button>
        </div>
      </header>

      <!-- Top info card -->
      <section class="info-card">
        <div class="info-card-header">
          <div class="card-title">
            <h2>{{ quotation.quotationNo }}</h2>
            <el-tag :type="statusTagType(quotation.status)" effect="light">
              {{ statusLabel(quotation.status) }}
            </el-tag>
            <span class="card-meta">
              报价日期：{{ formatDate(quotation.quoteDate) }}
              <template v-if="quotation.validityDays"> · 有效期 {{ quotation.validityDays }} 天</template>
              · {{ taxModeLabel(quotation.taxMode) }}
            </span>
          </div>
        </div>

        <div class="info-grid">
          <!-- 委托方信息 -->
          <div class="info-section">
            <h3>委托方信息</h3>
            <dl>
              <div>
                <dt>委托方 / 品牌公司</dt>
                <dd>{{ quotation.customerName || '-' }}</dd>
              </div>
              <div>
                <dt>项目负责人</dt>
                <dd>{{ quotation.contactPerson || '-' }}</dd>
              </div>
              <div>
                <dt>电话</dt>
                <dd>{{ quotation.contactPhone || '-' }}</dd>
              </div>
              <div>
                <dt>邮件</dt>
                <dd>{{ quotation.contactEmail || '-' }}</dd>
              </div>
              <div>
                <dt>交货期</dt>
                <dd>{{ quotation.deliveryPeriod || '-' }}</dd>
              </div>
            </dl>
          </div>

          <!-- 报价合计 -->
          <div class="info-section totals-section">
            <h3>报价合计（{{ taxModeLabel(quotation.taxMode) }}）</h3>
            <div class="totals-grid">
              <div class="total-block">
                <span class="total-label">首年</span>
                <strong class="total-amount">{{ formatMoney(firstYearTotal) }}</strong>
                <small>{{ quotation.currency || 'CNY' }}</small>
              </div>
              <div class="total-block">
                <span class="total-label">次年及以后</span>
                <strong class="total-amount">{{ formatMoney(subsequentYearTotal) }}</strong>
                <small>{{ quotation.currency || 'CNY' }}</small>
              </div>
            </div>
            <div class="invoice-info">
              <span>发票类型：{{ quotation.invoiceType || '增值税专用发票' }}</span>
            </div>
            <div v-if="quotation.opportunityName" class="opportunity-link">
              <span>关联商机：</span>
              <router-link v-if="quotation.opportunityId" :to="`/opportunities/${quotation.opportunityId}`">
                {{ quotation.opportunityName }}
              </router-link>
              <span v-else>{{ quotation.opportunityName }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Line items by section -->
      <section class="items-card">
        <h3>报价明细</h3>
        <el-collapse v-if="sectionNames.length" accordion>
          <el-collapse-item
            v-for="sectionName in sectionNames"
            :key="sectionName"
            :name="sectionName"
          >
            <template #title>
              <div class="section-title">
                <strong>{{ sectionName }}</strong>
                <span class="section-count">{{ lineItemsBySection.get(sectionName)?.length ?? 0 }} 项</span>
              </div>
            </template>
            <el-table
              :data="lineItemsBySection.get(sectionName) ?? []"
              border
              stripe
              class="items-table"
              size="small"
            >
              <el-table-column label="选择" width="60" align="center">
                <template #default="{ row }">
                  <el-tag
                    :type="row.isSelected === 1 ? 'success' : 'info'"
                    effect="light"
                    size="small"
                  >
                    {{ row.isSelected === 1 ? '已选' : '未选' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="itemName" label="明细项" min-width="180" show-overflow-tooltip />
              <el-table-column prop="description" label="功能描述" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="{ dim: row.isSelected !== 1 }">{{ row.description || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="80" align="center">
                <template #default="{ row }">{{ row.quantity }}</template>
              </el-table-column>
              <el-table-column label="单价" width="130" align="right">
                <template #default="{ row }">
                  <span :class="{ dim: row.isSelected !== 1 }">{{ formatMoney(unitPrice(row)) }}</span>
                  <template v-if="quotation.taxMode === 'TAX_EXCLUDED' && row.taxRate != null">
                    <br /><small class="tax-rate">税率 {{ ((row.taxRate ?? 0) * 100).toFixed(0) }}%</small>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="折扣率" width="90" align="center">
                <template #default="{ row }">
                  {{ ((row.discountRate ?? 1) * 100).toFixed(0) }}%
                </template>
              </el-table-column>
              <el-table-column label="小计" width="140" align="right">
                <template #default="{ row }">
                  <strong :class="{ dim: row.isSelected !== 1 }">{{ formatMoney(subtotal(row)) }}</strong>
                </template>
              </el-table-column>
              <el-table-column prop="chargeMethod" label="收费方式" width="120" show-overflow-tooltip />
              <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else description="暂无报价明细" :image-size="60" />
      </section>

      <!-- Brand scope -->
      <section v-if="quotation.brandScopes && quotation.brandScopes.length" class="items-card">
        <h3>报价品牌范围</h3>
        <el-table :data="quotation.brandScopes" border stripe class="items-table" size="small">
          <el-table-column prop="brand" label="品牌" min-width="120" />
          <el-table-column prop="store" label="店铺" min-width="150" />
          <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column prop="target" label="目标" min-width="180" show-overflow-tooltip />
        </el-table>
      </section>

      <!-- Quotation note -->
      <section v-if="quotation.quotationNote" class="items-card">
        <h3>报价说明</h3>
        <div class="quotation-note">
          <pre>{{ quotation.quotationNote }}</pre>
        </div>
      </section>

      <!-- Bottom actions -->
      <footer class="detail-footer">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="footer-actions">
          <el-button
            v-for="status in nextStatuses"
            :key="status"
            :type="status === 'APPROVED' ? 'success' : 'primary'"
            @click="updateStatus(status)"
          >
            {{ statusLabel(status) }}
          </el-button>
          <el-button type="primary" :icon="Download" @click="exportExcel">导出 Excel</el-button>
        </div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.quote-detail-page {
  width: 100%;
  min-width: 0;
  color: #25324b;
}

.error-state {
  display: grid;
  place-items: center;
  gap: 16px;
  padding: 80px 20px;
  color: #8391a7;
}

/* Header */
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Info card */
.info-card {
  padding: 24px;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(30 41 80 / 4%);
  margin-bottom: 20px;
}

.info-card-header {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eff2f7;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.card-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}

.card-meta {
  color: #8391a7;
  font-size: 13px;
  margin-left: 4px;
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.info-section h3 {
  margin: 0 0 14px;
  font-size: 15px;
  color: #25324b;
}

.info-section dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 0;
}

.info-section dl > div {
  display: grid;
  gap: 4px;
}

.info-section dt {
  color: #8391a7;
  font-size: 12px;
}

.info-section dd {
  margin: 0;
  color: #25324b;
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Totals */
.totals-section {
  border-left: 1px solid #eff2f7;
  padding-left: 24px;
}

.totals-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 14px;
}

.total-block {
  padding: 16px;
  border: 1px solid #e7ebf3;
  border-radius: 10px;
  background: #f8fafc;
  display: grid;
  gap: 6px;
}

.total-label {
  color: #8391a7;
  font-size: 12px;
}

.total-amount {
  font-size: 24px;
  font-weight: 700;
  color: #4f46e5;
  line-height: 1.2;
}

.total-block small {
  color: #8391a7;
  font-size: 12px;
}

.invoice-info {
  color: #8391a7;
  font-size: 13px;
  margin-bottom: 6px;
}

.opportunity-link {
  color: #52617b;
  font-size: 13px;
}

.opportunity-link a {
  color: #4f46e5;
  text-decoration: none;
}

.opportunity-link a:hover {
  text-decoration: underline;
}

/* Items card */
.items-card {
  padding: 20px 24px;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(30 41 80 / 4%);
  margin-bottom: 20px;
}

.items-card > h3 {
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 600;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.section-title strong {
  font-size: 14px;
}

.section-count {
  color: #8391a7;
  font-size: 12px;
}

.items-table {
  width: 100%;
}

.items-table :deep(.dim) {
  color: #b0bec5;
}

.tax-rate {
  color: #8391a7;
  font-size: 11px;
}

/* Collapse override */
:deep(.el-collapse-item__header) {
  padding: 14px 16px;
  font-size: 14px;
  border-radius: 8px;
}

:deep(.el-collapse-item__wrap) {
  border-radius: 0 0 8px 8px;
}

/* Note */
.quotation-note {
  padding: 16px;
  border: 1px solid #e7ebf3;
  border-radius: 10px;
  background: #f8fafc;
  max-height: 300px;
  overflow: auto;
}

.quotation-note pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  color: #40506a;
}

/* Footer */
.detail-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 20px;
}

.footer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

@media (max-width: 860px) {
  .info-grid {
    grid-template-columns: 1fr;
  }

  .totals-section {
    border-left: none;
    padding-left: 0;
    border-top: 1px solid #eff2f7;
    padding-top: 20px;
  }

  .info-section dl {
    grid-template-columns: 1fr;
  }

  .detail-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .detail-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>