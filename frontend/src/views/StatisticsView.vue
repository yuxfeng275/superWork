<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Check,
  CircleCheckFilled,
  CircleCloseFilled,
  Clock,
  Connection,
  Delete as DeleteIcon,
  Edit,
  List as ListIcon,
  Plus,
  QuestionFilled,
  Refresh,
  WarningFilled
} from '@element-plus/icons-vue'
import {
  api,
  type YunxiaoConfigPayload,
  type YunxiaoMemberOption,
  type YunxiaoProjectMapping,
  type YunxiaoProjectOption,
  type YunxiaoUserMapping
} from '@/utils/api'
import { getRoleLabel } from '@/constants/roles'

interface Milestone {
  id?: number
  name: string
  dueDate: string
  status: string
  sortOrder: number
  overdue?: boolean
}

interface Direction {
  id: number
  code: string
  name: string
  objective?: string
  ownerId?: number
  ownerName?: string
  startDate: string
  endDate: string
  status: string
  sortOrder: number
  progress: number
  health: string
  requirementCount: number
  completedRequirementCount: number
  taskCount: number
  completedTaskCount: number
  projectIds: number[]
  projectNames: string[]
  milestones: Milestone[]
}

interface CapacityItem {
  userId: number
  realName: string
  role: string
  activeTaskCount: number
  overdueTaskCount: number
  actualHours: number
  expectedHours: number
  actualEffortRate: number
  plannedHours: number
  plannedLoadRate: number
  loadStatus: string
  dataCompleteness: string
  yunxiaoMapped: boolean
  activeWork: string[]
}

interface WorklogItem {
  userId: number
  realName: string
  role: string
  workDate: string
  expectedHours: number
  actualHours: number
  status: string
  finalResult: boolean
}

type WorklogGroup = 'all' | 'completed' | 'missing' | 'insufficient' | 'unresolved'

interface Dashboard {
  periodStart: string
  periodEnd: string
  planWindowWorkdays: number
  summary: {
    directionCount: number
    atRiskDirectionCount: number
    activeRequirementCount: number
    overdueTaskCount: number
    overloadedPeopleCount: number
    missingWorklogPeopleCount: number
  }
  directions: Direction[]
  capacity: CapacityItem[]
  worklogs: WorklogItem[]
  integration: {
    enabled: boolean
    configured: boolean
    edition: string
    baseUrl: string
    organizationId?: string
    tokenConfigured: boolean
    tokenSource?: string
    organizationConfigured: boolean
    mappedProjects: number
    mappedUsers: number
    lastTestedAt?: string
    lastTestStatus?: string
    lastTestMessage?: string
    lastSuccessfulSync?: string
    lastError?: string
  }
}

interface ProjectOption {
  id: number
  name: string
}

interface UserOption {
  id: number
  username: string
  realName: string
  role: string
}

interface RecordEnvelope<T> {
  records?: T[]
  data?: T[] | { records?: T[] }
}

const emptyDashboard = (): Dashboard => ({
  periodStart: '',
  periodEnd: '',
  planWindowWorkdays: 10,
  summary: {
    directionCount: 0,
    atRiskDirectionCount: 0,
    activeRequirementCount: 0,
    overdueTaskCount: 0,
    overloadedPeopleCount: 0,
    missingWorklogPeopleCount: 0
  },
  directions: [],
  capacity: [],
  worklogs: [],
  integration: {
    enabled: false,
    configured: false,
    edition: 'center',
    baseUrl: '',
    tokenConfigured: false,
    organizationConfigured: false,
    mappedProjects: 0,
    mappedUsers: 0
  }
})

