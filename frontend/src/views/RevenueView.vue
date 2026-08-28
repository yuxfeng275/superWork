<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type UploadFile } from 'element-plus'
import { Delete, Edit, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type {
  RevenueBusinessLineSummary,
  RevenueEntryType,
  RevenueImportRecord,
  RevenueImportResult,
  RevenueManualEntryDTO,
  RevenueMapping,
  RevenueProjectSummary,
  RevenueSummary
} from '@/types/revenue'

interface BusinessLineOption {
  id: number
  name: string
}

interface ProjectOption {
  id: number
  name: string
  businessLineId: number
}

interface ProjectTreeNode {
  id: number
  name: string
  businessLineId: number
  children?: ProjectTreeNode[]
}

type SummaryRow =
  | { kind: 'business-line'; businessLine: RevenueBusinessLineSummary; project?: undefined }
  | { kind: 'project'; businessLine: RevenueBusinessLineSummary; project: RevenueProjectSummary }
  | { kind: 'total' }
type CellRow = SummaryRow
type ImportKind = 'cost' | 'income'

const currentYear = new Date().getFullYear()
const selectedYear = ref(currentYear)
const activeTab = ref('mappings')
const loading = ref(false)
const mappingsLoading = ref(false)
const manualLoading = ref(false)
const summary = ref<RevenueSummary | null>(null)
const mappings = ref<RevenueMapping[]>([])
const selectedSourceType = ref('')
const manualMonth = ref(`${currentYear}-${String(new Date().getMonth() + 1).padStart(2, '0')}`)
const manualEntries = ref<RevenueManualEntryDTO[]>([])
const importRecords = ref<RevenueImportRecord[]>([])
const importRecordsLoading = ref(false)
const businessLines = ref<BusinessLineOption[]>([])
const projects = ref<ProjectOption[]>([])
const importFiles = reactive<Record<ImportKind, File | null>>({ cost: null, income: null })
const importResults = reactive<Record<ImportKind, RevenueImportResult | null>>({ cost: null, income: null })
const importing = ref<ImportKind | null>(null)

const mappingDialogVisible = ref(false)
const editingMapping = ref<RevenueMapping | null>(null)
const mappingForm = reactive({ projectId: undefined as number | undefined, businessLineId: undefined as number | undefined, category: 'delivery' })
const manualDialogVisible = ref(false)
const editingManualId = ref<number | null>(null)
const manualFormRef = ref<FormInstance>()
const manualForm = reactive({
  yearMonth: manualMonth.value,
  projectId: undefined as number | undefined,
  businessLineId: undefined as number | undefined,
  entryType: 'partner_cost' as RevenueEntryType,
  amount: undefined as number | undefined,
  remark: ''
})

const entryTypeLabels: Record<RevenueEntryType, string> = {
  h2_estimate: 'H2预估交付',
  partner_cost: '协力成本',
  server_cost: '服务器成本',
  other_cost: '其他成本'
}
const categoryLabels: Record<string, string> = { delivery: '交付', sales: '销售', product: '产品' }

const yearOptions = Array.from({ length: 5 }, (_, index) => currentYear - 2 + index)
const sourceTypeOptions = computed(() => Array.from(new Set(mappings.value.map(item => item.sourceType))))
const filteredMappings = computed(() => selectedSourceType.value
  ? mappings.value.filter(item => item.sourceType === selectedSourceType.value)
  : mappings.value)
const totalReceivable = computed(() => (summary.value?.h1Receivable ?? 0) + (summary.value?.h2Receivable ?? 0))
const metricCards = computed(() => [
  { label: '年度累计营收', value: formatWan(totalReceivable.value, 1), tone: 'blue' },
  { label: 'H2预估', value: formatWan(summary.value?.h2Estimate, 1), tone: 'purple' },
  { label: '累计成本', value: formatWan(summary.value?.totalCost, 1), tone: 'orange' },
  { label: '毛利', value: formatWan(summary.value?.profit, 1), tone: profitTone(summary.value?.profit) },
  { label: '毛利率', value: totalReceivable.value > 0 ? formatRate(summary.value?.profitRate) : '—', tone: rateTone(summary.value?.profitRate, totalReceivable.value) }
])
const manualProjects = computed(() => manualForm.businessLineId
  ? projects.value.filter(item => item.businessLineId === manualForm.businessLineId)
  : projects.value)
const mappingProjects = computed(() => mappingForm.businessLineId
  ? projects.value.filter(item => item.businessLineId === mappingForm.businessLineId)
  : projects.value)
const trendMax = computed(() => Math.max(1, ...(summary.value?.monthlyTrend ?? []).flatMap(item => [item.income, item.cost])))
const summaryRows = computed<SummaryRow[]>(() => {
  const rows: SummaryRow[] = (summary.value?.businessLines ?? []).flatMap(businessLine => {
    if (businessLine.type === 'project_breakdown' && businessLine.projects?.length) {
      return [
        { kind: 'business-line' as const, businessLine },
        ...businessLine.projects.map(project => ({ kind: 'project' as const, businessLine, project }))
      ]
    }
    return [{ kind: 'business-line' as const, businessLine }]
  })
  if (summary.value) rows.push({ kind: 'total' })
  return rows
})

function formatWan(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined) return '—'
  return (value / 10000).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function formatRate(value: number | null | undefined) {
  if (value === null || value === undefined) return '—'
  return `${(value * 100).toFixed(2)}%`
}

function profitTone(value: number | null | undefined) {
  if (value === null || value === undefined) return ''
  return value >= 0 ? 'green' : 'red'
}

function rateTone(value: number | null | undefined, receivable: number | null | undefined) {
  if (value === null || value === undefined || !receivable) return ''
  return value >= 0 ? 'green' : 'red'
}

function formatHours(value: number | null | undefined) {
  if (value === null || value === undefined) return '—'
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function getEntryTypeLabel(value: string) {
  return entryTypeLabels[value as RevenueEntryType] || value
}

function profitClass(value: number | null | undefined) {
  if (value === null || value === undefined) return ''
  return value >= 0 ? 'positive' : 'negative'
}

function cellValue(row: CellRow, key: string): number | null | undefined {
  if (row.kind === 'business-line') return (row.businessLine as unknown as Record<string, number | null | undefined>)[key]
  if (row.kind === 'project') return (row.project as unknown as Record<string, number | null | undefined>)[key]
  return (summary.value as unknown as Record<string, number | null | undefined> | null)?.[key]
}

function cellWan(row: CellRow, key: string) {
  return formatWan(cellValue(row, key))
}

function cellHours(row: CellRow, key: string) {
  return formatHours(cellValue(row, key))
}

function cellRate(row: CellRow) {
  return formatRate(cellValue(row, 'profitRate'))
}

function extractList<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) return payload as T[]
  if (!payload || typeof payload !== 'object') return []
  const record = payload as { records?: unknown; data?: unknown }
  if (Array.isArray(record.records)) return record.records as T[]
  if (Array.isArray(record.data)) return record.data as T[]
  if (record.data && typeof record.data === 'object') {
    const nested = record.data as { records?: unknown }
    if (Array.isArray(nested.records)) return nested.records as T[]
  }
  return []
}

