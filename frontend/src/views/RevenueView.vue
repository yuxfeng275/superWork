<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Upload } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'
import { api } from '@/utils/api'
import type {
  RevenueCellDetail,
  RevenueCostEntry,
  RevenueWorklogEntry,
  RevenueEstimateEntry,
  RevenueImportBatch,
  RevenueMatrix,
  RevenueOpportunityOption,
  RevenueRow,
  RevenueSalesProject
} from '@/types/revenue'

interface BusinessLineOption { id: number; name: string }
interface ProjectOption { id: number; name: string; businessLineId?: number; parentId?: number | null }

interface FlatRow {
  lineId: number
  lineName: string
  lineSpan: number
  sectionType: string
  sectionLabel: string
  sectionSpan: number
  row: RevenueRow
}

const currentYear = new Date().getFullYear()
const year = ref(currentYear)
const loading = ref(false)
const matrix = ref<RevenueMatrix | null>(null)
const displayMode = ref<'merge' | 'hours' | 'cost'>('merge')
const activeTab = ref('matrix')

const errorMessage = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

const formatHours = (value?: number | null) => {
  if (value == null) return '—'
  const num = Number(value)
  if (num === 0) return '—'
  return String(Math.round(num * 100) / 100)
}

const formatWan = (value?: number | null) => {
  if (value == null) return '—'
  const num = Number(value) / 10000
  if (num === 0) return '—'
  return (Math.round(num * 100) / 100).toLocaleString('zh-CN')
}

const flatRows = computed<FlatRow[]>(() => {
  if (!matrix.value) return []
  const result: FlatRow[] = []
  matrix.value.lines.forEach(line => {
    const lineRows: FlatRow[] = []
    line.sections.forEach(section => {
      section.rows.forEach((row, index) => {
        lineRows.push({
          lineId: line.businessLineId,
          lineName: line.businessLineName,
          lineSpan: 0,
          sectionType: section.type,
          sectionLabel: section.type === 'project' ? '项目' : '销售',
          sectionSpan: index === 0 ? section.rows.length : 0,
          row
        })
      })
    })
    lineRows.forEach((item, index) => {
      item.lineSpan = index === 0 ? lineRows.length : 0
    })
    result.push(...lineRows)
  })
  return result
})

const loadMatrix = async () => {
  loading.value = true
  try {
    matrix.value = await api.getRevenueMatrix(year.value)
  } catch (error) {
    ElMessage.error(errorMessage(error, '营收矩阵加载失败'))
  } finally {
    loading.value = false
  }
}

// ---------- 月结 ----------
const closeToggling = ref('')
const toggleMonthClose = async (month: { yearMonth: string; closed: boolean }) => {
  try {
    await ElMessageBox.confirm(
      month.closed
        ? `取消完结后，${month.yearMonth} 将改回展示预估数据，且允许重新导入。确定继续吗？`
        : `完结后 ${month.yearMonth} 展示导入的实际数据并锁定（不可导入、不可改预估）。确定完结吗？`,
      month.closed ? '取消完结' : '标记完结',
      { type: 'warning' }
    )
  } catch {
    return
  }
  closeToggling.value = month.yearMonth
  try {
    if (month.closed) {
      await api.reopenRevenueMonth(month.yearMonth)
      ElMessage.success(`${month.yearMonth} 已取消完结`)
    } else {
      await api.closeRevenueMonth(month.yearMonth)
      ElMessage.success(`${month.yearMonth} 已完结`)
    }
    await loadMatrix()
  } catch (error) {
    ElMessage.error(errorMessage(error, '月结操作失败'))
  } finally {
    closeToggling.value = ''
  }
}

// ---------- 单元格下钻 ----------
const cellDrawer = ref(false)
const cellLoading = ref(false)
const cellContext = reactive({ yearMonth: '', lineId: 0, rowKey: '', title: '', closed: false })
const cellDetail = ref<RevenueCellDetail | null>(null)

const openCell = async (lineId: number, lineName: string, row: RevenueRow, monthIndex: number) => {
  if (row.kind === 'simple') return   // 单行汇总业务线不提供下钻
  const month = matrix.value?.months[monthIndex]
  if (!month) return
  cellContext.yearMonth = month.yearMonth
  cellContext.lineId = lineId
  cellContext.rowKey = row.rowKey
  cellContext.title = `${lineName} / ${row.name} / ${month.yearMonth}`
  cellContext.closed = month.closed
  cellDrawer.value = true
  cellLoading.value = true
  cellDetail.value = null
  try {
    cellDetail.value = await api.getRevenueCellDetail(month.yearMonth, lineId, row.rowKey)
  } catch (error) {
    ElMessage.error(errorMessage(error, '明细加载失败'))
  } finally {
    cellLoading.value = false
  }
}