const localDate = (value = new Date()) => {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const today = new Date()
const loading = ref(false)
const syncing = ref(false)
const configSaving = ref(false)
const connectionTesting = ref(false)
const loadError = ref('')
const activeTab = ref('worklogs')
const period = ref<[string, string]>([
  localDate(new Date(today.getFullYear(), today.getMonth(), 1)),
  localDate(today)
])
const planWindow = ref(10)
const dashboard = ref<Dashboard>(emptyDashboard())
const projects = ref<ProjectOption[]>([])
const users = ref<UserOption[]>([])
const projectMappings = ref<YunxiaoProjectMapping[]>([])
const userMappings = ref<YunxiaoUserMapping[]>([])
const yunxiaoProjects = ref<YunxiaoProjectOption[]>([])
const yunxiaoProjectsLoading = ref(false)
const yunxiaoProjectsError = ref('')
const yunxiaoMembers = ref<YunxiaoMemberOption[]>([])
const yunxiaoMembersLoading = ref(false)
const yunxiaoMembersError = ref('')
const worklogGroup = ref<WorklogGroup>('all')
const yunxiaoAnalysis = ref<any>({ total: 0, requirements: 0, tasks: 0, delayed: 0, byOwner: [] })

const directionDialogVisible = ref(false)
const projectMappingDialogVisible = ref(false)
const userMappingDialogVisible = ref(false)
const saving = ref(false)
const editingDirectionId = ref<number | null>(null)

const directionForm = reactive({
  code: '',
  name: '',
  objective: '',
  ownerId: undefined as number | undefined,
  dateRange: [] as string[],
  status: '未开始',
  sortOrder: 0,
  projectIds: [] as number[],
  milestones: [] as Milestone[]
})

const projectMappingForm = reactive({
  projectId: undefined as number | undefined,
  yunxiaoProjectId: '',
  workitemTypeId: '',
  category: 'Req',
  syncEnabled: 1
})

const userMappingForm = reactive({
  userId: undefined as number | undefined,
  yunxiaoUserId: '',
  syncEnabled: 1
})

const configForm = reactive<YunxiaoConfigPayload>({
  enabled: false,
  edition: 'center',
  baseUrl: 'https://openapi-rdc.aliyuncs.com',
  organizationId: '',
  token: ''
})

const editionOptions = [
  { label: '中心化版本', value: 'center' },
  { label: '专有云版本', value: 'region' }
]

const worklogGroupOptions = [
  { value: 'all' as const, label: '全部', icon: ListIcon },
  { value: 'completed' as const, label: '已填写', icon: CircleCheckFilled },
  { value: 'missing' as const, label: '未填写', icon: CircleCloseFilled },
  { value: 'insufficient' as const, label: '填写不足', icon: WarningFilled },
  { value: 'unresolved' as const, label: '待确认', icon: QuestionFilled }
]

const worklogGroupForStatus = (status: string): Exclude<WorklogGroup, 'all'> => {
  if (status === '已填写' || status === '已豁免') return 'completed'
  if (status === '未填写' || status === '预警未填') return 'missing'
  if (status === '填写不足' || status === '预警不足') return 'insufficient'
  return 'unresolved'
}

const historicalWorklogs = computed(() => {
  const today = localDate()
  return dashboard.value.worklogs.filter(item => item.workDate < today)
})

const worklogCounts = computed<Record<WorklogGroup, number>>(() => {
  const counts: Record<WorklogGroup, number> = {
    all: historicalWorklogs.value.length,
    completed: 0,
    missing: 0,
    insufficient: 0,
    unresolved: 0
  }
  historicalWorklogs.value.forEach(item => {
    counts[worklogGroupForStatus(item.status)] += 1
  })
  return counts
})

const filteredWorklogs = computed(() => {
  if (worklogGroup.value === 'all') return historicalWorklogs.value
  return historicalWorklogs.value.filter(
    item => worklogGroupForStatus(item.status) === worklogGroup.value
  )
})

const worklogReport = computed(() => {
  const rows = historicalWorklogs.value
  const totalExpected = rows.reduce((sum, item) => sum + Number(item.expectedHours || 0), 0)
  const totalActual = rows.reduce((sum, item) => sum + Number(item.actualHours || 0), 0)
  const filledRows = rows.filter(item => worklogGroupForStatus(item.status) === 'completed').length
  const people = new Map<number, {
    userId: number
    realName: string
    expectedHours: number
    actualHours: number
    filled: number
    total: number
  }>()
  rows.forEach(item => {
    const current = people.get(item.userId) || {
      userId: item.userId,
      realName: item.realName,
      expectedHours: 0,
      actualHours: 0,
      filled: 0,
      total: 0
    }
    current.expectedHours += Number(item.expectedHours || 0)
    current.actualHours += Number(item.actualHours || 0)
    current.filled += worklogGroupForStatus(item.status) === 'completed' ? 1 : 0
    current.total += 1
    people.set(item.userId, current)
  })
  const memberRows = Array.from(people.values())
    .map(item => ({ ...item, rate: item.total ? Math.round(item.filled / item.total * 100) : 0 }))
    .sort((left, right) => left.rate - right.rate || right.actualHours - left.actualHours)
  const statusRows = worklogGroupOptions.map(option => ({
    ...option,
    count: worklogCounts.value[option.value],
    rate: rows.length ? Math.round(worklogCounts.value[option.value] / rows.length * 100) : 0
  }))
  return {
    totalExpected,
    totalActual,
    filledRows,
    completionRate: rows.length ? Math.round(filledRows / rows.length * 100) : 0,
    memberCount: people.size,
    memberRows,
    statusRows
  }
})

const worklogRowKey = (row: WorklogItem) => `${row.userId}-${row.workDate}`

const handleTabChange = (name: string | number) => {
  if (name === 'integration') loadMappings()
}

const mappedProjectIds = computed(() => new Set(projectMappings.value.map(item => item.projectId)))
const mappedUserIds = computed(() => new Set(userMappings.value.map(item => item.userId)))
const mappedYunxiaoUserIds = computed(() =>
  new Set(userMappings.value.map(item => item.yunxiaoUserId))
)
const selectableYunxiaoProjects = computed(() => {
  const options = [...yunxiaoProjects.value]
  const currentId = projectMappingForm.yunxiaoProjectId
  if (currentId && !options.some(project => project.id === currentId)) {
    options.unshift({
      id: currentId,
      name: '当前映射（云效项目列表中已不可用）',
      status: 'UNAVAILABLE'
    })
  }
  return options
})
const selectableYunxiaoMembers = computed(() => {
  const options = [...yunxiaoMembers.value]
  const currentId = userMappingForm.yunxiaoUserId
  if (currentId && !options.some(member => member.userId === currentId)) {
    options.unshift({
      userId: currentId,
      name: '当前映射（云效成员列表中已不可用）',
      status: 'UNAVAILABLE'
    })
  }
  return options
})

const normalizeRecords = <T>(payload: unknown): T[] => {
  if (Array.isArray(payload)) return payload as T[]
  if (!payload || typeof payload !== 'object') return []
  const envelope = payload as RecordEnvelope<T>
  if (Array.isArray(envelope.records)) return envelope.records
  if (Array.isArray(envelope.data)) return envelope.data
  if (envelope.data && Array.isArray(envelope.data.records)) return envelope.data.records
  return []
}

const errorMessage = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

const loadDashboard = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const data = await api.getBuDashboard<Dashboard>({
      startDate: period.value[0],
      endDate: period.value[1],
      planWindowWorkdays: planWindow.value
    })
    dashboard.value = data
    yunxiaoAnalysis.value = await api.getYunxiaoAnalysis().catch(() => yunxiaoAnalysis.value)
    configForm.enabled = data.integration.enabled
    configForm.edition = data.integration.edition === 'region' ? 'region' : 'center'
    configForm.baseUrl = data.integration.baseUrl || 'https://openapi-rdc.aliyuncs.com'
    configForm.organizationId = data.integration.organizationId || ''
    configForm.token = ''
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '驾驶舱数据加载失败')
  } finally {
    loading.value = false
  }
}

const loadBaseData = async () => {
  try {
    const [projectPayload, userPayload] = await Promise.all([
      api.getProjects({ page: 1, size: 500 }),
      api.getUsers({ page: 1, size: 500, status: 1 })
    ])
    projects.value = normalizeRecords<ProjectOption>(projectPayload)
    users.value = normalizeRecords<UserOption>(userPayload)
      .filter(item => item.username !== 'admin')
  } catch {
    projects.value = []
    users.value = []
  }
}

const loadMappings = async () => {
  try {
    const [projectData, userData] = await Promise.all([
      api.getYunxiaoProjectMappings(),
      api.getYunxiaoUserMappings()
    ])
    projectMappings.value = projectData || []
    userMappings.value = userData || []
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '云效映射加载失败'))
  }
}

const loadYunxiaoProjects = async (showMessage = false) => {
  yunxiaoProjectsLoading.value = true
  yunxiaoProjectsError.value = ''
  try {
    yunxiaoProjects.value = await api.getYunxiaoProjects()
    if (showMessage) ElMessage.success(`已加载 ${yunxiaoProjects.value.length} 个云效项目`)
  } catch (error: unknown) {
    yunxiaoProjectsError.value = errorMessage(error, '云效项目加载失败')
    if (showMessage) ElMessage.error(yunxiaoProjectsError.value)
  } finally {
    yunxiaoProjectsLoading.value = false
  }
}

const loadYunxiaoMembers = async (showMessage = false) => {
  yunxiaoMembersLoading.value = true
  yunxiaoMembersError.value = ''
  try {
    yunxiaoMembers.value = await api.getYunxiaoMembers()
    if (showMessage) ElMessage.success(`已加载 ${yunxiaoMembers.value.length} 名云效成员`)
  } catch (error: unknown) {
    yunxiaoMembersError.value = errorMessage(error, '云效成员加载失败')
    if (showMessage) ElMessage.error(yunxiaoMembersError.value)
  } finally {
    yunxiaoMembersLoading.value = false
  }
}

const refresh = async () => {
  await Promise.all([loadDashboard(), loadBaseData()])
  if (activeTab.value === 'integration') await loadMappings()
}

const resetDirectionForm = () => {
  editingDirectionId.value = null
  directionForm.code = ''
  directionForm.name = ''
  directionForm.objective = ''
  directionForm.ownerId = undefined
  directionForm.dateRange = []
  directionForm.status = '未开始'
  directionForm.sortOrder = 0
  directionForm.projectIds = []
  directionForm.milestones = []
}

const openDirectionDialog = (direction?: Direction) => {
  resetDirectionForm()
  if (direction) {
    editingDirectionId.value = direction.id
    directionForm.code = direction.code
    directionForm.name = direction.name
    directionForm.objective = direction.objective || ''
    directionForm.ownerId = direction.ownerId
    directionForm.dateRange = [direction.startDate, direction.endDate]
    directionForm.status = direction.status
    directionForm.sortOrder = direction.sortOrder || 0
    directionForm.projectIds = [...direction.projectIds]
    directionForm.milestones = direction.milestones.map(item => ({
      id: item.id,
      name: item.name,
      dueDate: item.dueDate,
      status: item.status,
      sortOrder: item.sortOrder || 0
    }))
  }
  directionDialogVisible.value = true
}

const addMilestone = () => {
  directionForm.milestones.push({
    name: '',
    dueDate: '',
    status: '未开始',
    sortOrder: directionForm.milestones.length
  })
}

