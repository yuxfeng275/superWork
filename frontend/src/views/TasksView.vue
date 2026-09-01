<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '@/utils/api'
import WorkItemAnalysisPanel from '@/components/WorkItemAnalysisPanel.vue'
import type { WorkItemAnalysis, WorkItemDistributionItem } from '@/types/work-item'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

type ViewMode = 'project' | 'person'

interface TaskOverviewItem {
  recordKey?: string
  dataSource?: 'LOCAL' | 'YUNXIAO'
  readOnly?: boolean
  id?: number
  yunxiaoWorkitemId?: string
  serialNumber?: string
  normalizedStatus?: string
  projectIds?: number[]
  projectNames?: string[]
  requirementId?: number
  requirementNo?: string
  requirementTitle?: string
  projectId?: number
  projectName?: string
  projectFullPath?: string
  assigneeId?: number
  assigneeName?: string
  assigneeUsername?: string
  title: string
  description?: string
  taskType?: string
  estimatedHours?: number
  actualHours?: number
  status?: string
  startDate?: string
  endDate?: string
  createdAt?: string
  updatedAt?: string
  dueDate?: string
  overdueIncomplete?: boolean
  overdueDays?: number
}

interface TaskSummary {
  totalCount: number
  pendingCount: number
  inProgressCount: number
  completedCount: number
  testedCount: number
  unassignedCount: number
  totalEstimatedHours: number
  totalActualHours: number
  statusCounts: Record<string, number>
}

interface TaskOverviewPayload {
  summary?: Partial<TaskSummary>
  analysis?: WorkItemAnalysis
  tasks?: TaskOverviewItem[]
}

interface ProjectTreeNode {
  id: number
  parentId: number | null
  name: string
  fullPath: string
  businessLineId?: number
  children?: ProjectTreeNode[]
}

interface BusinessLineOption {
  id: number
  name: string
  status?: string | number | null
}

interface CreateProjectCard {
  id: number
  name: string
  businessLineId?: number
  subProjects: Array<{ id: number; name: string }>
}

interface UserOption {
  id: number
  username?: string
  realName?: string
  role?: string
  status?: string | number | null
}

interface ProjectMemberOption {
  userId: number
  username?: string
  realName?: string
  role?: string
}

interface RequirementOption {
  id: number
  reqNo?: string
  title?: string
  projectId?: number
  projectName?: string
  projectFullPath?: string
}

interface CreateTaskForm {
  projectId?: number
  requirementId?: number
  title: string
  assigneeId?: number
  taskType: string
  estimatedHours?: number
  description: string
}

interface TaskGroup {
  key: string
  title: string
  subtitle: string
  tasks: TaskOverviewItem[]
  statusCounts: Record<string, number>
  estimatedHours: number
  actualHours: number
  assignees: string[]
  projects: string[]
}

const loading = ref(false)
const activeView = ref<ViewMode>('project')
const selectedStatus = ref('')
const selectedProjectId = ref<number | undefined>(undefined)
const selectedAssigneeId = ref<number | undefined>(undefined)
const keyword = ref('')
const tasks = ref<TaskOverviewItem[]>([])
const summary = ref<TaskSummary>({
  totalCount: 0,
  pendingCount: 0,
  inProgressCount: 0,
  completedCount: 0,
  testedCount: 0,
  unassignedCount: 0,
  totalEstimatedHours: 0,
  totalActualHours: 0,
  statusCounts: {}
})
const emptyAnalysis = (): WorkItemAnalysis => ({
  statusDistribution: [], projectDistribution: [], ownerDistribution: [], sourceDistribution: [],
  priorityDistribution: [], overdueProjectDistribution: [], overdueOwnerDistribution: [],
  overdueAgeDistribution: [], totalEstimatedHours: 0, totalActualHours: 0,
  completionRate: 0, unassignedCount: 0, overdueIncompleteCount: 0, missingDueDateCount: 0
})
const analysis = ref<WorkItemAnalysis>(emptyAnalysis())
const projectOptions = ref<ProjectTreeNode[]>([])
const businessLineOptions = ref<BusinessLineOption[]>([])
const userOptions = ref<UserOption[]>([])
const requirementOptions = ref<RequirementOption[]>([])
const projectMemberOptions = ref<ProjectMemberOption[]>([])
const createBusinessLineId = ref<number | undefined>(undefined)
const createDialogVisible = ref(false)
const creatingTask = ref(false)
const loadingProjectMembers = ref(false)
const taskDetailVisible = ref(false)
const taskDetailLoading = ref(false)
const selectedTask = ref<TaskOverviewItem | null>(null)
const updatingTaskStatusId = ref<number | undefined>(undefined)
const createTaskFormRef = ref<FormInstance>()
const createTaskForm = reactive<CreateTaskForm>({
  projectId: undefined,
  requirementId: undefined,
  title: '',
  assigneeId: undefined,
  taskType: '开发任务',
  estimatedHours: undefined,
  description: ''
})

const statusFilters = [
  { label: '全部', value: '' },
  { label: '待开始', value: '待开始' },
  { label: '进行中', value: '进行中' },
  { label: '已完成', value: '已完成' },
  { label: '已测试', value: '已测试' }
]

const viewOptions: Array<{ label: string; value: ViewMode }> = [
  { label: '按项目', value: 'project' },
  { label: '按人员', value: 'person' }
]

const taskTypeOptions = ['开发任务', '前端开发', '后端开发', '测试', 'UI设计', '运维配置', '其他']
const taskStatusOptions = ['待开始', '进行中', '已完成', '已测试']

const createTaskRules: FormRules<CreateTaskForm> = {
  projectId: [{ required: true, message: '请选择所属项目', trigger: 'change' }],
  requirementId: [{ required: true, message: '请选择关联需求', trigger: 'change' }],
  title: [{ required: true, message: '请输入任务标题', trigger: 'blur' }]
}

const isObjectRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null
}

const normalizePageRecords = <T>(payload: unknown): T[] => {
  if (Array.isArray(payload)) return payload
  if (!isObjectRecord(payload)) return []

  const records = payload.records
  if (Array.isArray(records)) return records as T[]

  const data = payload.data
  if (Array.isArray(data)) return data as T[]
  if (isObjectRecord(data) && Array.isArray(data.records)) return data.records as T[]

  return []
}

