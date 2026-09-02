<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type {
  DeliveryContractBatch,
  DeliveryCostType,
  DeliveryOtherCost,
  DeliveryPendingContract,
  DeliveryPeriodBlock,
  DeliveryPlan,
  DeliveryProjectRow,
  DeliverySummary,
  DeliverySummaryLine,
  DeliveryUnallocatedItem
} from '@/types/revenue'

interface BusinessLineOption { id: number; name: string; revenueMode?: string }
interface ProjectOption { id: number; name: string; businessLineId?: number }

type PeriodKey = 'h1' | 'h2' | 'ytd'
type PeriodColumnKey = 'delivered' | 'estimated' | 'hours' | 'labor' | 'salesHours' | 'salesCost' | 'other' | 'profit' | 'rate'

/**
 * 归一化窗口视图（金额元、工时人月）：
 * - partnerCost/serverCost 为 null 表示服务端未给出拆分；
 * - 销售语义按行角色区分：项目行取 allocated*（成单销售），小计/合计行取 unallocated*。
 */
interface PeriodView {
  delivered: number
  estimated: number
  projectHours: number
  projectLaborCost: number
  estimatedLaborCost: number
  /** 该窗口销售工时合计（仅业务线/汇总行有值） */
  salesHours: number
  salesCost: number
  allocatedSalesHours: number
  allocatedSalesCost: number
  unallocatedSalesHours: number
  unallocatedSalesCost: number
  partnerCost: number | null
  serverCost: number | null
  otherCost: number
  grossProfit: number
  grossRate: number | null
  trueProfit: number
  trueProfitRate: number | null
}

interface RowContext {
  kind: 'project' | 'line' | 'grand'
  lineId: number
  lineName: string
  name: string
  projectId?: number | null
  isAggregate?: boolean
  oaContract: number | null
  /** 业务线小计行展示的未分配销售说明（不分摊到项目） */
  salesNote: string | null
  /** 业务线级未落项目合同的说明文案（如福田定制），null=无 */
  lineContractNote: string | null
  /** 业务线级未落项目合同总额/已交付/利润（元，无则 0/null） */
  lineUnallocatedContract: number
  lineUnallocatedDelivered: number
  lineUnallocatedProfit: number | null
  /** 交付日期为空合同的说明文案（null=无） */
  noDateNote: string | null
  /** 未分配销售原因明细行（全年，抽屉展示） */
  detailReasons: string[]
  lineSpan: number
  periods: Record<PeriodKey, PeriodView>
}

interface ProfitLine {
  label: string
  value: number | null
  strong?: boolean
  tone?: 'neg'
}

const props = defineProps<{
  year: number
  /** 所在 tab 是否处于激活态：激活时拉取数据、切换年份时联动刷新 */
  active: boolean
}>()

const errorMessage = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

const num = (value?: number | null) => {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}

const formatHours = (value?: number | null) => {
  if (value == null) return '—'
  const n = Number(value)
  if (n === 0) return '—'
  return String(Math.round(n * 100) / 100)
}

const formatWan = (value?: number | null) => {
  if (value == null) return '—'
  const n = Number(value) / 10000
  if (n === 0) return '—'
  return (Math.round(n * 100) / 100).toLocaleString('zh-CN')
}

const formatRate = (value?: number | null) => {
  if (value == null) return '—'
  return `${Math.round(Number(value) * 100) / 100}%`
}

// ---------- 列提示与业务线级合同说明 ----------
/** H1/H2/YTD 头部提示：已交付按合同交付日期归集 */
const groupTip = (group: { key: PeriodKey; label: string }) =>
  `${group.label}：实际已交付金额按合同交付日期（delivery_date）归入本窗口，年份与交付日期年份一致`

/** 业务线级合同说明 tooltip（金额已含在上方合计/明细中） */
const lineContractTip = (row: RowContext) => {
  if (!row.lineContractNote) return undefined
  const parts: string[] = [row.lineContractNote]
  parts.push('该类合同不生成项目行，在业务线/整表合计行列示；合同先归业务线再按品牌归属项目，前端不再跨业务线重算。')
  return parts.join('。')
}

// ---------- 汇总数据加载 ----------
const summaryLoading = ref(false)
const summary = ref<DeliverySummary | null>(null)
// 利润口径：true=含预估（营收含预估交付、成本含预估工时成本），false=只看实际
const includeEstimate = ref(true)
let summarySeq = 0

const refreshSummary = async () => {
  const seq = ++summarySeq
  summaryLoading.value = true
  try {
    const data = await api.getDeliverySummary({ year: props.year, includeEstimate: includeEstimate.value })
    if (seq !== summarySeq) return
    summary.value = data
  } catch (error) {
    if (seq !== summarySeq) return
    ElMessage.error(errorMessage(error, '交付营收汇总加载失败'))
  } finally {
    if (seq === summarySeq) summaryLoading.value = false
  }
}

// ---------- 业务线 / 项目选项（预估交付与待映射归属选择共用） ----------
const businessLines = ref<BusinessLineOption[]>([])
const projects = ref<ProjectOption[]>([])
let optionsLoaded = false

const normalizeRecords = <T>(payload: unknown): T[] => {
  if (Array.isArray(payload)) return payload as T[]
  if (!payload || typeof payload !== 'object') return []
  const envelope = payload as { records?: T[]; data?: T[] | { records?: T[] } }
  if (Array.isArray(envelope.records)) return envelope.records
  if (Array.isArray(envelope.data)) return envelope.data
  if (envelope.data && Array.isArray(envelope.data.records)) return envelope.data.records
  return []
}

const ensureOptions = async () => {
  if (optionsLoaded) return
  try {
    const [linePayload, projectPayload] = await Promise.all([
      api.getBusinessLines({ page: 1, size: 100, status: 1 }),
      api.getProjects({ page: 1, size: 500 })
    ])
    businessLines.value = normalizeRecords<BusinessLineOption>(linePayload)
    projects.value = normalizeRecords<ProjectOption>(projectPayload)
    optionsLoaded = true
  } catch (error) {
    ElMessage.error(errorMessage(error, '业务线与项目选项加载失败'))
  }
}

// ---------- 合同导入（批次历史）与待映射 ----------
const importFile = ref<File | null>(null)
const importing = ref(false)
const batchesLoading = ref(false)
const batches = ref<DeliveryContractBatch[]>([])
const pendingLoading = ref(false)
const pendingContracts = ref<DeliveryPendingContract[]>([])
// 归属值：number=真实项目；'line'=业务线级（不落具体项目，福田等）
const pendingDrafts = reactive({} as Record<number, number | 'line' | undefined>)
const resolvingId = ref<number | null>(null)