// ---------- 预估明细 ----------
const estimateDialog = ref(false)
const estimateSaving = ref(false)
const estimateForm = reactive({
  id: undefined as number | undefined,
  description: '',
  personMonths: 1
})

const estimateRowContext = computed(() => {
  const row = flatRows.value.find(item => item.row.rowKey === cellContext.rowKey && item.lineId === cellContext.lineId)
  return row?.row
})

const estimateUnitPrice = computed(() => estimateRowContext.value?.unitPrice ?? null)
const estimatePreviewAmount = computed(() =>
  estimateUnitPrice.value == null ? null : estimateForm.personMonths * estimateUnitPrice.value)

const openEstimateDialog = (entry?: RevenueEstimateEntry) => {
  estimateForm.id = entry?.id
  estimateForm.description = entry?.description || ''
  estimateForm.personMonths = entry ? Number(entry.personMonths) : 1
  estimateDialog.value = true
}

const estimatePayload = () => {
  const row = estimateRowContext.value
  const kindMap: Record<string, { workType: string; salesKind?: string | null }> = {
    project: { workType: 'project' },
    line_pool: { workType: 'project' },
    agg_project: { workType: 'project' },
    sales_specific: { workType: 'sales', salesKind: 'specific' },
    pool: { workType: 'sales', salesKind: 'pool' },
    agg_sales: { workType: 'sales' },
    other: { workType: 'sales', salesKind: 'other' }
  }
  const kind = kindMap[row?.kind || 'project'] || kindMap.project
  return {
    yearMonth: cellContext.yearMonth,
    businessLineId: cellContext.lineId,
    projectId: row?.projectId ?? null,
    salesProjectId: row?.salesProjectId ?? null,
    workType: kind.workType,
    salesKind: kind.salesKind ?? null,
    description: estimateForm.description.trim(),
    personMonths: estimateForm.personMonths
  }
}

const saveEstimate = async () => {
  if (!estimateForm.description.trim() || estimateForm.personMonths <= 0) {
    ElMessage.warning('请填写预估说明和大于 0 的人月')
    return
  }
  estimateSaving.value = true
  try {
    if (estimateForm.id) {
      await api.updateRevenueEstimate(estimateForm.id, estimatePayload())
      ElMessage.success('预估已更新')
    } else {
      await api.createRevenueEstimate(estimatePayload())
      ElMessage.success('预估已添加')
    }
    estimateDialog.value = false
    await loadMatrix()
    cellDetail.value = await api.getRevenueCellDetail(cellContext.yearMonth, cellContext.lineId, cellContext.rowKey)
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估保存失败'))
  } finally {
    estimateSaving.value = false
  }
}