const flattenProjects = (nodes: ProjectTreeNode[]): ProjectTreeNode[] => {
  return nodes.flatMap(node => [node, ...flattenProjects(node.children ?? [])])
}

const findProjectOption = (projectId?: number) => {
  if (!projectId) return undefined
  return projectOptions.value.find(project => project.id === projectId)
}

const findRootProject = (projectId?: number) => {
  let project = findProjectOption(projectId)
  const visited = new Set<number>()

  while (project?.parentId != null && !visited.has(project.id)) {
    visited.add(project.id)
    const parent = findProjectOption(project.parentId)
    if (!parent) break
    project = parent
  }

  return project
}

const getProjectScopeIds = (projectId?: number) => {
  if (!projectId) return new Set<number>()

  const ids = new Set<number>([projectId])
  let changed = true

  while (changed) {
    changed = false
    projectOptions.value.forEach(project => {
      if (project.parentId != null && ids.has(project.parentId) && !ids.has(project.id)) {
        ids.add(project.id)
        changed = true
      }
    })
  }

  return ids
}

const displayPerson = (task: TaskOverviewItem) => {
  return task.assigneeName || task.assigneeUsername || '未分配'
}

const displayProject = (task: TaskOverviewItem) => {
  return task.projectFullPath || task.projectName || '未归属项目'
}

const displayRequirement = (requirement: RequirementOption) => {
  const title = requirement.title || `需求${requirement.id}`
  return requirement.reqNo ? `${requirement.reqNo} · ${title}` : title
}

const displayUser = (user: UserOption | { id?: number; username?: string; realName?: string }) => {
  return user.realName || user.username || `用户${user.id}`
}

const isEnabledUser = (user: UserOption) => {
  const status = String(user.status ?? '1').trim().toLowerCase()
  return !status || status === '1' || status === 'enabled' || status === 'active'
}

const toNumber = (value?: number) => {
  if (value == null) return 0
  return Number(value) || 0
}

const formatHours = (value?: number) => {
  const num = toNumber(value)
  return Number.isInteger(num) ? `${num}` : num.toFixed(1)
}