const loadBatches = async () => {
  batchesLoading.value = true
  try {
    batches.value = await api.getDeliveryContractBatches()
  } catch (error) {
    ElMessage.error(errorMessage(error, '合同导入历史加载失败'))
  } finally {
    batchesLoading.value = false
  }
}

const loadPending = async () => {
  pendingLoading.value = true
  try {
    pendingContracts.value = await api.getPendingDeliveryContracts()
  } catch (error) {
    ElMessage.error(errorMessage(error, '待映射合同加载失败'))
  } finally {
    pendingLoading.value = false
  }
}

const runContractImport = async () => {
  const file = importFile.value
  if (!file) {
    ElMessage.warning('请先选择要导入的合同 Excel')
    return
  }
  importing.value = true
  try {
    const result = await api.importDeliveryContracts(file)
    importFile.value = null
    ElMessage.success(`导入完成：共 ${result.total} 行，成功 ${result.success} 行，待映射 ${result.pendingCount} 行`)
    await Promise.all([loadBatches(), refreshSummary()])
    if (result.pendingCount > 0) {
      ElMessage.warning(`有 ${result.pendingCount} 行合同未匹配项目，请在下方「待映射合同」中指定归属`)
      await Promise.all([loadPending(), scrollToPending()])
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '合同导入失败'))
  } finally {
    importing.value = false
  }
}

/** 待映射合同的业务线名：优先 bizLineId 查业务线，其次原始收款款项类型文本 */
const pendingLineName = (row: DeliveryPendingContract) =>
  businessLines.value.find(item => item.id === row.bizLineId)?.name
  ?? row.bizLineRaw
  ?? '—'

/** 归属项目候选：解析出业务线则只给该线项目（会员通等聚合线为空则仅业务线级）；未解析出业务线才给全量项目 */
const pendingProjectsOf = (row: DeliveryPendingContract) => {
  if (row.bizLineId != null) {
    return projects.value.filter(item => item.businessLineId === row.bizLineId)
  }
  return projects.value
}

const projectOptionLabel = (item: ProjectOption) => {
  const line = businessLines.value.find(l => l.id === item.businessLineId)
  return line ? `${item.name} · ${line.name}` : item.name
}

