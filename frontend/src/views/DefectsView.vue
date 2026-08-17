<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import WorkItemAnalysisPanel from '@/components/WorkItemAnalysisPanel.vue'
import type {
  NormalizedWorkItemStatus,
  WorkItemAnalysis,
  WorkItemDistributionItem,
  WorkItemOverviewItem,
  WorkItemOverviewResponse,
  WorkItemOverviewSummary
} from '@/types/work-item'

const loading = ref(false)
const detailVisible = ref(false)
const selectedDefect = ref<WorkItemOverviewItem>()
const records = ref<WorkItemOverviewItem[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const lastSyncedAt = ref('')
const emptyAnalysis = (): WorkItemAnalysis => ({
  statusDistribution: [],
  projectDistribution: [],
  ownerDistribution: [],
  sourceDistribution: [],
  priorityDistribution: [],
  overdueProjectDistribution: [], overdueOwnerDistribution: [], overdueAgeDistribution: [],
  totalEstimatedHours: 0,
  totalActualHours: 0,
  completionRate: 0,
  unassignedCount: 0, overdueIncompleteCount: 0, missingDueDateCount: 0
})
const analysis = ref<WorkItemAnalysis>(emptyAnalysis())
const summary = ref<WorkItemOverviewSummary>({
  totalCount: 0,
  localCount: 0,
  yunxiaoCount: 0,
  pendingCount: 0,
  inProgressCount: 0,
  completedCount: 0,
  otherCount: 0
})
const filters = reactive<{
  keyword: string
  projectId?: number
  assigneeId?: number
  normalizedStatus: NormalizedWorkItemStatus | ''
}>({
  keyword: '',
  projectId: undefined,
  assigneeId: undefined,
  normalizedStatus: ''
})

const statusOptions: Array<{ label: string; value: NormalizedWorkItemStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: '待处理', value: 'PENDING' },
  { label: '进行中', value: 'IN_PROGRESS' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '其他', value: 'OTHER' }
]

const projectOptions = computed(() => analysis.value.projectDistribution
  .filter(item => /^\d+$/.test(item.key))
  .map(item => ({ id: Number(item.key), name: item.label })))

const assigneeOptions = computed(() => analysis.value.ownerDistribution
  .filter(item => /^\d+$/.test(item.key))
  .map(item => ({ id: Number(item.key), name: item.label })))

const analysisSections = computed(() => [
  { key: 'status' as const, title: '状态分布', rows: analysis.value.statusDistribution },
  { key: 'project' as const, title: '项目分布', rows: analysis.value.projectDistribution },
  { key: 'owner' as const, title: '负责人分布', rows: analysis.value.ownerDistribution },
  { key: 'source' as const, title: '数据来源', rows: analysis.value.sourceDistribution, interactive: false }
])

const loadDefects = async () => {
  loading.value = true
  try {
    const response = await api.getDefectOverview({
      page: currentPage.value,
      size: pageSize.value,
      projectId: filters.projectId,
      assigneeId: filters.assigneeId,
      normalizedStatus: filters.normalizedStatus,
      keyword: filters.keyword.trim()
    }) as WorkItemOverviewResponse
    records.value = response?.records ?? []
    total.value = Number(response?.total ?? 0)
    summary.value = { ...summary.value, ...(response?.summary ?? {}) }
    analysis.value = response?.analysis ?? emptyAnalysis()
    lastSyncedAt.value = response?.lastSyncedAt ?? ''
  } catch {
    records.value = []
    total.value = 0
    ElMessage.error('缺陷数据加载失败，已保留云效中的原始数据')
  } finally {
    loading.value = false
  }
}

const search = () => {
  currentPage.value = 1
  void loadDefects()
}

const reset = () => {
  filters.keyword = ''
  filters.projectId = undefined
  filters.assigneeId = undefined
  filters.normalizedStatus = ''
  currentPage.value = 1
  void loadDefects()
}

const selectStatus = (status: NormalizedWorkItemStatus | '') => {
  filters.normalizedStatus = status
  search()
}

const handleAnalysisSelect = (section: string, item: WorkItemDistributionItem) => {
  if (section === 'status') filters.normalizedStatus = item.key as NormalizedWorkItemStatus
  if (section === 'project' && /^\d+$/.test(item.key)) filters.projectId = Number(item.key)
  if (section === 'owner') {
    if (/^\d+$/.test(item.key)) filters.assigneeId = Number(item.key)
    else filters.keyword = item.label === '未分配' ? '' : item.label
  }
  search()
}

const openDetail = (row: WorkItemOverviewItem) => {
  selectedDefect.value = row
  detailVisible.value = true
}

const handlePageChange = (page: number) => {
  currentPage.value = page
  void loadDefects()
}

const formatDateTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 16) : '—'
const formatHours = (value?: number) => value == null ? '—' : `${Number(value).toFixed(Number.isInteger(Number(value)) ? 0 : 1)}h`
const projectLabel = (item: WorkItemOverviewItem) => item.projectNames?.join(' / ') || item.projectName || '未映射项目'
const assigneeLabel = (item: WorkItemOverviewItem) => item.assigneeName || item.assigneeUsername || '未分配'
const statusClass = (status?: NormalizedWorkItemStatus) => ({
  PENDING: 'pending',
  IN_PROGRESS: 'progress',
  COMPLETED: 'completed',
  OTHER: 'other'
}[status || 'OTHER'])

onMounted(loadDefects)
</script>

<template>
  <div class="defects-page">
    <header class="page-header">
      <div>
        <h1>缺陷管理</h1>
        <p>数据更新于 {{ formatDateTime(lastSyncedAt) }}</p>
      </div>
      <el-tag type="warning" effect="plain">云效只读</el-tag>
    </header>

    <section class="summary-band" aria-label="缺陷状态汇总">
      <button type="button" :class="{ active: !filters.normalizedStatus }" @click="selectStatus('')">
        <span>全部缺陷</span><strong>{{ summary.totalCount }}</strong>
      </button>
      <button type="button" :class="{ active: filters.normalizedStatus === 'PENDING' }" @click="selectStatus('PENDING')">
        <span>待处理</span><strong class="pending-text">{{ summary.pendingCount }}</strong>
      </button>
      <button type="button" :class="{ active: filters.normalizedStatus === 'IN_PROGRESS' }" @click="selectStatus('IN_PROGRESS')">
        <span>进行中</span><strong class="progress-text">{{ summary.inProgressCount }}</strong>
      </button>
      <button type="button" :class="{ active: filters.normalizedStatus === 'COMPLETED' }" @click="selectStatus('COMPLETED')">
        <span>已完成</span><strong class="completed-text">{{ summary.completedCount }}</strong>
      </button>
    </section>

    <WorkItemAnalysisPanel
      title="缺陷结构分析"
      subtitle="聚焦未关闭缺陷、项目集中度与责任人分布"
      :analysis="analysis"
      :sections="analysisSections"
      @select="handleAnalysisSelect"
    />

    <section class="detail-section-heading">
      <div><h2>缺陷明细</h2><p>通过分析区或筛选器定位具体缺陷</p></div>
    </section>

    <section class="toolbar" aria-label="缺陷筛选">
      <el-input
        v-model="filters.keyword"
        clearable
        placeholder="搜索编号或标题"
        :prefix-icon="Search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="filters.projectId" clearable placeholder="全部项目" @change="search">
        <el-option v-for="project in projectOptions" :key="project.id" :label="project.name" :value="project.id" />
      </el-select>
      <el-select v-model="filters.assigneeId" clearable placeholder="全部负责人" @change="search">
        <el-option v-for="assignee in assigneeOptions" :key="assignee.id" :label="assignee.name" :value="assignee.id" />
      </el-select>
      <el-select v-model="filters.normalizedStatus" placeholder="全部状态" @change="search">
        <el-option v-for="status in statusOptions" :key="status.value" :label="status.label" :value="status.value" />
      </el-select>
      <el-button :icon="Refresh" title="重置筛选" aria-label="重置筛选" @click="reset" />
    </section>

    <el-table
      v-loading="loading"
      :data="records"
      row-key="recordKey"
      class="defect-table"
      empty-text="暂无云效缺陷"
      @row-click="openDetail"
    >
      <el-table-column label="编号" width="120">
        <template #default="{ row }"><span class="serial-number">{{ row.serialNumber || '—' }}</span></template>
      </el-table-column>
      <el-table-column prop="title" label="缺陷标题" min-width="260">
        <template #default="{ row }"><strong class="defect-title">{{ row.title }}</strong></template>
      </el-table-column>
      <el-table-column label="所属项目" min-width="180">
        <template #default="{ row }">{{ projectLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="负责人" width="130">
        <template #default="{ row }">{{ assigneeLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }"><span class="status-label" :class="statusClass(row.normalizedStatus)">{{ row.status || '其他' }}</span></template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }"><span class="created-date">{{ formatDateTime(row.createdAt) }}</span></template>
      </el-table-column>
      <el-table-column label="计划完成" width="140">
        <template #default="{ row }">
          <div class="due-date-cell" :class="{ overdue: row.overdueIncomplete }">
            <span>{{ row.dueDate || '未设置计划' }}</span>
            <small v-if="row.overdueIncomplete" class="overdue-pill">超期 {{ row.overdueDays }} 天</small>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <footer class="pagination-row">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="handlePageChange"
      />
    </footer>

    <el-drawer v-model="detailVisible" size="480px" destroy-on-close>
      <template #header>
        <div v-if="selectedDefect" class="drawer-heading">
          <span>{{ selectedDefect.serialNumber || '云效缺陷' }}</span>
          <h2>{{ selectedDefect.title }}</h2>
        </div>
      </template>
      <div v-if="selectedDefect" class="defect-detail">
        <div class="readonly-note">只读数据，以云效为准</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="原始状态">{{ selectedDefect.status || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属项目">{{ projectLabel(selectedDefect) }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ assigneeLabel(selectedDefect) }}</el-descriptions-item>
          <el-descriptions-item label="预估工时">{{ formatHours(selectedDefect.estimatedHours) }}</el-descriptions-item>
          <el-descriptions-item label="实际工时">{{ formatHours(selectedDefect.actualHours) }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(selectedDefect.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="计划完成">{{ selectedDefect.dueDate || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="超期情况">{{ selectedDefect.overdueIncomplete ? `超期 ${selectedDefect.overdueDays} 天` : '未超期' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(selectedDefect.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <section class="description-section">
          <h3>缺陷描述</h3>
          <p>{{ selectedDefect.description || '暂无描述' }}</p>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.defects-page { padding: 24px; min-width: 0; color: #252a31; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.page-header h1 { margin: 0; font-size: 24px; letter-spacing: 0; }
.page-header p { margin: 5px 0 0; color: #757d88; font-size: 13px; }
.summary-band { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); border: 1px solid #e0e4e9; border-radius: 6px; margin-bottom: 18px; overflow: hidden; }
.summary-band button { appearance: none; border: 0; border-right: 1px solid #e0e4e9; background: #fff; padding: 14px 16px; text-align: left; cursor: pointer; min-height: 72px; }
.summary-band button:last-child { border-right: 0; }
.summary-band button:hover, .summary-band button.active { background: #f6f8fa; box-shadow: inset 0 -2px 0 #3a67b7; }
.summary-band span { display: block; color: #6d7580; font-size: 13px; }
.summary-band strong { display: block; margin-top: 4px; font-size: 23px; }
.pending-text { color: #a76010; }
.progress-text { color: #2867bd; }
.completed-text { color: #237a50; }
.detail-section-heading { display: flex; align-items: end; justify-content: space-between; margin: 20px 0 10px; }
.detail-section-heading h2 { margin: 0; font-size: 16px; letter-spacing: 0; }
.detail-section-heading p { margin: 3px 0 0; color: #7a828d; font-size: 12px; }
.toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) 180px 160px 150px 40px; gap: 10px; margin-bottom: 14px; }
.defect-table { width: 100%; border: 1px solid #e3e6ea; border-radius: 6px; overflow: hidden; }
.defect-table :deep(.el-table__row) { cursor: pointer; }
.serial-number { color: #2867bd; font-weight: 600; }
.defect-title { font-weight: 600; color: #282d34; }
.created-date { color: #8a919b; font-size: 12px; font-variant-numeric: tabular-nums; }
.due-date-cell { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; color: #3f4650; font-size: 12px; font-variant-numeric: tabular-nums; }
.due-date-cell.overdue > span { color: #8d3e36; font-weight: 600; }
.overdue-pill { display: inline-flex; align-items: center; min-height: 20px; padding: 2px 7px; border: 1px solid #efc2ba; border-radius: 3px; background: #fff0ed; color: #a33f35; font-size: 10px; font-weight: 700; line-height: 1; white-space: nowrap; }
.status-label { display: inline-block; padding: 3px 7px; border-radius: 4px; font-size: 12px; white-space: nowrap; }
.status-label.pending { color: #80510c; background: #fff3d3; }
.status-label.progress { color: #245fae; background: #e8f1ff; }
.status-label.completed { color: #1f7048; background: #e8f6ef; }
.status-label.other { color: #5e6570; background: #eef0f3; }
.pagination-row { display: flex; justify-content: flex-end; padding-top: 16px; }
.drawer-heading { min-width: 0; }
.drawer-heading span { color: #2867bd; font-size: 12px; font-weight: 600; }
.drawer-heading h2 { margin: 4px 0 0; font-size: 18px; line-height: 1.4; letter-spacing: 0; overflow-wrap: anywhere; }
.readonly-note { margin-bottom: 16px; padding: 9px 11px; border-left: 3px solid #d5a136; background: #fff8e5; color: #73561c; font-size: 13px; }
.description-section { margin-top: 20px; }
.description-section h3 { margin: 0 0 9px; font-size: 15px; }
.description-section p { margin: 0; color: #555d68; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 900px) {
  .defects-page { padding: 16px; }
  .summary-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .summary-band button:nth-child(2) { border-right: 0; }
  .summary-band button:nth-child(-n+2) { border-bottom: 1px solid #e0e4e9; }
  .toolbar { grid-template-columns: 1fr 1fr; }
  .toolbar :deep(.el-input) { grid-column: 1 / -1; }
}
@media (max-width: 560px) {
  .page-header h1 { font-size: 21px; }
  .toolbar { grid-template-columns: 1fr; }
  .toolbar :deep(.el-input) { grid-column: auto; }
  .pagination-row { justify-content: center; overflow-x: auto; }
}
</style>