const formatDateTime = (value?: string) => {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

const statusCount = (status: string) => {
  return summary.value.statusCounts?.[status] ?? 0
}

const statusBadgeClass = (status?: string) => {
  if (status === '已完成') return 'green'
  if (status === '已测试') return 'blue'
  if (status === '进行中') return 'yellow'
  if (status === '待开始') return 'gray'
  return 'slate'
}

const getProgressPercent = (group: TaskGroup) => {
  if (group.tasks.length === 0) return 0
  const done = (group.statusCounts['已完成'] ?? 0) + (group.statusCounts['已测试'] ?? 0)
  return Math.round((done / group.tasks.length) * 100)
}

const buildGroup = (key: string, taskList: TaskOverviewItem[], mode: ViewMode): TaskGroup => {
  const first = taskList[0]
  const statusCounts = taskList.reduce<Record<string, number>>((acc, task) => {
    const status = task.status || '未设置'
    acc[status] = (acc[status] ?? 0) + 1
    return acc
  }, {})

  const projects = Array.from(new Set(taskList.map(displayProject))).filter(Boolean)
  const assignees = Array.from(new Set(taskList.map(displayPerson))).filter(Boolean)

  return {
    key,
    title: mode === 'project' ? displayProject(first) : displayPerson(first),
    subtitle: mode === 'project'
      ? `${assignees.length} 人参与`
      : `${projects.length} 个项目`,
    tasks: taskList,
    statusCounts,
    estimatedHours: taskList.reduce((sum, task) => sum + toNumber(task.estimatedHours), 0),
    actualHours: taskList.reduce((sum, task) => sum + toNumber(task.actualHours), 0),
    assignees,
    projects
  }
}

const groupTasks = (mode: ViewMode) => {
  const buckets = tasks.value.reduce<Record<string, TaskOverviewItem[]>>((acc, task) => {
    const key = mode === 'project'
      ? String(task.projectId ?? 'unassigned-project')
      : String(task.assigneeId ?? 'unassigned-person')
    if (!acc[key]) acc[key] = []
    acc[key].push(task)
    return acc
  }, {})

  return Object.entries(buckets)
    .map(([key, taskList]) => buildGroup(key, taskList, mode))
    .sort((left, right) => right.tasks.length - left.tasks.length || left.title.localeCompare(right.title, 'zh-CN'))
}

const analysisSections = computed(() => [
  { key: 'status' as const, title: '状态分布', rows: analysis.value.statusDistribution },
  { key: 'project' as const, title: '项目分布', rows: analysis.value.projectDistribution },
  { key: 'owner' as const, title: '负责人分布', rows: analysis.value.ownerDistribution },
  { key: 'source' as const, title: '数据来源', rows: analysis.value.sourceDistribution, interactive: false }
])
const effortExecutionRate = computed(() => {
  const estimated = Number(analysis.value.totalEstimatedHours || 0)
  if (!estimated) return 0
  return Math.round(Number(analysis.value.totalActualHours || 0) / estimated * 1000) / 10
})
const projectGroups = computed(() => groupTasks('project'))
const personGroups = computed(() => groupTasks('person'))
const visibleGroups = computed(() => activeView.value === 'project' ? projectGroups.value : personGroups.value)
const rootProjectOptions = computed(() => projectOptions.value.filter(project => project.parentId == null))
const createProjectCards = computed<CreateProjectCard[]>(() => {
  const childrenByParent = new Map<number, ProjectTreeNode[]>()

  projectOptions.value.forEach(project => {
    if (project.parentId == null) return
    const siblings = childrenByParent.get(project.parentId) ?? []
    siblings.push(project)
    childrenByParent.set(project.parentId, siblings)
  })

  return rootProjectOptions.value.map(project => ({
    id: project.id,
    name: project.name,
    businessLineId: project.businessLineId,
    subProjects: (childrenByParent.get(project.id) ?? []).map(subProject => ({
      id: subProject.id,
      name: subProject.name
    }))
  }))
})
const visibleCreateProjectCards = computed(() => {
  if (createBusinessLineId.value == null) return createProjectCards.value
  return createProjectCards.value.filter(project => project.businessLineId === createBusinessLineId.value)
})
const selectedCreateRootProjectId = computed(() => findRootProject(createTaskForm.projectId)?.id)
const createRequirementOptions = computed(() => {
  const projectIds = getProjectScopeIds(createTaskForm.projectId)
  if (projectIds.size === 0) return []
  return requirementOptions.value.filter(requirement => requirement.projectId != null && projectIds.has(requirement.projectId))
})
const getCurrentUserOption = (): UserOption | undefined => {
  try {
    const raw = localStorage.getItem('user')
    if (!raw) return undefined
    const parsed = JSON.parse(raw) as { id?: unknown; username?: string; realName?: string; role?: string }
    const id = Number(parsed.id)
    if (!Number.isFinite(id)) return undefined
    return {
      id,
      username: parsed.username,
      realName: parsed.realName,
      role: parsed.role,
      status: 1
    }
  } catch {
    return undefined
  }
}
const activeUserOptions = computed(() => {
  const users = userOptions.value.filter(isEnabledUser)
  const currentUser = getCurrentUserOption()
  if (currentUser && !users.some(user => user.id === currentUser.id)) {
    return [currentUser, ...users]
  }
  return users
})
const projectAssigneeOptions = computed<UserOption[]>(() => {
  const seen = new Set<number>()
  return projectMemberOptions.value
    .map(member => ({
      id: member.userId,
      username: member.username,
      realName: member.realName,
      role: member.role,
      status: 1
    }))
    .filter(user => {
      if (!user.id || seen.has(user.id)) return false
      seen.add(user.id)
      return true
    })
})
const createAssigneeOptions = computed(() => {
  return projectAssigneeOptions.value.length > 0 ? projectAssigneeOptions.value : activeUserOptions.value
})
const requirementEmptyText = computed(() => {
  return createTaskForm.projectId ? '当前项目暂无需求' : '请先选择项目'
})
const createProjectEmptyText = computed(() => {
  return createBusinessLineId.value == null ? '暂无可选项目' : '该业务线暂无项目'
})

const loadFilters = async () => {
  const [projectResult, businessLineResult, userResult, requirementResult] = await Promise.allSettled([
    api.getProjectTree(),
    api.getBusinessLines({ page: 1, size: 500, status: 1 }),
    api.getUsers({ page: 1, size: 500, status: 1 }),
    api.getRequirements({ page: 1, size: 500 })
  ])

  if (projectResult.status === 'fulfilled') {
    const tree = normalizePageRecords<ProjectTreeNode>(projectResult.value)
    projectOptions.value = flattenProjects(tree)
  } else {
    projectOptions.value = []
    ElMessage.error('项目列表加载失败')
  }

  if (businessLineResult.status === 'fulfilled') {
    businessLineOptions.value = normalizePageRecords<BusinessLineOption>(businessLineResult.value)
  } else {
    businessLineOptions.value = []
  }

  if (userResult.status === 'fulfilled') {
    userOptions.value = normalizePageRecords<UserOption>(userResult.value)
  } else {
    userOptions.value = []
    ElMessage.error('负责人列表加载失败')
  }

  if (requirementResult.status === 'fulfilled') {
    requirementOptions.value = normalizePageRecords<RequirementOption>(requirementResult.value)
  } else {
    requirementOptions.value = []
    ElMessage.error('需求列表加载失败')
  }
}

const loadOverview = async () => {
  loading.value = true
  try {
    const payload = await api.getTaskOverview({
      projectId: selectedProjectId.value,
      assigneeId: selectedAssigneeId.value,
      status: selectedStatus.value,
      keyword: keyword.value.trim()
    }) as TaskOverviewPayload
    tasks.value = Array.isArray(payload?.tasks) ? payload.tasks : []
    summary.value = {
      ...summary.value,
      ...(payload?.summary ?? {}),
      statusCounts: payload?.summary?.statusCounts ?? {}
    }
    analysis.value = payload?.analysis ?? emptyAnalysis()
  } catch (e) {
    ElMessage.error('任务概览加载失败')
    tasks.value = []
  } finally {
    loading.value = false
  }
}

const mergeTaskDetail = (base: TaskOverviewItem, detail: Partial<TaskOverviewItem>): TaskOverviewItem => ({
  ...base,
  ...detail,
  requirementNo: base.requirementNo ?? detail.requirementNo,
  requirementTitle: base.requirementTitle ?? detail.requirementTitle,
  projectId: base.projectId ?? detail.projectId,
  projectName: base.projectName ?? detail.projectName,
  projectFullPath: base.projectFullPath ?? detail.projectFullPath,
  assigneeName: base.assigneeName ?? detail.assigneeName,
  assigneeUsername: base.assigneeUsername ?? detail.assigneeUsername
})

const openTaskDetail = async (task: TaskOverviewItem) => {
  selectedTask.value = task
  taskDetailVisible.value = true
  if (task.readOnly || task.id == null) {
    taskDetailLoading.value = false
    return
  }
  taskDetailLoading.value = true

  try {
    const detail = await api.getTask(task.id) as Partial<TaskOverviewItem> | null
    if (detail) {
      selectedTask.value = mergeTaskDetail(task, detail)
    }
  } catch (e) {
    ElMessage.error('任务详情加载失败')
  } finally {
    taskDetailLoading.value = false
  }
}

const changeTaskStatus = async (task: TaskOverviewItem, status: string) => {
  if (task.readOnly || task.id == null || !status || task.status === status || updatingTaskStatusId.value) return

  updatingTaskStatusId.value = task.id
  try {
    const updatedTask = await api.updateTask(task.id, { status }) as Partial<TaskOverviewItem>
    task.status = status
    if (selectedTask.value?.id === task.id) {
      selectedTask.value = mergeTaskDetail(selectedTask.value, updatedTask ? { ...updatedTask, status } : { status })
    }
    await loadOverview()
    ElMessage.success('任务状态已更新')
  } catch (e) {
    ElMessage.error('任务状态更新失败')
  } finally {
    updatingTaskStatusId.value = undefined
  }
}

const handleTaskStatusChange = (task: TaskOverviewItem, event: Event) => {
  const value = (event.target as HTMLSelectElement).value
  void changeTaskStatus(task, value)
}

const activeMainTab = ref<'detail' | 'analysis'>('detail')

const handleAnalysisSelect = (section: string, item: WorkItemDistributionItem) => {
  activeMainTab.value = 'detail'
  if (section === 'status') selectedStatus.value = item.key
  if (section === 'project' && /^\d+$/.test(item.key)) {
    activeView.value = 'project'
    selectedProjectId.value = Number(item.key)
    selectedAssigneeId.value = undefined
  }
  if (section === 'owner') {
    if (/^\d+$/.test(item.key)) {
      activeView.value = 'person'
      selectedAssigneeId.value = Number(item.key)
      selectedProjectId.value = undefined
    } else {
      keyword.value = item.label === '未分配' ? '' : item.label
    }
  }
  void loadOverview()
}

const setStatus = (status: string) => {
  selectedStatus.value = status
  loadOverview()
}

const switchView = (mode: ViewMode) => {
  activeView.value = mode
  if (mode === 'project') {
    selectedAssigneeId.value = undefined
  } else {
    selectedProjectId.value = undefined
  }
  loadOverview()
}

const resetFilters = () => {
  selectedStatus.value = ''
  selectedProjectId.value = undefined
  selectedAssigneeId.value = undefined
  keyword.value = ''
  loadOverview()
}

const resetCreateForm = () => {
  createTaskForm.projectId = undefined
  createTaskForm.requirementId = undefined
  createTaskForm.title = ''
  createTaskForm.assigneeId = undefined
  createTaskForm.taskType = '开发任务'
  createTaskForm.estimatedHours = undefined
  createTaskForm.description = ''
  createBusinessLineId.value = undefined
  projectMemberOptions.value = []
  createTaskFormRef.value?.clearValidate()
}

const loadCreateProjectMembers = async (projectId?: number) => {
  projectMemberOptions.value = []
  if (!projectId) return

  loadingProjectMembers.value = true
  try {
    const members = await api.getProjectMembers(projectId)
    projectMemberOptions.value = normalizePageRecords<ProjectMemberOption>(members)
  } catch (e) {
    projectMemberOptions.value = []
  } finally {
    loadingProjectMembers.value = false
  }
}

const clearCreateProjectSelection = () => {
  createTaskForm.projectId = undefined
  createTaskForm.requirementId = undefined
  createTaskForm.assigneeId = undefined
  projectMemberOptions.value = []
  createTaskFormRef.value?.clearValidate(['projectId', 'requirementId', 'assigneeId'])
}

const syncCreateBusinessLineForProject = (projectId?: number) => {
  const selectedProject = findProjectOption(projectId)
  const rootProject = findRootProject(projectId)
  createBusinessLineId.value = selectedProject?.businessLineId ?? rootProject?.businessLineId
}

const selectCreateBusinessLine = (businessLineId?: number) => {
  createBusinessLineId.value = businessLineId
  clearCreateProjectSelection()
}

const selectCreateProject = async (projectId: number) => {
  const changed = createTaskForm.projectId !== projectId
  createTaskForm.projectId = projectId

  if (changed) {
    createTaskForm.requirementId = undefined
    createTaskForm.assigneeId = undefined
  }

  createTaskFormRef.value?.clearValidate(['projectId', 'requirementId', 'assigneeId'])
  if (changed) {
    await loadCreateProjectMembers(projectId)
  }
}

const selectCreateSubProject = async (rootProjectId: number, subProjectId: number) => {
  await selectCreateProject(createTaskForm.projectId === subProjectId ? rootProjectId : subProjectId)
}

const openCreateTaskDialog = async () => {
  resetCreateForm()
  if (activeView.value === 'project' && selectedProjectId.value) {
    createTaskForm.projectId = selectedProjectId.value
  }
  syncCreateBusinessLineForProject(createTaskForm.projectId)
  createDialogVisible.value = true
  await loadCreateProjectMembers(createTaskForm.projectId)
}

const getCurrentUserId = () => {
  try {
    const raw = localStorage.getItem('user')
    if (!raw) return undefined
    const parsed = JSON.parse(raw) as { id?: unknown }
    const id = Number(parsed.id)
    return Number.isFinite(id) ? id : undefined
  } catch {
    return undefined
  }
}

const submitCreateTask = async () => {
  const valid = await createTaskFormRef.value?.validate().catch(() => false)
  if (!valid) return

  const requirementId = createTaskForm.requirementId
  const title = createTaskForm.title.trim()
  if (!requirementId || !title) return

  const payload: {
    requirementId: number
    title: string
    description?: string
    assigneeId?: number
    taskType?: string
    createdBy?: number
    estimatedHours?: number
  } = {
    requirementId,
    title,
    taskType: createTaskForm.taskType
  }

  const description = createTaskForm.description.trim()
  const currentUserId = getCurrentUserId()
  if (description) payload.description = description
  if (createTaskForm.assigneeId) payload.assigneeId = createTaskForm.assigneeId
  if (createTaskForm.estimatedHours != null) payload.estimatedHours = createTaskForm.estimatedHours
  if (currentUserId) payload.createdBy = currentUserId

  creatingTask.value = true
  try {
    await api.createTask(payload)
    ElMessage.success('任务已创建')
    createDialogVisible.value = false
    resetCreateForm()
    await loadOverview()
  } catch (e) {
    ElMessage.error('任务创建失败')
  } finally {
    creatingTask.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await loadFilters()
    await loadOverview()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="tasks-page">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">任务管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ summary.totalCount }}</span>
            <span class="stat-text">个任务</span>
          </span>
          <span class="stat-divider">|</span>
          <span class="inline-stat">
            <span class="stat-num green">{{ summary.completedCount + summary.testedCount }}</span>
            <span class="stat-text">已完成</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-primary" type="button" @click="openCreateTaskDialog">
          <el-icon><Plus /></el-icon>
          新增任务
        </button>
        <button class="btn btn-default" type="button" @click="resetFilters">
          <el-icon><Refresh /></el-icon>
          重置
        </button>
      </div>
    </div>

    <div class="summary-grid">
      <div class="metric-tile">
        <span class="metric-label">待开始</span>
        <strong>{{ summary.pendingCount }}</strong>
      </div>
      <div class="metric-tile accent">
        <span class="metric-label">进行中</span>
        <strong>{{ summary.inProgressCount }}</strong>
      </div>
      <div class="metric-tile success">
        <span class="metric-label">完成/已测</span>
        <strong>{{ summary.completedCount + summary.testedCount }}</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">预估工时</span>
        <strong>{{ formatHours(summary.totalEstimatedHours) }}h</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">未分配</span>
        <strong>{{ summary.unassignedCount }}</strong>
      </div>
    </div>

    <div class="view-toggle main-tab-toggle" role="tablist" aria-label="任务页面视图">
      <button class="view-btn" role="tab" :aria-selected="activeMainTab === 'detail'" :class="{ active: activeMainTab === 'detail' }" @click="activeMainTab = 'detail'">任务明细</button>
      <button class="view-btn" role="tab" :aria-selected="activeMainTab === 'analysis'" :class="{ active: activeMainTab === 'analysis' }" @click="activeMainTab = 'analysis'">执行分析</button>
    </div>

    <WorkItemAnalysisPanel
      v-if="activeMainTab === 'analysis'"
      title="任务执行分析"
      subtitle="查看任务状态、资源分布与工时执行偏差"
      :analysis="analysis"
      :sections="analysisSections"
      @select="handleAnalysisSelect"
    >
      <div class="effort-band">
        <div><span>工时执行率</span><strong>{{ effortExecutionRate.toFixed(1) }}%</strong></div>
        <div class="effort-track"><i :style="{ width: `${Math.min(100, effortExecutionRate)}%` }" /></div>
        <small>实际 {{ formatHours(analysis.totalActualHours) }}h / 预估 {{ formatHours(analysis.totalEstimatedHours) }}h</small>
      </div>
    </WorkItemAnalysisPanel>

    <template v-if="activeMainTab === 'detail'">
    <section class="task-detail-heading"><div><h2>任务明细</h2><p>按项目或人员下钻查看具体任务</p></div></section>

    <div class="filter-section">
      <div class="status-rail" aria-label="任务状态筛选">
        <button
          v-for="item in statusFilters"
          :key="item.value || 'all'"
          class="status-filter"
          :class="{ active: selectedStatus === item.value }"
          type="button"
          @click="setStatus(item.value)"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value ? statusCount(item.value) : summary.totalCount }}</strong>
        </button>
      </div>

      <div class="filter-bar">
        <div class="view-switch" role="tablist" aria-label="任务视图">
          <button
            v-for="item in viewOptions"
            :key="item.value"
            type="button"
            :class="{ active: activeView === item.value }"
            @click="switchView(item.value)"
          >
            {{ item.label }}
          </button>
        </div>

        <el-select
          v-if="activeView === 'project'"
          v-model="selectedProjectId"
          clearable
          filterable
          placeholder="选择项目"
          style="width: 220px"
          @change="loadOverview"
          @clear="loadOverview"
        >
          <el-option
            v-for="project in projectOptions"
            :key="project.id"
            :label="project.fullPath || project.name"
            :value="project.id"
          />
        </el-select>

        <el-select
          v-else
          v-model="selectedAssigneeId"
          clearable
          filterable
          placeholder="选择负责人"
          style="width: 220px"
          @change="loadOverview"
          @clear="loadOverview"
        >
          <el-option
            v-for="user in userOptions"
            :key="user.id"
            :label="user.realName || user.username || `用户${user.id}`"
            :value="user.id"
          />
        </el-select>

        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索任务、需求、项目或人员"
          class="task-search"
          @keyup.enter="loadOverview"
          @clear="loadOverview"
        />

        <button class="btn btn-sm btn-primary" @click="loadOverview">查询</button>
      </div>
    </div>

    <div class="task-board" v-loading="loading">
      <el-empty v-if="!loading && visibleGroups.length === 0" description="暂无任务" style="padding: 72px 0" />

      <section v-for="group in visibleGroups" :key="group.key" class="task-group">
        <header class="group-header">
          <div class="group-title-block">
            <h3>{{ group.title }}</h3>
            <span>{{ group.subtitle }}</span>
          </div>
          <div class="group-metrics">
            <span>{{ group.tasks.length }} 项</span>
            <span>{{ formatHours(group.estimatedHours) }}h</span>
            <span>{{ getProgressPercent(group) }}%</span>
          </div>
        </header>

        <div class="group-context">
          <span
            v-for="name in (activeView === 'project' ? group.assignees : group.projects).slice(0, 6)"
            :key="name"
            class="context-chip"
          >
            {{ name }}
          </span>
        </div>

        <div class="task-list">
          <article
            v-for="task in group.tasks"
            :key="task.recordKey || task.id"
            class="task-row"
            role="button"
            tabindex="0"
            @click="openTaskDetail(task)"
            @keydown.enter.prevent="openTaskDetail(task)"
            @keydown.space.prevent="openTaskDetail(task)"
          >
            <div class="task-status-dot" :class="statusBadgeClass(task.status)" aria-hidden="true"></div>
            <div class="task-main">
              <div class="task-title-row">
                <span class="task-title" :class="{ done: ['已完成', '已测试'].includes(task.status || '') }">
                  {{ task.title }}
                </span>
                <span class="task-type">{{ task.taskType || '任务' }}</span>
                <span v-if="task.dataSource === 'YUNXIAO'" class="source-badge yunxiao">云效</span>
                <span v-else class="source-badge local">本地</span>
              </div>
              <div class="task-meta">
                <span>{{ task.requirementNo || `需求${task.requirementId}` }}</span>
                <span class="created-date">创建 {{ formatDateTime(task.createdAt) }}</span>
                <span class="planned-date" :class="{ overdue: task.overdueIncomplete }">{{ task.dueDate || task.endDate ? `计划 ${task.dueDate || task.endDate}` : '未设置计划' }}</span>
                <span v-if="task.overdueIncomplete" class="overdue-pill">超期 {{ task.overdueDays }} 天</span>
                <span>{{ task.requirementTitle || '未命名需求' }}</span>
                <span v-if="activeView === 'project'">{{ displayPerson(task) }}</span>
                <span v-else>{{ displayProject(task) }}</span>
              </div>
            </div>
            <div class="task-hours">
              <span>{{ formatHours(task.estimatedHours) }}h</span>
              <small>预估</small>
            </div>
            <select
              v-if="!task.readOnly"
              class="task-status-select"
              :class="statusBadgeClass(task.status)"
              :value="task.status || '待开始'"
              :disabled="updatingTaskStatusId === task.id"
              :aria-label="`修改任务状态：${task.title}`"
              @click.stop
              @keydown.stop
              @change="handleTaskStatusChange(task, $event)"
            >
              <option v-for="status in taskStatusOptions" :key="status" :value="status">{{ status }}</option>
            </select>
            <span v-else class="readonly-status" :class="statusBadgeClass(task.status)">{{ task.status || '未设置' }}</span>
          </article>
        </div>
      </section>
    </div>

    </template>

    <el-drawer
      v-model="taskDetailVisible"
      :title="selectedTask?.title ?? '任务详情'"
      size="440px"
      class="task-detail-drawer"
      destroy-on-close
    >
      <div v-if="selectedTask" class="task-detail" v-loading="taskDetailLoading">
        <div v-if="selectedTask.readOnly" class="readonly-note">只读数据，以云效为准</div>
        <div class="task-detail-head">
          <div>
            <div class="task-detail-title">{{ selectedTask.title }}</div>
            <div class="task-detail-subtitle">{{ selectedTask.taskType || '开发任务' }}</div>
          </div>
          <select
            v-if="!selectedTask.readOnly"
            class="task-status-select detail"
            :class="statusBadgeClass(selectedTask.status)"
            :value="selectedTask.status || '待开始'"
            :disabled="updatingTaskStatusId === selectedTask.id"
            aria-label="修改任务状态"
            @change="handleTaskStatusChange(selectedTask, $event)"
          >
            <option v-for="status in taskStatusOptions" :key="status" :value="status">{{ status }}</option>
          </select>
          <span v-else class="readonly-status" :class="statusBadgeClass(selectedTask.status)">{{ selectedTask.status || '未设置' }}</span>
        </div>

        <el-descriptions :column="1" border>
          <el-descriptions-item label="关联需求">
            <router-link
              v-if="selectedTask.requirementId"
              class="task-detail-link"
              :to="`/requirements-standalone/${selectedTask.requirementId}`"
            >
              {{ selectedTask.requirementNo || `需求${selectedTask.requirementId}` }} · {{ selectedTask.requirementTitle || '未命名需求' }}
            </router-link>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="所属项目">{{ displayProject(selectedTask) }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ displayPerson(selectedTask) }}</el-descriptions-item>
          <el-descriptions-item label="预估工时">{{ formatHours(selectedTask.estimatedHours) }}h</el-descriptions-item>
          <el-descriptions-item label="实际工时">{{ selectedTask.actualHours == null ? '—' : `${formatHours(selectedTask.actualHours)}h` }}</el-descriptions-item>
          <el-descriptions-item label="开始日期">{{ selectedTask.startDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(selectedTask.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="计划完成">{{ selectedTask.dueDate || selectedTask.endDate || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="超期情况">{{ selectedTask.overdueIncomplete ? `超期 ${selectedTask.overdueDays} 天` : '未超期' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(selectedTask.updatedAt) }}</el-descriptions-item>
        </el-descriptions>

        <section class="task-detail-section">
          <h4>任务描述</h4>
          <p>{{ selectedTask.description || '暂无描述' }}</p>
        </section>
      </div>
      <el-empty v-else description="未选择任务" />
    </el-drawer>

    <el-dialog
      v-model="createDialogVisible"
      title="新增任务"
      width="640px"
      class="task-create-dialog"
      destroy-on-close
      @closed="resetCreateForm"
    >
      <el-form
        ref="createTaskFormRef"
        :model="createTaskForm"
        :rules="createTaskRules"
        label-position="top"
        class="task-create-form"
      >
        <el-form-item label="所属项目" prop="projectId">
          <div class="project-selector-modal">
            <div class="business-tabs-modal">
              <button
                type="button"
                class="business-tab-modal"
                :class="{ active: createBusinessLineId === undefined }"
                @click="selectCreateBusinessLine(undefined)"
              >全部</button>
              <button
                v-for="businessLine in businessLineOptions"
                :key="businessLine.id"
                type="button"
                class="business-tab-modal"
                :class="{ active: createBusinessLineId === businessLine.id }"
                @click="selectCreateBusinessLine(businessLine.id)"
              >{{ businessLine.name }}</button>
            </div>
            <div v-if="visibleCreateProjectCards.length" class="project-cards-modal">
              <div
                v-for="project in visibleCreateProjectCards"
                :key="project.id"
                class="project-card-modal"
                :class="{ selected: selectedCreateRootProjectId === project.id }"
                role="button"
                tabindex="0"
                @click="selectCreateProject(project.id)"
                @keydown.enter.prevent="selectCreateProject(project.id)"
                @keydown.space.prevent="selectCreateProject(project.id)"
              >
                <span class="project-name">{{ project.name }}</span>
                <span v-if="project.subProjects.length" class="sub-count">{{ project.subProjects.length }}个子项目</span>
                <div
                  v-if="project.subProjects.length && selectedCreateRootProjectId === project.id"
                  class="sub-tags-modal"
                >
                  <span
                    v-for="subProject in project.subProjects"
                    :key="subProject.id"
                    class="sub-tag-modal"
                    :class="{ selected: createTaskForm.projectId === subProject.id }"
                    role="button"
                    tabindex="0"
                    @click.stop="selectCreateSubProject(project.id, subProject.id)"
                    @keydown.enter.stop.prevent="selectCreateSubProject(project.id, subProject.id)"
                    @keydown.space.stop.prevent="selectCreateSubProject(project.id, subProject.id)"
                  >{{ subProject.name }}</span>
                </div>
              </div>
            </div>
            <div v-else class="project-selector-empty">{{ createProjectEmptyText }}</div>
          </div>
        </el-form-item>

        <el-form-item label="关联需求" prop="requirementId">
          <el-select
            v-model="createTaskForm.requirementId"
            clearable
            filterable
            :disabled="!createTaskForm.projectId"
            placeholder="选择项目下的需求"
            :empty-text="requirementEmptyText"
            style="width: 100%"
          >
            <el-option
              v-for="requirement in createRequirementOptions"
              :key="requirement.id"
              :label="displayRequirement(requirement)"
              :value="requirement.id"
            >
              <div class="requirement-option">
                <span>{{ displayRequirement(requirement) }}</span>
                <small>{{ requirement.projectFullPath || requirement.projectName || '未关联项目' }}</small>
              </div>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="任务标题" prop="title">
          <el-input
            v-model="createTaskForm.title"
            maxlength="80"
            show-word-limit
            placeholder="例如：联调订单接口"
          />
        </el-form-item>

        <div class="task-create-grid">
          <el-form-item label="负责人" prop="assigneeId">
            <el-select
              v-model="createTaskForm.assigneeId"
              clearable
              filterable
              :loading="loadingProjectMembers"
              placeholder="选择负责人"
              empty-text="暂无可选负责人"
              style="width: 100%"
            >
              <el-option
                v-for="user in createAssigneeOptions"
                :key="user.id"
                :label="displayUser(user)"
                :value="user.id"
              >
                <div class="assignee-option">
                  <span>{{ displayUser(user) }}</span>
                  <small v-if="user.role">{{ user.role }}</small>
                </div>
              </el-option>
            </el-select>
          </el-form-item>

          <el-form-item label="任务类型" prop="taskType">
            <el-select v-model="createTaskForm.taskType" style="width: 100%">
              <el-option v-for="type in taskTypeOptions" :key="type" :label="type" :value="type" />
            </el-select>
          </el-form-item>

          <el-form-item label="预估工时" prop="estimatedHours">
            <el-input-number
              v-model="createTaskForm.estimatedHours"
              :min="0"
              :precision="1"
              :step="0.5"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <el-form-item label="任务描述" prop="description">
          <el-input
            v-model="createTaskForm.description"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="补充任务范围、交付物或注意事项"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <button class="btn btn-default" type="button" @click="createDialogVisible = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="creatingTask" @click="submitCreateTask">
          {{ creatingTask ? '创建中...' : '创建' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.main-tab-toggle {
  margin-bottom: 14px;
  width: fit-content;
}

.view-toggle {
  display: flex;
  background: var(--gray-100);
  border-radius: var(--radius-md, 8px);
  padding: 3px;
}

.view-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 13px;
  color: var(--gray-600);
  cursor: pointer;
  transition: all 0.15s ease;
}

.view-btn:hover {
  color: var(--gray-800);
}

.view-btn.active {
  background: white;
  color: var(--primary);
  box-shadow: var(--shadow-sm, 0 1px 2px rgb(0 0 0 / 8%));
}
.tasks-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title-with-stats,
.inline-stats,
.inline-stat,
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-with-stats {
  gap: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.inline-stats {
  gap: 8px;
  padding-left: 16px;
  border-left: 1px solid var(--gray-200);
}

.inline-stat {
  gap: 4px;
}

.stat-num {
  font-size: 16px;
  font-weight: 700;
  color: var(--gray-800);
}

.stat-num.green {
  color: var(--success);
}

.stat-text {
  font-size: 13px;
  color: var(--gray-500);
}

.stat-divider {
  color: var(--gray-300);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 12px;
}

.metric-tile {
  min-height: 74px;
  padding: 14px 16px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  background: #fff;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.metric-tile strong {
  color: var(--gray-800);
  font-size: 24px;
  line-height: 1;
}

.metric-tile.accent strong {
  color: var(--warning);
}

.metric-tile.success strong {
  color: var(--success);
}

.metric-label {
  color: var(--gray-500);
  font-size: 12px;
}

.filter-section {
  background: #fff;
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: 12px;
}

.status-rail {
  display: grid;
  grid-template-columns: repeat(5, minmax(96px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.status-filter {
  min-height: 42px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  background: var(--gray-50);
  color: var(--gray-600);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 12px;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}

.status-filter:hover,
.status-filter:focus-visible {
  border-color: var(--primary);
  outline: none;
}

.status-filter.active {
  background: var(--primary-light);
  color: var(--primary);
  border-color: #bfdbfe;
}

.status-filter span {
  font-size: 13px;
}

.status-filter strong {
  font-size: 15px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.view-switch {
  display: inline-flex;
  min-height: 34px;
  padding: 3px;
  border-radius: var(--radius-md);
  background: var(--gray-100);
}

.view-switch button {
  min-width: 72px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--gray-500);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.view-switch button.active {
  background: #fff;
  color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.view-switch button:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.task-search {
  width: 260px;
}

.task-board {
  min-height: 260px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-group {
  background: #fff;
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.group-header {
  min-height: 62px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--gray-100);
  background: linear-gradient(180deg, #fff, #f8fafc);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.group-title-block h3 {
  max-width: 680px;
  color: var(--gray-800);
  font-size: 15px;
  font-weight: 700;
  line-height: 1.35;
  margin: 0 0 4px;
  overflow-wrap: anywhere;
}

.group-title-block span,
.group-metrics span,
.task-meta,
.task-hours small {
  color: var(--gray-500);
  font-size: 12px;
}

.group-metrics {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.group-metrics span {
  min-width: 50px;
  text-align: center;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  background: var(--gray-100);
}

.group-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 38px;
  padding: 10px 16px 0;
}

.context-chip {
  display: inline-flex;
  align-items: center;
  max-width: 180px;
  height: 24px;
  padding: 0 8px;
  border-radius: var(--radius-sm);
  background: var(--primary-light);
  color: var(--primary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-list {
  padding: 8px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-row {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) 72px 76px;
  align-items: center;
  gap: 12px;
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-sm);
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.task-row:hover {
  border-color: #bfdbfe;
  background: #f8fbff;
}

.task-row:focus-visible {
  border-color: var(--primary);
  outline: 2px solid #bfdbfe;
  outline-offset: 2px;
}

.task-detail-heading { margin: 20px 0 10px; }
.task-detail-heading h2 { margin: 0; font-size: 16px; letter-spacing: 0; }
.task-detail-heading p { margin: 3px 0 0; color: var(--gray-500); font-size: 12px; }
.effort-band { display: grid; grid-template-columns: 140px minmax(160px, 1fr) auto; align-items: center; gap: 14px; padding: 13px 0; }
.effort-band > div:first-child { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.effort-band span, .effort-band small { color: var(--gray-500); font-size: 12px; }
.effort-band strong { font-size: 18px; color: var(--primary); }
.effort-track { height: 8px; overflow: hidden; border-radius: 2px; background: var(--gray-100); }
.effort-track i { display: block; height: 100%; background: var(--primary); }
.task-meta .created-date { color: #9299a3; }
.task-meta .planned-date { color: #5f6874; font-variant-numeric: tabular-nums; }
.task-meta .planned-date.overdue { color: #8d3e36; font-weight: 600; }
.task-meta .overdue-pill { display: inline-flex; align-items: center; min-height: 20px; padding: 2px 7px; border: 1px solid #efc2ba; border-radius: 3px; background: #fff0ed; color: #a33f35; font-size: 10px; font-weight: 700; line-height: 1; white-space: nowrap; }
.task-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--gray-300);
}

.task-status-dot.green { background: var(--success); }
.task-status-dot.blue { background: var(--info); }
.task-status-dot.yellow { background: var(--warning); }
.task-status-dot.gray { background: var(--gray-300); }
.task-status-dot.slate { background: var(--gray-500); }

.source-badge {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
}

.source-badge.local { color: #285fae; background: #e8f1ff; }
.source-badge.yunxiao { color: #765514; background: #fff3d1; }

.readonly-note {
  margin-bottom: 14px;
  padding: 9px 11px;
  border-left: 3px solid #d5a136;
  background: #fff8e5;
  color: #73561c;
  font-size: 13px;
}

.readonly-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 82px;
  min-height: 30px;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}

.readonly-status.green { color: #1f7048; background: #e8f6ef; }
.readonly-status.blue { color: #245fae; background: #e8f1ff; }
.readonly-status.yellow { color: #80510c; background: #fff3d3; }
.readonly-status.gray, .readonly-status.slate { color: #5e6570; background: #eef0f3; }

.task-main {
  min-width: 0;
}

.task-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 4px;
}

.task-title {
  min-width: 0;
  color: var(--gray-800);
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-title.done {
  color: var(--gray-400);
  text-decoration: line-through;
}

.task-type {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: var(--gray-100);
  color: var(--gray-500);
  font-size: 11px;
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.task-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta span + span::before {
  content: "";
  display: inline-block;
  width: 3px;
  height: 3px;
  margin-right: 8px;
  border-radius: 999px;
  background: var(--gray-300);
  vertical-align: middle;
}

.task-hours {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.task-hours span {
  color: var(--gray-700);
  font-size: 13px;
  font-weight: 700;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.status-badge.gray { background: var(--gray-100); color: var(--gray-600); }
.status-badge.green { background: #d1fae5; color: #047857; }
.status-badge.yellow { background: #fef3c7; color: #b45309; }
.status-badge.blue { background: #dbeafe; color: #1d4ed8; }
.status-badge.slate { background: #e2e8f0; color: var(--gray-600); }

.task-status-select {
  width: 76px;
  height: 28px;
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 0 22px 0 9px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  appearance: auto;
}

.task-status-select:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.task-status-select:disabled {
  cursor: wait;
  opacity: 0.72;
}

.task-status-select.gray {
  background: var(--gray-100);
  color: var(--gray-600);
}

.task-status-select.green {
  background: #d1fae5;
  color: #047857;
}

.task-status-select.yellow {
  background: #fef3c7;
  color: #b45309;
}

.task-status-select.blue {
  background: #dbeafe;
  color: #1d4ed8;
}

.task-status-select.slate {
  background: #e2e8f0;
  color: var(--gray-600);
}

.task-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.task-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.task-detail-title {
  color: var(--gray-800);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.task-detail-subtitle {
  margin-top: 4px;
  color: var(--gray-400);
  font-size: 12px;
}

.task-status-select.detail {
  width: 90px;
  flex-shrink: 0;
}

.task-detail-link {
  color: var(--primary);
  text-decoration: none;
}

.task-detail-link:hover {
  text-decoration: underline;
}

.task-detail-section {
  padding: 12px;
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-md);
  background: var(--gray-50);
}

.task-detail-section h4 {
  margin: 0 0 8px;
  color: var(--gray-700);
  font-size: 13px;
  font-weight: 700;
}

.task-detail-section p {
  margin: 0;
  color: var(--gray-600);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.task-create-form {
  padding-top: 4px;
}

.task-create-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.project-selector-modal {
  width: 100%;
  max-height: 190px;
  padding: 8px;
  border-radius: var(--radius-md);
  background: var(--gray-50);
  overflow-y: auto;
}

.business-tabs-modal {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.business-tab-modal {
  padding: 4px 10px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--gray-600);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.business-tab-modal:hover,
.business-tab-modal:focus-visible {
  background: var(--gray-200);
  outline: none;
}

.business-tab-modal.active {
  background: var(--primary);
  color: #fff;
}

.project-cards-modal {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 128px;
  overflow-y: auto;
}

.project-card-modal {
  min-width: 92px;
  max-width: 100%;
  padding: 6px 10px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-sm);
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.project-card-modal:hover,
.project-card-modal:focus-visible {
  border-color: var(--primary);
  outline: none;
}

.project-card-modal.selected {
  border-color: var(--primary);
  background: var(--primary-light);
}

.project-card-modal .project-name {
  display: block;
  color: var(--gray-800);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.project-card-modal .sub-count {
  color: var(--gray-400);
  font-size: 10px;
}

.sub-tags-modal {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.sub-tag-modal {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--gray-100);
  color: var(--gray-600);
  font-size: 10px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.sub-tag-modal:hover,
.sub-tag-modal:focus-visible {
  background: var(--gray-200);
  outline: none;
}

.sub-tag-modal.selected {
  background: var(--primary);
  color: #fff;
}

.project-selector-empty {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--gray-400);
  font-size: 12px;
}

.requirement-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.requirement-option span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.requirement-option small {
  flex-shrink: 0;
  color: var(--gray-400);
}

.assignee-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.assignee-option span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assignee-option small {
  flex-shrink: 0;
  color: var(--gray-400);
}

@media (max-width: 1100px) {
  .summary-grid,
  .status-rail {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-row {
    grid-template-columns: 12px minmax(0, 1fr) 72px;
  }

  .task-row .task-status-select {
    grid-column: 2 / 4;
    justify-self: start;
  }
}

@media (max-width: 720px) {
  .content-header,
  .group-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .title-with-stats,
  .filter-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .inline-stats {
    padding-left: 0;
    border-left: none;
  }

  .page-actions {
    width: 100%;
  }

  .page-actions .btn {
    flex: 1;
    justify-content: center;
  }

  .summary-grid,
  .status-rail {
    grid-template-columns: 1fr;
  }

  .task-create-grid {
    grid-template-columns: 1fr;
  }

  .task-search,
  .filter-bar :deep(.el-select) {
    width: 100% !important;
  }

  .view-switch {
    width: 100%;
  }

  .view-switch button {
    flex: 1;
  }

  .group-metrics {
    flex-wrap: wrap;
  }

  .task-row {
    grid-template-columns: 12px minmax(0, 1fr);
  }

  .task-hours,
  .task-row .task-status-select {
    grid-column: 2;
    justify-self: start;
    align-items: flex-start;
  }
}
</style>