function flattenProjects(nodes: ProjectTreeNode[], result: ProjectOption[] = []) {
  nodes.forEach(node => {
    result.push({ id: node.id, name: node.name, businessLineId: node.businessLineId })
    if (node.children?.length) flattenProjects(node.children, result)
  })
  return result
}

async function loadSummary() {
  loading.value = true
  try {
    summary.value = await api.getRevenueSummary(selectedYear.value)
  } catch {
    ElMessage.error('营收汇总加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMappings() {
  mappingsLoading.value = true
  try {
    mappings.value = await api.getRevenueMappings()
  } catch {
    ElMessage.error('项目映射加载失败')
  } finally {
    mappingsLoading.value = false
  }
}

async function loadManualEntries() {
  manualLoading.value = true
  try {
    manualEntries.value = await api.getRevenueManualEntries(manualMonth.value)
  } catch {
    ElMessage.error('手动维护项加载失败')
  } finally {
    manualLoading.value = false
  }
}

async function loadImportRecords() {
  importRecordsLoading.value = true
  try {
    importRecords.value = await api.getRevenueImportRecords()
  } catch {
    ElMessage.error('导入历史加载失败')
  } finally {
    importRecordsLoading.value = false
  }
}

async function loadOptions() {
  try {
    const [businessLinePayload, projectPayload] = await Promise.all([
      api.getBusinessLines({ size: 999 }),
      api.getProjectTree()
    ])
    businessLines.value = extractList<BusinessLineOption>(businessLinePayload)
    const tree = extractList<ProjectTreeNode>(projectPayload)
    projects.value = flattenProjects(tree)
  } catch {
    ElMessage.error('营收关联选项加载失败')
  }
}

async function loadPage() {
  await Promise.all([loadSummary(), loadMappings(), loadManualEntries(), loadOptions(), loadImportRecords()])
}

function rowClassName({ row }: { row: SummaryRow }) {
  if (row.kind === 'total') return 'total-row'
  return row.kind === 'business-line' ? 'business-line-row' : 'project-row'
}

function mappingRowClassName({ row }: { row: RevenueMapping }) {
  return !row.projectId && !row.businessLineId ? 'mapping-warning-row' : ''
}

function openMappingEdit(row: RevenueMapping) {
  editingMapping.value = row
  mappingForm.projectId = row.projectId ?? undefined
  mappingForm.businessLineId = row.businessLineId ?? undefined
  mappingForm.category = row.category || 'delivery'
  mappingDialogVisible.value = true
}

async function saveMapping() {
  if (!editingMapping.value || !mappingForm.businessLineId) {
    ElMessage.warning('请选择业务线')
    return
  }
  try {
    await api.updateRevenueMapping(editingMapping.value.id, {
      projectId: mappingForm.projectId,
      businessLineId: mappingForm.businessLineId,
      category: mappingForm.category
    })
    ElMessage.success('映射已更新')
    mappingDialogVisible.value = false
    await loadMappings()
    await loadSummary()
  } catch {
    ElMessage.error('映射更新失败')
  }
}

function syncManualBusinessLine() {
  if (!manualForm.projectId) return
  const project = projects.value.find(item => item.id === manualForm.projectId)
  if (project) manualForm.businessLineId = project.businessLineId
}

function onManualBusinessLineChange() {
  if (!manualForm.projectId) return
  const project = projects.value.find(item => item.id === manualForm.projectId)
  if (project && project.businessLineId !== manualForm.businessLineId) {
    manualForm.projectId = undefined
  }
}

function openManualAdd() {
  editingManualId.value = null
  manualForm.yearMonth = manualMonth.value
  manualForm.projectId = undefined
  manualForm.businessLineId = undefined
  manualForm.entryType = 'partner_cost'
  manualForm.amount = undefined
  manualForm.remark = ''
  manualDialogVisible.value = true
}

function openManualEdit(row: RevenueManualEntryDTO) {
  editingManualId.value = row.id
  manualForm.yearMonth = row.yearMonth
  manualForm.projectId = row.projectId ?? undefined
  manualForm.businessLineId = row.businessLineId
  manualForm.entryType = row.entryType
  manualForm.amount = row.amount
  manualForm.remark = row.remark || ''
  manualDialogVisible.value = true
}

async function saveManualEntry() {
  if (!manualFormRef.value) return
  const valid = await manualFormRef.value.validate().catch(() => false)
  if (!valid || manualForm.amount === undefined || !manualForm.businessLineId) return
  try {
    if (editingManualId.value) {
      await api.updateRevenueManualEntry(editingManualId.value, {
        id: editingManualId.value,
        yearMonth: manualForm.yearMonth,
        projectId: manualForm.projectId ?? null,
        businessLineId: manualForm.businessLineId,
        entryType: manualForm.entryType,
        amount: manualForm.amount,
        remark: manualForm.remark || null
      })
    } else {
      await api.createRevenueManualEntry({
        yearMonth: manualForm.yearMonth,
        projectId: manualForm.projectId ?? null,
        businessLineId: manualForm.businessLineId,
        entryType: manualForm.entryType,
        amount: manualForm.amount,
        remark: manualForm.remark || null
      })
    }
    ElMessage.success(editingManualId.value ? '维护项已更新' : '维护项已新增')
    manualDialogVisible.value = false
    await Promise.all([loadManualEntries(), loadSummary()])
  } catch {
    ElMessage.error(editingManualId.value ? '维护项更新失败' : '维护项新增失败')
  }
}

async function deleteManualEntry(row: RevenueManualEntryDTO) {
  try {
    await ElMessageBox.confirm('确定删除这条手动维护项吗？', '删除确认', { type: 'warning' })
    await api.deleteRevenueManualEntry(row.id)
    ElMessage.success('维护项已删除')
    await Promise.all([loadManualEntries(), loadSummary()])
  } catch (error: unknown) {
    if (error !== 'cancel') ElMessage.error('维护项删除失败')
  }
}

function selectImportFile(kind: ImportKind, file: UploadFile) {
  importFiles[kind] = file.raw ?? null
  importResults[kind] = null
}

function handleCostFileChange(file: UploadFile) {
  selectImportFile('cost', file)
}

function handleIncomeFileChange(file: UploadFile) {
  selectImportFile('income', file)
}

async function runImport(kind: ImportKind) {
  const file = importFiles[kind]
  if (!file) {
    ElMessage.warning('请先选择 Excel 文件')
    return
  }
  importing.value = kind
  try {
    importResults[kind] = kind === 'cost'
      ? await api.importCostExcel(file)
      : await api.importIncomeExcel(file)
    ElMessage.success('导入处理完成')
    await Promise.all([loadSummary(), loadMappings(), loadImportRecords()])
  } catch {
    ElMessage.error('Excel 导入失败')
  } finally {
    importing.value = null
  }
}

onMounted(loadPage)
</script>

<template>
  <div class="revenue-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">REVENUE MANAGEMENT</span>
        <h2>营收管理</h2>
        <p>查看年度营收、成本与业务线盈利情况，维护数据映射和手动调整项。</p>
      </div>
      <div class="head-actions">
        <el-select v-model="selectedYear" aria-label="选择年份" style="width: 130px" @change="loadSummary">
          <el-option v-for="year in yearOptions" :key="year" :label="`${year}年`" :value="year" />
        </el-select>
        <el-button :icon="Refresh" @click="loadPage">刷新</el-button>
      </div>
    </header>

    <section class="metrics-grid">
      <el-card v-for="metric in metricCards" :key="metric.label" class="metric-card" shadow="never">
        <div class="metric-label">{{ metric.label }}</div>
        <div :class="['metric-value', metric.tone]">{{ metric.value }}<small v-if="metric.label !== '毛利率'">万元</small></div>
        <div class="metric-year">{{ selectedYear }}年度</div>
      </el-card>
    </section>

    <section class="panel trend-panel">
      <div class="section-head"><div><h3>月度趋势</h3><p>按月对比营收与成本，单位：万元</p></div></div>
      <div v-if="summary?.monthlyTrend?.length" class="trend-list">
        <div v-for="item in summary.monthlyTrend" :key="item.month" class="trend-row">
          <span class="trend-month">{{ item.month.slice(5) }}月</span>
          <div class="trend-bars">
            <div class="bar-line"><span class="bar-label">营收</span><span class="bar-track"><span class="bar income" :style="{ width: `${item.income / trendMax * 100}%` }" /></span><strong>{{ formatWan(item.income, 1) }}</strong></div>
            <div class="bar-line"><span class="bar-label">成本</span><span class="bar-track"><span class="bar cost" :style="{ width: `${item.cost / trendMax * 100}%` }" /></span><strong>{{ formatWan(item.cost, 1) }}</strong></div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无月度数据" :image-size="70" />
    </section>

    <section class="panel summary-panel">
      <div class="section-head"><div><h3>业务线盈利明细</h3><p>金额单位：万元，工时单位：人月</p></div></div>
      <div class="table-scroll">
        <el-table :data="summaryRows" :row-class-name="rowClassName" empty-text="暂无业务线数据" class="revenue-table">
          <el-table-column type="expand" width="46">
            <template #default="scope">
              <div v-if="scope.row.kind === 'business-line' && scope.row.businessLine.months?.length" class="month-detail">
                <h4>{{ scope.row.businessLine.businessLineName }}月度明细</h4>
                <el-table :data="scope.row.businessLine.months" size="small">
                  <el-table-column prop="month" label="月份" width="120" />
                  <el-table-column label="营收(万)" width="130"><template #default="detail">{{ formatWan(detail.row.income) }}</template></el-table-column>
                  <el-table-column label="成本(万)" width="130"><template #default="detail">{{ formatWan(detail.row.cost) }}</template></el-table-column>
                </el-table>
              </div>
              <div v-else-if="scope.row.kind === 'project'" class="month-detail">
                <h4>{{ scope.row.project.projectName }}月度明细</h4>
                <el-table :data="scope.row.project.months" size="small">
                  <el-table-column prop="month" label="月份" width="120" />
                  <el-table-column label="营收(万)" width="130"><template #default="detail">{{ formatWan(detail.row.income) }}</template></el-table-column>
                  <el-table-column label="工时(人月)" width="130"><template #default="detail">{{ formatHours(detail.row.hours) }}</template></el-table-column>
                  <el-table-column label="成本(万)" width="130"><template #default="detail">{{ formatWan(detail.row.cost) }}</template></el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="名称" min-width="190">
            <template #default="scope"><strong :class="{ 'line-name': scope.row.kind === 'business-line' || scope.row.kind === 'total' }">{{ scope.row.kind === 'business-line' ? scope.row.businessLine.businessLineName : scope.row.kind === 'total' ? '合计' : `　${scope.row.project.projectName}` }}</strong></template>
          </el-table-column>
          <el-table-column label="H1交付(万)" min-width="115"><template #default="scope">{{ cellWan(scope.row, 'h1Receivable') }}</template></el-table-column>
          <el-table-column label="H1工时(人月)" min-width="115"><template #default="scope">{{ cellHours(scope.row, 'h1Hours') }}</template></el-table-column>
          <el-table-column label="H1工时成本(万)" min-width="130"><template #default="scope">{{ cellWan(scope.row, 'h1DeliveryCost') }}</template></el-table-column>
          <el-table-column label="H2交付(万)" min-width="115"><template #default="scope">{{ cellWan(scope.row, 'h2Receivable') }}</template></el-table-column>
          <el-table-column label="H2预估(万)" min-width="115"><template #default="scope">{{ cellWan(scope.row, 'h2Estimate') }}</template></el-table-column>
          <el-table-column label="H2工时(人月)" min-width="115"><template #default="scope">{{ cellHours(scope.row, 'h2Hours') }}</template></el-table-column>
          <el-table-column label="H2工时成本(万)" min-width="130"><template #default="scope">{{ cellWan(scope.row, 'h2DeliveryCost') }}</template></el-table-column>
          <el-table-column label="协力(万)" min-width="105"><template #default="scope">{{ cellWan(scope.row, 'partnerCost') }}</template></el-table-column>
          <el-table-column label="服务器(万)" min-width="115"><template #default="scope">{{ cellWan(scope.row, 'serverCost') }}</template></el-table-column>
          <el-table-column label="其他(万)" min-width="105"><template #default="scope">{{ cellWan(scope.row, 'otherCost') }}</template></el-table-column>
          <el-table-column label="合计成本(万)" min-width="125"><template #default="scope">{{ cellWan(scope.row, 'totalCost') }}</template></el-table-column>
          <el-table-column label="毛利(万)" min-width="110"><template #default="scope"><span :class="profitClass(cellValue(scope.row, 'profit'))">{{ cellWan(scope.row, 'profit') }}</span></template></el-table-column>
          <el-table-column label="毛利率" min-width="100"><template #default="scope"><span :class="profitClass(cellValue(scope.row, 'profitRate'))">{{ cellRate(scope.row) }}</span></template></el-table-column>
        </el-table>
      </div>
    </section>

    <section class="panel management-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="项目映射" name="mappings">
          <div class="tab-toolbar"><el-select v-model="selectedSourceType" clearable placeholder="按来源类型筛选" style="width: 220px"><el-option v-for="sourceType in sourceTypeOptions" :key="sourceType" :label="sourceType" :value="sourceType" /></el-select><el-button :icon="Refresh" @click="loadMappings">刷新</el-button></div>
          <div class="table-scroll">
            <el-table v-loading="mappingsLoading" :data="filteredMappings" :row-class-name="mappingRowClassName" class="revenue-table">
              <el-table-column prop="sourceType" label="来源类型" min-width="130" />
              <el-table-column prop="sourceName" label="来源名称" min-width="270" show-overflow-tooltip />
              <el-table-column prop="projectId" label="项目ID" width="100"><template #default="scope">{{ scope.row.projectId ?? '未设置' }}</template></el-table-column>
              <el-table-column prop="businessLineId" label="业务线ID" width="110"><template #default="scope">{{ scope.row.businessLineId ?? '未设置' }}</template></el-table-column>
              <el-table-column label="分类" width="110"><template #default="scope">{{ categoryLabels[scope.row.category] || scope.row.category }}</template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.status === 1 ? 'success' : 'info'" size="small">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openMappingEdit(scope.row)">编辑</el-button></template></el-table-column>
              <template #empty><el-empty description="暂无映射数据" :image-size="70" /></template>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="手动维护" name="manual">
          <div class="tab-toolbar"><el-date-picker v-model="manualMonth" type="month" value-format="YYYY-MM" placeholder="选择月份" style="width: 180px" @change="loadManualEntries" /><el-button type="primary" :icon="Plus" @click="openManualAdd">新增维护项</el-button></div>
          <div class="table-scroll">
            <el-table v-loading="manualLoading" :data="manualEntries" class="revenue-table">
              <el-table-column prop="yearMonth" label="月份" width="120" />
              <el-table-column label="类型" min-width="150"><template #default="scope">{{ getEntryTypeLabel(scope.row.entryType) }}</template></el-table-column>
              <el-table-column label="金额(万)" width="130"><template #default="scope">{{ formatWan(scope.row.amount) }}</template></el-table-column>
              <el-table-column prop="remark" label="备注" min-width="240" show-overflow-tooltip />
              <el-table-column label="操作" width="140" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openManualEdit(scope.row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="deleteManualEntry(scope.row)">删除</el-button></template></el-table-column>
              <template #empty><el-empty description="该月份暂无维护项" :image-size="70" /></template>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="导入" name="imports">
          <div class="import-grid">
            <div v-for="kind in (['cost', 'income'] as ImportKind[])" :key="kind" class="import-box">
              <div class="import-icon"><el-icon><Upload /></el-icon></div>
              <h4>{{ kind === 'cost' ? '成本导入' : '营收导入' }}</h4>
              <p>选择 .xlsx 或 .xls 文件后开始导入</p>
              <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx,.xls" :on-change="kind === 'cost' ? handleCostFileChange : handleIncomeFileChange"><el-button>选择文件</el-button></el-upload>
              <span v-if="importFiles[kind]" class="file-name">{{ importFiles[kind]?.name }}</span>
              <el-button class="import-button" type="primary" :loading="importing === kind" @click="runImport(kind)">开始导入</el-button>
              <el-alert v-if="importResults[kind]" class="import-result" type="success" :closable="false" show-icon>
                成功 {{ importResults[kind]?.successCount }} 条，新增映射 {{ importResults[kind]?.newMappingCount }} 条，待处理映射 {{ importResults[kind]?.pendingMappingCount }} 条
              </el-alert>
              <el-alert v-if="importResults[kind]?.errors.length" class="import-result" type="warning" :closable="false" title="部分数据未导入" show-icon><template #default><div v-for="error in importResults[kind]?.errors" :key="error">{{ error }}</div></template></el-alert>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="导入历史" name="history">
          <div class="tab-toolbar"><el-button :icon="Refresh" @click="loadImportRecords">刷新</el-button></div>
          <div class="table-scroll">
            <el-table v-loading="importRecordsLoading" :data="importRecords" class="revenue-table">
              <el-table-column label="时间" width="180"><template #default="scope">{{ scope.row.createdAt }}</template></el-table-column>
              <el-table-column label="类型" width="100"><template #default="scope"><el-tag size="small" :type="scope.row.importType === 'cost' ? 'warning' : 'success'">{{ scope.row.importType === 'cost' ? '成本' : '营收' }}</el-tag></template></el-table-column>
              <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
              <el-table-column prop="successCount" label="成功" width="90" />
              <el-table-column prop="newMappingCount" label="新增映射" width="100" />
              <el-table-column prop="pendingMappingCount" label="待处理" width="90" />
              <el-table-column label="错误" width="90"><template #default="scope"><span :class="scope.row.errorCount > 0 ? 'negative' : ''">{{ scope.row.errorCount }}</span></template></el-table-column>
              <template #empty><el-empty description="暂无导入记录" :image-size="70" /></template>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="mappingDialogVisible" title="编辑项目映射" width="500px">
      <el-alert v-if="editingMapping && !editingMapping.projectId && !editingMapping.businessLineId" title="该来源尚未关联项目或业务线" type="warning" :closable="false" show-icon class="dialog-alert" />
      <el-form label-width="90px">
        <el-form-item label="来源名称"><span class="form-readonly">{{ editingMapping?.sourceName }}</span></el-form-item>
        <el-form-item label="项目"><el-select v-model="mappingForm.projectId" clearable filterable placeholder="可选项目" style="width: 100%"><el-option v-for="project in mappingProjects" :key="project.id" :label="project.name" :value="project.id" /></el-select></el-form-item>
        <el-form-item label="业务线" required><el-select v-model="mappingForm.businessLineId" filterable placeholder="请选择业务线" style="width: 100%" @change="mappingForm.projectId = undefined"><el-option v-for="line in businessLines" :key="line.id" :label="line.name" :value="line.id" /></el-select></el-form-item>
        <el-form-item label="分类"><el-select v-model="mappingForm.category" style="width: 100%"><el-option label="交付" value="delivery" /><el-option label="销售" value="sales" /><el-option label="产品" value="product" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="mappingDialogVisible = false">取消</el-button><el-button type="primary" @click="saveMapping">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="manualDialogVisible" :title="editingManualId ? '编辑手动维护项' : '新增手动维护项'" width="540px">
      <el-form ref="manualFormRef" :model="manualForm" label-width="95px" :rules="{ yearMonth: [{ required: true, message: '请选择月份', trigger: 'change' }], businessLineId: [{ required: true, message: '请选择业务线', trigger: 'change' }], entryType: [{ required: true, message: '请选择类型', trigger: 'change' }], amount: [{ required: true, message: '请输入金额', trigger: 'blur' }] }">
        <el-form-item label="月份" prop="yearMonth"><el-date-picker v-model="manualForm.yearMonth" type="month" value-format="YYYY-MM" style="width: 100%" /></el-form-item>
        <el-form-item label="类型" prop="entryType"><el-select v-model="manualForm.entryType" style="width: 100%"><el-option v-for="(label, type) in entryTypeLabels" :key="type" :label="label" :value="type" /></el-select></el-form-item>
        <el-form-item label="金额(元)" prop="amount"><el-input-number v-model="manualForm.amount" :precision="0" controls-position="right" style="width: 100%" /></el-form-item>
        <el-form-item label="业务线" prop="businessLineId"><el-select v-model="manualForm.businessLineId" filterable placeholder="请选择业务线" style="width: 100%" @change="onManualBusinessLineChange"><el-option v-for="line in businessLines" :key="line.id" :label="line.name" :value="line.id" /></el-select></el-form-item>
        <el-form-item label="项目"><el-select v-model="manualForm.projectId" clearable filterable placeholder="可选项目" style="width: 100%" @change="syncManualBusinessLine"><el-option v-for="project in manualProjects" :key="project.id" :label="project.name" :value="project.id" /></el-select></el-form-item>
        <el-form-item label="备注"><el-input v-model="manualForm.remark" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="manualDialogVisible = false">取消</el-button><el-button type="primary" @click="saveManualEntry">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.revenue-page { display: flex; flex-direction: column; gap: 18px; min-width: 0; padding-bottom: 24px; }
.page-head, .panel { background: #fff; border: 1px solid var(--gray-200); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 22px 24px; }
.page-head h2 { margin: 4px 0 6px; color: var(--gray-800); font-size: 22px; }.page-head p, .section-head p { margin: 0; color: var(--gray-500); font-size: 13px; }.eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .1em; }.head-actions, .tab-toolbar { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }.metric-card { border: 1px solid var(--gray-200); border-radius: var(--radius-lg); }.metric-card :deep(.el-card__body) { padding: 18px 20px; }.metric-label { color: var(--gray-500); font-size: 13px; }.metric-value { margin-top: 10px; color: var(--gray-800); font-size: 27px; font-weight: 700; line-height: 1.2; }.metric-value small { margin-left: 5px; font-size: 12px; font-weight: 500; }.metric-value.blue { color: var(--primary); }.metric-value.orange { color: var(--warning); }.metric-value.purple { color: #7c5cd6; }.metric-value.green, .positive { color: var(--success); }.metric-value.red, .negative { color: var(--danger); }.metric-year { margin-top: 7px; color: var(--gray-400); font-size: 12px; }
.panel { padding: 20px; }.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }.section-head h3 { margin: 0 0 4px; color: var(--gray-800); font-size: 17px; }.trend-list { display: flex; flex-direction: column; gap: 13px; }.trend-row { display: flex; align-items: center; gap: 16px; }.trend-month { width: 38px; color: var(--gray-600); font-size: 13px; }.trend-bars { display: flex; flex: 1; flex-direction: column; gap: 6px; }.bar-line { display: flex; align-items: center; gap: 9px; min-width: 0; color: var(--gray-600); font-size: 12px; }.bar-label { width: 30px; }.bar-track { height: 9px; flex: 1; overflow: hidden; background: var(--gray-100); border-radius: 5px; }.bar { display: block; height: 100%; min-width: 2px; border-radius: inherit; }.bar.income { background: var(--primary); }.bar.cost { background: var(--warning); }.bar-line strong { width: 78px; color: var(--gray-700); font-size: 12px; font-weight: 500; text-align: right; }
.table-scroll { width: 100%; overflow-x: auto; }.revenue-table { min-width: 1780px; }.revenue-table :deep(.business-line-row td) { background: #f8fbff; }.revenue-table :deep(.business-line-row:hover td) { background: #f1f6ff !important; }.revenue-table :deep(.total-row td) { background: #f5f7fa; font-weight: 600; }.revenue-table :deep(.total-row:hover td) { background: #eef1f5 !important; }.revenue-table :deep(.mapping-warning-row td) { background: #fff8e6; }.revenue-table :deep(.mapping-warning-row:hover td) { background: #fff1cc !important; }.line-name { color: var(--gray-800); }.project-row .line-name { color: var(--gray-700); font-weight: 500; }.month-detail { padding: 12px 34px 16px 58px; background: var(--gray-50); }.month-detail h4 { margin: 0 0 10px; color: var(--gray-700); font-size: 13px; }
.management-panel :deep(.el-tabs__header) { margin-bottom: 18px; }.tab-toolbar { justify-content: space-between; margin-bottom: 14px; }.tab-toolbar > :first-child { margin-right: auto; }.import-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.import-box { display: flex; min-height: 240px; align-items: center; flex-direction: column; padding: 24px; border: 1px dashed var(--gray-300); border-radius: var(--radius-md); text-align: center; }.import-icon { display: grid; width: 42px; height: 42px; place-items: center; margin-bottom: 10px; border-radius: 50%; background: var(--primary-light); color: var(--primary); font-size: 20px; }.import-box h4 { margin: 0 0 5px; color: var(--gray-800); font-size: 16px; }.import-box p { margin: 0 0 15px; color: var(--gray-500); font-size: 12px; }.file-name { max-width: 100%; margin-top: 9px; overflow: hidden; color: var(--gray-600); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.import-button { margin-top: 14px; }.import-result { width: 100%; margin-top: 14px; text-align: left; }.dialog-alert { margin-bottom: 18px; }.form-readonly { color: var(--gray-600); font-size: 13px; }
@media (max-width: 900px) { .metrics-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .page-head { align-items: flex-start; flex-direction: column; padding: 18px; }.head-actions { width: 100%; }.head-actions .el-select, .head-actions .el-button { flex: 1; }.panel { padding: 15px; }.metrics-grid, .import-grid { grid-template-columns: 1fr; }.metric-value { font-size: 23px; }.trend-row { align-items: flex-start; }.trend-month { padding-top: 2px; }.tab-toolbar { align-items: stretch; flex-direction: column; }.tab-toolbar .el-select, .tab-toolbar .el-button { width: 100%; margin: 0; }.month-detail { padding-left: 18px; } }
</style>