const saveDirection = async () => {
  if (!directionForm.code.trim() || !directionForm.name.trim() || directionForm.dateRange.length !== 2) {
    ElMessage.warning('请填写方向编码、名称和起止日期')
    return
  }
  if (directionForm.milestones.some(item => !item.name.trim() || !item.dueDate)) {
    ElMessage.warning('请补全里程碑名称和计划日期')
    return
  }
  saving.value = true
  const payload = {
    code: directionForm.code.trim(),
    name: directionForm.name.trim(),
    objective: directionForm.objective,
    ownerId: directionForm.ownerId,
    startDate: directionForm.dateRange[0],
    endDate: directionForm.dateRange[1],
    status: directionForm.status,
    sortOrder: directionForm.sortOrder,
    projectIds: directionForm.projectIds,
    milestones: directionForm.milestones
  }
  try {
    if (editingDirectionId.value) {
      await api.updateBuDirection(editingDirectionId.value, payload)
    } else {
      await api.createBuDirection(payload)
    }
    ElMessage.success(editingDirectionId.value ? '方向已更新' : '方向已创建')
    directionDialogVisible.value = false
    await loadDashboard()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '方向保存失败'))
  } finally {
    saving.value = false
  }
}

const deleteDirection = async (direction: Direction) => {
  try {
    await ElMessageBox.confirm(
      `删除后将同时移除“${direction.name}”的项目关联和里程碑，是否继续？`,
      '删除方向',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await api.deleteBuDirection(direction.id)
    ElMessage.success('方向已删除')
    await loadDashboard()
  } catch (error: unknown) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorMessage(error, '删除失败'))
    }
  }
}

const openProjectMappingDialog = (mapping?: YunxiaoProjectMapping) => {
  projectMappingForm.projectId = mapping?.projectId
  projectMappingForm.yunxiaoProjectId = mapping?.yunxiaoProjectId || ''
  projectMappingForm.workitemTypeId = mapping?.workitemTypeId || ''
  projectMappingForm.category = mapping?.category || 'Req'
  projectMappingForm.syncEnabled = mapping?.syncEnabled ?? 1
  projectMappingDialogVisible.value = true
  void loadYunxiaoProjects()
}

const saveProjectMapping = async () => {
  if (!projectMappingForm.projectId || !projectMappingForm.yunxiaoProjectId.trim()) {
    ElMessage.warning('请选择本地项目和云效项目')
    return
  }
  saving.value = true
  try {
    await api.saveYunxiaoProjectMapping(projectMappingForm)
    projectMappingDialogVisible.value = false
    ElMessage.success('项目映射已保存')
    await Promise.all([loadMappings(), loadDashboard()])
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '项目映射保存失败'))
  } finally {
    saving.value = false
  }
}

const openUserMappingDialog = (mapping?: YunxiaoUserMapping) => {
  userMappingForm.userId = mapping?.userId
  userMappingForm.yunxiaoUserId = mapping?.yunxiaoUserId || ''
  userMappingForm.syncEnabled = mapping?.syncEnabled ?? 1
  userMappingDialogVisible.value = true
  void loadYunxiaoMembers()
}

const saveUserMapping = async () => {
  if (!userMappingForm.userId || !userMappingForm.yunxiaoUserId.trim()) {
    ElMessage.warning('请选择本地人员和云效人员')
    return
  }
  saving.value = true
  try {
    await api.saveYunxiaoUserMapping(userMappingForm)
    userMappingDialogVisible.value = false
    ElMessage.success('人员映射已保存')
    await Promise.all([loadMappings(), loadDashboard()])
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '人员映射保存失败'))
  } finally {
    saving.value = false
  }
}

const syncYunxiao = async () => {
  syncing.value = true
  try {
    const results = await api.syncYunxiao()
    const failed = results.filter(item => item.includes(':FAILED:')).length
    failed > 0
      ? ElMessage.warning(`同步完成，${failed} 个项目失败`)
      : ElMessage.success('云效数据同步完成')
    await Promise.all([loadMappings(), loadDashboard()])
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '云效同步失败'))
  } finally {
    syncing.value = false
  }
}

const validateYunxiaoConfig = () => {
  if (!configForm.baseUrl.trim()) {
    ElMessage.warning('请填写云效服务地址')
    return false
  }
  if (configForm.edition === 'center' && configForm.enabled && !configForm.organizationId?.trim()) {
    ElMessage.warning('中心化版本启用前需要填写组织ID')
    return false
  }
  if (configForm.enabled && !dashboard.value.integration.tokenConfigured && !configForm.token?.trim()) {
    ElMessage.warning('启用云效集成前需要填写个人访问令牌')
    return false
  }
  return true
}

const persistYunxiaoConfig = async (showSuccess: boolean) => {
  if (!validateYunxiaoConfig()) return false
  configSaving.value = true
  try {
    dashboard.value.integration = await api.updateYunxiaoConfig<Dashboard['integration']>({
      enabled: configForm.enabled,
      edition: configForm.edition,
      baseUrl: configForm.baseUrl.trim(),
      organizationId: configForm.organizationId?.trim(),
      token: configForm.token?.trim() || undefined
    })
    configForm.token = ''
    if (showSuccess) ElMessage.success('云效配置已保存')
    return true
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '云效配置保存失败'))
    return false
  } finally {
    configSaving.value = false
  }
}

const saveYunxiaoConfig = () => persistYunxiaoConfig(true)

const testYunxiaoConnection = async () => {
  if (!validateYunxiaoConfig()) return
  connectionTesting.value = true
  try {
    const saved = await persistYunxiaoConfig(false)
    if (!saved) return
    const result = await api.testYunxiaoConnection()
    dashboard.value.integration.lastTestedAt = result.testedAt
    dashboard.value.integration.lastTestStatus = result.success ? 'SUCCESS' : 'FAILED'
    dashboard.value.integration.lastTestMessage = result.message
    result.success
      ? ElMessage.success(`连接成功${result.userName ? `：${result.userName}` : ''}`)
      : ElMessage.error(result.message || '云效连接测试失败')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error, '云效连接测试失败'))
  } finally {
    connectionTesting.value = false
  }
}

const projectName = (id: number) => projects.value.find(item => item.id === id)?.name || `项目 #${id}`
const userName = (id: number) => users.value.find(item => item.id === id)?.realName || `用户 #${id}`
const yunxiaoProjectLabel = (project: YunxiaoProjectOption) =>
  project.customCode ? `${project.name} · ${project.customCode}` : project.name
const yunxiaoMemberLabel = (member: YunxiaoMemberOption) =>
  member.email ? `${member.name} · ${member.email}` : member.name

const healthType = (value: string) => {
  if (value === '正常' || value === '已完成') return 'success'
  if (value === '有风险') return 'warning'
  if (value === '已延期') return 'danger'
  return 'info'
}

const loadType = (value: string) => {
  if (value === '可承接') return 'success'
  if (value === '合理') return 'info'
  if (value === '饱和') return 'warning'
  if (value === '超负荷') return 'danger'
  return ''
}

const worklogType = (value: string) => {
  if (value === '已填写' || value === '已豁免') return 'success'
  if (value.includes('预警') || value === '填写不足') return 'warning'
  if (value === '未填写' || value === '未映射') return 'danger'
  if (value === '数据未知') return 'info'
  return 'info'
}

const worklogIcon = (value: string) => {
  const group = worklogGroupForStatus(value)
  return worklogGroupOptions.find(item => item.value === group)?.icon || Clock
}