const resolvePendingRow = async (row: DeliveryPendingContract) => {
  const draft = pendingDrafts[row.id]
  if (draft == null) {
    ElMessage.warning('请先选择归属：具体项目或业务线级')
    return
  }
  if (draft === 'line' && row.bizLineId == null) {
    ElMessage.warning('该合同未解析出业务线，请先选择具体项目')
    return
  }
  resolvingId.value = row.id
  try {
    if (draft === 'line') {
      await api.resolvePendingDeliveryContract(row.id, null, row.bizLineId)
      ElMessage.success('合同已归入业务线级收入（不落具体项目）')
    } else {
      await api.resolvePendingDeliveryContract(row.id, draft)
      ElMessage.success('合同已映射到项目')
    }
    delete pendingDrafts[row.id]
    await Promise.all([loadPending(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '映射保存失败'))
  } finally {
    resolvingId.value = null
  }
}

const pendingSection = ref<HTMLElement | null>(null)
const scrollToPending = async () => {
  await nextTick()
  pendingSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// ---------- 汇总表行模型 ----------
const periodGroups: Array<{ key: PeriodKey; label: string }> = [
  { key: 'h1', label: '上半年 H1' },
  { key: 'h2', label: '下半年 H2' },
  { key: 'ytd', label: '全年 YTD' }
]

const periodColumns: Array<{ key: PeriodColumnKey; label: string; unit: string }> = [
  { key: 'delivered', label: '已交付', unit: '万元' },
  { key: 'estimated', label: '预估交付', unit: '万元' },
  { key: 'hours', label: '工时', unit: '人月' },
  { key: 'labor', label: '工时成本', unit: '万元' },
  { key: 'salesHours', label: '销售工时', unit: '人月' },
  { key: 'salesCost', label: '销售成本', unit: '万元' },
  { key: 'other', label: '其他成本', unit: '万元' },
  { key: 'profit', label: '利润', unit: '万元' },
  { key: 'rate', label: '利润率', unit: '%' }
]

const windowView = (window?: DeliveryPeriodBlock | null): PeriodView => {
  const parts = window?.otherCosts
  const partner = parts?.partner == null ? null : num(parts.partner)
  const server = parts?.server == null ? null : num(parts.server)
  const otherCost = parts?.total == null
    ? num(parts?.other) + (partner ?? 0) + (server ?? 0)
    : num(parts.total)
  return {
    delivered: num(window?.delivered),
    estimated: num(window?.estimated),
    projectHours: num(window?.projectHours),
    projectLaborCost: num(window?.projectLaborCost),
    estimatedLaborCost: num(window?.estimatedLaborCost),
    salesHours: num(window?.salesHours),
    salesCost: num(window?.salesCost),
    allocatedSalesHours: num(window?.allocatedSalesHours),
    allocatedSalesCost: num(window?.allocatedSalesCost),
    unallocatedSalesHours: num(window?.unallocatedSalesHours),
    unallocatedSalesCost: num(window?.unallocatedSalesCost),
    partnerCost: partner,
    serverCost: server,
    otherCost,
    grossProfit: num(window?.grossProfit),
    grossRate: window?.grossRate ?? null,
    trueProfit: num(window?.trueProfit),
    trueProfitRate: window?.trueProfitRate ?? null
  }
}
const projectRow = (line: DeliverySummaryLine, project: DeliveryProjectRow, lineSpan: number): RowContext => ({
  kind: 'project',
  lineId: line.businessLineId,
  lineName: line.businessLineName,
  name: project.name,
  projectId: project.projectId,
  isAggregate: project.isAggregate,
  oaContract: num(project.oaContract),
  salesNote: null,
  lineContractNote: null,
  lineUnallocatedContract: 0,
  lineUnallocatedDelivered: 0,
  lineUnallocatedProfit: null,
  noDateNote: null,
  detailReasons: [],
  lineSpan,
  periods: {
    h1: windowView(project.h1),
    h2: windowView(project.h2),
    ytd: windowView(project.ytd)
  }
})

/** 业务线级未落具体项目合同的说明文案（如福田定制），无金额则 null */
const lineContractNoteText = (contract: number, delivered: number, profit: number | null) => {
  const parts: string[] = []
  if (contract > 0) parts.push(`合同 ${formatWan(contract)} 万`)
  if (delivered > 0) parts.push(`已交付 ${formatWan(delivered)} 万`)
  if (profit != null && profit !== 0) parts.push(`利润 ${formatWan(profit)} 万`)
  return parts.length ? `业务线级合同（未落具体项目）：${parts.join(' · ')}` : null
}

/** 交付日期为空合同的说明文案（未计入任何年份窗口），无则 null */
const noDateNoteText = (amount: number) =>
  amount > 0 ? `另有 ${formatWan(amount)} 万合同交付日期为空，未计入任何年份/H1/H2/YTD 窗口` : null

/** 业务线小计：直接使用服务端 totals 窗口（含线级销售拆分与重算后的利润） */
const lineTotalRow = (line: DeliverySummaryLine): RowContext => {
  const reasons = (line.salesUnallocatedDetail || []).map(describeUnallocated)
  const unallocatedHours = num(line.salesUnallocatedHours)
  const unallocatedCost = num(line.salesUnallocatedCost)
  const hasUnallocated = unallocatedHours > 0 || unallocatedCost > 0
  const salesNote = hasUnallocated
    ? `未分配销售 ${formatHours(unallocatedHours)} 人月 · ${formatWan(unallocatedCost)} 万（仅扣业务线利润，不分摊到项目）`
    : null
  // 业务线级未落项目合同（福田等）：优先 totals 镜像，缺字段回退 Line 级；后端未落地按 0/null
  const lineUnallocatedContract = num(line.totals?.lineUnallocatedContract ?? line.lineUnallocatedContract)
  const lineUnallocatedDelivered = num(line.totals?.lineUnallocatedDelivered ?? line.lineUnallocatedDelivered)
  const profitRaw = line.totals?.lineUnallocatedProfit ?? line.lineUnallocatedProfit
  const lineUnallocatedProfit = profitRaw == null ? null : num(profitRaw)
  // OA 列含业务线级合同：服务端 overview.totalOaContract 即按此口径；字段未落地时 +0 不回归
  const oaWithLine = num(line.totals?.oaContract) + lineUnallocatedContract
  return {
    kind: 'line',
    lineId: line.businessLineId,
    lineName: line.businessLineName,
    name: line.totals?.name || '合计',
    oaContract: oaWithLine,
    salesNote,
    lineContractNote: lineContractNoteText(lineUnallocatedContract, lineUnallocatedDelivered, lineUnallocatedProfit),
    lineUnallocatedContract,
    lineUnallocatedDelivered,
    lineUnallocatedProfit,
    noDateNote: noDateNoteText(num(line.noDeliveryDateContract)),
    detailReasons: reasons,
    lineSpan: 0,
    periods: {
      h1: windowView(line.totals?.h1),
      h2: windowView(line.totals?.h2),
      ytd: windowView(line.totals?.ytd)
    }
  }
}

/** 整表合计：按业务线 totals 窗口加总（利润与利润率按当前口径重算） */
const grandTotalRow = (data: DeliverySummary): RowContext => {
  const views = data.lines.map(line => ({
    h1: windowView(line.totals?.h1),
    h2: windowView(line.totals?.h2),
    ytd: windowView(line.totals?.ytd)
  }))
  const sumOf = (key: PeriodKey) => sumViews(views.map(v => v[key]))
  const oaContract = data.lines.reduce((sum, line) => sum + num(line.totals?.oaContract), 0)
  const overview = data.overview || {}
  const lineUnallocatedContract = num(overview.totalLineUnallocatedContract)
  const lineUnallocatedDelivered = num(overview.totalLineUnallocatedDelivered)
  const profitRaw = overview.totalLineUnallocatedProfit
  const lineUnallocatedProfit = profitRaw == null ? null : num(profitRaw)
  return {
    kind: 'grand',
    lineId: 0,
    lineName: '合计',
    name: '全表',
    oaContract: oaContract + lineUnallocatedContract,
    salesNote: null,
    lineContractNote: lineContractNoteText(lineUnallocatedContract, lineUnallocatedDelivered, lineUnallocatedProfit),
    lineUnallocatedContract,
    lineUnallocatedDelivered,
    lineUnallocatedProfit,
    noDateNote: noDateNoteText(num(overview.totalNoDeliveryDateContract)),
    detailReasons: (overview.salesUnallocatedDetail || []).map(describeUnallocated),
    lineSpan: 0,
    periods: { h1: sumOf('h1'), h2: sumOf('h2'), ytd: sumOf('ytd') }
  }
}

const describeUnallocated = (item: DeliveryUnallocatedItem) =>
  `${item.label}（${formatWan(item.cost)} 万）`

const sumViews = (views: PeriodView[]): PeriodView => {
  const add = (get: (v: PeriodView) => number) => views.reduce((s, v) => s + get(v), 0)
  const delivered = add(v => v.delivered)
  const estimated = add(v => v.estimated)
  const revenue = delivered + (includeEstimate.value ? estimated : 0)
  const trueProfit = add(v => profitOf(v))
  const grossProfit = add(v => v.grossProfit)
  return {
    delivered,
    estimated,
    projectHours: add(v => v.projectHours),
    projectLaborCost: add(v => v.projectLaborCost),
    estimatedLaborCost: add(v => v.estimatedLaborCost),
    salesHours: add(v => v.salesHours),
    salesCost: add(v => v.salesCost),
    allocatedSalesHours: add(v => v.allocatedSalesHours),
    allocatedSalesCost: add(v => v.allocatedSalesCost),
    unallocatedSalesHours: add(v => v.unallocatedSalesHours),
    unallocatedSalesCost: add(v => v.unallocatedSalesCost),
    partnerCost: null,
    serverCost: null,
    otherCost: add(v => v.otherCost),
    grossProfit,
    grossRate: revenue > 0 ? (grossProfit / revenue) * 100 : null,
    trueProfit,
    trueProfitRate: revenue > 0 ? (trueProfit / revenue) * 100 : null
  }
}

/** 真实利润优先；历史接口缺 trueProfit 时回退 grossProfit */
const profitOf = (view: PeriodView) =>
  view.trueProfit != null || view.grossProfit === null ? view.trueProfit : view.grossProfit

const rateOf = (view: PeriodView) => view.trueProfitRate ?? view.grossRate

const flatRows = computed<RowContext[]>(() => {
  const data = summary.value
  if (!data) return []
  const rows: RowContext[] = []
  data.lines.forEach(line => {
    line.projects.forEach((project, index) => {
      rows.push(projectRow(line, project, index === 0 ? line.projects.length : 0))
    })
    rows.push(lineTotalRow(line))
  })
  rows.push(grandTotalRow(data))
  return rows
})

const rowKey = (row: RowContext) =>
  `${row.lineId}-${row.kind}-${row.name}-${row.projectId ?? 'null'}`

// ---------- 表格单元格 ----------
/** 销售列语义：项目行=成单（已分配）销售；小计/合计行=未分配销售 */
const salesHoursOf = (row: RowContext, view: PeriodView) =>
  row.kind === 'project' ? view.allocatedSalesHours : view.unallocatedSalesHours

const salesCostOf = (row: RowContext, view: PeriodView) =>
  row.kind === 'project' ? view.allocatedSalesCost : view.unallocatedSalesCost

const cellText = (row: RowContext, view: PeriodView, column: PeriodColumnKey) => {
  switch (column) {
    case 'delivered': return formatWan(view.delivered)
    case 'estimated': return formatWan(view.estimated)
    case 'hours': return formatHours(view.projectHours)
    case 'labor': return formatWan(view.projectLaborCost)
    case 'salesHours': return formatHours(salesHoursOf(row, view))
    case 'salesCost': return formatWan(salesCostOf(row, view))
    case 'other': return formatWan(view.otherCost)
    case 'profit': return formatWan(profitOf(view))
    case 'rate': return formatRate(rateOf(view))
  }
}

const cellTone = (view: PeriodView, column: PeriodColumnKey) => {
  if (column === 'profit' && profitOf(view) < 0) return 'neg'
  if (column === 'rate' && profitOf(view) < 0) return 'neg'
  return ''
}

// ---------- 利润构成抽屉 ----------
const profitDrawer = ref(false)
const profitTitle = ref('')
const profitRows = ref<ProfitLine[]>([])
const profitSummary = reactive({ label: '', rate: '—' })
const profitNotes = ref<string[]>([])

const periodLabel = (key: PeriodKey) => periodGroups.find(g => g.key === key)?.label || key

const openProfitDetail = (row: RowContext, period: PeriodKey) => {
  const view = row.periods[period]
  profitTitle.value = `${row.lineName} / ${row.name} / ${periodLabel(period)}`
  const items: ProfitLine[] = [
    { label: '营收 · 已交付', value: view.delivered },
    { label: '营收 · 预估交付', value: view.estimated }
  ]
  const pushIf = (label: string, value: number) => {
    if (value > 0) items.push({ label, value })
  }
  pushIf('减 · 项目工时成本', view.projectLaborCost)
  pushIf('减 · 预估工时成本（含预估口径）', view.estimatedLaborCost)
  if (row.kind === 'project') {
    pushIf('减 · 成单销售成本', view.allocatedSalesCost)
  } else {
    pushIf('减 · 销售成本（含成单+未分配）', view.salesCost)
  }
  if (view.partnerCost != null) pushIf('减 · 协力成本', view.partnerCost)
  if (view.serverCost != null) pushIf('减 · 服务器成本', view.serverCost)
  pushIf('减 · 其他成本', view.otherCost)
  items.push({
    label: row.kind === 'project' ? '真实利润（已扣成单销售成本）' : '业务线利润',
    value: profitOf(view),
    strong: true,
    tone: profitOf(view) < 0 ? 'neg' : undefined
  })
  profitRows.value = items
  profitSummary.label = includeEstimate.value ? '含预估口径' : '只看实际口径'
  profitSummary.rate = formatRate(rateOf(view))
  const notes: string[] = []
  if (period === 'ytd' && row.detailReasons.length) {
    notes.push(`未分配销售成本原因：${row.detailReasons.join('；')}`)
  }
  if (period === 'ytd' && row.lineContractNote) {
    notes.push(`业务线级合同（未落具体项目，如福田定制）：合同 ${formatWan(row.lineUnallocatedContract)} 万 · 已交付 ${formatWan(row.lineUnallocatedDelivered)} 万${row.lineUnallocatedProfit != null && row.lineUnallocatedProfit !== 0 ? ` · 利润 ${formatWan(row.lineUnallocatedProfit)} 万` : ''}。该类收入不生成项目行，按业务线/整表口径计入。`)
  }
  if (row.kind === 'grand' && period === 'ytd') {
    const noDate = num(summary.value?.overview?.totalNoDeliveryDateContract)
    if (noDate > 0) {
      notes.push(`另有 ${formatWan(noDate)} 万合同交付日期为空，未计入任何年份/H1/H2/YTD 窗口（按项目交付日期口径）`)
    }
  }
  profitNotes.value = notes
  profitDrawer.value = true
}

// ---------- 概览卡 ----------
const overviewCards = computed(() => {
  const ov = summary.value?.overview
  if (!ov) return []
  const profit = ov.totalTrueProfit ?? ov.totalProfit
  const rate = ov.trueProfitRate ?? ov.profitRate
  return [
    { label: 'OA 合同总额', value: formatWan(ov.totalOaContract), strong: true },
    { label: '已交付', value: formatWan(ov.totalDelivered) },
    { label: '预估交付', value: formatWan(ov.totalEstimated) },
    { label: '人工成本', value: formatWan(ov.totalLaborCost) },
    { label: '其他成本', value: formatWan(ov.totalOtherCost) },
    { label: '真实利润', value: formatWan(profit), strong: true, tone: num(profit) < 0 ? 'neg' : undefined },
    { label: '真实利润率', value: formatRate(rate) }
  ]
})

// ---------- 预估交付管理 ----------
const plansDialog = ref(false)
const plansBusy = ref(false)
const plansSaving = ref(false)
const plansContext = ref<RowContext | null>(null)
const plans = ref<DeliveryPlan[]>([])
const unitPrice = ref<number | null>(null)
const editingPlanId = ref<number | null>(null)
const planEdit = reactive({ amountWan: 0, personMonths: 0 })

const yearMonths = computed(() =>
  Array.from({ length: 12 }, (_, i) => `${props.year}-${String(i + 1).padStart(2, '0')}`))

const emptyBatchRow = () => ({ yearMonth: '', amountWan: 0, personMonths: 0 })
const planBatchRows = ref<Array<{ yearMonth: string; amountWan: number; personMonths: number }>>([emptyBatchRow()])

const plansDialogTitle = computed(() => {
  const ctx = plansContext.value
  return ctx ? `${ctx.lineName} / ${ctx.name} · 预估交付计划` : '预估交付计划'
})

const plansOfRow = computed(() => {
  const ctx = plansContext.value
  if (!ctx) return []
  const projectId = ctx.projectId ?? null
  return plans.value.filter(plan =>
    projectId == null ? (plan.projectId == null) : (plan.projectId === projectId))
})

const loadPlanList = async () => {
  const ctx = plansContext.value
  if (!ctx) return
  plansBusy.value = true
  try {
    plans.value = await api.getDeliveryPlans({
      year: props.year,
      businessLineId: ctx.lineId,
      projectId: ctx.projectId
    })
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估交付记录加载失败'))
  } finally {
    plansBusy.value = false
  }
}

const loadUnitPrice = async () => {
  const ctx = plansContext.value
  unitPrice.value = null
  if (!ctx || ctx.projectId == null) return
  try {
    unitPrice.value = await api.getDeliveryUnitPrice(ctx.projectId)
  } catch {
    unitPrice.value = null
  }
}

const openPlansDialog = async (row: RowContext) => {
  plansContext.value = row
  plans.value = []
  editingPlanId.value = null
  planBatchRows.value = [emptyBatchRow()]
  unitPrice.value = null
  plansDialog.value = true
  await Promise.all([loadPlanList(), loadUnitPrice()])
}

const addPlanBatchRow = () => {
  planBatchRows.value.push(emptyBatchRow())
}

const removePlanBatchRow = (index: number) => {
  if (planBatchRows.value.length > 1) planBatchRows.value.splice(index, 1)
}

const savePlanBatch = async () => {
  const ctx = plansContext.value
  if (!ctx) return
  const rows = planBatchRows.value.filter(item => item.yearMonth && (item.amountWan > 0 || item.personMonths > 0))
  if (!rows.length) {
    ElMessage.warning('请至少填写一行：选择月份并填写金额（或人月）')
    return
  }
  plansSaving.value = true
  try {
    await api.createDeliveryPlansBatch({
      businessLineId: ctx.lineId,
      projectId: ctx.projectId ?? null,
      year: props.year,
      rows: rows.map(item => ({
        yearMonth: item.yearMonth,
        amountYuan: Math.round(item.amountWan * 10000),
        personMonths: item.personMonths
      }))
    })
    ElMessage.success(`已保存 ${rows.length} 条预估交付计划`)
    planBatchRows.value = [emptyBatchRow()]
    await Promise.all([loadPlanList(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估交付保存失败'))
  } finally {
    plansSaving.value = false
  }
}

const startEditPlan = (plan: DeliveryPlan) => {
  editingPlanId.value = plan.id
  planEdit.amountWan = num(plan.amountYuan) / 10000
  planEdit.personMonths = num(plan.personMonths)
}

const cancelEditPlan = () => {
  editingPlanId.value = null
}

const saveEditPlan = async (plan: DeliveryPlan) => {
  plansSaving.value = true
  try {
    await api.updateDeliveryPlan(plan.id, {
      yearMonth: plan.yearMonth,
      amountYuan: Math.round(planEdit.amountWan * 10000),
      personMonths: planEdit.personMonths
    })
    ElMessage.success('预估交付已更新')
    editingPlanId.value = null
    await Promise.all([loadPlanList(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估交付更新失败'))
  } finally {
    plansSaving.value = false
  }
}

const removePlan = async (plan: DeliveryPlan) => {
  try {
    await ElMessageBox.confirm(`删除 ${plan.yearMonth} 预估交付计划（金额 ${formatWan(plan.amountYuan)} 万）？`, '删除预估交付', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteDeliveryPlan(plan.id)
    ElMessage.success('预估交付已删除')
    await Promise.all([loadPlanList(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '预估交付删除失败'))
  }
}

// ---------- 其他成本维护 ----------
const costDialog = ref(false)
const costsBusy = ref(false)
const costSaving = ref(false)
const costsContext = ref<RowContext | null>(null)
const costs = ref<DeliveryOtherCost[]>([])
const costForm = reactive({
  id: undefined as number | undefined,
  yearMonth: '',
  costType: 'partner' as DeliveryCostType,
  amountWan: 0,
  note: ''
})

const costTypeOptions: Array<{ value: DeliveryCostType; label: string }> = [
  { value: 'partner', label: '协力成本' },
  { value: 'server', label: '服务器成本' },
  { value: 'other', label: '其他成本' }
]

const costTypeMeta = (type: DeliveryCostType) => {
  const option = costTypeOptions.find(item => item.value === type)
  return {
    label: option?.label || type,
    tag: type === 'partner' ? 'warning' : type === 'server' ? 'primary' : 'info'
  }
}

const costsOfRow = computed(() => {
  const ctx = costsContext.value
  if (!ctx) return []
  const projectId = ctx.projectId ?? null
  const yearPrefix = `${props.year}-`
  return costs.value.filter(item =>
    (projectId == null ? (item.projectId == null) : (item.projectId === projectId))
    && (item.yearMonth || '').startsWith(yearPrefix))
})

const loadCostList = async () => {
  const ctx = costsContext.value
  if (!ctx) return
  costsBusy.value = true
  try {
    costs.value = await api.getOtherCosts({
      businessLineId: ctx.lineId,
      projectId: ctx.projectId
    })
  } catch (error) {
    ElMessage.error(errorMessage(error, '其他成本记录加载失败'))
  } finally {
    costsBusy.value = false
  }
}

const costDialogTitle = computed(() => {
  const ctx = costsContext.value
  return ctx ? `${ctx.lineName} / ${ctx.name} · 其他成本` : '其他成本'
})

const openCostDialog = async (row: RowContext) => {
  costsContext.value = row
  costs.value = []
  costForm.id = undefined
  costForm.yearMonth = ''
  costForm.costType = 'partner'
  costForm.amountWan = 0
  costForm.note = ''
  costDialog.value = true
  await loadCostList()
}

const cancelCostEdit = () => {
  costForm.id = undefined
  costForm.yearMonth = ''
  costForm.amountWan = 0
  costForm.note = ''
}

const startEditCost = (item: DeliveryOtherCost) => {
  costForm.id = item.id
  costForm.yearMonth = item.yearMonth
  costForm.costType = item.costType
  costForm.amountWan = num(item.amountYuan) / 10000
  costForm.note = item.note || ''
}

const saveCost = async () => {
  const ctx = costsContext.value
  if (!ctx) return
  if (!costForm.yearMonth) {
    ElMessage.warning('请选择月份')
    return
  }
  if (costForm.amountWan <= 0) {
    ElMessage.warning('金额需大于 0')
    return
  }
  costSaving.value = true
  try {
    const body = {
      yearMonth: costForm.yearMonth,
      costType: costForm.costType,
      amountYuan: Math.round(costForm.amountWan * 10000),
      note: costForm.note.trim()
    }
    if (costForm.id) {
      await api.updateOtherCost(costForm.id, body)
      ElMessage.success('其他成本已更新')
    } else {
      await api.createOtherCost({
        ...body,
        businessLineId: ctx.lineId,
        projectId: ctx.projectId ?? null
      })
      ElMessage.success('其他成本已添加')
    }
    cancelCostEdit()
    await Promise.all([loadCostList(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '其他成本保存失败'))
  } finally {
    costSaving.value = false
  }
}

const removeCost = async (item: DeliveryOtherCost) => {
  try {
    await ElMessageBox.confirm(`删除 ${item.yearMonth} ${costTypeMeta(item.costType).label} ${formatWan(item.amountYuan)} 万？`, '删除其他成本', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteOtherCost(item.id)
    ElMessage.success('其他成本已删除')
    await Promise.all([loadCostList(), refreshSummary()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '其他成本删除失败'))
  }
}

// ---------- 生命周期：随 tab 激活拉取，年份联动 ----------
const loadAll = async () => {
  await Promise.all([refreshSummary(), loadBatches(), loadPending(), ensureOptions()])
}

onMounted(() => {
  if (props.active) void loadAll()
})

watch(() => props.active, (value) => {
  if (value) void loadAll()
})

watch(() => props.year, () => {
  if (props.active) void refreshSummary()
})

watch(includeEstimate, () => {
  void refreshSummary()
})

defineExpose({ reload: () => refreshSummary() })
</script>

<template>
  <div class="delivery-panel">
    <div v-loading="summaryLoading" class="delivery-body">
      <template v-if="summary">
        <div class="delivery-toolbar">
          <span class="delivery-note">
            {{ includeEstimate ? '含预估口径：营收=已交付+预估交付，成本含预估交付关联工时成本' : '只看实际口径：仅已交付与已发生成本参与利润' }}
          </span>
          <div class="segment-switch" aria-label="交付毛利口径">
            <button :class="{ active: includeEstimate }" @click="includeEstimate = true">含预估</button>
            <button :class="{ active: !includeEstimate }" @click="includeEstimate = false">只看实际</button>
          </div>
        </div>

        <section class="overview-strip" aria-label="交付与利润概览">
          <div v-for="card in overviewCards" :key="card.label" class="overview-cell">
            <span>{{ card.label }}</span>
            <strong :class="card.tone"><small v-if="card.label !== '真实利润率'">万</small>{{ card.value }}</strong>
          </div>
        </section>

        <div class="matrix-legend" aria-label="销售与利润口径说明">
          「销售工时/销售成本」：项目行 = 成单销售（已分配，有明确成单证据才计入）；
          小计/合计行 = 未分配销售（仅扣业务线/整表利润，<b>不分摊到项目</b>）。
          「利润/利润率」为真实利润口径：项目行扣成单销售成本，业务线/整表再扣未分配销售成本。
          H1/H2/YTD 已交付金额按合同交付日期（delivery_date）归入对应窗口（年份=交付日期年份）。
          业务线级合同（如福田定制，未落具体项目）在业务线合计/整表合计行单独列示，不消失。
        </div>

        <div class="matrix-scroll">
          <table class="matrix-table" aria-label="交付与利润汇总表">
            <thead>
              <tr class="group-header-row">
                <th class="col-line" rowspan="2">业务线</th>
                <th class="col-project" rowspan="2">项目</th>
                <th class="col-oa" rowspan="2">OA 合同总额<br><small>万元</small></th>
                <th
                  v-for="group in periodGroups"
                  :key="group.key"
                  class="group-head"
                  :colspan="periodColumns.length"
                  :title="groupTip(group)"
                >{{ group.label }}<br><small class="period-basis">按交付日期</small></th>
                <th class="col-actions" rowspan="2">操作</th>
              </tr>
              <tr>
                <template v-for="group in periodGroups" :key="`sub-${group.key}`">
                  <th v-for="column in periodColumns" :key="`${group.key}-${column.key}`" class="period-cell">
                    {{ column.label }}<br><small>{{ column.unit }}</small>
                  </th>
                </template>
              </tr>
            </thead>
            <tbody>
              <template v-for="row in flatRows" :key="rowKey(row)">
                <tr :class="row.kind === 'line' ? 'line-total-row' : row.kind === 'grand' ? 'grand-total-row' : ''">
                  <td v-if="row.lineSpan > 0" class="col-line" :rowspan="row.lineSpan">{{ row.lineName }}</td>
                  <td v-else-if="row.kind !== 'project'" class="col-line">{{ row.lineName }}</td>
                  <td class="col-project">
                    {{ row.name }}
                    <small v-if="row.salesNote" class="sales-note">{{ row.salesNote }}</small>
                    <small
                      v-if="row.lineContractNote"
                      class="sales-note contract-note"
                      :title="lineContractTip(row)"
                    >{{ row.lineContractNote }}</small>
                    <small v-if="row.noDateNote" class="sales-note no-date-note" :title="row.noDateNote">{{ row.noDateNote }}</small>
                  </td>
                  <td class="col-oa">{{ row.oaContract == null || row.oaContract === 0 ? '—' : formatWan(row.oaContract) }}</td>
                  <template v-for="group in periodGroups" :key="`g-${rowKey(row)}-${group.key}`">
                    <td
                      v-for="column in periodColumns"
                      :key="`c-${rowKey(row)}-${group.key}-${column.key}`"
                      class="cell"
                      :class="[column.key === 'profit' ? 'cell-profit clickable' : '', cellTone(row.periods[group.key], column.key)]"
                      :title="column.key === 'profit' ? '点击查看利润构成' : undefined"
                      @click="column.key === 'profit' && openProfitDetail(row, group.key)"
                    >
                      {{ cellText(row, row.periods[group.key], column.key) }}
                    </td>
                  </template>
                  <td class="col-actions">
                    <template v-if="row.kind === 'project'">
                      <el-button link type="primary" size="small" @click="openPlansDialog(row)">预估交付</el-button>
                      <el-button link size="small" @click="openCostDialog(row)">其他成本</el-button>
                    </template>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>
      <el-empty v-else-if="!summaryLoading" description="暂无交付营收数据，请先在下方导入合同或等待数据接入" />
    </div>

    <!-- 合同导入与待映射 -->
    <section class="delivery-tools">
      <div class="import-row">
        <div class="import-card">
          <h4>合同导入</h4>
          <p>选择合同（OA 合同 / 已交付）Excel，导入后自动按收款款项类型归属；未命中项目的进入待映射清单。</p>
          <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx,.xls"
            :on-change="(file: UploadFile) => { importFile = file.raw ?? null }">
            <el-button :icon="Upload">选择 xlsx 文件</el-button>
          </el-upload>
          <span v-if="importFile" class="file-name">{{ importFile.name }}</span>
          <el-button type="primary" :loading="importing" :disabled="!importFile" @click="runContractImport">开始导入</el-button>
        </div>
        <div class="batch-section">
          <h4>导入历史（{{ batches.length }}）</h4>
          <el-table v-loading="batchesLoading" :data="batches" class="data-table" empty-text="暂无导入批次" max-height="220">
            <el-table-column prop="createdAt" label="时间" width="170" />
            <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
            <el-table-column prop="totalCount" label="解析" width="70" />
            <el-table-column prop="successCount" label="成功" width="70" />
            <el-table-column prop="pendingCount" label="待映射" width="80" />
          </el-table>
        </div>
      </div>

      <div ref="pendingSection" class="pending-section">
        <div class="estimate-head">
          <h4>待映射合同（{{ pendingContracts.length }}）</h4>
          <span class="section-note">两级归属：真实项目（合同计入该项目行），或业务线级（福田等定制合同不落具体项目，计入业务线级合同列）。会员通聚合（项目集）合同在导入时已自动归属。</span>
        </div>
        <el-table v-loading="pendingLoading" :data="pendingContracts" class="data-table" empty-text="暂无待映射合同">
          <el-table-column label="品牌/客户" width="130">
            <template #default="{ row }">{{ row.brand || row.customer || '—' }}</template>
          </el-table-column>
          <el-table-column label="合同编号" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.contractNo || row.detailNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="合同名称" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.contractName || '—' }}</template>
          </el-table-column>
          <el-table-column label="业务线" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pendingLineName(row) }}</template>
          </el-table-column>
          <el-table-column label="应收金额(万)" width="110">
            <template #default="{ row }">{{ formatWan(row.receivableAmount) }}</template>
          </el-table-column>
          <el-table-column label="归属（业务线级/项目）" width="260">
            <template #default="{ row }">
              <el-select
                :model-value="pendingDrafts[row.id]"
                filterable
                clearable
                placeholder="选择业务线级或真实项目"
                @change="(value: number | 'line' | undefined) => pendingDrafts[row.id] = value"
              >
                <el-option
                  v-if="row.bizLineId != null"
                  :value="'line' as const"
                  :label="`业务线级 · ${pendingLineName(row)}（不落具体项目）`"
                />
                <el-option
                  v-for="option in pendingProjectsOf(row)"
                  :key="option.id"
                  :label="projectOptionLabel(option)"
                  :value="option.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" type="primary" :loading="resolvingId === row.id" @click="resolvePendingRow(row)">确定</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <!-- 利润构成抽屉 -->
    <el-drawer v-model="profitDrawer" :title="profitTitle" size="min(560px, 96vw)" destroy-on-close>
      <div class="profit-detail">
        <el-tag size="small" effect="plain" class="profit-mode">{{ profitSummary.label }}</el-tag>
        <div class="profit-block">
          <div v-for="item in profitRows" :key="item.label" class="profit-row" :class="{ 'profit-total': item.strong }">
            <span>{{ item.label }}</span>
            <strong :class="item.tone">{{ item.value == null ? '—' : formatWan(item.value) }}<small v-if="item.value != null"> 万</small></strong>
          </div>
          <div class="profit-row profit-rate">
            <span>利润率</span>
            <strong>{{ profitSummary.rate }}</strong>
          </div>
        </div>
        <p class="section-note">
          金额单位为万元。项目行利润 = 已交付+预估交付 − 项目工时成本(±预估) − 成单销售成本 − 其他成本；
          小计/合计行再扣减未分配销售成本（未分配销售不向项目分摊）。
        </p>
        <p v-for="note in profitNotes" :key="note" class="section-note">{{ note }}</p>
      </div>
    </el-drawer>

    <!-- 预估交付计划 dialog -->
    <el-dialog v-model="plansDialog" :title="plansDialogTitle" width="min(860px, 96vw)">
      <div v-loading="plansBusy" class="plans-body">
        <p v-if="unitPrice != null" class="section-note">该行历史完结单价：{{ formatWan(unitPrice) }} 万/人月（预估成本按单价快照计算，仅作参考）。</p>
        <h4>已有预估交付计划（{{ plansOfRow.length }}）</h4>
        <el-table :data="plansOfRow" class="data-table" empty-text="暂无预估交付计划，可在下方批量新增">
          <el-table-column label="月份" width="100">
            <template #default="{ row }">{{ row.yearMonth.slice(5) }}月</template>
          </el-table-column>
          <el-table-column label="金额(万)" width="140">
            <template #default="{ row }">
              <el-input-number
                v-if="editingPlanId === row.id"
                v-model="planEdit.amountWan"
                :min="0"
                :precision="2"
                size="small"
                :aria-label="`${row.yearMonth}金额万`"
              />
              <span v-else>{{ formatWan(row.amountYuan) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="人月" width="120">
            <template #default="{ row }">
              <el-input-number
                v-if="editingPlanId === row.id"
                v-model="planEdit.personMonths"
                :min="0"
                :precision="2"
                :step="0.1"
                size="small"
                :aria-label="`${row.yearMonth}人月`"
              />
              <span v-else>{{ formatHours(row.personMonths) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成本(万)" width="110">
            <template #default="{ row }">{{ formatWan(row.laborCostYuan) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <template v-if="editingPlanId === row.id">
                <el-button link type="primary" :loading="plansSaving" @click="saveEditPlan(row)">保存</el-button>
                <el-button link @click="cancelEditPlan">取消</el-button>
              </template>
              <template v-else>
                <el-button link type="primary" @click="startEditPlan(row)">编辑</el-button>
                <el-button link type="danger" @click="removePlan(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <div class="estimate-head" style="margin-top: 16px">
          <h4>批量新增预估交付</h4>
          <span class="section-note">金额与成本由服务端按月份/单价计算保存</span>
        </div>
        <div class="plan-batch-grid" aria-label="按月批量新增预估交付">
          <div v-for="(row, index) in planBatchRows" :key="index" class="plan-batch-row">
            <el-select v-model="row.yearMonth" size="small" :aria-label="`第${index + 1}行月份`" placeholder="月份">
              <el-option v-for="month in yearMonths" :key="month" :label="`${month.slice(5)}月`" :value="month" />
            </el-select>
            <el-input-number v-model="row.amountWan" :min="0" :precision="2" :step="1" size="small" :aria-label="`第${index + 1}行金额万`" placeholder="金额(万)" />
            <el-input-number v-model="row.personMonths" :min="0" :precision="2" :step="0.1" size="small" :aria-label="`第${index + 1}行人月`" placeholder="人月" />
            <el-button link type="danger" size="small" :disabled="planBatchRows.length <= 1" :aria-label="`删除第${index + 1}行`" @click="removePlanBatchRow(index)">删除</el-button>
          </div>
          <el-button size="small" class="batch-add" @click="addPlanBatchRow">+ 添加一条</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="plansDialog = false">关闭</el-button>
        <el-button type="primary" :loading="plansSaving" @click="savePlanBatch">保存批量新增</el-button>
      </template>
    </el-dialog>

    <!-- 其他成本维护 dialog -->
    <el-dialog v-model="costDialog" :title="costDialogTitle" width="min(760px, 96vw)">
      <div class="cost-form">
        <h4>{{ costForm.id ? '编辑其他成本' : '新增其他成本' }}</h4>
        <div class="cost-form-row">
          <el-select v-model="costForm.yearMonth" size="small" placeholder="月份" aria-label="成本月份">
            <el-option v-for="month in yearMonths" :key="month" :label="`${month.slice(5)}月`" :value="month" />
          </el-select>
          <el-select v-model="costForm.costType" size="small" aria-label="成本类型">
            <el-option v-for="option in costTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input-number v-model="costForm.amountWan" :min="0" :precision="2" :step="1" size="small" aria-label="成本金额万" placeholder="金额(万)" />
          <el-input v-model="costForm.note" size="small" maxlength="200" placeholder="备注（可留空）" aria-label="成本备注" />
          <el-button type="primary" size="small" :loading="costSaving" @click="saveCost">{{ costForm.id ? '保存修改' : '添加' }}</el-button>
          <el-button v-if="costForm.id" size="small" @click="cancelCostEdit">取消编辑</el-button>
        </div>
      </div>
      <el-table v-loading="costsBusy" :data="costsOfRow" class="data-table" style="margin-top: 12px" empty-text="该行暂无其他成本记录">
        <el-table-column label="月份" width="100">
          <template #default="{ row }">{{ row.yearMonth.slice(5) }}月</template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="costTypeMeta(row.costType).tag as any">{{ costTypeMeta(row.costType).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额(万)" width="120">
          <template #default="{ row }">{{ formatWan(row.amountYuan) }}</template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="startEditCost(row)">编辑</el-button>
            <el-button link type="danger" @click="removeCost(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.delivery-panel {
  width: 100%;
  min-width: 0;
  display: grid;
  gap: 16px;
}

.delivery-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.delivery-note {
  margin-right: auto;
  color: #64748b;
  font-size: 12px;
}

.segment-switch {
  display: inline-flex;
  padding: 3px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #f8fafc;
  gap: 2px;
}

.segment-switch button {
  border: 0;
  background: transparent;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.18s ease;
}

.segment-switch button:hover {
  color: var(--el-color-primary);
}

.segment-switch button.active {
  background: var(--el-color-primary);
  color: #fff;
  box-shadow: 0 1px 4px rgb(0 0 0 / 16%);
}

.overview-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 4px;
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

.overview-cell strong small {
  color: #94a3b8;
  font-size: 11px;
  margin-right: 3px;
  font-weight: 600;
}

.overview-cell strong.neg {
  color: #dc2626;
}

.matrix-legend {
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  line-height: 1.7;
}

.matrix-legend b {
  color: #475569;
}

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

.matrix-table th small {
  color: #94a3b8;
  font-size: 10px;
  font-weight: 600;
}

.group-head {
  background: #f1f5f9 !important;
}

.col-line {
  font-weight: 700;
  background: #fafcff;
}

.col-project {
  text-align: left !important;
  min-width: 160px;
}

.col-oa {
  color: #334155;
  min-width: 96px;
}

.col-actions {
  min-width: 140px;
}

.sales-note {
  display: block;
  color: #64748b;
  font-size: 11px;
  font-weight: 400;
}

.contract-note {
  color: #0f766e;
  max-width: 320px;
  white-space: normal;
  line-height: 1.5;
}

.no-date-note {
  color: #b45309;
  max-width: 320px;
  white-space: normal;
  line-height: 1.5;
}

.period-basis {
  color: #94a3b8;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
}

.cell {
  min-width: 76px;
}

.cell-profit {
  font-weight: 700;
  color: #0f766e;
}

.cell-profit.neg {
  color: #dc2626;
}

.cell-profit.clickable:hover {
  background: #ecfdf5;
}

.cell.neg {
  color: #dc2626;
}

.line-total-row td {
  background: #f8fafc;
  font-weight: 700;
}

.grand-total-row td {
  background: #f1f5f9;
  font-weight: 700;
}

.section-note {
  color: #64748b;
  font-size: 12px;
  margin: 4px 0 10px;
}

.file-name {
  color: #475569;
  font-size: 12px;
}

.delivery-tools {
  display: grid;
  gap: 16px;
}

.import-row {
  display: grid;
  grid-template-columns: minmax(300px, 1fr) 2fr;
  gap: 16px;
  align-items: start;
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

.import-card h4,
.batch-section h4,
.pending-section h4 {
  margin: 0;
}

.import-card p {
  margin: 0;
  color: #64748b;
  font-size: 12px;
}

.batch-section {
  padding: 18px;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #fff;
}

.batch-section .data-table {
  margin-top: 10px;
}

.pending-section {
  padding: 18px;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #fff;
}

.pending-section .data-table {
  margin-top: 10px;
}

.estimate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.estimate-head .section-note {
  margin: 0;
}

.plans-body h4 {
  margin: 0 0 8px;
}

.plan-batch-grid {
  display: grid;
  gap: 8px;
  width: 100%;
}

.plan-batch-row {
  display: grid;
  grid-template-columns: 110px 150px 120px 52px;
  align-items: center;
  gap: 8px;
}

.batch-add {
  justify-self: start;
}

.cost-form h4 {
  margin: 0 0 10px;
}

.cost-form-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.cost-form-row .el-select {
  width: 120px;
}

.profit-detail .profit-mode {
  margin-bottom: 12px;
}

.profit-block {
  border: 1px solid #e8edf4;
  border-radius: 10px;
  overflow: hidden;
}

.profit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13px;
  color: #475569;
}

.profit-row:last-child {
  border-bottom: 0;
}

.profit-row strong {
  color: #334155;
}

.profit-row strong.neg {
  color: #dc2626;
}

.profit-total {
  background: #f8fafc;
  font-weight: 700;
}

.profit-total span,
.profit-total strong {
  font-size: 14px;
}

.profit-rate {
  background: #f1f5f9;
}

.profit-rate strong {
  font-weight: 700;
}

.profit-row strong small {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 600;
}

.data-table {
  width: 100%;
}
</style>