const removeEstimate = async (entry: RevenueEstimateEntry) => {
  try {
    await ElMessageBox.confirm(`删除预估「${entry.description}」？`, '删除预估', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteRevenueEstimate(entry.id)
    ElMessage.success('预估已删除')
    await loadMatrix()
    cellDetail.value = await api.getRevenueCellDetail(cellContext.yearMonth, cellContext.lineId, cellContext.rowKey)
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估删除失败'))
  }
}

// ---------- 完结月手工补录/修改 ----------
const entryDialog = ref(false)
const entrySaving = ref(false)
const entryKind = ref<'worklog' | 'cost'>('worklog')
const entryForm = reactive({
  id: undefined as number | undefined,
  employeeName: '',
  department: '',
  hours: 0.1,
  workNote: '',
  specialNote: '',
  projectNameRaw: '',
  employeeCount: undefined as number | undefined,
  costAmount: 0,
  personMonthCost: undefined as number | undefined
})

const openEntryDialog = (kind: 'worklog' | 'cost', entry?: Partial<RevenueWorklogEntry & RevenueCostEntry>) => {
  entryKind.value = kind
  entryForm.id = entry?.id
  entryForm.employeeName = entry?.employeeName || ''
  entryForm.department = entry?.department || ''
  entryForm.hours = entry ? Number(entry.hours) : 0.1
  entryForm.workNote = entry?.workNote || ''
  entryForm.specialNote = entry?.specialNote || ''
  entryForm.projectNameRaw = entry?.projectNameRaw || (estimateRowContext.value?.name ?? '') + '（手工补录）'
  entryForm.employeeCount = entry?.employeeCount ?? undefined
  entryForm.costAmount = entry ? Number(entry.costAmount) : 0
  entryForm.personMonthCost = entry?.personMonthCost ?? undefined
  entryDialog.value = true
}

const saveEntry = async () => {
  if (entryForm.hours < 0) {
    ElMessage.warning('人月不能为负')
    return
  }
  entrySaving.value = true
  const row = estimateRowContext.value
  const kindPayload = estimatePayload()
  try {
    if (entryKind.value === 'worklog') {
      const payload = {
        yearMonth: cellContext.yearMonth,
        businessLineId: cellContext.lineId,
        projectId: row?.projectId ?? null,
        salesProjectId: row?.salesProjectId ?? null,
        workType: kindPayload.workType,
        salesKind: kindPayload.salesKind ?? null,
        employeeName: entryForm.employeeName.trim(),
        department: entryForm.department.trim(),
        hours: entryForm.hours,
        workNote: entryForm.workNote.trim(),
        specialNote: entryForm.specialNote.trim(),
        projectNameRaw: entryForm.projectNameRaw
      }
      if (entryForm.id) await api.updateRevenueWorklogEntry(entryForm.id, payload)
      else await api.createRevenueWorklogEntry(payload)
    } else {
      const payload = {
        yearMonth: cellContext.yearMonth,
        businessLineId: cellContext.lineId,
        projectId: row?.projectId ?? null,
        salesProjectId: row?.salesProjectId ?? null,
        workType: kindPayload.workType,
        salesKind: kindPayload.salesKind ?? null,
        projectNameRaw: entryForm.projectNameRaw,
        employeeCount: entryForm.employeeCount ?? null,
        hours: entryForm.hours,
        costAmount: entryForm.costAmount,
        personMonthCost: entryForm.personMonthCost ?? null
      }
      if (entryForm.id) await api.updateRevenueCostEntry(entryForm.id, payload)
      else await api.createRevenueCostEntry(payload)
    }
    ElMessage.success('明细已保存')
    entryDialog.value = false
    await loadMatrix()
    cellDetail.value = await api.getRevenueCellDetail(cellContext.yearMonth, cellContext.lineId, cellContext.rowKey)
  } catch (error) {
    ElMessage.error(errorMessage(error, '明细保存失败'))
  } finally {
    entrySaving.value = false
  }
}

const removeEntry = async (kind: 'worklog' | 'cost', id: number) => {
  try {
    await ElMessageBox.confirm('删除该条明细？', '删除明细', { type: 'warning' })
  } catch {
    return
  }
  try {
    if (kind === 'worklog') await api.deleteRevenueWorklogEntry(id)
    else await api.deleteRevenueCostEntry(id)
    ElMessage.success('明细已删除')
    await loadMatrix()
    cellDetail.value = await api.getRevenueCellDetail(cellContext.yearMonth, cellContext.lineId, cellContext.rowKey)
  } catch (error) {
    ElMessage.error(errorMessage(error, '明细删除失败'))
  }
}

// ---------- 数据导入 ----------
const worklogFile = ref<File | null>(null)
const worklogMonth = ref(`${currentYear}-${String(new Date().getMonth() + 1).padStart(2, '0')}`)
const costFile = ref<File | null>(null)
const importing = ref('')
const batches = ref<RevenueImportBatch[]>([])
const batchesLoading = ref(false)

const loadBatches = async () => {
  batchesLoading.value = true
  try {
    batches.value = await api.getRevenueImportBatches()
  } catch (error) {
    ElMessage.error(errorMessage(error, '导入历史加载失败'))
  } finally {
    batchesLoading.value = false
  }
}

const runImport = async (kind: 'worklog' | 'cost') => {
  const file = kind === 'worklog' ? worklogFile.value : costFile.value
  if (!file) {
    ElMessage.warning('请先选择文件')
    return
  }
  importing.value = kind
  try {
    const result = kind === 'worklog'
      ? await api.importRevenueWorklog(file, worklogMonth.value)
      : await api.importRevenueCost(file)
    ElMessage.success(`导入完成：共 ${result.totalCount} 行，成功 ${result.successCount} 行，待映射 ${result.pendingCount} 行`)
    worklogFile.value = null
    costFile.value = null
    await Promise.all([loadBatches(), loadMatrix(), loadPending()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '导入失败'))
  } finally {
    importing.value = ''
  }
}

// ---------- 待映射 ----------
const pendingLoading = ref(false)
const pendingWorklog = ref<import('@/types/revenue').RevenueWorklogEntry[]>([])
const pendingCost = ref<import('@/types/revenue').RevenueCostEntry[]>([])
const businessLines = ref<BusinessLineOption[]>([])
const projects = ref<ProjectOption[]>([])
const resolveDrafts = reactive(new Map<string, { businessLineId?: number; projectId?: number }>())

const normalizeRecords = <T>(payload: unknown): T[] => {
  if (Array.isArray(payload)) return payload as T[]
  if (!payload || typeof payload !== 'object') return []
  const envelope = payload as { records?: T[]; data?: T[] | { records?: T[] } }
  if (Array.isArray(envelope.records)) return envelope.records
  if (Array.isArray(envelope.data)) return envelope.data
  if (envelope.data && Array.isArray(envelope.data.records)) return envelope.data.records
  return []
}

const loadPending = async () => {
  pendingLoading.value = true
  try {
    const [pending, linePayload, projectPayload] = await Promise.all([
      api.getRevenuePending(),
      api.getBusinessLines({ page: 1, size: 100, status: 1 }),
      api.getProjects({ page: 1, size: 500 })
    ])
    pendingWorklog.value = pending.worklog || []
    pendingCost.value = pending.cost || []
    businessLines.value = normalizeRecords<BusinessLineOption>(linePayload)
    projects.value = normalizeRecords<ProjectOption>(projectPayload)
  } catch (error) {
    ElMessage.error(errorMessage(error, '待映射清单加载失败'))
  } finally {
    pendingLoading.value = false
  }
}

const projectsOfLine = (lineId?: number) =>
  projects.value.filter(item => item.businessLineId === lineId)

const resolvePendingRow = async (type: 'worklog' | 'cost', id: number) => {
  const draft = resolveDrafts.get(`${type}-${id}`)
  if (!draft?.businessLineId) {
    ElMessage.warning('请选择归属业务线')
    return
  }
  try {
    await api.resolveRevenuePending(type, id, draft.businessLineId, draft.projectId)
    ElMessage.success('已指定归属')
    await Promise.all([loadPending(), loadMatrix()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '归属保存失败'))
  }
}

// ---------- 销售项目 / 商机关联 ----------
const salesProjects = ref<RevenueSalesProject[]>([])
const opportunityOptions = ref<RevenueOpportunityOption[]>([])

const loadSalesProjects = async () => {
  try {
    const [sp, options] = await Promise.all([
      api.getRevenueSalesProjects(),
      api.getRevenueOpportunityOptions()
    ])
    salesProjects.value = sp
    opportunityOptions.value = options
  } catch {
    salesProjects.value = []
  }
}

const bindOpportunity = async (item: RevenueSalesProject, opportunityId: number | null) => {
  try {
    await api.bindRevenueSalesProject(item.id, opportunityId)
    ElMessage.success('商机关联已保存')
    await Promise.all([loadSalesProjects(), loadMatrix()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '商机关联失败'))
  }
}

const businessLineNameOf = (id: number) =>
  businessLines.value.find(item => item.id === id)?.name || `#${id}`

const handleTabChange = (name: string | number) => {
  if (name === 'import') loadBatches()
  if (name === 'pending') Promise.all([loadPending(), loadSalesProjects()])
}

onMounted(loadMatrix)
</script>

<template>
  <div class="revenue-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">REVENUE MANAGEMENT</span>
        <h2>营收管理</h2>
        <p>工时与成本矩阵：完结月展示导入实际值，未完结月展示预估，点击单元格查看明细。</p>
      </div>
      <div class="head-actions">
        <el-select v-model="year" aria-label="选择年份" style="width: 130px" @change="loadMatrix">
          <el-option v-for="y in [currentYear - 1, currentYear, currentYear + 1]" :key="y" :label="`${y}年`" :value="y" />
        </el-select>
        <el-button :icon="Refresh" aria-label="刷新" @click="loadMatrix" />
      </div>
    </header>

    <el-tabs v-model="activeTab" class="revenue-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="工时 & 成本" name="matrix">
        <template v-if="matrix">
          <section class="overview-strip" aria-label="年度概览">
            <div class="overview-cell"><span>年度总工时</span><strong>{{ formatHours(matrix.overview.totalHours) }}</strong><small>人月</small></div>
            <div class="overview-cell"><span>项目工时</span><strong>{{ formatHours(matrix.overview.projectHours) }}</strong><small>人月</small></div>
            <div class="overview-cell"><span>销售工时</span><strong>{{ formatHours(matrix.overview.salesHours) }}</strong><small>人月</small></div>
            <div class="overview-cell"><span>年度总成本</span><strong>{{ formatWan(matrix.overview.totalCost) }}</strong><small>万元</small></div>
            <div class="overview-cell"><span>综合单价</span><strong>{{ matrix.overview.avgUnitPrice == null ? '—' : formatWan(matrix.overview.avgUnitPrice) }}</strong><small>万/人月</small></div>
            <div class="overview-cell"><span>已完结月份</span><strong>{{ matrix.overview.closedMonthCount }}</strong><small>/ 12</small></div>
          </section>

          <div class="matrix-toolbar">
            <el-radio-group v-model="displayMode" aria-label="展示内容">
              <el-radio-button value="merge">工时 + 成本</el-radio-button>
              <el-radio-button value="hours">仅工时</el-radio-button>
              <el-radio-button value="cost">仅成本</el-radio-button>
            </el-radio-group>
            <span class="matrix-legend">
              <i class="legend-swatch actual" />实际（已完结）
              <i class="legend-swatch estimate" />预估
            </span>
          </div>

          <div class="matrix-scroll">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="col-line">业务线</th>
                  <th class="col-type">类型</th>
                  <th class="col-project">项目</th>
                  <th class="col-price">单价<br><small>万/人月</small></th>
                  <th v-for="(month, index) in matrix.months" :key="month.yearMonth" class="col-month">
                    <button
                      class="month-head"
                      :class="{ closed: month.closed }"
                      :title="month.closed ? '已完结，点击取消完结' : '未完结，点击标记完结'"
                      :disabled="closeToggling === month.yearMonth"
                      @click="toggleMonthClose(month)"
                    >
                      {{ index + 1 }}月
                      <em v-if="month.closed">完</em>
                    </button>
                  </th>
                  <th class="col-total">合计</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in flatRows" :key="item.lineId + '-' + item.row.rowKey">
                  <td v-if="item.lineSpan" class="col-line" :rowspan="item.lineSpan">{{ item.lineName }}</td>
                  <td v-if="item.sectionSpan" class="col-type" :rowspan="item.sectionSpan">{{ item.row.kind === 'simple' ? '—' : item.sectionLabel }}</td>
                  <td class="col-project">
                    {{ item.row.name }}
                    <small v-if="item.row.opportunityName" class="opp-tag">商机:{{ item.row.opportunityName }}</small>
                  </td>
                  <td class="col-price">{{ item.row.unitPrice == null ? '—' : formatWan(item.row.unitPrice) }}</td>
                  <td
                    v-for="(cell, monthIndex) in item.row.months"
                    :key="monthIndex"
                    class="col-month cell"
                    :class="[cell.source, { clickable: item.row.kind !== 'simple' }]"
                    @click="openCell(item.lineId, item.lineName, item.row, monthIndex)"
                  >
                    <template v-if="cell.source">
                      <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(cell.cost) }}</span>
                      <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(cell.hours) }}</span>
                      <i v-if="cell.source === 'estimate'" class="estimate-dot">预</i>
                    </template>
                    <span v-else class="cell-empty">—</span>
                  </td>
                  <td class="col-total cell">
                    <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(item.row.totals.cost) }}</span>
                    <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(item.row.totals.hours) }}</span>
                  </td>
                </tr>
                <template v-for="line in matrix.lines" :key="'total-' + line.businessLineId">
                  <tr class="line-total-row">
                    <td class="col-line">{{ line.businessLineName }}</td>
                    <td class="col-type" colspan="2">小计</td>
                    <td class="col-price">—</td>
                    <td v-for="(cell, i) in line.monthTotals" :key="i" class="col-month cell total">
                      <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(cell.cost) }}</span>
                      <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(cell.hours) }}</span>
                    </td>
                    <td class="col-total cell total">
                      <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(line.totals.cost) }}</span>
                      <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(line.totals.hours) }}</span>
                    </td>
                  </tr>
                </template>
                <tr class="grand-total-row">
                  <td class="col-line">合计</td>
                  <td class="col-type" colspan="2"></td>
                  <td class="col-price">—</td>
                  <td v-for="(cell, i) in matrix.monthTotals" :key="i" class="col-month cell total">
                    <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(cell.cost) }}</span>
                    <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(cell.hours) }}</span>
                  </td>
                  <td class="col-total cell total">
                    <span v-if="displayMode !== 'hours'" class="cell-cost">{{ formatWan(matrix.grandTotal.cost) }}</span>
                    <span v-if="displayMode !== 'cost'" class="cell-hours">{{ formatHours(matrix.grandTotal.hours) }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="暂无营收数据，请先在「数据导入」中导入工时与成本明细" />
      </el-tab-pane>

      <el-tab-pane label="数据导入" name="import">
        <div class="import-grid">
          <section class="import-card">
            <h4>工时明细导入</h4>
            <p>工时数据_业务线明细 Excel；同月重复导入替换该月的导入数据（手工补录保留）；已完结月份不可导入。</p>
            <el-date-picker v-model="worklogMonth" type="month" value-format="YYYY-MM" aria-label="工时归属月份" />
            <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx,.xls"
              :on-change="(f: UploadFile) => { worklogFile = f.raw ?? null }">
              <el-button :icon="Upload">选择文件</el-button>
            </el-upload>
            <span v-if="worklogFile" class="file-name">{{ worklogFile.name }}</span>
            <el-button type="primary" :loading="importing === 'worklog'" @click="runImport('worklog')">开始导入</el-button>
          </section>
          <section class="import-card">
            <h4>成本明细导入</h4>
            <p>成本分析_项目 Excel，月份取自文件内「月份」列；同月重复导入替换该月的导入数据（手工补录保留）。</p>
            <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx,.xls"
              :on-change="(f: UploadFile) => { costFile = f.raw ?? null }">
              <el-button :icon="Upload">选择文件</el-button>
            </el-upload>
            <span v-if="costFile" class="file-name">{{ costFile.name }}</span>
            <el-button type="primary" :loading="importing === 'cost'" @click="runImport('cost')">开始导入</el-button>
          </section>
        </div>
        <section class="batch-section">
          <h4>导入历史</h4>
          <el-table v-loading="batchesLoading" :data="batches" class="data-table" empty-text="暂无导入记录">
            <el-table-column prop="createdAt" label="时间" width="180" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.importType === 'worklog' ? 'warning' : 'success'">
                  {{ row.importType === 'worklog' ? '工时' : '成本' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="yearMonth" label="归属月份" width="100" />
            <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
            <el-table-column prop="totalCount" label="解析" width="80" />
            <el-table-column prop="successCount" label="成功" width="80" />
            <el-table-column prop="pendingCount" label="待映射" width="90" />
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="待映射与销售项目" name="pending">
        <section class="pending-section" v-loading="pendingLoading">
          <h4>待映射明细（{{ pendingWorklog.length + pendingCost.length }}）</h4>
          <el-table :data="pendingWorklog" class="data-table" empty-text="暂无待映射工时明细">
            <el-table-column prop="yearMonth" label="月份" width="90" />
            <el-table-column label="类型" width="80"><template #default>工时</template></el-table-column>
            <el-table-column prop="businessLineName" label="原始业务线" min-width="200" show-overflow-tooltip />
            <el-table-column prop="projectNameRaw" label="原始项目" min-width="200" show-overflow-tooltip />
            <el-table-column prop="employeeName" label="姓名" width="100" />
            <el-table-column prop="hours" label="人月" width="90" />
            <el-table-column label="归属业务线" width="180">
              <template #default="{ row }">
                <el-select
                  :model-value="resolveDrafts.get(`worklog-${row.id}`)?.businessLineId"
                  placeholder="选择业务线"
                  @change="(v: number) => resolveDrafts.set(`worklog-${row.id}`, { businessLineId: v })"
                >
                  <el-option v-for="line in businessLines" :key="line.id" :label="line.name" :value="line.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="归属项目" width="180">
              <template #default="{ row }">
                <el-select
                  :model-value="resolveDrafts.get(`worklog-${row.id}`)?.projectId"
                  clearable
                  placeholder="留空=业务线级"
                  :disabled="!resolveDrafts.get(`worklog-${row.id}`)?.businessLineId"
                  @change="(v: number | undefined) => resolveDrafts.set(`worklog-${row.id}`, { ...resolveDrafts.get(`worklog-${row.id}`), projectId: v })"
                >
                  <el-option v-for="p in projectsOfLine(resolveDrafts.get(`worklog-${row.id}`)?.businessLineId)" :key="p.id" :label="p.name" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="resolvePendingRow('worklog', row.id)">确定</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-table :data="pendingCost" class="data-table" style="margin-top: 16px" empty-text="暂无待映射成本明细">
            <el-table-column prop="yearMonth" label="月份" width="90" />
            <el-table-column label="类型" width="80"><template #default>成本</template></el-table-column>
            <el-table-column prop="businessLineName" label="原始业务线" min-width="200" show-overflow-tooltip />
            <el-table-column prop="projectNameRaw" label="原始项目" min-width="200" show-overflow-tooltip />
            <el-table-column prop="hours" label="人月" width="90" />
            <el-table-column label="成本(元)" width="110">
              <template #default="{ row }">{{ row.costAmount }}</template>
            </el-table-column>
            <el-table-column label="归属业务线" width="180">
              <template #default="{ row }">
                <el-select
                  :model-value="resolveDrafts.get(`cost-${row.id}`)?.businessLineId"
                  placeholder="选择业务线"
                  @change="(v: number) => resolveDrafts.set(`cost-${row.id}`, { businessLineId: v })"
                >
                  <el-option v-for="line in businessLines" :key="line.id" :label="line.name" :value="line.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="归属项目" width="180">
              <template #default="{ row }">
                <el-select
                  :model-value="resolveDrafts.get(`cost-${row.id}`)?.projectId"
                  clearable
                  placeholder="留空=业务线级"
                  :disabled="!resolveDrafts.get(`cost-${row.id}`)?.businessLineId"
                  @change="(v: number | undefined) => resolveDrafts.set(`cost-${row.id}`, { ...resolveDrafts.get(`cost-${row.id}`), projectId: v })"
                >
                  <el-option v-for="p in projectsOfLine(resolveDrafts.get(`cost-${row.id}`)?.businessLineId)" :key="p.id" :label="p.name" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="resolvePendingRow('cost', row.id)">确定</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="pending-section" style="margin-top: 24px">
          <h4>销售项目（{{ salesProjects.length }}）</h4>
          <p class="section-note">「京博【销售】」这类具体销售项目在导入时自动注册，可在此手动关联商机。</p>
          <el-table :data="salesProjects" class="data-table" empty-text="暂无销售项目">
            <el-table-column prop="name" label="销售项目" min-width="160" />
            <el-table-column label="业务线" min-width="160">
              <template #default="{ row }">{{ businessLineNameOf(row.businessLineId) }}</template>
            </el-table-column>
            <el-table-column label="关联商机" min-width="240">
              <template #default="{ row }">
                <el-select
                  :model-value="row.opportunityId ?? undefined"
                  clearable
                  filterable
                  placeholder="选择商机"
                  @change="(v: number | undefined) => bindOpportunity(row, v ?? null)"
                >
                  <el-option v-for="o in opportunityOptions" :key="o.id" :label="`${o.name}${o.customer ? ' · ' + o.customer : ''}`" :value="o.id" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="cellDrawer" :title="cellContext.title" size="min(640px, 96vw)" destroy-on-close>
      <div v-loading="cellLoading" class="cell-drawer">
        <template v-if="cellDetail">
          <template v-if="cellDetail.closed">
            <section>
              <div class="estimate-head">
                <h4>工时明细（{{ cellDetail.worklogEntries?.length || 0 }}）</h4>
                <el-button type="primary" size="small" @click="openEntryDialog('worklog')">新增工时</el-button>
              </div>
              <el-table :data="cellDetail.worklogEntries || []" class="data-table" empty-text="该月无工时明细，可点击右上角补录">
                <el-table-column prop="employeeName" label="姓名" width="90" />
                <el-table-column prop="department" label="部门" min-width="130" show-overflow-tooltip />
                <el-table-column prop="hours" label="人月" width="80" />
                <el-table-column prop="workNote" label="工作说明" min-width="200" show-overflow-tooltip />
                <el-table-column label="标签" min-width="120">
                  <template #default="{ row }">
                    <el-tag v-for="tag in (row.tags || '').split(',').filter(Boolean)" :key="tag" size="small" style="margin-right: 4px">{{ tag }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="110">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openEntryDialog('worklog', row)">编辑</el-button>
                    <el-button link type="danger" @click="removeEntry('worklog', row.id)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
            <section style="margin-top: 16px">
              <div class="estimate-head">
                <h4>成本明细（{{ cellDetail.costEntries?.length || 0 }}）</h4>
                <el-button type="primary" size="small" @click="openEntryDialog('cost')">新增成本</el-button>
              </div>
              <el-table :data="cellDetail.costEntries || []" class="data-table" empty-text="该月无成本明细，可点击右上角补录">
                <el-table-column prop="projectNameRaw" label="项目" min-width="160" show-overflow-tooltip />
                <el-table-column prop="employeeCount" label="人数" width="70" />
                <el-table-column prop="hours" label="人月" width="80" />
                <el-table-column label="成本(元)" width="110"><template #default="{ row }">{{ row.costAmount }}</template></el-table-column>
                <el-table-column label="人月成本(元)" width="120"><template #default="{ row }">{{ row.personMonthCost ?? '—' }}</template></el-table-column>
                <el-table-column label="操作" width="110">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openEntryDialog('cost', row)">编辑</el-button>
                    <el-button link type="danger" @click="removeEntry('cost', row.id)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>
          <template v-else>
            <section>
              <div class="estimate-head">
                <h4>预估明细（{{ cellDetail.estimates?.length || 0 }}）</h4>
                <el-button type="primary" size="small" @click="openEstimateDialog()">新增预估</el-button>
              </div>
              <p v-if="estimateUnitPrice != null" class="section-note">
                当前行历史完结单价：{{ formatWan(estimateUnitPrice) }} 万/人月，金额按此自动计算。
              </p>
              <p v-else class="section-note">该行暂无完结历史，预估金额暂不计算。</p>
              <el-table :data="cellDetail.estimates || []" class="data-table" empty-text="暂无预估明细，点击右上角新增">
                <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
                <el-table-column prop="personMonths" label="人月" width="90" />
                <el-table-column label="预估金额(元)" width="120">
                  <template #default="{ row }">{{ row.amount == null ? '—' : row.amount }}</template>
                </el-table-column>
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openEstimateDialog(row)">编辑</el-button>
                    <el-button link type="danger" @click="removeEstimate(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="entryDialog" :title="`${entryForm.id ? '编辑' : '新增'}${entryKind === 'worklog' ? '工时' : '成本'}明细`" width="min(520px, 94vw)">
      <el-form label-position="top">
        <template v-if="entryKind === 'worklog'">
          <el-form-item label="姓名">
            <el-input v-model="entryForm.employeeName" maxlength="50" placeholder="填写人姓名，可留空" />
          </el-form-item>
          <el-form-item label="部门">
            <el-input v-model="entryForm.department" maxlength="100" placeholder="可留空" />
          </el-form-item>
          <el-form-item label="人月">
            <el-input-number v-model="entryForm.hours" :min="0.01" :max="100" :step="0.05" :precision="4" />
          </el-form-item>
          <el-form-item label="工作说明">
            <el-input v-model="entryForm.workNote" type="textarea" :rows="3" maxlength="500" placeholder="商机集合行的标签由工作说明自动识别" />
          </el-form-item>
          <el-form-item label="特殊说明">
            <el-input v-model="entryForm.specialNote" maxlength="200" placeholder="可留空" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="项目名（原始口径）">
            <el-input v-model="entryForm.projectNameRaw" maxlength="200" />
          </el-form-item>
          <el-form-item label="人数">
            <el-input-number v-model="entryForm.employeeCount" :min="0" :max="500" placeholder="可留空" />
          </el-form-item>
          <el-form-item label="人月">
            <el-input-number v-model="entryForm.hours" :min="0" :max="100" :step="0.05" :precision="4" />
          </el-form-item>
          <el-form-item label="工时成本（元）">
            <el-input-number v-model="entryForm.costAmount" :min="0" :precision="2" :step="1000" />
          </el-form-item>
          <el-form-item label="人月成本（元）">
            <el-input-number v-model="entryForm.personMonthCost" :min="0" :precision="2" placeholder="可留空" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="entryDialog = false">取消</el-button>
        <el-button type="primary" :loading="entrySaving" @click="saveEntry">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="estimateDialog" :title="estimateForm.id ? '编辑预估' : '新增预估'" width="min(480px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="说明">
          <el-input v-model="estimateForm.description" maxlength="200" show-word-limit placeholder="例如：黄天鹅物码项目 1 人月" />
        </el-form-item>
        <el-form-item label="人月">
          <el-input-number v-model="estimateForm.personMonths" :min="0.1" :max="100" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="预估金额">
          <span v-if="estimatePreviewAmount != null">{{ estimatePreviewAmount.toFixed(2) }} 元（人月 × 历史完结单价）</span>
          <span v-else class="section-note">暂无完结历史单价，金额暂不计算</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="estimateDialog = false">取消</el-button>
        <el-button type="primary" :loading="estimateSaving" @click="saveEstimate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.revenue-page {
  width: 100%;
  min-width: 0;
  display: grid;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.page-head h2 {
  margin: 4px 0;
}

.page-head p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.overview-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.overview-cell {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #fff;
}

.overview-cell span {
  color: #64748b;
  font-size: 12px;
}

.overview-cell strong {
  font-size: 22px;
}

.overview-cell small {
  color: #94a3b8;
  font-size: 11px;
}

.matrix-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.matrix-legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 12px;
}

.legend-swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  margin-left: 10px;
}

.legend-swatch.actual { background: #f0fdf4; border: 1px solid #86efac; }
.legend-swatch.estimate { background: #eff6ff; border: 1px solid #93c5fd; }

.matrix-scroll {
  overflow-x: auto;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #fff;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.matrix-table th,
.matrix-table td {
  padding: 8px 10px;
  border-bottom: 1px solid #eef2f7;
  border-right: 1px solid #f4f7fb;
  text-align: center;
  white-space: nowrap;
}

.matrix-table thead th {
  position: sticky;
  top: 0;
  background: #f8fafc;
  font-weight: 700;
  color: #475569;
  z-index: 1;
}

.col-line { font-weight: 700; background: #fafcff; }
.col-type { color: #64748b; }
.col-project { text-align: left !important; min-width: 140px; }
.col-price { color: #64748b; }
.col-total { font-weight: 700; background: #fafcff; }

.month-head {
  border: 0;
  background: none;
  cursor: pointer;
  font: inherit;
  color: inherit;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.month-head em {
  font-style: normal;
  font-size: 10px;
  color: #15803d;
  border: 1px solid #86efac;
  border-radius: 4px;
  padding: 0 3px;
}

.cell.clickable { cursor: pointer; }
.cell.clickable:hover { background: #f1f5f9; }
.cell.actual { background: #f0fdf4; }
.cell.estimate { background: #eff6ff; }

.cell-cost { display: block; font-weight: 650; }
.cell-hours { display: block; color: #64748b; font-size: 12px; }
.cell-empty { color: #cbd5e1; }

.estimate-dot {
  font-style: normal;
  font-size: 10px;
  color: #1d4ed8;
  border: 1px solid #93c5fd;
  border-radius: 4px;
  padding: 0 3px;
  margin-left: 4px;
}

.line-total-row td { background: #f8fafc; font-weight: 650; }
.grand-total-row td { background: #f1f5f9; font-weight: 700; }

.opp-tag {
  display: block;
  color: #8b5cf6;
  font-size: 11px;
}

.import-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.import-card {
  display: grid;
  gap: 10px;
  justify-items: start;
  padding: 18px;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #fff;
}

.import-card h4, .batch-section h4, .pending-section h4 { margin: 0; }
.import-card p { margin: 0; color: #64748b; font-size: 12px; }

.file-name { color: #475569; font-size: 12px; }
.section-note { color: #64748b; font-size: 12px; margin: 4px 0 10px; }

.estimate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.estimate-head h4 { margin: 0; }

.data-table { width: 100%; }
</style>
