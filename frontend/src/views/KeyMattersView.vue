<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Calendar, Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/utils/api'
import type {
  BuKeyMatter,
  BuKeyMatterPayload,
  BuKeyMatterWeeklyUpdate,
  BuKeyMatterWeeklyUpdatePayload
} from '@/utils/api'

interface RecordEnvelope<T> {
  records?: T[]
  data?: T[] | { records?: T[] }
}

interface UserOption {
  id: number
  username: string
  realName: string
  status: number
}

interface ProjectOption {
  id: number
  name: string
  parentId?: number | null
  fullPath?: string
}

interface MatterFormState {
  title: string
  description: string
  projectId?: number
  ownerId?: number
  priority: string
  status: string
  progress: number
  startDate: string
  plannedCompletionDate: string
  sortOrder: number
}

type ViewMode = 'register' | 'meeting'
type MeetingGroupBy = 'owner' | 'project'

interface MeetingGroup {
  key: string
  label: string
  subtitle: string
  matters: BuKeyMatter[]
  riskCount: number
  updatedCount: number
  updateRequiredCount: number
  averageProgress: number
}

interface MilestoneGroup {
  date: string
  label: string
  matters: BuKeyMatter[]
}

interface MilestoneItem {
  key: string
  date: string
  label: string
  matters: BuKeyMatter[]
}

interface QuickListGroup {
  key: string
  label: string
  count: number
  id?: number
}

interface PresentationDraft {
  status: string
  progress: number
  progressSummary: string
  issues: string
  nextWeekPlan: string
  supportNeeded: string
}

type PresentationGroupBy = 'project' | 'owner'

interface PresentationGroup {
  key: string
  label: string
  matters: BuKeyMatter[]
  riskCount: number
  updatedCount: number
  updateRequiredCount: number
  averageProgress: number
}

const route = useRoute()
const router = useRouter()
const isMeetingStandalone = computed(() => route.name === 'KeyMattersMeeting')
const statusOptions = ['未开始', '推进中', '有风险', '已阻塞', '已完成', '已暂停']
const priorityOptions = ['P0', 'P1', 'P2']
const femaleOwnerNames = new Set(['丛宁', '姜涛', '小刘洋', '黄金玲', '李芳晨'])
function isCompletedMatter(matter: BuKeyMatter) {
  return matter.status === '已完成'
}
function requiresWeeklyUpdate(matter: BuKeyMatter) {
  return !isCompletedMatter(matter)
}
function isCompletedWeeklyUpdateError(error: unknown) {
  return error instanceof Error
    && error.message.includes('已完成事项无需新增周进展')
}
const matters = ref<BuKeyMatter[]>([])
const allMatters = ref<BuKeyMatter[]>([])
const currentPage = ref(1)
const pageSize = ref(10)
const meetingMatters = ref<BuKeyMatter[]>([])
const milestoneMatters = ref<BuKeyMatter[]>([])
const users = ref<UserOption[]>([])
const projects = ref<ProjectOption[]>([])
const loading = ref(false)
const loadError = ref('')
const mode = ref<ViewMode>(route.name === 'KeyMattersMeeting' ? 'meeting' : 'register')
const selectedWeek = ref(currentWeekStart())
const meetingGroupBy = ref<MeetingGroupBy>('owner')
const selectedMilestoneMonth = ref(formatDate(new Date()).slice(0, 7))
const milestoneExpanded = ref(false)
const milestoneScroller = ref<HTMLElement>()
const milestoneCanScrollLeft = ref(false)
const milestoneCanScrollRight = ref(false)
const activeMilestoneItem = ref<MilestoneItem | null>(null)
const milestonePopoverStyle = ref<Record<string, string>>({})
let milestonePopoverHideTimer: number | undefined
const presentationMode = ref(false)
const standaloneMeetingReady = ref(!isMeetingStandalone.value)
const presentationStageRef = ref<HTMLElement>()
const presentationIndex = ref(0)
const presentationGroupBy = ref<PresentationGroupBy>('project')
const presentationEditing = ref(false)
const presentationSaving = ref(false)
const presentationDrafts = new Map<number, PresentationDraft>()
const presentationForm = reactive<BuKeyMatterWeeklyUpdatePayload>({
  status: '推进中',
  progress: 0,
  progressSummary: '',
  issues: '',
  nextWeekPlan: '',
  supportNeeded: ''
})

const filters = reactive({
  keyword: '',
  status: '',
  priority: '',
  ownerId: undefined as number | undefined,
  projectId: undefined as number | undefined
})

const matterDrawer = ref(false)
const matterFormRef = ref<FormInstance>()
const editingMatterId = ref<number>()
const matterSaving = ref(false)
const matterForm = reactive<MatterFormState>(emptyMatterForm())
const matterRules: FormRules<MatterFormState> = {
  title: [{ required: true, message: '请输入事项标题', trigger: 'blur' }],
  ownerId: [{ required: true, message: '请选择负责人', trigger: 'change' }],
  startDate: [{ required: true, message: '请选择开始日期', trigger: 'change' }],
  plannedCompletionDate: [{ required: true, message: '请选择计划完成日期', trigger: 'change' }]
}

const detailDrawer = ref(false)
const detailLoading = ref(false)
const selectedMatter = ref<BuKeyMatter>()

const weeklyDrawer = ref(false)
const weeklyFormRef = ref<FormInstance>()
const weeklyMatterId = ref<number>()
const weeklyDate = ref(selectedWeek.value)
const weeklySaving = ref(false)
const weeklyEditingExisting = ref(false)
const weeklyForm = reactive<BuKeyMatterWeeklyUpdatePayload>({
  status: '推进中',
  progress: 0,
  progressSummary: '',
  issues: '',
  nextWeekPlan: '',
  supportNeeded: ''
})
const weeklyRules: FormRules<BuKeyMatterWeeklyUpdatePayload> = {
  status: [{ required: true, message: '请选择事项状态', trigger: 'change' }],
  progressSummary: [{ required: true, message: '请输入本周进展', trigger: 'blur' }]
}

const summary = computed(() => {
  const total = allMatters.value.length
  const progressing = allMatters.value.filter(item => item.status === '推进中').length
  const risks = allMatters.value.filter(item => ['有风险', '已阻塞'].includes(item.status)).length
  const updateRequired = allMatters.value.filter(requiresWeeklyUpdate)
  return {
    total,
    progressing,
    risks,
    pendingUpdate: updateRequired.filter(item => !item.currentWeekUpdated).length,
    updateRequiredCount: updateRequired.length,
    updatedCount: updateRequired.filter(item => item.currentWeekUpdated).length,
    progressingRate: total ? Math.round(progressing / total * 100) : 0,
    riskRate: total ? Math.round(risks / total * 100) : 0
  }
})

const pagedMatters = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return matters.value.slice(start, start + pageSize.value)
})

function clampCurrentPage() {
  const lastPage = Math.max(1, Math.ceil(matters.value.length / pageSize.value))
  currentPage.value = Math.min(Math.max(currentPage.value, 1), lastPage)
}

function resetCurrentPage() {
  currentPage.value = 1
}

function searchMatters() {
  resetCurrentPage()
  return loadMatters()
}

function handlePageSizeChange() {
  resetCurrentPage()
}

const meetingWeekLabel = computed(() => {
  const end = parseDate(selectedWeek.value)
  end.setDate(end.getDate() + 6)
  return `${selectedWeek.value.slice(5).replace('-', '/')} - ${formatDate(end).slice(5).replace('-', '/')}`
})

const modeNote = computed(() => {
  if (mode.value === 'meeting') return `${meetingWeekLabel.value} 周会汇报`
  return '持续跟踪重点事项与责任人'
})

function projectPresentation(matter: BuKeyMatter) {
  const project = matter.projectId === undefined
    ? undefined
    : projects.value.find(item => item.id === matter.projectId)
  const root = matter.projectRootId
    ? projects.value.find(item => item.id === matter.projectRootId)
    : project?.parentId
      ? projects.value.find(item => item.id === project.parentId)
      : project
  const rootId = matter.projectRootId ?? root?.id ?? matter.projectId
  const displayName = root && project && root.id !== project.id
    ? `${root.name}-${project.name}`
    : matter.projectName || root?.name || 'BU 内部事项'
  return { rootId, displayName }
}

// 列表模式的项目筛选只按主项目聚合；事项本身仍保留“主项目-子项目”的上下文。
const rootProjectOptions = computed(() => projects.value.filter(project => project.parentId == null))

const meetingGroups = computed<MeetingGroup[]>(() => {
  const grouped = new Map<string, { label: string; matters: BuKeyMatter[] }>()
  for (const matter of meetingMatters.value) {
    const key = meetingGroupBy.value === 'owner'
      ? `owner-${matter.ownerId ?? 'none'}`
      : `project-${projectPresentation(matter).rootId ?? 'none'}`
    const label = meetingGroupBy.value === 'owner'
      ? (matter.ownerName || '未指定负责人')
      : projectPresentation(matter).displayName
    const group = grouped.get(key) || { label, matters: [] }
    group.matters.push(matter)
    grouped.set(key, group)
  }
  return Array.from(grouped, ([key, group]) => {
    const contextLabels = Array.from(new Set(group.matters.map(matter => meetingGroupBy.value === 'owner'
      ? projectPresentation(matter).displayName
      : (matter.ownerName || '未指定负责人'))))
    return {
      key,
      label: group.label,
      subtitle: `${meetingGroupBy.value === 'owner' ? '事项负责人' : '关联成员'} · ${contextLabels.slice(0, 2).join('、')}`,
      matters: group.matters,
      riskCount: group.matters.filter(matter => ['有风险', '已阻塞'].includes(meetingStatus(matter))).length,
      updatedCount: group.matters.filter(matter => requiresWeeklyUpdate(matter) && Boolean(reportUpdate(matter))).length,
      updateRequiredCount: group.matters.filter(requiresWeeklyUpdate).length,
      averageProgress: Math.round(
        group.matters.reduce((total, matter) => total + meetingProgress(matter), 0) / group.matters.length
      )
    }
  })
})

const meetingSummary = computed(() => {
  const total = meetingMatters.value.length
  const updateRequiredCount = meetingMatters.value.filter(requiresWeeklyUpdate).length
  const updated = meetingMatters.value
    .filter(matter => requiresWeeklyUpdate(matter) && Boolean(reportUpdate(matter))).length
  const risks = meetingMatters.value.filter(matter => ['有风险', '已阻塞'].includes(meetingStatus(matter))).length
  const averageProgress = total
    ? Math.round(meetingMatters.value.reduce((sum, matter) => sum + meetingProgress(matter), 0) / total)
    : 0
  return {
    total,
    updated,
    pending: Math.max(updateRequiredCount - updated, 0),
    risks,
    averageProgress,
    updatedRate: updateRequiredCount ? Math.round(updated / updateRequiredCount * 100) : 100,
    pendingRate: updateRequiredCount ? Math.round((updateRequiredCount - updated) / updateRequiredCount * 100) : 0
  }
})

const presentationGroups = computed<PresentationGroup[]>(() => {
  const grouped = new Map<string, BuKeyMatter[]>()
  meetingMatters.value.forEach(matter => {
    const key = presentationGroupBy.value === 'project'
      ? `project-${projectPresentation(matter).rootId ?? 'none'}`
      : `owner-${matter.ownerId ?? 'none'}`
    grouped.set(key, [...(grouped.get(key) || []), matter])
  })
  return Array.from(grouped, ([key, groupedMatters]) => ({
    key,
    label: presentationGroupBy.value === 'project'
      ? projectPresentation(groupedMatters[0]).displayName
      : (groupedMatters[0].ownerName || '未指定负责人'),
    matters: groupedMatters,
    riskCount: groupedMatters.filter(matter => ['有风险', '已阻塞'].includes(meetingStatus(matter))).length,
    updatedCount: groupedMatters.filter(matter => requiresWeeklyUpdate(matter) && Boolean(reportUpdate(matter))).length,
    updateRequiredCount: groupedMatters.filter(requiresWeeklyUpdate).length,
    averageProgress: Math.round(
      groupedMatters.reduce((total, matter) => total + meetingProgress(matter), 0) / groupedMatters.length
    )
  }))
})

// 演示导航顺序必须与当前分组视图一致：先走完一个项目/负责人组，再进入下一个组。
const presentationOrderedMatters = computed(() => presentationGroups.value.flatMap(group => group.matters))
const presentationMatter = computed(() => presentationOrderedMatters.value[presentationIndex.value])
const presentationUpdate = computed(() => presentationMatter.value ? reportUpdate(presentationMatter.value) : undefined)
const presentationRequiresUpdate = computed(() => presentationMatter.value ? requiresWeeklyUpdate(presentationMatter.value) : false)

const currentPresentationGroupKey = computed(() => {
  const matter = presentationMatter.value
  if (!matter) return ''
  return presentationGroupBy.value === 'project'
    ? `project-${projectPresentation(matter).rootId ?? 'none'}`
    : `owner-${matter.ownerId ?? 'none'}`
})

const previousPresentationMatter = computed(() => {
  if (!presentationOrderedMatters.value.length) return undefined
  return presentationOrderedMatters.value[
    (presentationIndex.value - 1 + presentationOrderedMatters.value.length) % presentationOrderedMatters.value.length
  ]
})

const nextPresentationMatter = computed(() => {
  if (!presentationOrderedMatters.value.length) return undefined
  return presentationOrderedMatters.value[(presentationIndex.value + 1) % presentationOrderedMatters.value.length]
})

const milestoneGroups = computed<MilestoneGroup[]>(() => {
  const grouped = new Map<string, BuKeyMatter[]>()
  milestoneMatters.value
    .filter(matter => matter.plannedCompletionDate?.startsWith(selectedMilestoneMonth.value))
    .sort((left, right) => left.plannedCompletionDate.localeCompare(right.plannedCompletionDate)
      || left.sortOrder - right.sortOrder)
    .forEach(matter => {
      const group = grouped.get(matter.plannedCompletionDate) || []
      group.push(matter)
      grouped.set(matter.plannedCompletionDate, group)
    })
  return Array.from(grouped, ([date, groupedMatters]) => ({
    date,
    label: formatChineseDay(date),
    matters: groupedMatters
  }))
})

const milestoneItems = computed<MilestoneItem[]>(() => milestoneGroups.value.map(group => ({
  key: group.date,
  date: group.date,
  label: group.label,
  matters: group.matters
})))

const milestoneTitle = computed(() => {
  const [, month] = selectedMilestoneMonth.value.split('-').map(Number)
  return `${month}月大事儿`
})

function currentWeekStart() {
  const date = new Date()
  date.setHours(12, 0, 0, 0)
  const daysAfterMonday = (date.getDay() + 6) % 7
  date.setDate(date.getDate() - daysAfterMonday)
  return formatDate(date)
}

function formatDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseDate(value: string) {
  return new Date(`${value}T12:00:00`)
}

function formatChineseDay(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'short'
  }).format(parseDate(value))
}

function previousWeekStart(value: string) {
  const date = parseDate(value)
  date.setDate(date.getDate() - 7)
  return formatDate(date)
}

function emptyMatterForm(): MatterFormState {
  return {
    title: '',
    description: '',
    projectId: undefined,
    ownerId: undefined,
    priority: 'P1',
    status: '未开始',
    progress: 0,
    startDate: formatDate(new Date()),
    plannedCompletionDate: '',
    sortOrder: 0
  }
}

function normalizeRecords<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) return payload as T[]
  if (!payload || typeof payload !== 'object') return []
  const envelope = payload as RecordEnvelope<T>
  if (Array.isArray(envelope.records)) return envelope.records
  if (Array.isArray(envelope.data)) return envelope.data
  if (envelope.data && Array.isArray(envelope.data.records)) return envelope.data.records
  return []
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function loadBaseData() {
  try {
    const [projectPayload, userPayload] = await Promise.all([
      api.getProjects({ page: 1, size: 500 }),
      api.getUsers({ page: 1, size: 500, status: 1 })
    ])
    projects.value = normalizeRecords<ProjectOption>(projectPayload)
    // 负责人可从全部启用的团队成员中选择，管理员也属于可分派成员。
    users.value = normalizeRecords<UserOption>(userPayload)
      .filter(user => user.status !== 0)
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '负责人和项目选项加载失败')
  }
}

async function loadMatters() {
  loading.value = true
  loadError.value = ''
  try {
    const query = {
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      priority: filters.priority || undefined,
      ownerId: filters.ownerId,
      projectId: filters.projectId
    }
    const hasFilters = Boolean(query.keyword || query.status || query.priority
      || query.ownerId !== undefined || query.projectId !== undefined)
    const [result, completeResult] = await Promise.all([
      api.getKeyMatters(query),
      hasFilters ? api.getKeyMatters() : Promise.resolve(undefined)
    ])
    matters.value = result
    allMatters.value = completeResult ?? result
    clampCurrentPage()
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '大事儿台账加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMeeting() {
  loading.value = true
  loadError.value = ''
  try {
    meetingMatters.value = await api.getKeyMatterMeeting(selectedWeek.value)
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '周会数据加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMilestones() {
  loading.value = true
  loadError.value = ''
  try {
    milestoneMatters.value = await api.getKeyMatters()
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '里程碑数据加载失败')
  } finally {
    loading.value = false
  }
}

function startMeetingMode() {
  const meetingUrl = router.resolve({ name: 'KeyMattersMeeting' }).href
  window.open(meetingUrl, '_blank', 'noopener,noreferrer')
}

async function refreshActiveMode() {
  if (mode.value === 'meeting') return loadMeeting()
  await Promise.all([loadMatters(), loadMilestones()])
}

function resetFilters() {
  Object.assign(filters, {
    keyword: '',
    status: '',
    priority: '',
    ownerId: undefined,
    projectId: undefined
  })
  resetCurrentPage()
  loadMatters()
}

const listProjectGroups = computed<QuickListGroup[]>(() => {
  const grouped = new Map<string, QuickListGroup>()
  allMatters.value.forEach(matter => {
    const project = projectPresentation(matter)
    const key = `project-${project.rootId ?? 'none'}`
    const rootProject = project.rootId === undefined
      ? undefined
      : projects.value.find(item => item.id === project.rootId)
    const group = grouped.get(key) || {
      key,
      label: rootProject?.name || (project.rootId === undefined ? '未关联项目' : project.displayName),
      count: 0,
      id: project.rootId
    }
    group.count += 1
    grouped.set(key, group)
  })
  return Array.from(grouped.values()).sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))
})

const listOwnerGroups = computed<QuickListGroup[]>(() => {
  const grouped = new Map<string, QuickListGroup>()
  allMatters.value.forEach(matter => {
    const key = `owner-${matter.ownerId ?? 'none'}`
    const group = grouped.get(key) || {
      key,
      label: matter.ownerName || '未指定负责人',
      count: 0,
      id: matter.ownerId ?? undefined
    }
    group.count += 1
    grouped.set(key, group)
  })
  return Array.from(grouped.values()).sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))
})

const listFilterActive = computed(() => filters.ownerId !== undefined || filters.projectId !== undefined)