const formatHours = (value: number) => `${Number(value || 0).toFixed(1)}h`
const formatRate = (value: number) => `${Number(value || 0).toFixed(1)}%`
const formatDateTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 16) : ''

onMounted(refresh)
</script>

<template>
  <div class="dashboard-page" v-loading="loading">
    <div class="control-bar">
      <div class="period-control">
        <span class="control-label">统计周期</span>
        <el-date-picker
          v-model="period"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          @change="loadDashboard"
        />
        <span class="control-label">负荷窗口</span>
        <el-select v-model="planWindow" class="window-select" @change="loadDashboard">
          <el-option :value="5" label="未来 5 个工作日" />
          <el-option :value="10" label="未来 10 个工作日" />
          <el-option :value="20" label="未来 20 个工作日" />
        </el-select>
      </div>
      <el-button :icon="Refresh" title="刷新驾驶舱" @click="refresh">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      class="load-alert"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    />

    <section class="summary-grid" aria-label="BU整体状态">
      <div class="summary-card">
        <span class="summary-label">周期实际工时</span>
        <strong>{{ formatHours(worklogReport.totalActual) }}</strong>
        <span class="summary-note">应填 {{ formatHours(worklogReport.totalExpected) }}</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">推进中需求</span>
        <strong>{{ dashboard.summary.activeRequirementCount }}</strong>
        <span class="summary-note">{{ dashboard.summary.overdueTaskCount }} 个任务已逾期</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">人员饱和</span>
        <strong>{{ dashboard.summary.overloadedPeopleCount }}</strong>
        <span class="summary-note">计划负荷超过 100%</span>
      </div>
      <div class="summary-card">
        <span class="summary-label">工时异常</span>
        <strong>{{ dashboard.summary.missingWorklogPeopleCount }}</strong>
        <span class="summary-note">周期内存在终态缺填</span>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="dashboard-tabs" @tab-change="handleTabChange">
      <el-tab-pane v-if="false" label="方向总览" name="directions">
        <div class="section-toolbar">
          <div>
            <h2>重点方向与计划</h2>
            <p>进度由关联项目下的需求和任务完成情况自动汇总。</p>
          </div>
          <el-button type="primary" :icon="Plus" @click="openDirectionDialog()">新增方向</el-button>
        </div>

        <el-empty v-if="!dashboard.directions.length" description="尚未设置BU重点方向">
          <el-button type="primary" @click="openDirectionDialog()">创建第一个方向</el-button>
        </el-empty>

        <div v-else class="direction-list">
          <article v-for="direction in dashboard.directions" :key="direction.id" class="direction-item">
            <div class="direction-main">
              <div class="direction-heading">
                <span class="direction-code">{{ direction.code }}</span>
                <h3>{{ direction.name }}</h3>
                <el-tag :type="healthType(direction.health)" effect="light">{{ direction.health }}</el-tag>
              </div>
              <p class="direction-objective">{{ direction.objective || '暂未填写方向目标' }}</p>
              <div class="direction-meta">
                <span><el-icon><User /></el-icon>{{ direction.ownerName || '未指定负责人' }}</span>
                <span><el-icon><Calendar /></el-icon>{{ direction.startDate }} 至 {{ direction.endDate }}</span>
                <span><el-icon><Folder /></el-icon>{{ direction.projectNames.join('、') || '未关联项目' }}</span>
              </div>
            </div>
            <div class="direction-progress">
              <div class="progress-value">{{ formatRate(direction.progress) }}</div>
              <el-progress :percentage="Number(direction.progress)" :show-text="false" :stroke-width="8" />
              <div class="progress-note">
                {{ direction.completedRequirementCount }}/{{ direction.requirementCount }} 需求，
                {{ direction.completedTaskCount }}/{{ direction.taskCount }} 任务
              </div>
            </div>
            <div class="direction-actions">
              <el-button circle :icon="Edit" title="编辑方向" @click="openDirectionDialog(direction)" />
              <el-button circle :icon="DeleteIcon" title="删除方向" @click="deleteDirection(direction)" />
            </div>
            <div v-if="direction.milestones.length" class="milestone-row">
              <div
                v-for="milestone in direction.milestones"
                :key="milestone.id || milestone.name"
                class="milestone"
                :class="{ overdue: milestone.overdue, done: milestone.status === '已完成' }"
              >
                <span class="milestone-dot"></span>
                <div>
                  <strong>{{ milestone.name }}</strong>
                  <span>{{ milestone.dueDate }} · {{ milestone.status }}</span>
                </div>
              </div>
            </div>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane label="人员负荷" name="capacity">
        <div class="section-toolbar">
          <div>
            <h2>人员工作与饱和度</h2>
            <p>实际投入率用于回看周期投入；未来计划负荷只用于资源安排，不作为绩效评价。</p>
          </div>
        </div>
        <el-table :data="dashboard.capacity" row-key="userId" class="data-table">
          <el-table-column label="人员" min-width="150" fixed>
            <template #default="{ row }">
              <div class="person-cell">
                <span class="person-avatar">{{ row.realName.charAt(0) }}</span>
                <div><strong>{{ row.realName }}</strong><span>{{ getRoleLabel(row.role) }}</span></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="当前工作" min-width="260">
            <template #default="{ row }">
              <div v-if="row.activeWork.length" class="work-list">
                <span v-for="work in row.activeWork" :key="work">{{ work }}</span>
              </div>
              <span v-else class="muted">暂无进行中工作</span>
            </template>
          </el-table-column>
          <el-table-column label="任务/逾期" width="110" align="center">
            <template #default="{ row }">
              <strong>{{ row.activeTaskCount }}</strong>
              <span :class="{ 'danger-text': row.overdueTaskCount > 0 }"> / {{ row.overdueTaskCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="周期实际投入" min-width="150">
            <template #default="{ row }">
              <strong>{{ formatHours(row.actualHours) }}</strong>
              <span class="metric-sub"> / {{ formatHours(row.expectedHours) }}</span>
              <el-progress
                :percentage="Math.min(100, Number(row.actualEffortRate))"
                :show-text="false"
                :stroke-width="5"
              />
              <span class="metric-sub">{{ formatRate(row.actualEffortRate) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="`未来 ${dashboard.planWindowWorkdays} 日负荷`" min-width="170">
            <template #default="{ row }">
              <div class="load-metric">
                <strong>{{ formatHours(row.plannedHours) }}</strong>
                <el-tag :type="loadType(row.loadStatus)" effect="light">{{ row.loadStatus }}</el-tag>
              </div>
              <el-progress
                :percentage="Math.min(100, Number(row.plannedLoadRate))"
                :show-text="false"
                :stroke-width="5"
                :status="row.plannedLoadRate > 120 ? 'exception' : undefined"
              />
              <span class="metric-sub">{{ formatRate(row.plannedLoadRate) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="数据完整性" min-width="190">
            <template #default="{ row }">
              <span :class="{ 'warning-text': !row.yunxiaoMapped }">{{ row.dataCompleteness }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="工时检查" name="worklogs">
        <div class="section-toolbar worklog-toolbar">
          <div>
            <h2>每日工时填写情况</h2>
            <p>仅展示前一天及更早记录；当天工时将在次日进入检查。</p>
          </div>
          <div class="worklog-quick-filter" role="group" aria-label="工时状态快速筛选">
            <button
              v-for="option in worklogGroupOptions"
              :key="option.value"
              type="button"
              class="worklog-filter-button"
              :class="[`is-${option.value}`, { active: worklogGroup === option.value }]"
              :aria-pressed="worklogGroup === option.value"
              :aria-label="`查看${option.label} ${worklogCounts[option.value]} 条记录`"
              @click="worklogGroup = option.value"
            >
              <el-icon><component :is="option.icon" /></el-icon>
              <span>{{ option.label }}</span>
              <strong>{{ worklogCounts[option.value] }}</strong>
            </button>
          </div>
        </div>
        <section class="worklog-report-grid" aria-label="工时填写统计">
          <article class="worklog-report-card accent-blue">
            <span>填写完成率</span>
            <strong>{{ worklogReport.completionRate }}%</strong>
            <small>{{ worklogReport.filledRows }}/{{ worklogCounts.all }} 条记录已完成</small>
          </article>
          <article class="worklog-report-card accent-indigo">
            <span>团队实际工时</span>
            <strong>{{ formatHours(worklogReport.totalActual) }}</strong>
            <small>应填 {{ formatHours(worklogReport.totalExpected) }}</small>
          </article>
          <article class="worklog-report-card accent-amber">
            <span>涉及成员</span>
            <strong>{{ worklogReport.memberCount }}</strong>
            <small>按成员查看填写分布</small>
          </article>
          <article class="worklog-report-card accent-red">
            <span>待跟进记录</span>
            <strong>{{ worklogCounts.missing + worklogCounts.insufficient + worklogCounts.unresolved }}</strong>
            <small>未填、不足或待确认</small>
          </article>
        </section>
        <section class="worklog-report-panels" aria-label="工时填写分布报表">
          <article class="worklog-distribution-panel">
            <header><div><h3>填写状态分布</h3><p>快速识别团队填写集中在哪些状态</p></div><span>{{ worklogCounts.all }} 条记录</span></header>
            <div class="worklog-distribution-list">
              <div v-for="item in worklogReport.statusRows" :key="item.value" class="worklog-distribution-row">
                <div class="distribution-label"><span>{{ item.label }}</span><strong>{{ item.count }}</strong></div>
                <div class="distribution-track"><i :class="`tone-${item.value}`" :style="{ width: `${item.rate}%` }" /></div>
                <small>{{ item.rate }}%</small>
              </div>
            </div>
          </article>
          <article class="worklog-distribution-panel">
            <header><div><h3>成员填写分布</h3><p>按完成率升序排列，优先显示需要关注的成员</p></div><span>{{ worklogReport.memberCount }} 人</span></header>
            <div class="member-distribution-list">
              <div v-for="item in worklogReport.memberRows.slice(0, 6)" :key="item.userId" class="member-distribution-row">
                <span class="person-avatar">{{ item.realName.charAt(0) }}</span>
                <div class="member-distribution-main"><strong>{{ item.realName }}</strong><small>{{ formatHours(item.actualHours) }} / {{ formatHours(item.expectedHours) }}</small></div>
                <el-progress :percentage="item.rate" :show-text="false" :stroke-width="6" />
                <strong class="member-rate">{{ item.rate }}%</strong>
              </div>
              <el-empty v-if="!worklogReport.memberRows.length" description="暂无成员工时记录" :image-size="48" />
            </div>
          </article>
        </section>
        <el-table :data="filteredWorklogs" :row-key="worklogRowKey" class="data-table">
          <el-table-column prop="workDate" label="日期" width="120" fixed />
          <el-table-column label="人员" min-width="150">
            <template #default="{ row }">
              <strong>{{ row.realName }}</strong>
              <span class="role-inline">{{ getRoleLabel(row.role) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已填 / 应填" width="150">
            <template #default="{ row }">
              <strong>{{ formatHours(row.actualHours) }}</strong>
              <span class="metric-sub"> / {{ formatHours(row.expectedHours) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="检查状态" width="130">
            <template #default="{ row }">
              <el-tag
                :type="worklogType(row.status)"
                effect="light"
                class="worklog-status-tag"
                :aria-label="`${row.realName}工时状态：${row.status}`"
              >
                <el-icon><component :is="worklogIcon(row.status)" /></el-icon>
                <span>{{ row.status }}</span>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="口径" min-width="160">
            <template #default="{ row }">
              <span class="muted">{{ row.finalResult ? '最终结果' : '当日动态结果' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="云效分析" name="yunxiao-analysis">
        <div class="section-toolbar"><div><h2>云效需求与任务分析</h2><p>基于已同步的云效工作项缓存，识别延期、人员分布和执行负荷。</p></div><el-tag type="info" effect="light">{{ yunxiaoAnalysis.total }} 个工作项</el-tag></div>
        <section class="worklog-report-grid" aria-label="云效分析概览"><article class="worklog-report-card accent-indigo"><span>云效需求</span><strong>{{ yunxiaoAnalysis.requirements }}</strong><small>已同步需求工作项</small></article><article class="worklog-report-card accent-blue"><span>云效任务</span><strong>{{ yunxiaoAnalysis.tasks }}</strong><small>已同步任务及缺陷</small></article><article class="worklog-report-card accent-red"><span>可能延期</span><strong>{{ yunxiaoAnalysis.delayed }}</strong><small>计划完成日期已过</small></article><article class="worklog-report-card accent-amber"><span>涉及成员</span><strong>{{ yunxiaoAnalysis.byOwner?.length || 0 }}</strong><small>按负责人查看分布</small></article></section>
        <el-table :data="yunxiaoAnalysis.byOwner" class="data-table" empty-text="暂无同步数据"><el-table-column prop="name" label="负责人" min-width="160" /><el-table-column prop="count" label="工作项数" width="120" /><el-table-column prop="delayed" label="延期数" width="120"><template #default="{ row }"><span :class="{ 'danger-text': row.delayed > 0 }">{{ row.delayed }}</span></template></el-table-column><el-table-column label="实际工时" width="140"><template #default="{ row }">{{ Number(row.actualHours || 0).toFixed(1) }}h</template></el-table-column><el-table-column label="分布"><template #default="{ row }"><el-progress :percentage="yunxiaoAnalysis.total ? Math.round(row.count / yunxiaoAnalysis.total * 100) : 0" :stroke-width="8" /></template></el-table-column></el-table>
      </el-tab-pane>

      <el-tab-pane label="云效配置" name="integration">
        <div
          class="integration-band"
          :class="{
            ready: dashboard.integration.configured,
            inactive: !dashboard.integration.enabled
          }"
        >
          <div class="integration-status-icon">
            <el-icon><CircleCheck v-if="dashboard.integration.configured" /><Warning v-else /></el-icon>
          </div>
          <div>
            <strong>
              {{ dashboard.integration.configured
                ? '云效连接已配置'
                : dashboard.integration.enabled ? '云效连接待补充' : '云效集成已停用' }}
            </strong>
            <p>
              {{ dashboard.integration.configured
                ? `已映射 ${dashboard.integration.mappedProjects} 个项目、${dashboard.integration.mappedUsers} 名人员`
                : '在下方维护连接参数，启用并测试成功后即可同步数据。' }}
            </p>
          </div>
          <el-button
            type="primary"
            :icon="Refresh"
            :loading="syncing"
            :disabled="!dashboard.integration.configured"
            @click="syncYunxiao"
          >
            立即同步
          </el-button>
        </div>

        <section class="connection-section" aria-labelledby="yunxiao-connection-title">
          <div class="section-toolbar compact connection-heading">
            <div>
              <h2 id="yunxiao-connection-title">连接参数</h2>
              <p>令牌加密保存，页面不会回显原文。</p>
            </div>
            <el-tag
              v-if="dashboard.integration.lastTestStatus"
              :type="dashboard.integration.lastTestStatus === 'SUCCESS' ? 'success' : 'danger'"
              effect="light"
            >
              {{ dashboard.integration.lastTestStatus === 'SUCCESS' ? '连接测试通过' : dashboard.integration.lastTestStatus === 'CONFIG_ERROR' ? '配置需要恢复' : '连接测试失败' }}
            </el-tag>
          </div>

          <el-alert
            v-if="dashboard.integration.tokenSource === 'UNREADABLE'"
            title="现有云效令牌无法解密"
            description="服务器解密密钥已缺失。请在下方重新输入个人访问令牌并保存，后台同步会在恢复前安全暂停。"
            type="error"
            :closable="false"
            show-icon
            class="connection-recovery-alert"
          />

          <el-form label-position="top" class="connection-form">
            <div class="connection-grid">
              <el-form-item label="集成状态">
                <el-switch
                  v-model="configForm.enabled"
                  inline-prompt
                  active-text="启用"
                  inactive-text="停用"
                />
              </el-form-item>
              <el-form-item label="云效版本">
                <el-segmented v-model="configForm.edition" :options="editionOptions" />
              </el-form-item>
              <el-form-item label="服务地址" class="connection-wide" required>
                <el-input
                  v-model="configForm.baseUrl"
                  placeholder="https://openapi-rdc.aliyuncs.com"
                />
              </el-form-item>
              <el-form-item
                v-if="configForm.edition === 'center'"
                label="组织ID"
                :required="configForm.enabled"
              >
                <el-input v-model="configForm.organizationId" placeholder="云效企业组织ID" />
              </el-form-item>
              <el-form-item label="个人访问令牌" :required="configForm.enabled && !dashboard.integration.tokenConfigured">
                <el-input
                  v-model="configForm.token"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :placeholder="dashboard.integration.tokenSource === 'UNREADABLE' ? '必须重新输入个人访问令牌' : dashboard.integration.tokenConfigured ? '已配置，留空保持不变' : '输入个人访问令牌'"
                />
                <span v-if="dashboard.integration.tokenConfigured" class="field-note">
                  {{ dashboard.integration.tokenSource === 'PAGE' ? '已由页面安全保存' : '当前使用服务器环境变量' }}
                </span>
              </el-form-item>
            </div>
          </el-form>

          <div
            v-if="dashboard.integration.lastTestedAt"
            class="connection-test-result"
            :class="{ failed: dashboard.integration.lastTestStatus === 'FAILED' }"
          >
            <span>{{ formatDateTime(dashboard.integration.lastTestedAt) }}</span>
            <strong>{{ dashboard.integration.lastTestMessage || '连接测试已完成' }}</strong>
          </div>

          <div class="connection-actions">
            <el-button
              :icon="Connection"
              :loading="connectionTesting"
              :disabled="configSaving"
              @click="testYunxiaoConnection"
            >
              测试连接
            </el-button>
            <el-button
              type="primary"
              :icon="Check"
              :loading="configSaving"
              :disabled="connectionTesting"
              @click="saveYunxiaoConfig"
            >
              保存配置
            </el-button>
          </div>
        </section>

        <div class="mapping-section">
          <div class="section-toolbar compact">
            <div>
              <h2>项目映射</h2>
              <p>工作项类型ID用于需求确认后的自动创建。</p>
            </div>
            <el-button :icon="Plus" @click="openProjectMappingDialog()">新增映射</el-button>
          </div>
          <el-table :data="projectMappings" class="data-table" empty-text="尚未配置项目映射">
            <el-table-column label="本地项目" min-width="180">
              <template #default="{ row }">{{ projectName(row.projectId) }}</template>
            </el-table-column>
            <el-table-column prop="yunxiaoProjectId" label="云效项目ID" min-width="220" />
            <el-table-column prop="workitemTypeId" label="需求工作项类型ID" min-width="220" />
            <el-table-column prop="lastSyncStatus" label="同步状态" width="110" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button circle :icon="Edit" title="编辑项目映射" @click="openProjectMappingDialog(row)" />
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="mapping-section">
          <div class="section-toolbar compact">
            <div>
              <h2>人员映射</h2>
              <p>人员映射决定工时归属和需求默认负责人。</p>
            </div>
            <el-button :icon="Plus" @click="openUserMappingDialog()">新增映射</el-button>
          </div>
          <el-table :data="userMappings" class="data-table" empty-text="尚未配置人员映射">
            <el-table-column label="本地人员" min-width="180">
              <template #default="{ row }">{{ userName(row.userId) }}</template>
            </el-table-column>
            <el-table-column prop="yunxiaoUserId" label="云效用户ID" min-width="260" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.syncEnabled === 1 ? 'success' : 'info'">
                  {{ row.syncEnabled === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button circle :icon="Edit" title="编辑人员映射" @click="openUserMappingDialog(row)" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="directionDialogVisible"
      :title="editingDirectionId ? '编辑重点方向' : '新增重点方向'"
      width="720px"
      destroy-on-close
    >
      <el-form label-position="top" class="direction-form">
        <div class="form-grid">
          <el-form-item label="方向编码" required>
            <el-input v-model="directionForm.code" placeholder="例如：2026-Q3-GROWTH" />
          </el-form-item>
          <el-form-item label="方向名称" required>
            <el-input v-model="directionForm.name" placeholder="面向管理层的清晰方向名称" />
          </el-form-item>
        </div>
        <el-form-item label="目标说明">
          <el-input v-model="directionForm.objective" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="负责人">
            <el-select v-model="directionForm.ownerId" filterable clearable placeholder="选择负责人">
              <el-option v-for="user in users" :key="user.id" :value="user.id" :label="user.realName" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="directionForm.status">
              <el-option v-for="status in ['未开始', '进行中', '已完成', '已暂停']" :key="status" :value="status" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="计划周期" required>
          <el-date-picker
            v-model="directionForm.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <el-form-item label="关联项目">
          <el-select v-model="directionForm.projectIds" multiple filterable collapse-tags placeholder="选择支撑该方向的项目">
            <el-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name" />
          </el-select>
        </el-form-item>
        <div class="milestone-editor-header">
          <span>里程碑</span>
          <el-button text type="primary" :icon="Plus" @click="addMilestone">添加里程碑</el-button>
        </div>
        <div v-for="(milestone, index) in directionForm.milestones" :key="index" class="milestone-editor-row">
          <el-input v-model="milestone.name" placeholder="里程碑名称" />
          <el-date-picker v-model="milestone.dueDate" value-format="YYYY-MM-DD" placeholder="计划日期" />
          <el-select v-model="milestone.status">
            <el-option v-for="status in ['未开始', '进行中', '已完成']" :key="status" :value="status" />
          </el-select>
          <el-button circle :icon="DeleteIcon" title="移除里程碑" @click="directionForm.milestones.splice(index, 1)" />
        </div>
      </el-form>
      <template #footer>
        <el-button @click="directionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDirection">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="projectMappingDialogVisible"
      title="项目映射"
      width="min(520px, calc(100vw - 32px))"
    >
      <el-form label-position="top" class="mapping-form">
        <el-form-item label="本地项目" required>
          <el-select v-model="projectMappingForm.projectId" filterable>
            <el-option
              v-for="project in projects"
              :key="project.id"
              :value="project.id"
              :label="project.name"
              :disabled="mappedProjectIds.has(project.id) && project.id !== projectMappingForm.projectId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="云效项目" required>
          <div class="mapping-select-row">
            <el-select
              v-model="projectMappingForm.yunxiaoProjectId"
              filterable
              :loading="yunxiaoProjectsLoading"
              placeholder="搜索云效项目名称或编码"
              no-data-text="未获取到云效项目"
              popper-class="yunxiao-project-popper"
            >
              <el-option
                v-for="project in selectableYunxiaoProjects"
                :key="project.id"
                :value="project.id"
                :label="yunxiaoProjectLabel(project)"
              >
                <div class="yunxiao-project-option">
                  <span>{{ project.name }}</span>
                  <small>{{ project.customCode || project.id }}</small>
                </div>
              </el-option>
            </el-select>
            <el-button
              circle
              :icon="Refresh"
              :loading="yunxiaoProjectsLoading"
              title="刷新云效项目"
              aria-label="刷新云效项目"
              @click="loadYunxiaoProjects(true)"
            />
          </div>
          <span v-if="yunxiaoProjectsError" class="field-error">{{ yunxiaoProjectsError }}</span>
          <span v-else class="field-note">系统保存云效项目稳定 ID，无需手工录入。</span>
        </el-form-item>
        <el-form-item label="需求工作项类型ID">
          <el-input v-model="projectMappingForm.workitemTypeId" placeholder="自动创建需求时必填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectMappingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProjectMapping">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="userMappingDialogVisible"
      title="人员映射"
      width="min(520px, calc(100vw - 32px))"
    >
      <el-form label-position="top" class="mapping-form">
        <el-form-item label="本地人员" required>
          <el-select v-model="userMappingForm.userId" filterable>
            <el-option
              v-for="user in users"
              :key="user.id"
              :value="user.id"
              :label="`${user.realName} · ${getRoleLabel(user.role)}`"
              :disabled="mappedUserIds.has(user.id) && user.id !== userMappingForm.userId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="云效人员" required>
          <div class="mapping-select-row">
            <el-select
              v-model="userMappingForm.yunxiaoUserId"
              filterable
              :loading="yunxiaoMembersLoading"
              placeholder="搜索云效人员姓名或邮箱"
              no-data-text="未获取到云效成员"
              popper-class="yunxiao-project-popper"
            >
              <el-option
                v-for="member in selectableYunxiaoMembers"
                :key="member.userId"
                :value="member.userId"
                :label="yunxiaoMemberLabel(member)"
                :disabled="mappedYunxiaoUserIds.has(member.userId) && member.userId !== userMappingForm.yunxiaoUserId"
              >
                <div class="yunxiao-project-option">
                  <span>{{ member.name }}</span>
                  <small>{{ member.email || member.userId }}</small>
                </div>
              </el-option>
            </el-select>
            <el-button
              circle
              :icon="Refresh"
              :loading="yunxiaoMembersLoading"
              title="刷新云效成员"
              aria-label="刷新云效成员"
              @click="loadYunxiaoMembers(true)"
            />
          </div>
          <span v-if="yunxiaoMembersError" class="field-error">{{ yunxiaoMembersError }}</span>
          <span v-else class="field-note">系统保存云效 userId，用于工时归属和需求负责人。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userMappingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveUserMapping">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dashboard-page {
  width: 100%;
  min-width: 0;
  min-height: 100%;
}

.control-bar,
.section-toolbar,
.integration-band,
.direction-heading,
.direction-meta,
.direction-actions,
.load-metric,
.milestone-editor-header {
  display: flex;
  align-items: center;
}

.control-bar {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.period-control {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.control-label {
  color: var(--gray-500);
  font-size: 13px;
}

.window-select {
  width: 168px;
}

.load-alert {
  margin-bottom: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.summary-card {
  min-height: 108px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.summary-label,
.summary-note,
.metric-sub,
.muted,
.role-inline {
  color: var(--gray-500);
  font-size: 12px;
}

.summary-card strong {
  color: var(--gray-800);
  font-size: 28px;
  line-height: 1;
}

.dashboard-tabs {
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  padding: 0 20px 20px;
}

.section-toolbar {
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0 18px;
}

.section-toolbar.compact {
  padding-top: 0;
}

.section-toolbar h2 {
  margin: 0 0 3px;
  color: var(--gray-800);
  font-size: 16px;
  letter-spacing: 0;
}

.section-toolbar p,
.integration-band p {
  margin: 0;
  color: var(--gray-500);
  font-size: 12px;
}

.direction-list {
  display: grid;
  gap: 10px;
}

.direction-item {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) 220px auto;
  gap: 20px;
  padding: 16px;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
}

.direction-heading {
  gap: 9px;
}

.direction-heading h3 {
  margin: 0;
  color: var(--gray-800);
  font-size: 15px;
  letter-spacing: 0;
}

.direction-code {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-size: 11px;
  font-weight: 600;
}

.direction-objective {
  margin: 8px 0;
  color: var(--gray-600);
  font-size: 13px;
}

.direction-meta {
  flex-wrap: wrap;
  gap: 14px;
  color: var(--gray-500);
  font-size: 12px;
}

.direction-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.direction-progress {
  align-self: center;
}

.progress-value {
  margin-bottom: 6px;
  color: var(--gray-800);
  font-size: 20px;
  font-weight: 700;
}

.progress-note {
  margin-top: 5px;
  color: var(--gray-500);
  font-size: 11px;
}

.direction-actions {
  align-self: start;
  gap: 6px;
}

.milestone-row {
  grid-column: 1 / -1;
  display: flex;
  gap: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--gray-100);
  overflow-x: auto;
}

.milestone {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 150px;
}

.milestone-dot {
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--primary);
  flex-shrink: 0;
}

.milestone.overdue .milestone-dot {
  background: var(--danger);
}

.milestone.done .milestone-dot {
  background: var(--success);
}

.milestone strong,
.milestone span {
  display: block;
}

.milestone strong {
  color: var(--gray-700);
  font-size: 12px;
}

.milestone span {
  color: var(--gray-500);
  font-size: 11px;
}

.data-table {
  width: 100%;
}

.person-cell {
  display: flex;
  align-items: center;
  gap: 9px;
}

.person-avatar {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  background: var(--gray-800);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 12px;
}

.person-cell strong,
.person-cell span {
  display: block;
}

.person-cell strong {
  color: var(--gray-800);
  font-size: 13px;
}

.person-cell div span {
  color: var(--gray-500);
  font-size: 11px;
}

.work-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.work-list span {
  max-width: 180px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.load-metric {
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 5px;
}

.role-inline {
  margin-left: 8px;
}

.danger-text {
  color: var(--danger) !important;
}

.warning-text {
  color: var(--warning);
}

.worklog-toolbar {
  align-items: flex-end;
}

.worklog-quick-filter {
  max-width: 100%;
  display: flex;
  overflow-x: auto;
  border: 1px solid var(--gray-200);
  border-radius: 6px;
  background: #fff;
}

.worklog-filter-button {
  min-width: 94px;
  height: 38px;
  padding: 0 6px;
  border: 0;
  border-right: 1px solid var(--gray-200);
  background: transparent;
  color: var(--gray-600);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex: 0 0 auto;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}

.worklog-filter-button:last-child {
  border-right: 0;
}

.worklog-filter-button:hover,
.worklog-filter-button.active {
  background: var(--gray-100);
  color: var(--gray-800);
}

.worklog-filter-button:focus-visible {
  position: relative;
  z-index: 1;
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}

.worklog-filter-button strong {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--gray-100);
  color: var(--gray-700);
  display: inline-grid;
  place-items: center;
  font-size: 11px;
  line-height: 18px;
}

.worklog-filter-button.active strong {
  background: #fff;
}

.worklog-filter-button.is-completed .el-icon {
  color: var(--success);
}

.worklog-filter-button.is-missing .el-icon {
  color: var(--danger);
}

.worklog-filter-button.is-insufficient .el-icon {
  color: var(--warning);
}

.worklog-filter-button.is-unresolved .el-icon {
  color: var(--gray-500);
}

.worklog-status-tag :deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.worklog-report-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 14px;
}

.worklog-report-card,
.worklog-distribution-panel {
  border: 1px solid var(--gray-200);
  border-radius: 10px;
  background: #fff;
}

.worklog-report-card {
  position: relative;
  min-height: 104px;
  padding: 14px 16px 13px;
  overflow: hidden;
}

.worklog-report-card::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  content: '';
  background: #2563eb;
}

.worklog-report-card.accent-indigo::before { background: #6366f1; }
.worklog-report-card.accent-amber::before { background: #d97706; }
.worklog-report-card.accent-red::before { background: #dc2626; }

.worklog-report-card span,
.worklog-report-card small {
  display: block;
  color: var(--gray-500);
  font-size: 12px;
}

.worklog-report-card strong {
  display: block;
  margin: 9px 0 5px;
  color: var(--gray-800);
  font-size: 27px;
  line-height: 1;
}

.worklog-report-panels {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.worklog-distribution-panel {
  padding: 16px;
}

.worklog-distribution-panel > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.worklog-distribution-panel h3 {
  margin: 0;
  color: var(--gray-800);
  font-size: 15px;
}

.worklog-distribution-panel p {
  margin: 4px 0 0;
  color: var(--gray-500);
  font-size: 12px;
}

.worklog-distribution-panel > header > span {
  flex: 0 0 auto;
  color: var(--gray-500);
  font-size: 12px;
}

.worklog-distribution-row {
  display: grid;
  grid-template-columns: 92px minmax(80px, 1fr) 38px;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.distribution-label {
  display: flex;
  justify-content: space-between;
  gap: 5px;
  color: var(--gray-600);
  font-size: 12px;
}

.distribution-label strong,
.worklog-distribution-row > small {
  color: var(--gray-800);
  font-size: 12px;
}

.distribution-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--gray-100);
}

.distribution-track i {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: inherit;
  background: #2563eb;
}

.distribution-track i.tone-completed { background: #10b981; }
.distribution-track i.tone-missing { background: #ef4444; }
.distribution-track i.tone-insufficient { background: #f59e0b; }
.distribution-track i.tone-unresolved { background: #94a3b8; }

.member-distribution-row {
  display: grid;
  grid-template-columns: 30px minmax(100px, 1fr) minmax(80px, 1.2fr) 42px;
  align-items: center;
  gap: 9px;
  min-height: 39px;
  border-bottom: 1px solid var(--gray-100);
}

.member-distribution-row:last-child { border-bottom: 0; }

.member-distribution-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.member-distribution-main strong {
  overflow: hidden;
  color: var(--gray-700);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-distribution-main small,
.member-rate {
  color: var(--gray-500);
  font-size: 11px;
}

.member-rate {
  color: var(--gray-800);
  text-align: right;
}

.integration-band {
  gap: 12px;
  padding: 14px 16px;
  margin: 4px 0 22px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
}

.integration-band.ready {
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.integration-band.inactive {
  border-color: var(--gray-200);
  background: var(--gray-50);
}

.integration-band > div:nth-child(2) {
  flex: 1;
}

.integration-status-icon {
  color: var(--warning);
  font-size: 22px;
}

.integration-band.ready .integration-status-icon {
  color: var(--success);
}

.integration-band.inactive .integration-status-icon {
  color: var(--gray-400);
}

.connection-section {
  padding: 0 0 24px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--gray-200);
}

.connection-heading {
  align-items: flex-end;
}

.connection-form {
  max-width: 900px;
}

.connection-grid {
  display: grid;
  grid-template-columns: minmax(220px, 0.7fr) minmax(300px, 1.3fr);
  gap: 2px 20px;
}

.connection-wide {
  grid-column: 1 / -1;
}

.connection-form :deep(.el-input),
.connection-form :deep(.el-segmented) {
  width: 100%;
}

.field-note {
  display: block;
  margin-top: 5px;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 1.4;
}

.connection-test-result {
  display: flex;
  gap: 10px;
  align-items: center;
  width: fit-content;
  max-width: 100%;
  padding: 8px 10px;
  margin-top: 2px;
  border-left: 3px solid var(--success);
  background: #f0fdf4;
  color: var(--gray-700);
  font-size: 12px;
}

.connection-test-result.failed {
  border-left-color: var(--danger);
  background: #fef2f2;
}

.connection-test-result span {
  color: var(--gray-500);
  white-space: nowrap;
}

.connection-test-result strong {
  overflow-wrap: anywhere;
}

.connection-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  max-width: 900px;
  margin-top: 18px;
}

.mapping-section + .mapping-section {
  margin-top: 28px;
}

.mapping-form :deep(.el-select),
.mapping-select-row {
  width: 100%;
}

.mapping-select-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  gap: 8px;
  align-items: center;
}

.field-error {
  display: block;
  margin-top: 5px;
  color: var(--danger);
  font-size: 12px;
  line-height: 1.4;
}

:global(.yunxiao-project-option) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

:global(.yunxiao-project-popper) {
  z-index: 3000 !important;
  box-sizing: border-box;
  max-width: calc(100vw - 16px);
}

:global(.yunxiao-project-popper .el-select-dropdown),
:global(.yunxiao-project-popper .el-scrollbar),
:global(.yunxiao-project-popper .el-select-dropdown__wrap) {
  background: #fff;
}

:global(.yunxiao-project-option span) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.yunxiao-project-option small) {
  color: var(--gray-500);
  font-size: 11px;
  flex-shrink: 0;
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.direction-form :deep(.el-select),
.direction-form :deep(.el-date-editor),
.direction-form :deep(.el-input) {
  width: 100%;
}

.milestone-editor-header {
  justify-content: space-between;
  margin: 8px 0;
  color: var(--gray-700);
  font-size: 14px;
  font-weight: 600;
}

.milestone-editor-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 150px 110px 34px;
  gap: 8px;
  margin-bottom: 8px;
}

@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .worklog-report-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .direction-item {
    grid-template-columns: 1fr 180px auto;
  }
}

@media (max-width: 760px) {
  .control-bar,
  .section-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .period-control {
    width: 100%;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: stretch;
  }

  .period-control :deep(.el-date-editor),
  .window-select {
    width: 100%;
    min-width: 0;
    max-width: 100%;
  }

  .control-bar > :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .dashboard-tabs {
    max-width: 100%;
    padding-right: 12px;
    padding-left: 12px;
  }

  .worklog-quick-filter {
    width: 100%;
  }

  .summary-grid {
    grid-template-columns: 1fr 1fr;
  }

  .worklog-report-panels {
    grid-template-columns: 1fr;
  }

  .direction-item {
    grid-template-columns: 1fr auto;
  }

  .direction-progress {
    grid-column: 1 / -1;
    width: 100%;
  }

  .form-grid,
  .milestone-editor-row,
  .connection-grid {
    grid-template-columns: 1fr;
  }

  .connection-wide {
    grid-column: auto;
  }

  .connection-actions,
  .connection-test-result,
  .integration-band {
    align-items: stretch;
    flex-direction: column;
  }

  .connection-actions .el-button,
  .integration-band .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