function applyQuickListFilter(type: 'project' | 'owner', id?: number) {
  filters.keyword = ''
  filters.status = ''
  filters.priority = ''
  filters.projectId = type === 'project' ? id : undefined
  filters.ownerId = type === 'owner' ? id : undefined
  resetCurrentPage()
  loadMatters()
}

function openCreate() {
  editingMatterId.value = undefined
  Object.assign(matterForm, emptyMatterForm())
  matterDrawer.value = true
}

function openEdit(matter: BuKeyMatter) {
  editingMatterId.value = matter.id
  Object.assign(matterForm, {
    title: matter.title,
    description: matter.description || '',
    projectId: matter.projectId,
    ownerId: matter.ownerId,
    priority: matter.priority,
    status: matter.status,
    progress: matter.progress,
    startDate: matter.startDate,
    plannedCompletionDate: matter.plannedCompletionDate,
    sortOrder: matter.sortOrder || 0
  })
  detailDrawer.value = false
  matterDrawer.value = true
}

function handleMatterStatusChange(status: string | number | boolean | undefined) {
  if (status === '已完成') matterForm.progress = 100
  if (status !== '已完成' && matterForm.progress === 100) matterForm.progress = 99
}

async function saveMatter() {
  if (!matterFormRef.value) return
  const valid = await matterFormRef.value.validate().catch(() => false)
  if (!valid || matterForm.ownerId === undefined) return
  if (matterForm.plannedCompletionDate < matterForm.startDate) {
    ElMessage.warning('计划完成日期不能早于开始日期')
    return
  }
  const payload: BuKeyMatterPayload = {
    title: matterForm.title.trim(),
    description: matterForm.description.trim() || undefined,
    projectId: matterForm.projectId,
    ownerId: matterForm.ownerId,
    priority: matterForm.priority,
    status: matterForm.status,
    progress: matterForm.status === '已完成' ? 100 : matterForm.progress,
    startDate: matterForm.startDate,
    plannedCompletionDate: matterForm.plannedCompletionDate,
    sortOrder: matterForm.sortOrder
  }
  matterSaving.value = true
  try {
    if (editingMatterId.value) {
      await api.updateKeyMatter(editingMatterId.value, payload)
      ElMessage.success('事项已更新')
    } else {
      await api.createKeyMatter(payload)
      ElMessage.success('事项已创建')
    }
    matterDrawer.value = false
    await refreshActiveMode()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '事项保存失败'))
  } finally {
    matterSaving.value = false
  }
}

async function confirmDeleteMatter(matter: BuKeyMatter) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${matter.title}」吗？其全部周进展也会删除。`,
      '删除大事儿',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await api.deleteKeyMatter(matter.id)
    ElMessage.success('事项已删除')
    detailDrawer.value = false
    await refreshActiveMode()
  } catch (error: unknown) {
    if (error instanceof Error) ElMessage.error(errorMessage(error, '事项删除失败'))
  }
}

async function openDetail(matter: BuKeyMatter) {
  selectedMatter.value = matter
  detailDrawer.value = true
  detailLoading.value = true
  try {
    const detail = await api.getKeyMatter(matter.id)
    if (detail && !Array.isArray(detail) && detail.id === matter.id) {
      selectedMatter.value = detail
    }
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '事项详情加载失败'))
  } finally {
    detailLoading.value = false
  }
}

function openWeekly(matter: BuKeyMatter, week = selectedWeek.value) {
  const update = matter.weeklyUpdates?.find(item => item.weekStartDate === week)
    || (matter.currentWeekUpdate?.weekStartDate === week ? matter.currentWeekUpdate : undefined)
  if (isCompletedMatter(matter) && !update) {
    ElMessage.info('本周已完成，无需更新')
    return
  }
  weeklyMatterId.value = matter.id
  weeklyDate.value = week
  weeklyEditingExisting.value = Boolean(update)
  Object.assign(weeklyForm, {
    status: update?.status || matter.status,
    progress: update?.progress ?? matter.progress,
    progressSummary: update?.progressSummary || '',
    issues: update?.issues || '',
    nextWeekPlan: update?.nextWeekPlan || '',
    supportNeeded: update?.supportNeeded || ''
  })
  detailDrawer.value = false
  weeklyDrawer.value = true
}

function handleWeeklyStatusChange(status: string | number | boolean | undefined) {
  if (status === '已完成') weeklyForm.progress = 100
  if (status !== '已完成' && weeklyForm.progress === 100) weeklyForm.progress = 99
}

function handlePresentationStatusChange(status: string | number | boolean | undefined) {
  if (status === '已完成') presentationForm.progress = 100
  if (status !== '已完成' && presentationForm.progress === 100) presentationForm.progress = 99
}

function handlePresentationProgressInput(value: number | number[]) {
  if (Array.isArray(value)) return
  presentationForm.progress = Math.round(value)
  if (presentationForm.progress === 100) {
    presentationForm.status = '已完成'
  } else if (presentationForm.status === '已完成') {
    presentationForm.status = '推进中'
  }
}

function selectPresentationStatus(status: string) {
  presentationForm.status = status
  handlePresentationStatusChange(status)
}

async function saveWeeklyUpdate() {
  if (!weeklyFormRef.value || weeklyMatterId.value === undefined) return
  const valid = await weeklyFormRef.value.validate().catch(() => false)
  if (!valid) return
  weeklySaving.value = true
  try {
    await api.upsertKeyMatterWeeklyUpdate(weeklyMatterId.value, weeklyDate.value, {
      ...weeklyForm,
      progress: weeklyForm.status === '已完成' ? 100 : weeklyForm.progress,
      progressSummary: weeklyForm.progressSummary.trim(),
      issues: weeklyForm.issues?.trim() || undefined,
      nextWeekPlan: weeklyForm.nextWeekPlan?.trim() || undefined,
      supportNeeded: weeklyForm.supportNeeded?.trim() || undefined
    })
    ElMessage.success('周进展已保存')
    weeklyDrawer.value = false
    await refreshActiveMode()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '周进展保存失败'))
    if (!weeklyEditingExisting.value && isCompletedWeeklyUpdateError(error)) {
      weeklyDrawer.value = false
      await refreshActiveMode()
    }
  } finally {
    weeklySaving.value = false
  }
}

async function confirmDeleteWeekly(matter: BuKeyMatter, update: BuKeyMatterWeeklyUpdate) {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${update.weekStartDate} 这一周的进展吗？`,
      '删除周进展',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await api.deleteKeyMatterWeeklyUpdate(matter.id, update.weekStartDate)
    ElMessage.success('周进展已删除')
    await openDetail(matter)
    await loadMatters()
  } catch (error: unknown) {
    if (error instanceof Error) ElMessage.error(errorMessage(error, '周进展删除失败'))
  }
}

function isFemaleOwner(name?: string) {
  return Boolean(name && femaleOwnerNames.has(name.trim()))
}

function statusType(status: string) {
  if (status === '已完成') return 'success'
  if (status === '有风险') return 'warning'
  if (status === '已阻塞') return 'danger'
  if (status === '已暂停') return 'info'
  return 'primary'
}

function listStatusTone(status: string) {
  const tones: Record<string, string> = {
    '未开始': 'not-started',
    '推进中': 'progressing',
    '有风险': 'risk',
    '已阻塞': 'blocked',
    '已完成': 'completed',
    '已暂停': 'paused'
  }
  return tones[status] || 'default'
}

function reportUpdate(matter: BuKeyMatter) {
  return matter.currentWeekUpdate
}

function meetingStatus(matter: BuKeyMatter) {
  return reportUpdate(matter)?.status || matter.status
}

function meetingProgress(matter: BuKeyMatter) {
  return reportUpdate(matter)?.progress ?? matter.progress
}

function weekComparison(matter: BuKeyMatter) {
  const current = reportUpdate(matter)
  if (!current) {
    if (isCompletedMatter(matter)) return { label: '本周已完成，无需更新', tone: 'complete' }
    return { label: '本周待更新', tone: 'missing' }
  }
  const previousWeek = previousWeekStart(selectedWeek.value)
  const previous = matter.weeklyUpdates?.find(update => update.weekStartDate === previousWeek)
  return progressComparison(current.progress, previous?.progress, '上周无数据')
}

function progressComparison(current: number, previous: number | undefined, missingLabel = '暂无对比数据') {
  if (previous === undefined) return { label: missingLabel, tone: 'muted' }
  const delta = current - previous
  if (delta > 0) return { label: `较上周 +${delta}%`, tone: 'up' }
  if (delta < 0) return { label: `较上周 ${delta}%`, tone: 'down' }
  return { label: '较上周持平', tone: 'flat' }
}

function detailComparison(matter: BuKeyMatter) {
  const latest = matter.latestUpdate || matter.weeklyUpdates?.[0]
  if (!latest) return { label: '尚无周进展', tone: 'missing' }
  const previous = matter.weeklyUpdates
    ?.find(update => update.weekStartDate < latest.weekStartDate)
  return progressComparison(latest.progress, previous?.progress)
}

function historyComparison(matter: BuKeyMatter, index: number) {
  const update = matter.weeklyUpdates[index]
  if (!update) return { label: '基线', tone: 'muted' }
  const previous = matter.weeklyUpdates[index + 1]
  if (!previous) return { label: '基线', tone: 'muted' }
  const comparison = progressComparison(update.progress, previous.progress)
  return {
    ...comparison,
    label: comparison.tone === 'up'
      ? `+${update.progress - previous.progress}%`
      : comparison.tone === 'down'
        ? `${update.progress - previous.progress}%`
        : '持平'
  }
}

function milestoneTiming(matter: BuKeyMatter) {
  if (matter.status === '已完成') return { label: '已完成', tone: 'complete' }
  const today = parseDate(formatDate(new Date()))
  const dueDate = parseDate(matter.plannedCompletionDate)
  const days = Math.round((dueDate.getTime() - today.getTime()) / 86_400_000)
  if (days < 0) return { label: `逾期 ${Math.abs(days)} 天`, tone: 'overdue' }
  if (days === 0) return { label: '今日到期', tone: 'today' }
  return { label: `${days} 天后`, tone: 'upcoming' }
}

function milestoneGroupTone(group: MilestoneItem) {
  const hasRisk = group.matters.some(matter => {
    const timing = milestoneTiming(matter)
    return timing.tone === 'overdue' || ['有风险', '已阻塞'].includes(matter.status)
  })
  if (hasRisk) return 'risk'
  if (group.matters.every(matter => matter.status === '已完成')) return 'complete'
  if (group.matters.some(matter => milestoneTiming(matter).tone === 'today')) return 'today'
  return 'upcoming'
}

function milestoneGroupSymbol(group: MilestoneItem) {
  const tone = milestoneGroupTone(group)
  if (tone === 'complete') return '✓'
  if (tone === 'today') return '◷'
  if (tone === 'risk') return '!'
  return '?'
}

function openMilestoneNode(group: MilestoneItem) {
  if (group.matters.length === 1) {
    openDetail(group.matters[0])
  }
}

function updateMilestoneScrollState() {
  const scroller = milestoneScroller.value
  if (!scroller) {
    milestoneCanScrollLeft.value = false
    milestoneCanScrollRight.value = false
    return
  }
  const maxScrollLeft = Math.max(scroller.scrollWidth - scroller.clientWidth, 0)
  milestoneCanScrollLeft.value = scroller.scrollLeft > 2
  milestoneCanScrollRight.value = scroller.scrollLeft < maxScrollLeft - 2
}

function scrollMilestones(direction: -1 | 1) {
  const scroller = milestoneScroller.value
  if (!scroller) return
  scroller.scrollBy({
    left: direction * Math.max(scroller.clientWidth * 0.72, 320),
    behavior: 'smooth'
  })
  window.setTimeout(updateMilestoneScrollState, 260)
}

async function toggleMilestones() {
  milestoneExpanded.value = !milestoneExpanded.value
  if (!milestoneExpanded.value) {
    activeMilestoneItem.value = null
    return
  }
  await refreshMilestoneViewport(true)
}

async function refreshMilestoneViewport(reset = true) {
  activeMilestoneItem.value = null
  await nextTick()
  if (reset && milestoneScroller.value) {
    milestoneScroller.value.scrollLeft = 0
  }
  updateMilestoneScrollState()
}

function showMilestonePopover(item: MilestoneItem, event: MouseEvent | FocusEvent) {
  if (milestonePopoverHideTimer !== undefined) {
    window.clearTimeout(milestonePopoverHideTimer)
    milestonePopoverHideTimer = undefined
  }
  activeMilestoneItem.value = item
  const trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : undefined
  if (!trigger) return
  const rect = trigger.getBoundingClientRect()
  const popoverWidth = 320
  const left = Math.min(
    Math.max(rect.left + rect.width / 2, popoverWidth / 2 + 12),
    window.innerWidth - popoverWidth / 2 - 12
  )
  milestonePopoverStyle.value = {
    left: `${left}px`,
    top: `${rect.bottom + 10}px`
  }
}

function hideMilestonePopover() {
  if (milestonePopoverHideTimer !== undefined) {
    window.clearTimeout(milestonePopoverHideTimer)
  }
  milestonePopoverHideTimer = window.setTimeout(() => {
    activeMilestoneItem.value = null
    milestonePopoverHideTimer = undefined
  }, 140)
}

function keepMilestonePopover() {
  if (milestonePopoverHideTimer !== undefined) {
    window.clearTimeout(milestonePopoverHideTimer)
    milestonePopoverHideTimer = undefined
  }
}

function splitProgressSummary(summary?: string) {
  const items = (summary || '')
    .split(/\r?\n|[；;]/)
    .map(item => item.replace(/^\s*\d+[、.]\s*/, '').trim())
    .filter(Boolean)
  return items.length ? items : ['', '']
}

function draftFromMatter(matter: BuKeyMatter): PresentationDraft {
  const update = reportUpdate(matter)
  return {
    status: update?.status || matter.status,
    progress: update?.progress ?? matter.progress,
    progressSummary: update?.progressSummary || '',
    issues: update?.issues || '',
    nextWeekPlan: update?.nextWeekPlan || '',
    supportNeeded: update?.supportNeeded || ''
  }
}

function hydratePresentationForm(forceEdit = false) {
  const matter = presentationMatter.value
  if (!matter) return
  const draft = presentationDrafts.get(matter.id) || draftFromMatter(matter)
  Object.assign(presentationForm, {
    status: draft.status,
    progress: draft.progress,
    progressSummary: draft.progressSummary,
    issues: draft.issues,
    nextWeekPlan: draft.nextWeekPlan,
    supportNeeded: draft.supportNeeded
  })
  if (isCompletedMatter(matter)) {
    presentationEditing.value = false
    return
  }
  presentationEditing.value = forceEdit || !reportUpdate(matter) || presentationDrafts.has(matter.id)
}

function cachePresentationDraft() {
  const matter = presentationMatter.value
  if (!matter || !presentationEditing.value) return
  presentationDrafts.set(matter.id, {
    status: presentationForm.status,
    progress: presentationForm.progress,
    progressSummary: presentationForm.progressSummary,
    issues: presentationForm.issues || '',
    nextWeekPlan: presentationForm.nextWeekPlan || '',
    supportNeeded: presentationForm.supportNeeded || ''
  })
}

function openPresentation(index = 0, requestFullscreen = !isMeetingStandalone.value) {
  if (!presentationOrderedMatters.value.length) return
  presentationIndex.value = Math.max(0, Math.min(index, presentationOrderedMatters.value.length - 1))
  presentationMode.value = true
  document.body.classList.add('key-matters-presentation')
  hydratePresentationForm()
  nextTick(() => presentationStageRef.value?.focus())
  if (requestFullscreen) {
    const fullscreenRequest = document.documentElement.requestFullscreen?.()
    if (fullscreenRequest) void fullscreenRequest.catch(() => undefined)
  }
}

function movePresentation(delta: number) {
  if (!presentationOrderedMatters.value.length) return
  cachePresentationDraft()
  presentationIndex.value = (presentationIndex.value + delta + presentationOrderedMatters.value.length)
    % presentationOrderedMatters.value.length
  hydratePresentationForm()
}

function jumpPresentation(index: number) {
  cachePresentationDraft()
  presentationIndex.value = index
  hydratePresentationForm()
}

function setPresentationGroupBy(groupBy: PresentationGroupBy) {
  const currentMatterId = presentationMatter.value?.id
  cachePresentationDraft()
  presentationGroupBy.value = groupBy
  const nextIndex = presentationOrderedMatters.value.findIndex(matter => matter.id === currentMatterId)
  presentationIndex.value = nextIndex >= 0 ? nextIndex : 0
  hydratePresentationForm()
}

function selectPresentationGroup(group: PresentationGroup) {
  const currentIndex = group.matters.some(matter => matter.id === presentationMatter.value?.id)
    ? presentationIndex.value
    : presentationOrderedMatters.value.findIndex(matter => matter.id === group.matters[0]?.id)
  if (currentIndex >= 0) jumpPresentation(currentIndex)
}

function presentationIndexOf(matterId: number) {
  return presentationOrderedMatters.value.findIndex(matter => matter.id === matterId)
}

function startPresentationEdit() {
  const matter = presentationMatter.value
  if (!matter || isCompletedMatter(matter)) return
  hydratePresentationForm(true)
}

function stashPresentationDraft() {
  cachePresentationDraft()
  ElMessage.success('草稿已暂存，本次周会期间可继续编辑')
}

async function savePresentationAndNext() {
  const matter = presentationMatter.value
  if (!matter) return
  if (isCompletedMatter(matter)) return
  const progressSummary = presentationForm.progressSummary.trim()
  if (!progressSummary) {
    ElMessage.warning('请至少填写一项本周进展')
    return
  }
  presentationSaving.value = true
  const nextIndex = Math.min(presentationIndex.value + 1, presentationOrderedMatters.value.length - 1)
  const nextMatterId = presentationOrderedMatters.value[nextIndex]?.id
  try {
    await api.upsertKeyMatterWeeklyUpdate(matter.id, selectedWeek.value, {
      status: presentationForm.status,
      progress: presentationForm.status === '已完成' ? 100 : presentationForm.progress,
      progressSummary,
      issues: presentationForm.issues?.trim() || undefined,
      nextWeekPlan: presentationForm.nextWeekPlan?.trim() || undefined,
      supportNeeded: presentationForm.supportNeeded?.trim() || undefined
    })
    presentationDrafts.delete(matter.id)
    ElMessage.success(nextIndex === presentationIndex.value ? '周报已保存' : '周报已保存，已切换到下一项')
    await loadMeeting()
    const refreshedNextIndex = presentationOrderedMatters.value.findIndex(item => item.id === nextMatterId)
    presentationIndex.value = refreshedNextIndex >= 0
      ? refreshedNextIndex
      : Math.min(nextIndex, Math.max(presentationOrderedMatters.value.length - 1, 0))
    hydratePresentationForm()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '周报保存失败'))
    if (isCompletedWeeklyUpdateError(error)) {
      presentationDrafts.delete(matter.id)
      await loadMeeting()
      hydratePresentationForm()
    }
  } finally {
    presentationSaving.value = false
  }
}

function handlePresentationKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented) return
  const target = event.target as HTMLElement | null
  const isEditingText = target?.matches('input, textarea, [contenteditable="true"]')

  // 周会列表视图：F 进入全屏演示
  if (!presentationMode.value && mode.value === 'meeting') {
    if (isEditingText) return
    if (event.key === 'f' || event.key === 'F') {
      event.preventDefault()
      openPresentation(0, true)
    }
    return
  }

  if (!presentationMode.value) return

  if (isEditingText && event.key !== 'Escape') return
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    movePresentation(-1)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    movePresentation(1)
  } else if (event.key === 'Escape' && document.fullscreenElement) {
    event.preventDefault()
    const exitRequest = document.exitFullscreen?.()
    if (exitRequest) void exitRequest.catch(() => undefined)
  } else if (event.key === 'f' || event.key === 'F') {
    event.preventDefault()
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen?.()?.catch(() => undefined)
    } else {
      document.exitFullscreen?.()?.catch(() => undefined)
    }
  }
}

function handlePresentationFullscreenChange() {
  if (presentationMode.value) nextTick(() => presentationStageRef.value?.focus())
}

watch(selectedMilestoneMonth, () => {
  void refreshMilestoneViewport(true)
})

watch(milestoneItems, () => {
  void refreshMilestoneViewport(false)
}, { flush: 'post' })

onMounted(async () => {
  window.addEventListener('keydown', handlePresentationKeydown)
  window.addEventListener('resize', updateMilestoneScrollState)
  document.addEventListener('fullscreenchange', handlePresentationFullscreenChange)
  if (isMeetingStandalone.value) {
    mode.value = 'meeting'
    document.body.classList.add('key-matters-presentation')
    await Promise.all([loadBaseData(), loadMeeting()])
    if (meetingMatters.value.length) {
      openPresentation(0, false)
    } else {
      presentationMode.value = true
    }
    standaloneMeetingReady.value = true
    await nextTick()
    presentationStageRef.value?.focus()
  } else {
    await Promise.all([loadBaseData(), loadMatters(), loadMilestones()])
    await refreshMilestoneViewport(true)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handlePresentationKeydown)
  window.removeEventListener('resize', updateMilestoneScrollState)
  document.removeEventListener('fullscreenchange', handlePresentationFullscreenChange)
  if (milestonePopoverHideTimer !== undefined) {
    window.clearTimeout(milestonePopoverHideTimer)
  }
  activeMilestoneItem.value = null
  document.body.classList.remove('key-matters-presentation')
  if (document.fullscreenElement) {
    const exitRequest = document.exitFullscreen?.()
    if (exitRequest) void exitRequest.catch(() => undefined)
  }
})
</script>

<template>
  <div class="key-matters-page" :class="{ 'standalone-meeting-page': isMeetingStandalone }">
    <section v-if="!isMeetingStandalone" class="page-toolbar" aria-label="大事儿操作栏">
      <div class="register-titlebar">
        <h1>{{ mode === 'register' ? milestoneTitle : '周会汇报' }}</h1>
        <p>{{ mode === 'register' ? '按计划完成日期查看大事儿交付节点' : modeNote }}</p>
      </div>
      <section v-if="mode === 'register'" class="summary-strip toolbar-summary" aria-label="事项概览">
        <div class="summary-cell all">
          <div class="summary-label"><span><el-icon><Document /></el-icon></span>全部事项</div>
          <div class="summary-value"><strong>{{ summary.total }}</strong><small>跟踪</small></div>
          <div class="summary-meter"><i :style="{ width: '100%' }" /></div>
        </div>
        <div class="summary-cell progressing">
          <div class="summary-label"><span><el-icon><VideoPlay /></el-icon></span>推进中</div>
          <div class="summary-value"><strong>{{ summary.progressing }}</strong><small>{{ summary.progressingRate }}%</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.progressingRate}%` }" /></div>
        </div>
        <div class="summary-cell risk">
          <div class="summary-label"><span><el-icon><Warning /></el-icon></span>风险/阻塞</div>
          <div class="summary-value"><strong>{{ summary.risks }}</strong><small>{{ summary.riskRate }}%</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.riskRate}%` }" /></div>
        </div>
        <div class="summary-cell pending">
          <div class="summary-label"><span><el-icon><Calendar /></el-icon></span>待更新</div>
          <div class="summary-value"><strong>{{ summary.pendingUpdate }}</strong><small>{{ summary.updatedCount }}/{{ summary.updateRequiredCount }}</small></div>
          <div class="summary-meter"><i :style="{ width: `${summary.updateRequiredCount ? summary.updatedCount / summary.updateRequiredCount * 100 : 100}%` }" /></div>
        </div>
      </section>
      <div class="toolbar-actions">
        <el-tooltip content="进入周会全屏" placement="bottom">
          <el-button
            class="meeting-mode-trigger"
            :type="mode === 'meeting' ? 'primary' : 'default'"
            circle
            :aria-label="mode === 'meeting' ? '重新进入周会全屏' : '进入周会全屏'"
            @click="startMeetingMode"
          >
            <el-icon><Monitor /></el-icon>
          </el-button>
        </el-tooltip>
        <el-date-picker
          v-if="mode === 'register'"
          v-model="selectedMilestoneMonth"
          type="month"
          value-format="YYYY-MM"
          format="YYYY年MM月"
          aria-label="里程碑月份"
          :clearable="false"
        />
        <el-button
          v-if="mode === 'register'"
          :icon="Refresh"
          aria-label="刷新"
          @click="refreshActiveMode"
        />
        <el-button type="primary" :icon="Plus" @click="openCreate">新增事项</el-button>
      </div>
    </section>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :title="loadError"
      show-icon
      :closable="false"
    />

    <section
      v-if="isMeetingStandalone && !standaloneMeetingReady"
      v-loading="true"
      class="meeting-bootstrap"
      aria-label="周会模式加载中"
      element-loading-text="周会数据加载中"
    />

    <template v-else-if="mode === 'register'">
      <section
        class="milestone-top-section"
        :class="{ collapsed: !milestoneExpanded }"
        aria-label="列表顶部里程碑"
      >
        <header class="milestone-collapse-header">
          <div>
            <span>交付里程碑</span>
            <small>{{ milestoneItems.length }} 个节点</small>
          </div>
          <button
            type="button"
            :aria-label="milestoneExpanded ? '收起里程碑' : '展开里程碑'"
            :aria-expanded="milestoneExpanded"
            @click="toggleMilestones"
          >
            <span>{{ milestoneExpanded ? '收起' : '展开' }}</span>
            <el-icon :class="{ expanded: milestoneExpanded }"><ArrowDown /></el-icon>
          </button>
        </header>
        <section v-if="milestoneExpanded" v-loading="loading" class="milestone-panel" aria-label="里程碑时间线">
          <el-empty v-if="!loading && milestoneItems.length === 0" description="本月暂无计划完成的事项" />
          <template v-else>
            <button
              type="button"
              class="milestone-slide-button previous"
              :disabled="!milestoneCanScrollLeft"
              aria-label="向左查看里程碑"
              @click="scrollMilestones(-1)"
            >
              ‹
            </button>
            <div ref="milestoneScroller" class="milestone-scroller" @scroll="updateMilestoneScrollState">
              <ol class="milestone-node-track">
                <li
                  v-for="item in milestoneItems"
                  :key="item.key"
                  class="milestone-node-item"
                  :class="`node-${milestoneGroupTone(item)}`"
                  @mouseleave="hideMilestonePopover"
                >
                  <button
                    type="button"
                    class="milestone-node"
                    :aria-label="`${item.date}，${item.matters.length}个事项`"
                    @click="openMilestoneNode(item)"
                    @mouseenter="showMilestonePopover(item, $event)"
                    @focus="showMilestonePopover(item, $event)"
                    @blur="hideMilestonePopover"
                  >
                    <span class="milestone-dot"><i>{{ milestoneGroupSymbol(item) }}</i></span>
                    <time :datetime="item.date">{{ item.date }}</time>
                  </button>
                </li>
              </ol>
            </div>
            <button
              type="button"
              class="milestone-slide-button next"
              :disabled="!milestoneCanScrollRight"
              aria-label="向右查看里程碑"
              @click="scrollMilestones(1)"
            >
              ›
            </button>
          </template>
        </section>
      </section>

      <Teleport to="body">
        <article
          v-if="activeMilestoneItem"
          class="milestone-floating-popover"
          :class="`node-${milestoneGroupTone(activeMilestoneItem)}`"
          :style="milestonePopoverStyle"
          role="tooltip"
          @mouseenter="keepMilestonePopover"
          @mouseleave="hideMilestonePopover"
        >
          <header>
            <span class="milestone-dot"><i>{{ milestoneGroupSymbol(activeMilestoneItem) }}</i></span>
            <strong>{{ activeMilestoneItem.date }} · {{ activeMilestoneItem.matters.length }}个事项</strong>
          </header>
          <div class="milestone-popover-list">
            <button
              v-for="matter in activeMilestoneItem.matters"
              :key="matter.id"
              type="button"
              class="milestone-popover-matter"
              @click="openDetail(matter)"
            >
              <strong>{{ matter.title }}</strong>
              <span>{{ projectPresentation(matter).displayName }}</span>
              <small>
                <em>{{ matter.ownerName || '未指定负责人' }}</em>
                <el-tag :type="statusType(matter.status)" effect="light">{{ matter.status }}</el-tag>
                <b>{{ matter.progress }}%</b>
              </small>
            </button>
          </div>
        </article>
      </Teleport>

      <div class="register-layout">
        <aside class="list-filter-rail" aria-label="列表快速筛选">
          <header class="list-filter-header">
            <div>
              <span>快速筛选</span>
              <strong>事项列表</strong>
            </div>
            <span>{{ allMatters.length }}项</span>
          </header>
          <button
            type="button"
            class="list-filter-all"
            :class="{ active: !listFilterActive }"
            @click="applyQuickListFilter('project')"
          >
            <span class="list-filter-icon all"><el-icon><Document /></el-icon></span>
            <span><strong>全部事项</strong><small>{{ allMatters.length }} 项持续跟踪</small></span>
          </button>
          <section class="list-filter-section" aria-label="按项目筛选">
            <header><span>项目</span><small>{{ listProjectGroups.length }}</small></header>
            <button
              v-for="group in listProjectGroups"
              :key="group.key"
              type="button"
              :class="{ active: filters.projectId === group.id && filters.ownerId === undefined }"
              @click="applyQuickListFilter('project', group.id)"
            >
              <span class="list-filter-icon project"><el-icon><Folder /></el-icon></span>
              <span><strong>{{ group.label }}</strong><small>{{ group.count }} 项</small></span>
            </button>
          </section>
          <section class="list-filter-section" aria-label="按负责人筛选">
            <header><span>负责人</span><small>{{ listOwnerGroups.length }}</small></header>
            <button
              v-for="group in listOwnerGroups"
              :key="group.key"
              type="button"
              :class="{ active: filters.ownerId === group.id && filters.projectId === undefined }"
              @click="applyQuickListFilter('owner', group.id)"
            >
              <span class="list-filter-icon owner" :class="{ female: isFemaleOwner(group.label) }"><el-icon><User /></el-icon></span>
              <span><strong :class="{ 'female-owner-name': isFemaleOwner(group.label) }">{{ group.label }}</strong><small>{{ group.count }} 项</small></span>
            </button>
          </section>
        </aside>

        <div class="register-content">
        <section class="filter-bar" aria-label="事项筛选">
        <el-input
          v-model="filters.keyword"
          class="keyword-input"
          placeholder="搜索标题或说明"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchMatters"
        />
        <el-select v-model="filters.priority" placeholder="优先级" clearable>
          <el-option v-for="item in priorityOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="filters.ownerId" placeholder="负责人" clearable filterable>
          <el-option v-for="user in users" :key="user.id" :label="user.realName" :value="user.id" />
        </el-select>
        <el-select v-model="filters.projectId" placeholder="关联项目" clearable filterable>
          <el-option v-for="project in rootProjectOptions" :key="project.id" :label="project.name" :value="project.id" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="searchMatters">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        </section>

        <section class="table-panel" aria-label="大事儿列表">
        <el-table
          v-loading="loading"
          :data="pagedMatters"
          row-key="id"
          class="matter-table"
          empty-text="暂无大事儿，点击右上角新增事项"
          scrollbar-always-on
          @row-click="openDetail"
        >
          <el-table-column label="优先级" width="72" align="center">
            <template #default="{ row }">
              <span class="priority-mark" :class="row.priority.toLowerCase()">{{ row.priority }}</span>
            </template>
          </el-table-column>
          <el-table-column label="重点事项" min-width="240">
            <template #default="{ row }">
              <div class="matter-title">{{ row.title }}</div>
              <div class="matter-subline">{{ projectPresentation(row).displayName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="负责人" width="90">
            <template #default="{ row }">
              <span class="owner-name" :class="{ female: isFemaleOwner(row.ownerName) }">{{ row.ownerName || '未指定' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <el-tag class="list-status-tag" :class="`status-${listStatusTone(row.status)}`" effect="light">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="135">
            <template #default="{ row }">
              <div class="progress-cell">
                <el-progress :percentage="row.progress" :stroke-width="8" :show-text="false" />
                <span>{{ row.progress }}%</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="计划完成" width="110">
            <template #default="{ row }">
              <span :class="{ 'date-overdue': row.overdue }">{{ row.plannedCompletionDate }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本周进展" width="95" align="center">
            <template #default="{ row }">
              <span v-if="row.currentWeekUpdated" class="updated-state">
                <el-icon><CircleCheck /></el-icon>已更新
              </span>
              <span v-else-if="isCompletedMatter(row)" class="completed-update-state">无需更新</span>
              <span v-else class="pending-state">本周待更新</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="126" fixed="right" align="center">
            <template #default="{ row }">
              <el-tooltip v-if="!isCompletedMatter(row)" content="更新周进展" placement="top">
                <el-button
                  link
                  size="small"
                  type="primary"
                  :icon="Calendar"
                  aria-label="更新周进展"
                  @click.stop="openWeekly(row)"
                />
              </el-tooltip>
              <el-tooltip content="编辑事项" placement="top">
                <el-button link size="small" type="primary" :icon="Edit" aria-label="编辑事项" @click.stop="openEdit(row)" />
              </el-tooltip>
              <el-tooltip content="删除事项" placement="top">
                <el-button link size="small" type="danger" :icon="Delete" aria-label="删除事项" @click.stop="confirmDeleteMatter(row)" />
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <footer class="table-pagination" aria-label="事项列表分页">
          <span>共 {{ matters.length }} 项</span>
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50]"
            :total="matters.length"
            layout="sizes, prev, pager, next"
            background
            @size-change="handlePageSizeChange"
          />
        </footer>
        </section>
        </div>
      </div>
    </template>

    <template v-else-if="mode === 'meeting'">
      <template v-if="!presentationMode">
        <section class="meeting-hero" aria-labelledby="meeting-heading">
          <div class="meeting-heading-lockup">
            <div class="meeting-total" aria-hidden="true">
              <strong>{{ meetingSummary.total }}</strong><span>项</span>
            </div>
            <div>
              <h1 id="meeting-heading">周会汇报</h1>
              <p class="meeting-count">周会汇报 · {{ meetingSummary.total }} 项</p>
              <p>{{ meetingWeekLabel }} · 进行中事项及本周完成事项</p>
            </div>
          </div>
          <div class="grouping-segment" role="group" aria-label="汇总方式">
            <button
              type="button"
              :class="{ active: meetingGroupBy === 'owner' }"
              :aria-pressed="meetingGroupBy === 'owner'"
              @click="meetingGroupBy = 'owner'"
            >
              <el-icon><User /></el-icon>按负责人
            </button>
            <button
              type="button"
              :class="{ active: meetingGroupBy === 'project' }"
              :aria-pressed="meetingGroupBy === 'project'"
              @click="meetingGroupBy = 'project'"
            >
              <el-icon><Folder /></el-icon>按项目
            </button>
          </div>
        </section>

        <section class="meeting-summary-grid" aria-label="周会概览">
          <article class="meeting-stat updated">
            <span class="stat-icon"><el-icon><Select /></el-icon></span>
            <strong>{{ meetingSummary.updated }}</strong>
            <h2>已更新周报</h2>
            <div class="stat-progress"><span :style="{ width: `${meetingSummary.updatedRate}%` }" /></div>
            <p>占总数 {{ meetingSummary.updatedRate }}%</p>
          </article>
          <article class="meeting-stat pending">
            <span class="stat-icon"><el-icon><Clock /></el-icon></span>
            <strong>{{ meetingSummary.pending }}</strong>
            <h2>待更新周报</h2>
            <div class="stat-progress"><span :style="{ width: `${meetingSummary.pendingRate}%` }" /></div>
            <p>占总数 {{ meetingSummary.pendingRate }}%</p>
          </article>
          <article class="meeting-stat risk">
            <span class="stat-icon"><el-icon><Warning /></el-icon></span>
            <strong>{{ meetingSummary.risks }}</strong>
            <h2>存在风险</h2>
            <p class="stat-health"><i />{{ meetingSummary.risks ? '请重点关注' : '全部正常' }}</p>
          </article>
          <article class="meeting-stat progress">
            <span class="stat-icon"><el-icon><Odometer /></el-icon></span>
            <strong>{{ meetingSummary.averageProgress }}%</strong>
            <h2>整体平均进度</h2>
            <div class="stat-progress"><span :style="{ width: `${meetingSummary.averageProgress}%` }" /></div>
          </article>
        </section>

        <section v-loading="loading" class="meeting-list" aria-label="周会汇报事项">
          <el-empty v-if="!loading && meetingMatters.length === 0" description="本周暂无汇报事项" />
          <section v-for="(group, groupIndex) in meetingGroups" :key="group.key" class="meeting-group">
            <header class="meeting-group-header">
              <div class="group-identity">
                <span
                  class="group-avatar"
                  :class="[`tone-${groupIndex % 4}`, { female: meetingGroupBy === 'owner' && isFemaleOwner(group.label) }]"
                >{{ group.label.slice(0, 1) }}</span>
                <div>
                  <h2>{{ group.label }}</h2>
                  <p>{{ group.subtitle }}</p>
                </div>
              </div>
              <dl class="group-summary">
                <div><dd>{{ group.matters.length }}</dd><dt>事项</dt></div>
                <div><dd v-if="group.updateRequiredCount > 0">{{ group.updatedCount }}/{{ group.updateRequiredCount }}</dd><dd v-else>无需更新</dd><dt>已更新</dt></div>
                <div :class="{ risk: group.riskCount > 0 }"><dd>{{ group.riskCount }}</dd><dt>风险</dt></div>
                <div><dd>{{ group.averageProgress }}%</dd><dt>平均进度</dt></div>
              </dl>
            </header>

            <div class="meeting-items-stack">
              <article
                v-for="matter in group.matters"
                :key="matter.id"
                class="meeting-item"
                :class="[`priority-${matter.priority.toLowerCase()}`, `status-${meetingStatus(matter)}`]"
              >
                <header class="meeting-item-header">
                  <div class="meeting-title-group">
                    <span class="priority-mark" :class="matter.priority.toLowerCase()">{{ matter.priority }}</span>
                    <div>
                      <p class="meeting-kicker">{{ projectPresentation(matter).displayName }} · {{ matter.ownerName || '未指定负责人' }}</p>
                      <h3>{{ matter.title }}</h3>
                    </div>
                  </div>
                  <div class="meeting-status">
                    <el-tag :type="statusType(meetingStatus(matter))" effect="light">{{ meetingStatus(matter) }}</el-tag>
                    <span class="matter-progress-inline"><i><b :style="{ width: `${meetingProgress(matter)}%` }" /></i>{{ meetingProgress(matter) }}%</span>
                    <template v-if="isCompletedMatter(matter)">
                      <span class="week-delta tone-complete">本周已完成，无需更新</span>
                    </template>
                    <template v-else>
                      <span class="week-delta" :class="`tone-${weekComparison(matter).tone}`">{{ weekComparison(matter).label }}</span>
                      <el-button
                        v-if="!reportUpdate(matter)"
                        type="warning"
                        size="small"
                        @click="openPresentation(presentationIndexOf(matter.id))"
                      >立即更新</el-button>
                      <el-button v-else link type="primary" :icon="Calendar" @click="openWeekly(matter)">更新周报</el-button>
                    </template>
                  </div>
                </header>

                <div v-if="reportUpdate(matter)" class="meeting-brief" aria-label="周会简报">
                  <section class="meeting-primary-report">
                    <div class="brief-label"><span>01</span> 本周进展</div>
                    <p>{{ reportUpdate(matter)?.progressSummary }}</p>
                  </section>
                  <aside class="meeting-signal-rail">
                    <section class="meeting-signal risk-signal">
                      <span>问题 / 风险</span>
                      <p>{{ reportUpdate(matter)?.issues || '本周暂无风险' }}</p>
                    </section>
                    <section class="meeting-signal decision-signal">
                      <span>需协调 / 决策</span>
                      <p>{{ reportUpdate(matter)?.supportNeeded || '暂无待协调事项' }}</p>
                    </section>
                  </aside>
                  <section class="meeting-next-action">
                    <span class="action-label">下一步行动</span>
                    <p>{{ reportUpdate(matter)?.nextWeekPlan || '待补充下一步行动' }}</p>
                    <time :datetime="matter.plannedCompletionDate">目标 {{ matter.plannedCompletionDate }}</time>
                  </section>
                </div>
                <div v-else-if="isCompletedMatter(matter)" class="completed-no-update" aria-label="已完成事项无需更新">
                  <strong>本周已完成，无需更新</strong>
                  <small>该事项已完成，无需继续提交周进展</small>
                </div>
                <button
                  v-else
                  class="missing-update"
                  type="button"
                  @click="openPresentation(presentationIndexOf(matter.id))"
                >
                  <span class="missing-icon"><el-icon><EditPen /></el-icon></span>
                  <strong>本周待更新</strong>
                  <small>请及时填写本周进展、风险及下一步计划</small>
                </button>

                <footer class="meeting-item-footer">
                  <span><el-icon><User /></el-icon>{{ matter.ownerName || '未指定负责人' }}负责</span>
                  <span><el-icon><Calendar /></el-icon>计划完成 {{ matter.plannedCompletionDate }}</span>
                  <el-button link @click="openDetail(matter)">查看详情 <el-icon><TopRight /></el-icon></el-button>
                </footer>
              </article>
            </div>
          </section>
        </section>
      </template>

      <div v-else class="presentation-layout">
        <aside class="presentation-group-rail" aria-label="演示分组导航">
          <header class="presentation-group-header">
            <div>
              <span class="presentation-rail-kicker">快速导航</span>
              <strong>{{ presentationGroupBy === 'project' ? '项目分组' : '负责人分组' }}</strong>
            </div>
            <span class="presentation-group-total">{{ presentationGroups.length }}组</span>
          </header>
          <div class="presentation-group-switch" role="group" aria-label="演示分组方式">
            <button
              type="button"
              :class="{ active: presentationGroupBy === 'project' }"
              :aria-pressed="presentationGroupBy === 'project'"
              @click="setPresentationGroupBy('project')"
            >项目</button>
            <button
              type="button"
              :class="{ active: presentationGroupBy === 'owner' }"
              :aria-pressed="presentationGroupBy === 'owner'"
              @click="setPresentationGroupBy('owner')"
            >负责人</button>
          </div>
          <nav class="presentation-group-list" aria-label="演示分组卡片">
            <article
              v-for="group in presentationGroups"
              :key="group.key"
              class="presentation-group-card"
              :class="{ active: currentPresentationGroupKey === group.key }"
            >
              <button type="button" class="presentation-group-card-main" @click="selectPresentationGroup(group)">
                <span
                  class="presentation-group-avatar"
                  :class="{ female: presentationGroupBy === 'owner' && isFemaleOwner(group.label) }"
                >{{ group.label.slice(0, 1) }}</span>
                <span class="presentation-group-copy">
                  <strong>{{ group.label }}</strong>
                  <small>{{ group.matters.length }} 项事项 · {{ group.averageProgress }}%完成</small>
                </span>
                <span class="presentation-group-chevron">›</span>
              </button>
              <div class="presentation-group-meter" aria-hidden="true"><i :style="{ width: `${group.averageProgress}%` }" /></div>
              <div class="presentation-group-stats">
                <span v-if="group.updateRequiredCount > 0">{{ group.updatedCount }}/{{ group.updateRequiredCount }} 已更新</span>
                <span v-else>无需更新</span>
                <span v-if="group.riskCount" class="has-risk">{{ group.riskCount }} 风险</span>
              </div>
              <div class="presentation-group-matters">
                <button
                  v-for="matter in group.matters"
                  :key="matter.id"
                  type="button"
                  :class="{ active: matter.id === presentationMatter?.id }"
                  @click="jumpPresentation(presentationIndexOf(matter.id))"
                >
                  <i :class="{ pending: !reportUpdate(matter) }" />
                  <span>{{ matter.title }}</span>
                </button>
              </div>
            </article>
          </nav>
        </aside>

        <section
          ref="presentationStageRef"
          class="presentation-stage"
          aria-label="周会演示模式"
          tabindex="-1"
        >
        <template v-if="presentationMatter">
          <div class="presentation-shell">
            <button
              class="presentation-arrow previous"
              type="button"
              :aria-label="`上一项：${previousPresentationMatter?.title || ''}`"
              @click="movePresentation(-1)"
            >
              <el-icon><ArrowLeft /></el-icon>
            </button>

            <article class="presentation-card" :class="{ 'is-pending': presentationRequiresUpdate && (!presentationUpdate || presentationEditing) }">
              <div class="presentation-accent" />
              <header class="presentation-card-header">
                <div class="presentation-tags">
                  <span class="priority-mark" :class="presentationMatter.priority.toLowerCase()">{{ presentationMatter.priority }}</span>
                  <span class="project-chip"><el-icon><Folder /></el-icon>{{ projectPresentation(presentationMatter).displayName }}</span>
                </div>
                <div class="presentation-tags">
                  <span v-if="!presentationRequiresUpdate" class="completed-update-state">无需更新</span>
                  <span v-else-if="presentationUpdate && !presentationEditing" class="updated-chip"><el-icon><Select /></el-icon>已更新</span>
                  <span v-else class="presentation-pending-label">本周待更新</span>
                  <span class="presentation-status-chip" :class="`status-${meetingStatus(presentationMatter)}`">
                    <i />{{ meetingStatus(presentationMatter) }}
                  </span>

                </div>
              </header>

              <div class="presentation-title-block">
                <h1>{{ presentationMatter.title }}</h1>
                <div class="presentation-meta">
                  <template v-if="presentationEditing">
                    <div class="presentation-meta-status">
                      <button
                        v-for="status in statusOptions"
                        :key="status"
                        type="button"
                        :class="[`status-${status}`, { active: presentationForm.status === status }]"
                        @click="selectPresentationStatus(status)"
                      >{{ status }}</button>
                    </div>
                    <i class="presentation-meta-divider" />
                  </template>
                  <span class="presentation-avatar" :class="{ female: isFemaleOwner(presentationMatter.ownerName) }">{{ (presentationMatter.ownerName || '未').slice(0, 1) }}</span>
                  <strong>{{ presentationMatter.ownerName || '未指定负责人' }}</strong>
                  <i />
                  <span><el-icon><Calendar /></el-icon>计划完成 {{ presentationMatter.plannedCompletionDate }}</span>
                  <i />
                  <span class="presentation-inline-progress" :class="{ 'is-editable': presentationEditing }">
                    <el-slider
                      v-if="presentationEditing"
                      v-model="presentationForm.progress"
                      class="presentation-progress-slider"
                      :min="0"
                      :max="100"
                      :show-tooltip="false"
                      aria-label="演示完成进度"
                      @input="handlePresentationProgressInput"
                    />
                    <b v-else><i :style="{ width: `${meetingProgress(presentationMatter)}%` }" /></b>
                    <span class="presentation-progress-text">{{ presentationEditing ? presentationForm.progress : meetingProgress(presentationMatter) }}%</span>
                  </span>
                </div>
              </div>

              <div v-if="presentationUpdate && !presentationEditing" class="presentation-read-view" aria-label="演示事项简报">
                <section class="presentation-main-report">
                  <div class="brief-label"><span>01</span> 本周进展</div>
                  <ol>
                    <li v-for="item in splitProgressSummary(presentationUpdate.progressSummary)" :key="item">{{ item }}</li>
                  </ol>
                </section>
                <aside class="presentation-signals">
                  <section class="meeting-signal risk-signal">
                    <span>问题 / 风险</span>
                    <p>{{ presentationUpdate.issues || '本周暂无风险' }}</p>
                  </section>
                  <section class="meeting-signal decision-signal">
                    <span>需协调 / 决策</span>
                    <p>{{ presentationUpdate.supportNeeded || '暂无待协调事项' }}</p>
                  </section>
                </aside>
                <section class="presentation-next-action">
                  <span class="action-label">下一步行动</span>
                  <p>{{ presentationUpdate.nextWeekPlan || '待补充下一步行动' }}</p>
                  <time :datetime="presentationMatter.plannedCompletionDate">目标 {{ presentationMatter.plannedCompletionDate }}</time>
                </section>
              </div>

              <div v-else-if="!presentationRequiresUpdate" class="presentation-complete-view" aria-label="已完成事项无需更新">
                <strong>本周已完成，无需更新</strong>
                <p>该事项已完成，无需继续提交周进展；历史周报可在大事儿详情中查看。</p>
              </div>

              <div v-else class="presentation-edit-view" aria-label="演示中更新周报">
                <div class="weekly-workspace weekly-workspace-compact">
                  <header class="weekly-workspace-header">
                    <div>
                      <span class="weekly-workspace-kicker">WEEKLY UPDATE</span>
                      <strong>结构化周进展</strong>
                    </div>
                    <time :datetime="selectedWeek">{{ meetingWeekLabel }}</time>
                  </header>

                  <section class="weekly-section weekly-outcomes" aria-labelledby="presentation-outcomes-title">
                    <header>
                      <span>01</span>
                      <div><h3 id="presentation-outcomes-title">本周成果</h3><small>记录已完成的关键动作与可验证结果</small></div>
                    </header>
                    <el-input
                      v-model="presentationForm.progressSummary"
                      type="textarea"
                      :rows="3"
                      aria-label="演示本周成果"
                      placeholder="逐条说明本周完成了什么、形成了什么结果"
                    />
                  </section>

                  <div class="weekly-signal-grid">
                    <section class="weekly-section weekly-risk" aria-labelledby="presentation-risk-title">
                      <header>
                        <span>02</span>
                        <div><h3 id="presentation-risk-title">问题 / 风险</h3><small>说明阻碍、偏差和影响</small></div>
                      </header>
                      <el-input v-model="presentationForm.issues" type="textarea" :rows="2" aria-label="演示问题与风险" placeholder="没有可留空" />
                    </section>
                    <section class="weekly-section weekly-support" aria-labelledby="presentation-support-title">
                      <header>
                        <span>03</span>
                        <div><h3 id="presentation-support-title">需协调 / 决策</h3><small>明确需要谁推动什么</small></div>
                      </header>
                      <el-input v-model="presentationForm.supportNeeded" type="textarea" :rows="2" aria-label="演示需协调与决策" placeholder="没有可留空" />
                    </section>
                  </div>

                  <section class="weekly-section weekly-next" aria-labelledby="presentation-next-title">
                    <header>
                      <span>04</span>
                      <div><h3 id="presentation-next-title">下一步行动</h3><small>写清动作、目标和交付</small></div>
                    </header>
                    <el-input v-model="presentationForm.nextWeekPlan" type="textarea" :rows="2" aria-label="演示下一步行动" placeholder="说明下一周期的关键动作" />
                  </section>
                </div>
              </div>

              <footer class="presentation-card-footer">
                <div class="presentation-key-hints">
                  <button type="button" @click="movePresentation(-1)"><el-icon><ArrowLeft /></el-icon></button>上一项
                  <button type="button" @click="movePresentation(1)"><el-icon><ArrowRight /></el-icon></button>下一项
                </div>
                <div v-if="!presentationRequiresUpdate" class="presentation-actions">
                  <el-button type="primary" @click="openDetail(presentationMatter)">查看详情 <el-icon><Right /></el-icon></el-button>
                </div>
                <div v-else-if="presentationUpdate && !presentationEditing" class="presentation-actions">
                  <el-button @click="startPresentationEdit"><el-icon><EditPen /></el-icon>编辑周报</el-button>
                  <el-button type="primary" @click="openDetail(presentationMatter)">查看详情 <el-icon><Right /></el-icon></el-button>
                </div>
                <div v-else class="presentation-actions">
                  <el-button @click="stashPresentationDraft"><el-icon><Collection /></el-icon>暂存草稿</el-button>
                  <el-button type="warning" :loading="presentationSaving" @click="savePresentationAndNext">
                    <el-icon><Select /></el-icon>保存并下一项 <el-icon><Right /></el-icon>
                  </el-button>
                </div>
              </footer>
            </article>

            <button
              class="presentation-arrow next"
              type="button"
              :aria-label="`下一项：${nextPresentationMatter?.title || ''}`"
              @click="movePresentation(1)"
            >
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>

          <nav class="presentation-thumbnails" aria-label="演示事项快速导航">
            <button
              v-for="(matter, index) in presentationOrderedMatters"
              :key="matter.id"
              type="button"
              :class="{
                active: index === presentationIndex,
                complete: Boolean(reportUpdate(matter)) && index !== presentationIndex,
                pending: !reportUpdate(matter)
              }"
              :aria-label="`跳转到第 ${index + 1} 项`"
              @click="jumpPresentation(index)"
            >
              <el-icon v-if="reportUpdate(matter) && index !== presentationIndex"><Select /></el-icon>
              <el-icon v-else-if="!reportUpdate(matter) && index === presentationIndex"><EditPen /></el-icon>
              <span v-else>{{ index + 1 }}</span>
            </button>
          </nav>
        </template>
        <el-empty v-else description="本周暂无可演示事项" />
        </section>
      </div>
    </template>
    <el-drawer
      v-model="matterDrawer"
      :title="editingMatterId ? '编辑大事儿' : '新增大事儿'"
      size="min(560px, 96vw)"
      destroy-on-close
    >
      <el-form ref="matterFormRef" :model="matterForm" :rules="matterRules" label-position="top">
        <el-form-item label="事项标题" prop="title">
          <el-input v-model="matterForm.title" maxlength="200" show-word-limit placeholder="用一句话说明要推进的大事儿" />
        </el-form-item>
        <el-form-item label="事项说明">
          <el-input
            v-model="matterForm.description"
            type="textarea"
            :rows="4"
            resize="vertical"
            placeholder="补充目标、范围或验收标准"
          />
        </el-form-item>
        <div class="form-grid two-columns">
          <el-form-item label="负责人" prop="ownerId">
            <el-select v-model="matterForm.ownerId" filterable placeholder="选择负责人">
              <el-option v-for="user in users" :key="user.id" :label="user.realName" :value="user.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="关联项目">
            <el-select v-model="matterForm.projectId" filterable clearable placeholder="可不关联项目">
              <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid three-columns">
          <el-form-item label="优先级">
            <el-radio-group v-model="matterForm.priority">
              <el-radio-button v-for="item in priorityOptions" :key="item" :value="item">{{ item }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="matterForm.status" @change="handleMatterStatusChange">
              <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="matterForm.sortOrder" :min="0" :max="999" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item label="当前进度">
          <div class="progress-editor">
            <el-slider v-model="matterForm.progress" :disabled="matterForm.status === '已完成'" />
            <el-input-number
              v-model="matterForm.progress"
              :min="0"
              :max="matterForm.status === '已完成' ? 100 : 99"
              :disabled="matterForm.status === '已完成'"
            />
          </div>
        </el-form-item>
        <div class="form-grid two-columns">
          <el-form-item label="开始日期" prop="startDate">
            <el-date-picker
              v-model="matterForm.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              format="YYYY年MM月DD日"
              placeholder="选择开始日期"
            />
          </el-form-item>
          <el-form-item label="计划完成" prop="plannedCompletionDate">
            <el-date-picker
              v-model="matterForm.plannedCompletionDate"
              type="date"
              value-format="YYYY-MM-DD"
              format="YYYY年MM月DD日"
              placeholder="选择计划完成日期"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="matterDrawer = false">取消</el-button>
        <el-button type="primary" :loading="matterSaving" @click="saveMatter">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer
      v-model="detailDrawer"
      class="key-matter-detail-drawer"
      size="860px"
      destroy-on-close
    >
      <template #header>
        <div class="detail-drawer-header">
          <button type="button" aria-label="返回大事儿列表" @click="detailDrawer = false">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <strong>大事儿详情</strong>
          <span>重点事项跟踪</span>
          <button type="button" aria-label="关闭大事儿详情" @click="detailDrawer = false">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </template>
      <div v-if="selectedMatter" v-loading="detailLoading" class="detail-content detail-clean">
        <header class="detail-clean-hero">
          <div class="detail-clean-title-row">
            <div class="detail-clean-title">
              <div class="detail-badges">
                <span class="priority-mark" :class="selectedMatter.priority.toLowerCase()">{{ selectedMatter.priority }}</span>
                <el-tag :type="statusType(selectedMatter.status)" effect="light">{{ selectedMatter.status }}</el-tag>
                <span v-if="selectedMatter.overdue" class="detail-overdue">已逾期</span>
              </div>
              <h2>{{ selectedMatter.title }}</h2>
              <p>{{ selectedMatter.description || '暂无事项说明' }}</p>
            </div>
            <div class="detail-clean-actions" aria-label="详情快捷操作">
              <el-button v-if="!isCompletedMatter(selectedMatter)" type="primary" :icon="Calendar" @click="openWeekly(selectedMatter)">更新周进展</el-button>
              <el-button :icon="Edit" @click="openEdit(selectedMatter)">编辑事项</el-button>
            </div>
          </div>

          <dl class="detail-clean-facts" aria-label="事项关键信息">
            <div><dt>负责人</dt><dd>{{ selectedMatter.ownerName || '未指定' }}</dd></div>
            <div><dt>关联项目</dt><dd>{{ projectPresentation(selectedMatter).displayName }}</dd></div>
            <div><dt>开始日期</dt><dd>{{ selectedMatter.startDate }}</dd></div>
            <div><dt>计划完成</dt><dd :class="{ 'date-overdue': selectedMatter.overdue }">{{ selectedMatter.plannedCompletionDate }}</dd></div>
            <div><dt>交付窗口</dt><dd>{{ milestoneTiming(selectedMatter).label }}</dd></div>
          </dl>

          <section class="detail-clean-progress" aria-label="事项总进度">
            <div>
              <span>事项总进度</span>
              <strong>{{ selectedMatter.progress }}<small>%</small></strong>
              <em :class="`tone-${detailComparison(selectedMatter).tone}`">{{ detailComparison(selectedMatter).label }}</em>
            </div>
            <el-progress :percentage="selectedMatter.progress" :stroke-width="8" :show-text="false" />
          </section>
        </header>

        <main class="detail-clean-body">
          <section class="detail-clean-card detail-clean-brief" aria-label="最新周进展">
            <header>
              <div>
                <span>最新周进展</span>
                <h3>本周工作简报</h3>
              </div>
              <time v-if="selectedMatter.latestUpdate" :datetime="selectedMatter.latestUpdate.weekStartDate">
                {{ formatChineseDay(selectedMatter.latestUpdate.weekStartDate) }}
              </time>
            </header>

            <div v-if="selectedMatter.latestUpdate" class="detail-clean-brief-grid">
              <article class="brief-main">
                <span>本周进展</span>
                <p>{{ selectedMatter.latestUpdate.progressSummary }}</p>
              </article>
              <article>
                <span>问题 / 风险</span>
                <p>{{ selectedMatter.latestUpdate.issues || '本周暂无风险' }}</p>
              </article>
              <article>
                <span>需协调 / 决策</span>
                <p>{{ selectedMatter.latestUpdate.supportNeeded || '暂无待协调事项' }}</p>
              </article>
              <article class="brief-next">
                <span>下一步行动</span>
                <p>{{ selectedMatter.latestUpdate.nextWeekPlan || '待补充下一步行动' }}</p>
              </article>
            </div>
            <el-empty v-else :image-size="72" description="尚未填写周进展" />
          </section>

          <section class="detail-clean-card detail-clean-history" aria-label="周进展记录">
            <header>
              <div>
                <span>历史记录</span>
                <h3>周进展记录</h3>
              </div>
              <small>{{ selectedMatter.weeklyUpdates?.length || 0 }} 次更新</small>
            </header>
            <ol v-if="selectedMatter.weeklyUpdates?.length" class="detail-clean-timeline">
              <li v-for="(update, index) in selectedMatter.weeklyUpdates" :key="update.id">
                <time :datetime="update.weekStartDate">{{ formatChineseDay(update.weekStartDate) }}</time>
                <article>
                  <header>
                    <div class="history-status">
                      <el-tag size="small" :type="statusType(update.status)">{{ update.status }}</el-tag>
                      <strong>{{ update.progress }}%</strong>
                      <span class="history-delta" :class="`tone-${historyComparison(selectedMatter, index).tone}`">
                        {{ historyComparison(selectedMatter, index).label }}
                      </span>
                      <span v-if="index === 0" class="latest-pill">最新</span>
                    </div>
                    <div class="history-actions">
                      <el-button link type="primary" :icon="Edit" aria-label="编辑周进展" @click="openWeekly(selectedMatter, update.weekStartDate)" />
                      <el-button link type="danger" :icon="Delete" aria-label="删除周进展" @click="confirmDeleteWeekly(selectedMatter, update)" />
                    </div>
                  </header>
                  <p>{{ update.progressSummary }}</p>
                </article>
              </li>
            </ol>
            <el-empty v-else :image-size="72" description="暂无周进展记录" />
          </section>
        </main>
      </div>
      <template #footer>
        <el-button v-if="selectedMatter" type="danger" plain :icon="Delete" @click="confirmDeleteMatter(selectedMatter)">删除</el-button>
      </template>
    </el-drawer>

    <el-drawer
      v-model="weeklyDrawer"
      class="key-matter-weekly-drawer"
      title="更新周进展"
      size="min(560px, 96vw)"
      destroy-on-close
    >
      <el-form ref="weeklyFormRef" :model="weeklyForm" :rules="weeklyRules" label-position="top" class="weekly-workspace weekly-workspace-full">
        <header class="weekly-workspace-header">
          <div>
            <span class="weekly-workspace-kicker">WEEKLY UPDATE</span>
            <strong>结构化周进展</strong>
          </div>
          <time :datetime="weeklyDate"><el-icon><Calendar /></el-icon>{{ formatChineseDay(weeklyDate) }}当周</time>
        </header>

        <section class="weekly-state-bar" aria-label="周期状态与进度">
          <el-form-item label="事项状态" prop="status">
            <el-select v-model="weeklyForm.status" aria-label="事项状态" @change="handleWeeklyStatusChange">
              <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="完成进度">
            <el-input-number
              v-model="weeklyForm.progress"
              aria-label="完成进度"
              :min="0"
              :max="weeklyForm.status === '已完成' ? 100 : 99"
              :disabled="weeklyForm.status === '已完成'"
            />
          </el-form-item>
        </section>

        <section class="weekly-section weekly-outcomes" aria-labelledby="weekly-outcomes-title">
          <header>
            <span>01</span>
            <div><h3 id="weekly-outcomes-title">本周成果</h3><small>记录已完成的关键动作与可验证结果</small></div>
          </header>
          <el-form-item prop="progressSummary">
            <el-input v-model="weeklyForm.progressSummary" type="textarea" :rows="5" aria-label="本周成果" placeholder="逐条说明本周完成了什么、形成了什么结果" />
          </el-form-item>
        </section>

        <div class="weekly-signal-grid">
          <section class="weekly-section weekly-risk" aria-labelledby="weekly-risk-title">
            <header>
              <span>02</span>
              <div><h3 id="weekly-risk-title">问题 / 风险</h3><small>说明阻碍、偏差和影响</small></div>
            </header>
            <el-input v-model="weeklyForm.issues" type="textarea" :rows="3" aria-label="问题与风险" placeholder="没有可留空" />
          </section>
          <section class="weekly-section weekly-support" aria-labelledby="weekly-support-title">
            <header>
              <span>03</span>
              <div><h3 id="weekly-support-title">需协调 / 决策</h3><small>明确需要谁推动什么</small></div>
            </header>
            <el-input v-model="weeklyForm.supportNeeded" type="textarea" :rows="3" aria-label="需协调与决策" placeholder="没有可留空" />
          </section>
        </div>

        <section class="weekly-section weekly-next" aria-labelledby="weekly-next-title">
          <header>
            <span>04</span>
            <div><h3 id="weekly-next-title">下一步行动</h3><small>写清动作、目标和交付</small></div>
          </header>
          <el-input v-model="weeklyForm.nextWeekPlan" type="textarea" :rows="3" aria-label="下一步行动" placeholder="说明下一周期的关键动作" />
        </section>
      </el-form>
      <template #footer>
        <el-button @click="weeklyDrawer = false">取消</el-button>
        <el-button type="primary" :loading="weeklySaving" @click="saveWeeklyUpdate">保存周进展</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.key-matters-page {
  width: 100%;
  min-width: 0;
  color: var(--gray-800);
}

.page-toolbar,
.meeting-toolbar,
.milestone-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.mode-switch,
.toolbar-actions,
.meeting-status,
.meeting-title-group,
.detail-badges,
.section-heading {
  display: flex;
  align-items: center;
}

.mode-switch {
  gap: 16px;
  min-width: 0;
}

.register-titlebar {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.register-titlebar h1 {
  margin: 0;
  color: var(--gray-900);
  font-size: 22px;
  font-weight: 760;
  line-height: 1.25;
}

.register-titlebar p {
  margin: 0;
  color: var(--gray-500);
  font-size: 13px;
}

.mode-note,
.meeting-range {
  color: var(--gray-500);
  font-size: 13px;
}

.toolbar-actions,
.meeting-status,
.detail-badges {
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.load-error {
  margin-bottom: 16px;
}

.milestone-top-section {
  margin-bottom: 18px;
}

.milestone-top-section.collapsed {
  margin-bottom: 12px;
}

.milestone-collapse-header {
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px 8px 14px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: #fff;
}

.milestone-collapse-header > div {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.milestone-collapse-header > div > span {
  color: var(--gray-800);
  font-size: 13px;
  font-weight: 700;
}

.milestone-collapse-header small {
  color: var(--gray-500);
  font-size: 11px;
}

.milestone-collapse-header button {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 7px;
  border: 0;
  color: var(--gray-500);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.milestone-collapse-header button:hover {
  color: var(--km-primary);
}

.milestone-collapse-header .el-icon {
  transition: transform var(--km-motion);
}

.milestone-collapse-header .el-icon.expanded {
  transform: rotate(180deg);
}

.milestone-top-section .milestone-panel {
  margin-top: 8px;
}

.milestone-top-section .milestone-toolbar {
  margin-bottom: 10px;
}

.milestone-summary.compact {
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  margin-bottom: 10px;
  border-radius: 10px;
}

.milestone-summary.compact .summary-cell {
  min-height: 56px;
  padding: 10px 16px;
}

.milestone-summary.compact .summary-cell span {
  font-size: 12px;
}

.milestone-summary.compact .summary-cell strong {
  font-size: 22px;
}

.register-layout {
  display: grid;
  grid-template-columns: 184px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.register-content {
  min-width: 0;
}

.list-filter-rail {
  position: sticky;
  top: 0;
  display: grid;
  gap: 8px;
  padding: 14px 10px;
  border: 1px solid var(--gray-200);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 22px rgb(15 23 42 / 4%);
}

.list-filter-header,
.list-filter-section > header,
.list-filter-all,
.list-filter-section button {
  display: flex;
  align-items: center;
}

.list-filter-header {
  justify-content: space-between;
  padding: 0 4px 8px;
}

.list-filter-header > div {
  display: grid;
  gap: 3px;
}

.list-filter-header span:first-child,
.list-filter-section > header {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 700;
}

.list-filter-header strong {
  color: var(--gray-800);
  font-size: 16px;
}

.list-filter-header > span:last-child,
.list-filter-section > header small {
  color: #94a3b8;
  font-size: 11px;
}

.list-filter-all,
.list-filter-section button {
  width: 100%;
  gap: 9px;
  min-width: 0;
  padding: 9px 8px;
  border: 1px solid transparent;
  border-radius: 9px;
  color: var(--gray-600);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.list-filter-all:hover,
.list-filter-section button:hover,
.list-filter-all.active,
.list-filter-section button.active {
  color: var(--primary);
  background: #eef2ff;
  border-color: #c7d2fe;
}

.list-filter-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #4f5cf7;
  background: #eef2ff;
}

.list-filter-icon.project {
  color: #0891b2;
  background: #ecfeff;
}

.list-filter-icon.owner {
  color: #7c3aed;
  background: #f5f3ff;
}

.list-filter-icon.owner.female {
  color: #be185d;
  background: #fdf2f8;
}

.female-owner-name,
.owner-name.female {
  color: #be185d;
  font-weight: 700;
}

.list-filter-all > span:last-child,
.list-filter-section button > span:last-child {
  min-width: 0;
  display: grid;
  gap: 2px;
  flex: 1;
}

.list-filter-all strong,
.list-filter-section button strong {
  overflow: hidden;
  color: inherit;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-filter-all small,
.list-filter-section button small {
  color: #94a3b8;
  font-size: 10px;
}

.list-filter-section {
  display: grid;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--gray-100);
}

.list-filter-section > header {
  justify-content: space-between;
  padding: 0 6px 3px;
}

.list-status-tag.status-not-started {
  color: #64748b;
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.list-status-tag.status-progressing {
  color: #2563eb;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.list-status-tag.status-risk {
  color: #d97706;
  background: #fffbeb;
  border-color: #fde68a;
}

.list-status-tag.status-blocked {
  color: #dc2626;
  background: #fef2f2;
  border-color: #fecaca;
}

.list-status-tag.status-completed {
  color: #059669;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.list-status-tag.status-paused {
  color: #7c3aed;
  background: #f5f3ff;
  border-color: #ddd6fe;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  margin-bottom: 16px;
  overflow: hidden;
}

.summary-cell {
  min-height: 84px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-right: 1px solid var(--gray-200);
}

.summary-cell:last-child {
  border-right: 0;
}

.summary-cell span {
  color: var(--gray-500);
  font-size: 13px;
}

.summary-cell strong {
  font-size: 28px;
  line-height: 1;
  color: var(--gray-800);
}

.summary-cell.risk strong {
  color: var(--danger);
}

.summary-cell.pending strong {
  color: var(--warning);
}

.filter-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(140px, 100%), 1fr));
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.filter-bar :deep(.el-button) {
  margin-left: 0;
}

.table-panel {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
}

.table-pagination {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-top: 1px solid var(--gray-200);
  color: var(--gray-500);
  font-size: 12px;
}

.matter-table {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  cursor: pointer;
}

@media (max-width: 720px) {
  .table-pagination {
    align-items: stretch;
    flex-direction: column;
  }

  .table-pagination :deep(.el-pagination) {
    max-width: 100%;
    justify-content: flex-start;
    overflow-x: auto;
  }
}

.matter-title {
  font-weight: 600;
  color: var(--gray-800);
  line-height: 1.4;
}

.matter-subline {
  margin-top: 4px;
  font-size: 12px;
  color: var(--gray-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.priority-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 24px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
}

.priority-mark.p0 {
  color: #991B1B;
  background: #FEE2E2;
}

.priority-mark.p1 {
  color: #9A3412;
  background: #FFEDD5;
}

.priority-mark.p2 {
  color: #1E40AF;
  background: #DBEAFE;
}

.progress-cell {
  display: grid;
  grid-template-columns: 1fr 38px;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--gray-600);
}

.updated-state,
.pending-state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.updated-state {
  color: var(--success);
}

.completed-update-state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--success);
  font-size: 12px;
  font-weight: 600;
}

.pending-state,
.date-overdue {
  color: var(--danger);
  font-weight: 600;
}

.meeting-toolbar {
  padding: 14px 0 18px;
  border-bottom: 1px solid var(--gray-200);
}

.milestone-toolbar {
  padding: 14px 0 18px;
}

.meeting-controls,
.grouping-control {
  display: flex;
  align-items: center;
  gap: 12px;
}

.grouping-control > span {
  color: var(--gray-500);
  font-size: 12px;
  white-space: nowrap;
}

.grouping-segment {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: #fff;
}

.grouping-segment button {
  min-height: 28px;
  padding: 0 12px;
  color: var(--gray-600);
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
}

.grouping-segment button.active {
  color: #fff;
  background: var(--primary);
}

.meeting-count {
  font-size: 20px;
  font-weight: 700;
}

.meeting-list {
  min-height: 240px;
}

.meeting-group {
  margin-bottom: 22px;
}

.meeting-group-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 18px;
  background: var(--gray-50);
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  margin-bottom: 12px;
}

.group-eyebrow {
  display: block;
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.meeting-group-header h2 {
  margin: 4px 0 0;
  color: var(--gray-800);
  font-size: 18px;
}

.group-summary {
  display: flex;
  align-items: center;
  margin: 0;
}

.group-summary > div {
  min-width: 76px;
  padding: 0 14px;
  border-left: 1px solid var(--gray-200);
  text-align: right;
}

.group-summary dt {
  color: var(--gray-500);
  font-size: 11px;
}

.group-summary dd {
  margin: 3px 0 0;
  color: var(--gray-800);
  font-size: 15px;
  font-weight: 700;
}

.group-summary .risk dd {
  color: var(--danger);
}

.meeting-items-stack {
  display: grid;
  gap: 12px;
}

.meeting-item {
  --status-color: var(--primary);
  position: relative;
  padding: 0;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.meeting-group .meeting-item:last-child {
  border-radius: 10px;
  margin-bottom: 0;
}

.meeting-item::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--status-color);
}

.meeting-item.status-有风险 {
  --status-color: var(--warning);
}

.meeting-item.status-已阻塞 {
  --status-color: var(--danger);
}

.meeting-item.status-已完成 {
  --status-color: var(--success);
}

.meeting-item-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  padding: 18px 20px 14px 24px;
}

.meeting-title-group {
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.meeting-title-group h3,
.detail-header h2 {
  font-size: 18px;
  line-height: 1.4;
  margin: 0;
  overflow-wrap: anywhere;
}

.meeting-title-group .meeting-kicker {
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 600;
  margin: 0 0 4px;
}

.meeting-status {
  flex-shrink: 0;
}

.meeting-status strong {
  min-width: 44px;
  color: var(--gray-700);
}

.week-delta {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.week-delta.tone-up {
  color: #166534;
  background: #DCFCE7;
}

.week-delta.tone-down,
.tone-overdue {
  color: #B91C1C;
}

.week-delta.tone-down {
  background: #FEE2E2;
}

.week-delta.tone-missing {
  color: #92400E;
  background: #FEF3C7;
}

.week-delta.tone-complete {
  color: var(--success);
  background: var(--km-success-soft);
}

.meeting-progress-track {
  height: 3px;
  margin: 0 20px 0 24px;
  overflow: hidden;
  background: var(--gray-100);
  border-radius: 999px;
}

.meeting-progress-track span {
  display: block;
  height: 100%;
  background: var(--status-color);
  border-radius: inherit;
}

.meeting-brief {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(260px, 0.85fr);
  gap: 14px 18px;
  padding: 18px 20px 0 24px;
}

.meeting-primary-report {
  min-height: 148px;
  padding: 18px 20px;
  background: #F8FAFC;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
}

.brief-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 600;
  margin-bottom: 14px;
}

.brief-label span {
  color: var(--primary);
  font-variant-numeric: tabular-nums;
}

.meeting-primary-report > p {
  color: var(--gray-800);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.meeting-signal-rail {
  display: grid;
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.meeting-signal {
  min-width: 0;
  padding: 13px 14px;
  border-left: 3px solid var(--gray-300);
  border-radius: 0 6px 6px 0;
  background: var(--gray-50);
}

.meeting-signal > span,
.detail-signal > span {
  display: block;
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 600;
  margin-bottom: 5px;
}

.meeting-signal p,
.detail-signal p {
  color: var(--gray-700);
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.risk-signal {
  border-left-color: var(--warning);
  background: #FFF7ED;
}

.risk-signal p {
  color: #9A3412;
}

.decision-signal {
  border-left-color: var(--primary);
  background: #EFF6FF;
}

.decision-signal p {
  color: #1E3A8A;
}

.meeting-next-action {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  min-height: 48px;
  padding: 10px 14px;
  background: #F8FAFC;
  border: 1px solid var(--gray-200);
  border-radius: 7px;
}

.action-label {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  color: #1E40AF;
  background: #DBEAFE;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.meeting-next-action p {
  color: var(--gray-800);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.meeting-next-action time {
  color: var(--gray-500);
  font-size: 11px;
  white-space: nowrap;
}

.missing-update {
  width: auto;
  min-height: 74px;
  padding: 16px;
  border: 1px dashed #F59E0B;
  background: #FFFBEB;
  color: #92400E;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  margin: 18px 20px 18px 24px;
  border-radius: 6px;
  cursor: pointer;
  font: inherit;
  text-align: center;
}

.missing-update > span {
  font-weight: 700;
}

.missing-update small {
  color: #B45309;
}

.completed-no-update {
  width: auto;
  min-height: 74px;
  padding: 16px;
  border: 1px solid #bbf7d0;
  background: var(--km-success-soft);
  color: var(--success);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  margin: 18px 20px 18px 24px;
  border-radius: 6px;
  font: inherit;
  text-align: center;
}

.completed-no-update strong {
  font-size: 13px;
}

.completed-no-update small {
  color: #047857;
}

.missing-update:focus-visible,
.nav-item:focus-visible,
button:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.meeting-item-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  color: var(--gray-500);
  font-size: 12px;
  padding: 12px 20px 14px 24px;
}

.milestone-summary {
  margin-top: 0;
}

.milestone-panel {
  position: relative;
  min-height: 132px;
  padding: 16px 46px 18px;
  overflow-x: visible;
  overflow-y: visible;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
}

.milestone-scroller {
  overflow: hidden;
  scroll-behavior: smooth;
}

.milestone-node-track {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 78px;
  min-width: max-content;
  margin: 0;
  padding: 22px 10px 4px;
  list-style: none;
}

.milestone-node-track::before {
  content: '';
  position: absolute;
  top: 34px;
  left: 20px;
  right: 20px;
  height: 1px;
  background: #dbe1ea;
}

.milestone-node-item {
  position: relative;
  width: 124px;
  min-width: 124px;
  display: grid;
  justify-items: center;
  z-index: 1;
}

.milestone-node {
  width: 100%;
  min-height: 58px;
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 0;
  border: 0;
  color: var(--gray-700);
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: center;
}

.milestone-dot {
  width: 22px;
  height: 22px;
  display: inline-grid;
  place-items: center;
  border: 3px solid #fff;
  border-radius: 50% 50% 50% 4px;
  color: #fff;
  font-size: 11px;
  font-weight: 900;
  line-height: 1;
  box-shadow: 0 0 0 1px rgb(15 23 42 / 8%);
  transform: rotate(-45deg);
}

.milestone-node .milestone-dot {
  margin-bottom: 3px;
}

.milestone-dot {
  background: #cbd5e1;
}

.node-complete .milestone-dot {
  background: var(--km-success);
}

.node-risk .milestone-dot {
  background: #ff8f87;
}

.node-today .milestone-dot {
  background: #7dd3fc;
}

.node-upcoming .milestone-dot {
  background: #c9ced6;
}

.milestone-dot {
  text-indent: 0;
}

.milestone-dot {
  font-family: Arial, sans-serif;
}

.milestone-dot i {
  display: inline-block;
  font-style: normal;
  transform: rotate(45deg);
}

.milestone-node strong,
.milestone-node time {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-node strong {
  color: var(--gray-700);
  font-size: 13px;
  font-weight: 650;
}

.milestone-node time {
  color: #667085;
  font-size: 12px;
  font-weight: 650;
}

.milestone-slide-button {
  position: absolute;
  top: 50%;
  z-index: 2;
  width: 30px;
  height: 30px;
  display: inline-grid;
  place-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 50%;
  color: #64748b;
  background: #fff;
  box-shadow: 0 8px 18px rgb(15 23 42 / 10%);
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
  transform: translateY(-50%);
}

.milestone-slide-button.previous {
  left: 12px;
}

.milestone-slide-button.next {
  right: 12px;
}

.milestone-slide-button:hover:not(:disabled) {
  border-color: var(--km-primary);
  color: var(--km-primary);
}

.milestone-slide-button:disabled {
  opacity: 0.34;
  cursor: not-allowed;
  box-shadow: none;
}

.milestone-floating-popover {
  position: fixed;
  z-index: 4000;
  width: 320px;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 12px 28px rgb(15 23 42 / 16%);
  pointer-events: auto;
  text-align: left;
  transform: translateX(-50%);
}

.milestone-floating-popover header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid #eef1f5;
  background: #f8fafc;
}

.milestone-floating-popover header .milestone-dot {
  width: 18px;
  height: 18px;
  border-width: 2px;
}

.milestone-floating-popover header strong {
  min-width: 0;
  overflow: hidden;
  color: var(--gray-800);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-popover-list {
  display: grid;
  gap: 0;
  max-height: 360px;
  overflow-y: auto;
  padding: 8px;
}

.milestone-popover-matter {
  width: 100%;
  display: grid;
  gap: 5px;
  padding: 10px 8px;
  border: 0;
  border-radius: 6px;
  color: var(--gray-700);
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.milestone-popover-matter + .milestone-popover-matter {
  border-top: 1px solid #eef1f5;
  border-radius: 0;
}

.milestone-popover-matter:hover {
  background: #f8fafc;
}

.milestone-popover-matter strong,
.milestone-popover-matter span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-popover-matter strong {
  color: var(--gray-800);
  font-size: 13px;
  font-weight: 720;
}

.milestone-popover-matter span {
  color: #667085;
  font-size: 12px;
}

.milestone-popover-matter small {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #8b95a5;
  font-size: 12px;
}

.milestone-popover-matter em,
.milestone-popover-matter b {
  font-style: normal;
}

.milestone-popover-matter b {
  margin-left: auto;
  color: var(--gray-700);
  font-weight: 760;
}

.milestone-floating-popover dl {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 10px 14px;
}

.milestone-floating-popover dl > div {
  display: grid;
  grid-template-columns: 94px minmax(0, 1fr);
  align-items: center;
  min-height: 36px;
}

.milestone-floating-popover dt {
  color: #8b95a5;
  font-size: 12px;
}

.milestone-floating-popover dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--gray-700);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.milestone-floating-popover footer {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 10px 14px 12px;
  border-top: 1px solid #eef1f5;
  color: #8b95a5;
  font-size: 12px;
}

.milestone-floating-popover footer span {
  font-weight: 700;
}

.milestone-floating-popover footer i {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #edf2f7;
}

.milestone-floating-popover footer b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #34d399, #10b981);
}

.milestone-floating-popover footer em {
  color: var(--gray-700);
  font-style: normal;
  font-weight: 700;
}

.milestone-floating-popover .tone-complete {
  color: var(--km-success);
}

.milestone-floating-popover .tone-today {
  color: #0284c7;
}

.milestone-floating-popover .tone-overdue {
  color: var(--km-danger);
}

.form-grid {
  display: grid;
  gap: 14px;
}

.two-columns {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.three-columns {
  grid-template-columns: 1.25fr 1fr 0.65fr;
}

.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor),
.form-grid :deep(.el-input-number) {
  width: 100%;
}

.progress-editor {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 120px;
  align-items: center;
  gap: 20px;
}

.detail-content {
  min-height: 240px;
}

:global(.key-matter-detail-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 14px 22px;
  border-bottom: 1px solid #e2e8f0;
  background: #fff;
}

:global(.key-matter-detail-drawer.el-drawer) {
  width: min(860px, 96vw) !important;
}

:global(.key-matter-detail-drawer .el-drawer__body) {
  padding: 18px 22px 24px;
  background: #f8fafc;
}

.detail-drawer-header {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #1e293b;
}

.detail-drawer-header button {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  color: #64748b;
  background: transparent;
  cursor: pointer;
}

.detail-drawer-header button:hover {
  color: #4f5cf7;
  background: #eef2ff;
}

.detail-drawer-header strong {
  font-size: 15px;
}

.detail-drawer-header > span {
  color: #94a3b8;
  font-size: 12px;
}

.detail-drawer-header button:last-child {
  margin-left: auto;
}

.detail-overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 190px;
  align-items: stretch;
  gap: 24px;
  padding: 26px;
  color: #fff;
  background: linear-gradient(135deg, #4f46e5 0%, #5b5cf0 58%, #818cf8 100%);
  border: 0;
  border-radius: 16px;
  box-shadow: 0 16px 32px rgb(79 70 229 / 20%);
}

.detail-heading {
  min-width: 0;
}

.detail-heading h2 {
  margin: 12px 0 0;
  color: #fff;
  font-size: 25px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.detail-heading > p {
  max-width: 64ch;
  margin-top: 8px;
  color: rgb(255 255 255 / 78%);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.detail-overdue {
  color: #fee2e2;
  font-size: 12px;
  font-weight: 700;
}

.detail-progress-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 16px 0 4px 24px;
  background: transparent;
  border: 0;
  border-left: 1px solid rgb(255 255 255 / 28%);
  border-radius: 0;
}

.detail-progress-panel > span:first-child {
  color: rgb(255 255 255 / 76%);
  font-size: 11px;
  font-weight: 600;
}

.detail-progress-value {
  margin: 3px 0 8px;
  color: #fff;
  font-size: 36px;
  font-weight: 750;
  line-height: 1;
}

.detail-progress-value small {
  margin-left: 2px;
  color: rgb(255 255 255 / 72%);
  font-size: 16px;
}

.detail-progress-panel .week-delta {
  align-self: flex-start;
  margin-top: 12px;
}

.detail-progress-panel :deep(.el-progress-bar__outer) {
  background: rgb(255 255 255 / 28%);
}

.detail-progress-panel :deep(.el-progress-bar__inner) {
  background: #34d399;
}

.detail-hero-actions {
  display: flex;
  gap: 10px;
  margin: -2px 0 18px;
  padding: 0 2px;
}

.detail-hero-actions :deep(.el-button) {
  min-width: 132px;
  border-radius: 8px;
}

.detail-hero-actions :deep(.el-button--primary) {
  background: #4f5cf7;
  border-color: #4f5cf7;
  box-shadow: 0 7px 14px rgb(79 92 247 / 20%);
}

.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 250px;
  align-items: start;
  gap: 20px;
  margin-top: 0;
}

.detail-main {
  min-width: 0;
  display: grid;
  gap: 20px;
}

.detail-brief,
.detail-history,
.detail-fact-card,
.detail-deadline-card {
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 14px;
  box-shadow: 0 8px 22px rgb(15 23 42 / 4%);
}

.detail-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--gray-200);
}

.detail-section-header > div > span {
  display: block;
  color: #4f5cf7;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.detail-section-header h3 {
  margin-top: 3px;
  color: var(--gray-800);
  font-size: 17px;
}

.detail-section-header p {
  margin-top: 3px;
  color: var(--gray-500);
  font-size: 11px;
}

.detail-brief-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(210px, 0.85fr);
  gap: 14px;
  padding: 18px 20px 20px;
}

.detail-primary-report {
  min-height: 154px;
  padding: 18px 20px;
  background: #F8FAFC;
  border: 1px solid var(--gray-200);
  border-radius: 12px;
}

.detail-primary-report > p {
  color: var(--gray-800);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.detail-signal-rail {
  display: grid;
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.detail-signal {
  padding: 13px 14px;
  border-left: 3px solid var(--gray-300);
  border-radius: 0 6px 6px 0;
  background: var(--gray-50);
}

.detail-next-action {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  padding: 13px 15px;
  background: #EFF6FF;
  border-left: 3px solid var(--primary);
  border-radius: 0 7px 7px 0;
}

.detail-next-action > span {
  color: #1E40AF;
  font-size: 11px;
  font-weight: 700;
}

.detail-next-action p {
  color: var(--gray-800);
  font-weight: 600;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.detail-aside {
  position: sticky;
  top: 12px;
  display: grid;
  gap: 12px;
}

.aside-heading {
  padding: 15px 16px;
  color: var(--gray-800);
  border-bottom: 1px solid var(--gray-200);
  font-size: 14px;
  font-weight: 700;
}

.detail-fact-card dl {
  margin: 0;
  padding: 4px 16px 10px;
}

.detail-fact-card dl > div {
  padding: 11px 0;
  border-bottom: 1px solid var(--gray-100);
}

.detail-fact-card dl > div:last-child {
  border-bottom: 0;
}

.detail-fact-card dt {
  color: var(--gray-500);
  font-size: 11px;
}

.detail-fact-card dd {
  margin: 4px 0 0;
  color: var(--gray-800);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.detail-deadline-card {
  padding: 16px;
  border-top: 3px solid var(--primary);
}

.detail-deadline-card > span {
  color: var(--gray-500);
  font-size: 11px;
}

.detail-deadline-card strong {
  display: block;
  margin-top: 6px;
  color: var(--gray-800);
  font-size: 19px;
}

.detail-deadline-card p {
  margin-top: 6px;
  color: var(--gray-500);
  font-size: 11px;
  line-height: 1.5;
}

.detail-deadline-card.tone-overdue {
  border-top-color: var(--danger);
  background: #FEF2F2;
}

.detail-deadline-card.tone-today {
  border-top-color: var(--warning);
  background: #FFFBEB;
}

.detail-deadline-card.tone-complete {
  border-top-color: var(--success);
  background: #F0FDF4;
}

.history-heading {
  border-bottom: 0;
}

.history-timeline {
  position: relative;
  margin: 0;
  padding: 0 20px 20px;
  list-style: none;
}

.history-timeline::before {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 32px;
  left: 137px;
  width: 1px;
  background: var(--gray-200);
}

.history-entry {
  position: relative;
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 34px;
  padding-bottom: 14px;
}

.history-entry:last-child {
  padding-bottom: 0;
}

.history-entry::before {
  content: '';
  position: absolute;
  top: 17px;
  left: 113px;
  z-index: 1;
  width: 8px;
  height: 8px;
  border: 3px solid #fff;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 1px var(--primary);
}

.history-marker {
  padding-top: 10px;
  text-align: right;
}

.history-marker time {
  display: block;
  color: var(--gray-700);
  font-size: 11px;
  font-weight: 650;
}

.history-marker > span {
  display: inline-flex;
  margin-top: 5px;
  padding: 2px 6px;
  color: #1E40AF;
  background: #DBEAFE;
  border-radius: 999px;
  font-size: 10px;
}

.history-entry > article {
  padding: 13px 14px;
  background: #F8FAFC;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
}

.history-entry article > header,
.history-status {
  display: flex;
  align-items: center;
}

.history-entry article > header {
  justify-content: space-between;
  gap: 12px;
}

.history-status {
  gap: 8px;
}

.history-status strong {
  color: var(--gray-800);
  font-size: 13px;
}

.history-delta {
  color: var(--gray-500);
  font-size: 11px;
  font-weight: 650;
}

.history-delta.tone-up {
  color: #15803D;
}

.history-delta.tone-down {
  color: var(--danger);
}

.history-entry article > p {
  margin-top: 9px;
  color: var(--gray-700);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.history-actions {
  white-space: nowrap;
}

.weekly-workspace {
  display: grid;
  gap: 14px;
  color: #1e293b;
}

.weekly-workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.weekly-workspace-header > div {
  display: grid;
  gap: 3px;
}

.weekly-workspace-kicker {
  color: #4f5cf7;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .08em;
}

.weekly-workspace-header strong {
  color: #0f172a;
  font-size: 17px;
}

.weekly-workspace-header time {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.weekly-state-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  padding: 14px;
  border: 1px solid #dfe3ea;
  border-radius: 7px;
  background: #f8fafc;
}

.weekly-state-bar > label {
  min-width: 0;
  display: grid;
  gap: 7px;
}

.weekly-state-bar > label > span,
.weekly-state-bar :deep(.el-form-item__label) {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
}

.weekly-state-bar :deep(.el-form-item) {
  margin-bottom: 0;
}

.weekly-state-bar :deep(.el-select),
.weekly-state-bar :deep(.el-input-number) {
  width: 100%;
}

.weekly-section {
  min-width: 0;
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid #dfe3ea;
  border-radius: 7px;
  background: #fff;
}

.weekly-section > header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.weekly-section > header > span {
  width: 25px;
  height: 25px;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  color: #4f5cf7;
  background: #eef2ff;
  font-size: 10px;
  font-weight: 800;
}

.weekly-section > header > div {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.weekly-section > header h3 {
  margin: 0;
  color: #1e293b;
  font-size: 13px;
}

.weekly-section > header small {
  color: #94a3b8;
  font-size: 11px;
  line-height: 1.35;
}

.weekly-outcomes {
  border-left: 3px solid #4f5cf7;
}

.weekly-signal-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.weekly-risk > header > span {
  color: #b45309;
  background: #fffbeb;
}

.weekly-support > header > span {
  color: #0369a1;
  background: #f0f9ff;
}

.weekly-next {
  border-left: 3px solid #0f766e;
}

.weekly-next > header > span {
  color: #0f766e;
  background: #f0fdfa;
}

.weekly-section :deep(.el-form-item) {
  margin-bottom: 0;
}

.weekly-section :deep(.el-textarea__inner) {
  resize: vertical;
}

:global(.key-matter-weekly-drawer .el-drawer__body) {
  padding-top: 12px;
  background: #fbfcfe;
}

:global(.key-matter-weekly-drawer .el-drawer__footer) {
  border-top: 1px solid #e5e7eb;
}

@media (max-width: 720px) {
  .page-toolbar,
  .meeting-toolbar,
  .milestone-toolbar,
  .meeting-item-header {
    align-items: stretch;
    flex-direction: column;
  }

  .mode-switch {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .toolbar-actions {
    justify-content: flex-end;
  }

  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .register-layout {
    display: block;
  }

  .list-filter-rail {
    position: static;
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 12px;
    padding: 10px;
    overflow-x: auto;
  }

  .list-filter-header,
  .list-filter-section > header {
    display: none;
  }

  .list-filter-all,
  .list-filter-section,
  .list-filter-section button {
    flex: 0 0 auto;
  }

  .list-filter-section {
    display: flex;
    gap: 6px;
    padding-top: 0;
    border-top: 0;
  }

  .list-filter-all,
  .list-filter-section button {
    width: 150px;
    min-height: 48px;
  }

  .summary-cell {
    min-height: 72px;
    padding: 12px;
    border-bottom: 1px solid var(--gray-200);
  }

  .summary-cell:nth-child(2) {
    border-right: 0;
  }

  .summary-cell:nth-child(n + 3) {
    border-bottom: 0;
  }

  .summary-cell strong {
    font-size: 23px;
  }

  .filter-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .keyword-input {
    grid-column: span 2;
  }

  .meeting-status {
    justify-content: flex-start;
    flex-wrap: wrap;
  }

  .meeting-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .grouping-control {
    justify-content: space-between;
  }

  .meeting-controls :deep(.el-date-editor),
  .milestone-toolbar :deep(.el-date-editor) {
    width: 100%;
  }

  .meeting-group-header {
    align-items: stretch;
    flex-direction: column;
    gap: 14px;
  }

  .group-summary {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .group-summary > div {
    min-width: 0;
    padding: 0 8px;
  }

  .meeting-item {
    padding: 0;
  }

  .two-columns,
  .three-columns {
    grid-template-columns: 1fr;
  }

  .meeting-brief,
  .detail-brief-grid,
  .detail-overview,
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .meeting-item-header {
    padding: 16px 14px 13px 18px;
  }

  .meeting-progress-track {
    margin: 0 14px 0 18px;
  }

  .meeting-brief {
    padding: 15px 14px 0 18px;
  }

  .meeting-primary-report,
  .detail-primary-report {
    min-height: 0;
  }

  .meeting-next-action,
  .detail-next-action {
    grid-template-columns: 1fr;
    gap: 7px;
  }

  .meeting-next-action time {
    white-space: normal;
  }

  .meeting-item-footer {
    justify-content: flex-start;
    flex-wrap: wrap;
    padding: 11px 14px 13px 18px;
  }

  .missing-update {
    padding: 14px;
  }

  .progress-editor {
    grid-template-columns: 1fr 96px;
  }

  .detail-overview {
    gap: 16px;
    padding: 18px 16px;
  }

  .detail-heading h2 {
    font-size: 19px;
  }

  .detail-progress-panel {
    padding: 14px 0 0;
    border-top: 1px solid rgb(255 255 255 / 28%);
    border-left: 0;
  }

  .detail-hero-actions {
    flex-direction: column;
    margin-top: -4px;
  }

  .detail-hero-actions :deep(.el-button) {
    width: 100%;
  }

  .detail-layout {
    gap: 14px;
    margin-top: 14px;
  }

  .detail-aside {
    position: static;
    order: -1;
    grid-template-columns: 1fr;
  }

  .detail-section-header {
    align-items: flex-start;
    flex-direction: column;
    padding: 16px;
  }

  .detail-section-header :deep(.el-button) {
    width: 100%;
  }

  .detail-brief-grid {
    padding: 14px 16px 16px;
  }

  .history-timeline {
    padding: 0 16px 16px;
  }

  .history-timeline::before {
    left: 21px;
  }

  .history-entry {
    grid-template-columns: 1fr;
    gap: 8px;
    padding-left: 28px;
  }

  .history-entry::before {
    left: 0;
  }

  .history-marker {
    padding-top: 0;
    text-align: left;
  }

  .history-marker time,
  .history-marker > span {
    display: inline-flex;
  }

  .history-marker > span {
    margin: 0 0 0 6px;
  }

  .history-entry article > header {
    align-items: flex-start;
  }

  .history-status {
    flex-wrap: wrap;
  }

  .milestone-panel {
    padding: 18px 14px;
  }

  .milestone-node-track {
    gap: 44px;
    padding-inline: 18px;
  }
}

/* Calicat 大事儿画布：冷白驾驶舱 + 逐事项周会演示台 */
.key-matters-page {
  --km-primary: #4f5cf7;
  --km-primary-strong: #315efb;
  --km-indigo-soft: #eef2ff;
  --km-canvas: #f8fafc;
  --km-surface: #ffffff;
  --km-surface-muted: #f8fafc;
  --km-border: #e2e8f0;
  --km-ink: #1e293b;
  --km-muted: #64748b;
  --km-success: #10b981;
  --km-success-soft: #ecfdf5;
  --km-warning: #f59e0b;
  --km-warning-soft: #fffbeb;
  --km-danger: #ef4444;
  --km-danger-soft: #fff1f2;
  --km-radius-sm: 8px;
  --km-radius-md: 12px;
  --km-radius-lg: 18px;
  --km-shadow: 0 12px 30px rgb(15 23 42 / 7%);
  --km-motion: 180ms cubic-bezier(.2, .8, .2, 1);
}

:global(body.key-matters-presentation) {
  overflow: hidden;
}

.key-matters-page.standalone-meeting-page {
  min-height: 100dvh;
  height: 100dvh;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 12%, rgb(79 92 247 / 10%), transparent 26%),
    linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
}

.meeting-bootstrap {
  width: 100%;
  min-height: 100dvh;
  background: #f8fafc;
}

.meeting-bootstrap :deep(.el-loading-mask) {
  background: #f8fafc;
}

.meeting-bootstrap :deep(.el-loading-text) {
  color: var(--km-muted);
  font-size: 13px;
}

.standalone-meeting-page .presentation-layout {
  height: 100dvh;
}

.key-matters-page :deep(.el-button--primary) {
  --el-button-bg-color: var(--km-primary-strong);
  --el-button-border-color: var(--km-primary-strong);
  --el-button-hover-bg-color: #244fe4;
  --el-button-hover-border-color: #244fe4;
  box-shadow: 0 8px 18px rgb(49 94 251 / 20%);
}

.key-matters-page :deep(.el-radio-button__inner) {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  padding: 0 17px;
  border-color: var(--km-border);
  color: #475569;
  font-weight: 650;
}

.key-matters-page :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: linear-gradient(135deg, #6366f1, #2563eb);
  border-color: #4f5cf7;
  box-shadow: 0 6px 14px rgb(79 92 247 / 24%);
}

.page-toolbar {
  display: grid;
  grid-template-columns: max-content minmax(440px, 1fr) max-content;
  align-items: center;
  gap: 18px;
  min-height: 46px;
  margin-bottom: 20px;
}

.mode-note {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.mode-note .el-icon {
  color: var(--km-primary);
}

.meeting-mode-trigger {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 10px !important;
}

.toolbar-actions :deep(.el-date-editor) {
  width: 178px;
}

.summary-strip {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  overflow: visible;
  border: 0;
  background: transparent;
}

.summary-cell {
  min-height: 104px;
  display: grid;
  grid-template-columns: 1fr;
  align-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: var(--km-surface);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
}

.summary-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--km-muted);
  font-size: 13px;
  font-weight: 650;
}

.summary-label > span {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  color: var(--km-primary);
  background: var(--km-indigo-soft);
}

.summary-cell.progressing .summary-label > span {
  color: #2563eb;
  background: #eff6ff;
}

.summary-cell.risk .summary-label > span {
  color: var(--km-danger);
  background: var(--km-danger-soft);
}

.summary-cell.pending .summary-label > span {
  color: var(--km-warning);
  background: var(--km-warning-soft);
}

.summary-value {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.summary-value strong {
  color: var(--km-ink);
  font-size: 25px;
  line-height: 1;
}

.summary-value small {
  color: var(--km-muted);
  font-size: 11px;
}

.summary-meter,
.stat-progress {
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: #eef2f7;
}

.summary-meter i,
.stat-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #6366f1, #4f5cf7);
}

.summary-cell.progressing .summary-meter i { background: #3b82f6; }
.summary-cell.risk .summary-meter i { background: var(--km-danger); }
.summary-cell.pending .summary-meter i { background: var(--km-warning); }

.toolbar-summary {
  min-width: 0;
  margin: 0;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--km-border);
  border-radius: 10px;
  background: var(--km-surface);
  box-shadow: 0 3px 12px rgb(15 23 42 / 4%);
}

.toolbar-summary .summary-cell {
  min-width: 0;
  min-height: 58px;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto 3px;
  align-content: center;
  gap: 7px 8px;
  padding: 8px 10px;
  border: 0;
  border-right: 1px solid var(--km-border);
  border-radius: 0;
  box-shadow: none;
}

.toolbar-summary .summary-cell:last-child {
  border-right: 0;
}

.toolbar-summary .summary-label {
  min-width: 0;
  gap: 6px;
  font-size: 11px;
  white-space: nowrap;
}

.toolbar-summary .summary-label > span {
  width: 24px;
  height: 24px;
  border-radius: 7px;
}

.toolbar-summary .summary-value {
  align-items: baseline;
  justify-content: flex-end;
  gap: 5px;
  white-space: nowrap;
}

.toolbar-summary .summary-value strong {
  font-size: 19px;
}

.toolbar-summary .summary-value small {
  font-size: 9px;
}

.toolbar-summary .summary-meter {
  grid-column: 1 / -1;
  height: 3px;
}

.filter-bar {
  padding: 16px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: var(--km-surface);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
}

.table-panel {
  border-radius: var(--km-radius-md);
  box-shadow: 0 4px 14px rgb(15 23 42 / 4%);
}

.meeting-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin: 8px 0 22px;
}

.meeting-heading-lockup {
  display: flex;
  align-items: flex-end;
  gap: 22px;
}

.meeting-total {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  color: var(--km-primary);
  line-height: .88;
}

.meeting-total strong {
  color: var(--km-ink);
  font-size: clamp(54px, 7vw, 74px);
  font-weight: 800;
  letter-spacing: -.06em;
}

.meeting-total span {
  padding-bottom: 4px;
  font-size: 24px;
  font-weight: 750;
}

.meeting-heading-lockup h1 {
  margin: 0;
  color: var(--km-ink);
  font-size: 30px;
  line-height: 1.08;
}

.meeting-heading-lockup p {
  margin-top: 7px;
  color: var(--km-muted);
  font-size: 13px;
}

.meeting-heading-lockup .meeting-count {
  margin-top: 5px;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.grouping-segment {
  padding: 4px;
  border-radius: 10px;
  background: #f1f5f9;
}

.grouping-segment button {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  border-radius: 8px;
}

.grouping-segment button.active {
  color: var(--km-ink);
  background: #fff;
  box-shadow: 0 2px 8px rgb(15 23 42 / 8%);
}

.meeting-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 24px;
}

.meeting-stat {
  min-height: 184px;
  padding: 22px 24px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-md);
  background: #fff;
}

.meeting-stat .stat-icon {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: rgb(255 255 255 / 85%);
  box-shadow: 0 6px 14px rgb(15 23 42 / 6%);
}

.meeting-stat > strong {
  display: block;
  margin-top: 16px;
  font-size: 38px;
  line-height: 1;
}

.meeting-stat h2 {
  margin: 5px 0 14px;
  font-size: 14px;
  font-weight: 700;
}

.meeting-stat p {
  margin-top: 8px;
  font-size: 11px;
  opacity: .72;
}

.meeting-stat.updated { color: #059669; border-color: #bbf7d0; background: var(--km-success-soft); }
.meeting-stat.updated .stat-progress span { background: var(--km-success); }
.meeting-stat.pending { color: #d97706; border-color: #fde68a; background: var(--km-warning-soft); }
.meeting-stat.pending .stat-progress span { background: var(--km-warning); }
.meeting-stat.risk { color: #dc2626; border-color: #fecdd3; background: var(--km-danger-soft); }
.meeting-stat.progress { color: #4f46e5; border-color: #dbeafe; background: #eef2ff; }
.meeting-stat.progress .stat-progress span { background: #6366f1; }

.stat-health {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
}

.stat-health i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--km-success);
}

.meeting-list {
  display: grid;
  gap: 20px;
}

.meeting-group {
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-lg);
  background: #fff;
  box-shadow: 0 8px 24px rgb(15 23 42 / 4%);
}

.meeting-group-header {
  align-items: center;
  padding: 18px 22px;
  margin: 0;
  border: 0;
  border-bottom: 1px solid #edf2f7;
  border-radius: 0;
  background: linear-gradient(90deg, #fbfdff, #fff);
}

.group-identity {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.group-avatar,
.presentation-avatar {
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: #fff;
  background: linear-gradient(135deg, #34d399, #0ea5e9);
  box-shadow: 0 7px 14px rgb(14 165 233 / 18%);
  font-weight: 750;
}

.group-avatar.tone-1 { background: linear-gradient(135deg, #3b82f6, #2563eb); }
.group-avatar.tone-2 { background: linear-gradient(135deg, #a855f7, #7c3aed); }
.group-avatar.tone-3 { background: linear-gradient(135deg, #f59e0b, #f97316); }
.group-avatar.female {
  background: linear-gradient(135deg, #fb7185, #db2777);
  box-shadow: 0 7px 14px rgb(219 39 119 / 20%);
}

.group-identity h2 {
  margin: 0;
  color: var(--km-ink);
  font-size: 18px;
}

.group-identity p {
  margin-top: 3px;
  color: var(--km-muted);
  font-size: 12px;
}

.group-summary > div {
  min-width: 70px;
  border: 0;
}

.group-summary dd {
  color: var(--km-ink);
  font-size: 19px;
}

.group-summary dt {
  margin-top: 3px;
}

.meeting-items-stack {
  gap: 14px;
  padding: 18px 20px 20px;
}

.meeting-item {
  border-radius: var(--km-radius-md);
  box-shadow: none;
}

.meeting-item::before {
  display: none;
}

.meeting-item.status-有风险,
.meeting-item.status-已阻塞 {
  border-color: #fed7aa;
  background: #fffdf8;
}

.meeting-item-header {
  align-items: center;
  padding: 16px 18px;
}

.meeting-title-group {
  align-items: center;
}

.meeting-title-group h3 {
  font-size: 16px;
}

.meeting-status {
  align-items: center;
  flex-wrap: wrap;
}

.matter-progress-inline {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #334155;
  font-size: 12px;
  font-weight: 750;
}

.matter-progress-inline > i {
  width: 74px;
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: #eef2f7;
}

.matter-progress-inline b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #60a5fa, #6366f1);
}

.meeting-brief {
  padding: 0 18px 0;
}

.meeting-primary-report {
  border-color: #c7d2fe;
  box-shadow: inset 0 0 0 1px #c7d2fe;
}

.meeting-primary-report > p,
.presentation-main-report ol {
  white-space: pre-line;
}

.meeting-item-footer {
  justify-content: flex-start;
  align-items: center;
  padding: 12px 18px 14px;
  border-top: 1px solid #f1f5f9;
}

.meeting-item-footer > span,
.meeting-item-footer :deep(.el-button) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.meeting-item-footer :deep(.el-button) {
  margin-left: auto;
}

.missing-update {
  min-height: 156px;
  margin: 0 18px 14px;
  border-style: solid;
}

.missing-icon {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fef3c7;
}

.presentation-layout {
  --presentation-panel-height: min(740px, calc(100dvh - 120px));
  display: grid;
  grid-template-columns: 252px minmax(0, 1fr);
  align-items: center;
  gap: 24px;
  height: 100dvh;
  padding: 32px 40px;
  box-sizing: border-box;
  overflow: hidden;
}

.presentation-group-rail {
  min-height: 0;
  height: var(--presentation-panel-height);
  max-height: none;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 18px 14px;
  border: 1px solid var(--km-border);
  border-radius: var(--km-radius-lg);
  background: rgb(255 255 255 / 92%);
  box-shadow: var(--km-shadow);
}

.presentation-group-header,
.presentation-group-card-main,
.presentation-group-stats {
  display: flex;
  align-items: center;
}

.presentation-group-header {
  justify-content: space-between;
  padding: 0 4px 14px;
}

.presentation-group-header > div {
  display: grid;
  gap: 4px;
}

.presentation-rail-kicker {
  color: var(--km-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.presentation-group-header strong {
  color: var(--km-ink);
  font-size: 16px;
}

.presentation-group-total {
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--km-muted);
  background: var(--km-surface-muted);
  font-size: 12px;
  font-weight: 700;
}

.presentation-group-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: var(--km-surface-muted);
}

.presentation-group-switch button {
  min-height: 32px;
  border: 0;
  border-radius: 7px;
  color: var(--km-muted);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  font-weight: 750;
}

.presentation-group-switch button.active {
  color: var(--km-primary);
  background: #fff;
  box-shadow: 0 3px 10px rgb(15 23 42 / 8%);
}

.presentation-group-list {
  min-height: 0;
  display: grid;
  grid-auto-rows: max-content;
  align-content: start;
  gap: 10px;
  flex: 1;
  max-height: none;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 2px 4px 2px 2px;
}

.presentation-group-card {
  overflow: hidden;
  border: 1px solid var(--km-border);
  border-radius: 13px;
  background: #fff;
  transition: border-color var(--km-motion), box-shadow var(--km-motion), transform var(--km-motion);
}

.presentation-group-card.active {
  border-color: rgb(79 92 247 / 55%);
  box-shadow: 0 8px 18px rgb(79 92 247 / 10%);
  transform: translateY(-1px);
}

.presentation-group-card-main {
  width: 100%;
  gap: 9px;
  padding: 12px 10px 9px;
  border: 0;
  color: var(--km-ink);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.presentation-group-avatar {
  width: 30px;
  height: 30px;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  color: var(--km-primary);
  background: var(--km-indigo-soft);
  font-size: 13px;
  font-weight: 800;
}

.presentation-group-avatar.female {
  color: #be185d;
  background: #fdf2f8;
  box-shadow: inset 0 0 0 1px #fbcfe8;
}

.presentation-group-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
  flex: 1;
}

.presentation-group-copy strong {
  overflow: hidden;
  color: var(--km-ink);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.presentation-group-copy small {
  color: var(--km-muted);
  font-size: 11px;
}

.presentation-group-chevron {
  color: #94a3b8;
  font-size: 22px;
  line-height: 1;
}

.presentation-group-meter {
  height: 4px;
  margin: 0 10px;
  overflow: hidden;
  border-radius: 99px;
  background: #eef2f7;
}

.presentation-group-meter i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--km-primary), #7c83ff);
}

.presentation-group-stats {
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px 8px;
  color: #94a3b8;
  font-size: 10px;
}

.presentation-group-stats .has-risk {
  color: #d97706;
  font-weight: 700;
}

.presentation-group-matters {
  display: grid;
  gap: 2px;
  padding: 0 6px 7px;
}

.presentation-group-matters button {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 6px 5px;
  border: 0;
  border-radius: 6px;
  color: var(--km-muted);
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-size: 11px;
}

.presentation-group-matters button:hover,
.presentation-group-matters button.active {
  color: var(--km-primary);
  background: var(--km-indigo-soft);
}

.presentation-group-matters button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.presentation-group-matters button i {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--km-success);
}

.presentation-group-matters button i.pending {
  background: var(--km-warning);
}

.presentation-stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  height: var(--presentation-panel-height);
  box-sizing: border-box;
  padding: 0;
  outline: none;
  display: flex;
  flex-direction: column;
  justify-content: stretch;
}

.presentation-pending-label {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid #fde68a;
  border-radius: 999px;
  color: #d97706;
  background: var(--km-warning-soft);
  font-size: 12px;
  font-weight: 700;
}

.presentation-shell {
  min-height: 0;
  height: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 960px) auto;
  align-items: center;
  justify-content: center;
  gap: 14px;
}

.presentation-arrow {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--km-border);
  border-radius: 50%;
  color: var(--km-muted);
  background: rgb(255 255 255 / 88%);
  cursor: pointer;
  font-size: 18px;
  transition: border-color var(--km-motion), color var(--km-motion), box-shadow var(--km-motion), transform var(--km-motion);
  flex: 0 0 auto;
}

.presentation-arrow:hover {
  border-color: var(--km-primary);
  color: var(--km-primary);
  box-shadow: 0 4px 12px rgb(79 92 247 / 18%);
  transform: scale(1.08);
}

.presentation-arrow:active {
  transform: scale(0.95);
}

.presentation-card {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 520px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--km-border);
  border-radius: 24px;
  background: #fff;
  box-shadow: var(--km-shadow);
}

.presentation-card.is-pending {
  border-color: #fbbf24;
}

.presentation-accent {
  flex: 0 0 auto;
  height: 8px;
  background: linear-gradient(90deg, #34d399, #10b981);
}

.presentation-card.is-pending .presentation-accent {
  background: linear-gradient(90deg, #fbbf24, #f59e0b);
}

.presentation-card-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 32px 10px;
}

.presentation-tags {
  display: flex;
  align-items: center;
  gap: 12px;
}

.project-chip,
.updated-chip {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border-radius: 8px;
  color: #475569;
  background: #f1f5f9;
  font-size: 13px;
  font-weight: 650;
}

.updated-chip {
  color: #059669;
  background: var(--km-success-soft);
}

.presentation-status-chip {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 11px;
  border-radius: 8px;
  color: #2563eb;
  background: #eff6ff;
  font-size: 12px;
  font-weight: 700;
}

.presentation-status-chip i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.presentation-status-chip.status-有风险,
.presentation-status-chip.status-已暂停 {
  color: #d97706;
  background: var(--km-warning-soft);
}

.presentation-status-chip.status-已阻塞 {
  color: #dc2626;
  background: var(--km-danger-soft);
}

.presentation-status-chip.status-已完成 {
  color: #059669;
  background: var(--km-success-soft);
}

.presentation-title-block {
  flex: 0 0 auto;
  padding: 0 32px 16px;
}

.presentation-title-block h1 {
  margin: 0;
  color: var(--km-ink);
  font-size: clamp(25px, 3vw, 34px);
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.presentation-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  color: var(--km-muted);
  font-size: 13px;
}

.presentation-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  font-size: 11px;
}

.presentation-avatar.female {
  background: linear-gradient(135deg, #fb7185, #db2777);
  box-shadow: 0 5px 12px rgb(219 39 119 / 20%);
}

.presentation-meta strong {
  color: #475569;
}

.presentation-meta > i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #cbd5e1;
}

.presentation-meta > span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.presentation-inline-progress b {
  width: 120px;
  height: 10px;
  display: inline-block;
  overflow: hidden;
  border-radius: 999px;
  background: #f1f5f9;
}

.presentation-inline-progress b i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #34d399, #10b981);
  transition: width .15s ease;
}

.presentation-inline-progress.is-editable {
  min-width: 206px;
  user-select: none;
}

.presentation-progress-slider {
  width: 160px;
  height: 20px;
  touch-action: none;
}

.presentation-progress-slider :deep(.el-slider__runway) {
  height: 8px;
  margin: 6px 0;
  background: #e2e8f0;
}

.presentation-progress-slider :deep(.el-slider__bar) {
  height: 8px;
  background: linear-gradient(90deg, #34d399, #10b981);
}

.presentation-progress-slider :deep(.el-slider__button) {
  width: 14px;
  height: 14px;
  border: 3px solid #10b981;
  box-shadow: 0 2px 7px rgb(15 118 110 / 24%);
}

.presentation-progress-slider :deep(.el-slider__button-wrapper) {
  top: -14px;
}

.presentation-progress-slider :deep(.el-slider__button-wrapper:hover .el-slider__button),
.presentation-progress-slider :deep(.el-slider__button-wrapper.hover .el-slider__button),
.presentation-progress-slider :deep(.el-slider__button-wrapper.dragging .el-slider__button) {
  transform: scale(1.12);
}

.presentation-progress-text {
  min-width: 36px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.presentation-meta-status {
  display: flex;
  align-items: center;
  gap: 4px;
}

.presentation-meta-status button {
  min-height: 28px;
  padding: 2px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  color: #64748b;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  transition: border-color var(--km-motion), color var(--km-motion), background var(--km-motion);
}

.presentation-meta-status button:hover {
  border-color: #94a3b8;
  color: #334155;
}

.presentation-meta-status button.active {
  border-color: var(--km-primary);
  color: var(--km-primary);
  background: #eef2ff;
}

/* 状态颜色 */
.presentation-meta-status button.status-未开始.active {
  border-color: #94a3b8;
  color: #475569;
  background: #f1f5f9;
}
.presentation-meta-status button.status-推进中.active {
  border-color: #3b82f6;
  color: #2563eb;
  background: #eff6ff;
}
.presentation-meta-status button.status-有风险.active {
  border-color: #f59e0b;
  color: #d97706;
  background: #fffbeb;
}
.presentation-meta-status button.status-已阻塞.active {
  border-color: #ef4444;
  color: #dc2626;
  background: #fef2f2;
}
.presentation-meta-status button.status-已完成.active {
  border-color: #10b981;
  color: #059669;
  background: #ecfdf5;
}
.presentation-meta-status button.status-已暂停.active {
  border-color: #6b7280;
  color: #4b5563;
  background: #f9fafb;
}

.presentation-meta-divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  flex: 0 0 auto;
  border-radius: 0;
}

.presentation-read-view {
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, .75fr);
  gap: 14px 18px;
  padding: 0 32px 24px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}

.presentation-read-view::-webkit-scrollbar,
.presentation-edit-view::-webkit-scrollbar {
  display: none;
}

.presentation-main-report {
  min-height: 154px;
  padding: 20px;
  border: 2px solid #c7d2fe;
  border-radius: 12px;
  background: #f8fafc;
}

.presentation-main-report ol {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-left: 22px;
  color: #334155;
  line-height: 1.55;
}

.presentation-signals {
  display: grid;
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.presentation-signals .meeting-signal {
  border-left: 0;
  border-radius: 12px;
}

.presentation-next-action {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #eff6ff;
}

.presentation-next-action p {
  color: var(--km-ink);
  font-weight: 650;
}

.presentation-next-action time {
  color: var(--km-muted);
  font-size: 12px;
}

.presentation-edit-view {
  min-height: 0;
  flex: 1;
  padding: 0 32px 24px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}

.presentation-complete-view {
  min-height: 0;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  padding: 24px 32px;
  text-align: center;
}

.presentation-complete-view strong {
  color: var(--success);
  font-size: 20px;
}

.presentation-complete-view p {
  max-width: 46ch;
  margin: 0;
  color: var(--km-muted);
  line-height: 1.6;
}

.weekly-workspace-compact {
  gap: 8px;
}

.weekly-workspace-compact .weekly-workspace-header {
  padding-bottom: 6px;
}

.weekly-workspace-compact .weekly-workspace-header strong {
  font-size: 14px;
}

.weekly-workspace-compact .weekly-state-bar,
.weekly-workspace-compact .weekly-section {
  padding: 8px;
}

.weekly-workspace-compact .weekly-state-bar {
  grid-template-columns: 180px 150px;
  justify-content: start;
}

.weekly-workspace-compact .weekly-section {
  gap: 6px;
}

.weekly-workspace-compact .weekly-section > header small {
  display: inline;
  margin-left: 5px;
}

.weekly-workspace-compact :deep(.el-textarea__inner) {
  min-height: 58px !important;
}

.presentation-card-footer {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 32px;
  border-top: 1px solid #edf2f7;
  background: #fbfdff;
}

.presentation-key-hints,
.presentation-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #94a3b8;
  font-size: 12px;
}

.presentation-key-hints button {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--km-border);
  border-radius: 7px;
  color: var(--km-primary);
  background: #fff;
  cursor: pointer;
}

.presentation-key-hints button:disabled {
  color: #cbd5e1;
  cursor: not-allowed;
}

.presentation-thumbnails {
  position: absolute;
  right: 0;
  bottom: -42px;
  left: 0;
  display: flex;
  justify-content: center;
  gap: 6px;
  margin: 0;
}

.presentation-thumbnails button {
  width: 40px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--km-border);
  border-radius: 6px;
  color: #94a3b8;
  background: #fff;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.presentation-thumbnails button.active {
  border: 2px solid var(--km-primary);
  color: var(--km-primary);
  background: var(--km-indigo-soft);
}

.presentation-thumbnails button.complete {
  color: var(--km-success);
  border-color: #bbf7d0;
  background: var(--km-success-soft);
}

.presentation-thumbnails button.pending.active {
  color: #d97706;
  border-color: var(--km-warning);
  background: var(--km-warning-soft);
}

.detail-clean {
  display: grid;
  gap: 16px;
}

.detail-clean-hero,
.detail-clean-card {
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 22px rgb(15 23 42 / 4%);
}

.detail-clean-hero {
  display: grid;
  gap: 18px;
  padding: 22px;
}

.detail-clean-title-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 18px;
}

.detail-clean-title {
  min-width: 0;
}

.detail-clean-title h2 {
  margin: 10px 0 0;
  color: #0f172a;
  font-size: 23px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.detail-clean-title p {
  max-width: 76ch;
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.detail-clean-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-clean-actions :deep(.el-button) {
  border-radius: 8px;
}

.detail-clean-facts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
  overflow: hidden;
}

.detail-clean-facts > div {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid #e5e7eb;
}

.detail-clean-facts > div:last-child {
  border-right: 0;
}

.detail-clean-facts dt {
  color: #94a3b8;
  font-size: 11px;
}

.detail-clean-facts dd {
  margin: 5px 0 0;
  overflow: hidden;
  color: #1e293b;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-clean-progress {
  display: grid;
  gap: 9px;
}

.detail-clean-progress > div {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.detail-clean-progress span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.detail-clean-progress strong {
  color: #0f172a;
  font-size: 26px;
  line-height: 1;
}

.detail-clean-progress small {
  margin-left: 1px;
  color: #64748b;
  font-size: 14px;
}

.detail-clean-progress em {
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.detail-clean-progress em.tone-up,
.history-delta.tone-up {
  color: #15803d;
}

.detail-clean-progress em.tone-down,
.history-delta.tone-down {
  color: #dc2626;
}

.detail-clean-body {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(320px, 0.85fr);
  align-items: start;
  gap: 16px;
}

.detail-clean-card {
  min-width: 0;
  overflow: hidden;
}

.detail-clean-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 16px 18px;
  border-bottom: 1px solid #e2e8f0;
}

.detail-clean-card > header span {
  display: block;
  color: #4f5cf7;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.detail-clean-card > header h3 {
  margin: 3px 0 0;
  color: #0f172a;
  font-size: 16px;
}

.detail-clean-card > header time,
.detail-clean-card > header small {
  color: #94a3b8;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.detail-clean-brief-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
}

.detail-clean-brief-grid article {
  min-width: 0;
  padding: 16px 18px;
  border-right: 1px solid #eef2f7;
  border-bottom: 1px solid #eef2f7;
}

.detail-clean-brief-grid article:nth-child(2n),
.detail-clean-brief-grid article:last-child {
  border-right: 0;
}

.detail-clean-brief-grid article:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.detail-clean-brief-grid .brief-main {
  grid-column: 1 / -1;
}

.detail-clean-brief-grid .brief-main,
.detail-clean-brief-grid .brief-next {
  background: #f8fafc;
}

.detail-clean-brief-grid article > span {
  display: block;
  margin-bottom: 8px;
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}

.detail-clean-brief-grid p {
  margin: 0;
  color: #1e293b;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.detail-clean-timeline {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.detail-clean-timeline > li {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 14px;
  padding: 14px 16px;
  border-bottom: 1px solid #eef2f7;
}

.detail-clean-timeline > li:last-child {
  border-bottom: 0;
}

.detail-clean-timeline time {
  padding-top: 4px;
  color: #64748b;
  font-size: 12px;
  font-weight: 750;
}

.detail-clean-timeline article {
  min-width: 0;
}

.detail-clean-timeline article > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.detail-clean-timeline article > p {
  margin: 8px 0 0;
  color: #334155;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.latest-pill {
  display: inline-flex;
  padding: 2px 7px;
  border-radius: 999px;
  color: #1d4ed8;
  background: #dbeafe;
  font-size: 11px;
  font-weight: 750;
}

.key-matters-page button:focus-visible,
.key-matters-page :deep(.el-button:focus-visible),
.key-matters-page :deep(.el-input__wrapper.is-focus),
.key-matters-page :deep(.el-select__wrapper.is-focused) {
  outline: 3px solid rgb(79 92 247 / 28%);
  outline-offset: 2px;
}

@media (max-width: 1440px) {
  .presentation-layout {
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 16px;
    padding: 24px;
  }

  .presentation-shell {
    gap: 10px;
  }
}

@media (max-width: 1280px) {
  .page-toolbar {
    grid-template-columns: minmax(0, 1fr) max-content;
  }

  .toolbar-summary {
    grid-column: 1 / -1;
    grid-row: 2;
  }
}

@media (max-width: 1050px) {
  .meeting-summary-grid,
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-summary {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .detail-clean-title-row,
  .detail-clean-body {
    grid-template-columns: 1fr;
  }

  .detail-clean-actions {
    justify-content: flex-start;
  }

  .detail-clean-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-clean-facts > div {
    border-right: 1px solid #e5e7eb;
    border-bottom: 1px solid #e5e7eb;
  }

  .detail-clean-facts > div:nth-child(2n),
  .detail-clean-facts > div:last-child {
    border-right: 0;
  }

  .presentation-layout {
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 14px;
  }

  .presentation-shell {
    gap: 8px;
  }

  .presentation-read-view {
    grid-template-columns: 1fr;
  }

  .presentation-signals {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: auto;
  }
}

@media (max-width: 1180px) {
  .presentation-layout {
    grid-template-columns: 200px minmax(0, 1fr);
  }

  .presentation-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .presentation-arrow {
    display: none;
  }
}

@media (max-height: 820px) and (min-width: 721px) {
  .presentation-layout {
    height: 100dvh;
    align-items: start;
    padding-top: 16px;
    padding-bottom: 16px;
    overflow-x: hidden;
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .presentation-group-rail {
    height: calc(100dvh - 32px);
    max-height: calc(100dvh - 32px);
    padding-top: 12px;
    padding-bottom: 12px;
  }

  .presentation-group-header {
    padding-bottom: 8px;
  }

  .presentation-group-switch {
    margin-bottom: 8px;
  }

  .presentation-group-list {
    max-height: none;
    gap: 7px;
  }

  .presentation-stage {
    height: auto;
    min-height: calc(100dvh - 32px);
    padding: 0;
    justify-content: flex-start;
  }

  .presentation-shell {
    min-height: auto;
    flex: 0 0 auto;
  }

  .presentation-card {
    height: auto;
    min-height: 0;
  }

  .presentation-card-header {
    padding: 10px 20px 5px;
  }

  .presentation-title-block {
    padding: 0 20px 8px;
  }

  .presentation-title-block h1 {
    font-size: 22px;
  }

  .presentation-meta {
    margin-top: 4px;
  }

  .presentation-read-view,
  .presentation-edit-view {
    padding-right: 20px;
    padding-bottom: 16px;
    padding-left: 20px;
    overflow: visible;
  }

  .weekly-workspace-compact {
    gap: 8px;
  }

  .weekly-workspace-compact .weekly-state-bar,
  .weekly-workspace-compact .weekly-section {
    padding: 8px;
  }

  .weekly-workspace-compact .weekly-section {
    gap: 6px;
  }

  .weekly-workspace-compact .weekly-section > header small {
    display: inline;
  }

  .weekly-workspace-compact :deep(.el-textarea__inner) {
    height: auto;
    min-height: 58px !important;
  }

  .weekly-workspace-compact .weekly-next {
    grid-template-columns: 1fr;
    align-items: stretch;
  }

  .presentation-card-footer {
    padding: 10px 20px;
  }

  .presentation-thumbnails {
    position: static;
    flex: 0 0 auto;
    margin-top: 10px;
  }

  .presentation-thumbnails button {
    height: 26px;
  }
}

@media (max-width: 720px) {
  .page-toolbar {
    align-items: stretch;
    grid-template-columns: minmax(0, 1fr);
  }

  .toolbar-actions,
  .toolbar-summary {
    grid-column: 1;
  }

  .toolbar-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-summary .summary-cell:nth-child(2) {
    border-right: 0;
  }

  .toolbar-summary .summary-cell:nth-child(-n + 2) {
    border-bottom: 1px solid var(--km-border);
  }

  .detail-clean-hero {
    padding: 16px;
  }

  .detail-clean-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-clean-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .detail-clean-facts,
  .detail-clean-brief-grid {
    grid-template-columns: 1fr;
  }

  .detail-clean-facts > div,
  .detail-clean-brief-grid article {
    border-right: 0;
  }

  .detail-clean-brief-grid article:nth-last-child(-n + 2) {
    border-bottom: 1px solid #eef2f7;
  }

  .detail-clean-brief-grid article:last-child {
    border-bottom: 0;
  }

  .detail-clean-timeline > li {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .detail-clean-timeline article > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar-actions :deep(.el-date-editor) {
    width: 100%;
  }

  .meeting-hero,
  .meeting-heading-lockup,
  .presentation-card-header,
  .presentation-card-footer,
  .presentation-meta {
    align-items: flex-start;
  }

  .meeting-hero,
  .presentation-card-header,
  .presentation-card-footer {
    flex-direction: column;
  }

  .meeting-total strong {
    font-size: 52px;
  }

  .meeting-heading-lockup h1 {
    font-size: 24px;
  }

  .meeting-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .meeting-stat {
    min-height: 152px;
    padding: 16px;
  }

  .meeting-stat > strong {
    font-size: 30px;
  }

  .group-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .group-summary > div {
    text-align: left;
  }

  .meeting-items-stack {
    padding: 12px;
  }

  .meeting-status {
    padding-left: 46px;
  }

  .meeting-brief {
    padding: 0 12px;
  }

  .meeting-item-footer :deep(.el-button) {
    width: auto;
    margin-left: 0;
  }

  .presentation-layout {
    display: block;
    width: 100%;
    height: 100dvh;
    padding: 10px;
    overflow-x: hidden;
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .presentation-group-rail {
    position: static;
    min-height: 0;
    height: auto;
    max-height: none;
    overflow: visible;
    margin-bottom: 10px;
    padding: 10px;
  }

  .presentation-group-list {
    display: flex;
    flex: none;
    max-height: none;
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-gutter: auto;
    padding-bottom: 2px;
  }

  .presentation-group-card {
    width: min(220px, 70vw);
    flex: 0 0 min(220px, 70vw);
  }

  .presentation-group-stats,
  .presentation-group-matters {
    display: none;
  }

  .presentation-shell {
    display: block;
  }

  .presentation-stage {
    position: static;
    height: auto;
    display: block;
    padding: 0 0 12px;
  }

  .presentation-arrow {
    display: none;
  }

  .presentation-card {
    height: auto;
    min-height: 0;
    max-height: none;
    border-radius: 16px;
  }

  .presentation-read-view,
  .presentation-edit-view {
    overflow: visible;
  }

  .presentation-card-header,
  .presentation-title-block,
  .presentation-read-view,
  .presentation-edit-view,
  .presentation-card-footer {
    padding-left: 16px;
    padding-right: 16px;
  }

  .presentation-title-block h1 {
    font-size: 24px;
  }

  .presentation-meta {
    flex-wrap: wrap;
  }

  .presentation-meta > i {
    display: none;
  }

  .presentation-inline-progress {
    width: 100%;
    min-width: 0;
  }

  .presentation-inline-progress b,
  .presentation-progress-slider {
    width: auto;
    flex: 1;
  }

  .presentation-signals,
  .weekly-signal-grid,
  .weekly-state-bar,
  .weekly-workspace-compact .weekly-state-bar {
    grid-template-columns: 1fr;
  }

  .presentation-next-action {
    grid-template-columns: 1fr;
  }

  .weekly-workspace-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .presentation-actions {
    width: 100%;
  }

  .presentation-actions :deep(.el-button) {
    flex: 1;
    margin: 0;
  }

  .presentation-key-hints {
    display: none;
  }

  .presentation-thumbnails {
    position: static;
    gap: 4px;
    margin-top: 10px;
  }

  .presentation-thumbnails button {
    width: 34px;
    height: 26px;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
  }
}
</style>
