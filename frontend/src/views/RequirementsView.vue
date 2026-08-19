<script setup lang="ts">
import { ref, computed, reactive, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RequirementDesignPlannerDialog from '@/components/RequirementDesignPlannerDialog.vue'
import WorkItemAnalysisPanel from '@/components/WorkItemAnalysisPanel.vue'
import type { Requirement } from '@/types/requirement'
import type { WorkItemAnalysis, WorkItemDistributionItem, WorkItemOverviewResponse } from '@/types/work-item'
import { api } from '@/utils/api'
import { getRequirementStageActions } from '@/utils/requirement-stage'
import { sanitizeHtml } from '@/utils/sanitize-html'

const viewMode = ref<'table' | 'kanban'>('table')
const showCreateModal = ref(false)
const showDetailDrawer = ref(false)
const showEvaluationModal = ref(false)
const currentRequirement = ref<Requirement | null>(null)
const safeCurrentRequirementDescription = computed(() =>
  sanitizeHtml(currentRequirement.value?.description || '暂无描述')
)
const loading = ref(false)
const emptyAnalysis = (): WorkItemAnalysis => ({
  statusDistribution: [], projectDistribution: [], ownerDistribution: [], sourceDistribution: [],
  priorityDistribution: [], overdueProjectDistribution: [], overdueOwnerDistribution: [],
  overdueAgeDistribution: [], totalEstimatedHours: 0, totalActualHours: 0,
  completionRate: 0, unassignedCount: 0, overdueIncompleteCount: 0, missingDueDateCount: 0
})
const analysis = ref<WorkItemAnalysis>(emptyAnalysis())
const actionLoading = ref('')
const showDesignPlanner = ref(false)
const plannerRequirement = ref<Requirement | null>(null)
const plannerPlans = ref<any[]>([])

const evaluationForm = reactive({
  isFeasible: true,
  estimatedWorkload: '',
  estimatedCost: '',
  estimatedOnlineDate: ''
})

const getStoredUser = () => {
  try {
    return JSON.parse(localStorage.getItem('user') || '{}')
  } catch {
    return {}
  }
}

const currentUserId = computed(() => Number(getStoredUser()?.id) || 1)

const newRequirement = reactive({
  title: '',
  businessLine: '',
  project: '',
  subProject: '',
  source: '内部',
  requester: '',
  customerContactId: undefined as number | undefined,
  type: '产品需求',
  priority: '中',
  expectDate: '',
  owner: '',
  description: ''
})

// 客户联系人列表（项目需求必填）
const customerContacts = ref<any[]>([])
const loadingContacts = ref(false)

// 当项目或类型变化时，加载对应项目的客户联系人
const loadCustomerContacts = async (projectId: number | undefined) => {
  if (!projectId) {
    customerContacts.value = []
    return
  }
  loadingContacts.value = true
  try {
    const data = await api.getCustomerContacts(projectId)
    customerContacts.value = data
  } catch (error) {
    console.error('加载客户联系人失败:', error)
    customerContacts.value = []
  } finally {
    loadingContacts.value = false
  }
}

const editorRef = ref<HTMLElement | null>(null)

// 用户列表（负责人下拉）
const allUsers = ref<any[]>([])
const isProjectRequirement = computed(() => newRequirement.type === '项目需求')
const actorFieldLabel = computed(() => isProjectRequirement.value ? '客户信息' : '提出人')

// 弹窗内项目选择
const modalProjects = computed(() => {
  if (!newRequirement.businessLine) {
    return businessLines.value.flatMap(bl => bl.projects)
  }
  const bl = businessLines.value.find(b => b.name === newRequirement.businessLine)
  return bl ? bl.projects : []
})

const execCommand = (command: string) => {
  document.execCommand(command, false)
}

const filters = reactive({
  status: [] as string[],
  dataSource: '' as '' | 'LOCAL' | 'YUNXIAO',
  type: '',
  priority: '',
  search: ''
})

const statusOptions = [
  { label: '待评估', value: '待评估', class: 'yellow' },
  { label: '评估中', value: '评估中', class: 'blue' },
  { label: '已拒绝', value: '已拒绝', class: 'gray' },
  { label: '待设计', value: '待设计', class: 'yellow' },
  { label: '设计中', value: '设计中', class: 'green' },
  { label: '待确认', value: '待确认', class: 'yellow' },
  { label: '开发中', value: '开发中', class: 'red' },
  { label: '测试中', value: '测试中', class: 'purple' },
  { label: '待上线', value: '待上线', class: 'cyan' },
  { label: '已上线', value: '已上线', class: 'green' },
  { label: '已交付', value: '已交付', class: 'green' },
  { label: '已验收', value: '已验收', class: 'green' }
]

const typeOptions = [
  { label: '产品需求', value: '产品需求', class: 'type-product' },
  { label: '项目需求', value: '项目需求', class: 'type-project' }
]

const priorityOptions = [
  { label: '全部', value: '' },
  { label: '高', value: '高', class: 'red' },
  { label: '中', value: '中', class: 'yellow' },
  { label: '低', value: '低', class: 'gray' }
]

// 下拉选择状态
const showStatusDropdown = ref(false)
const statusDropdownRef = ref<HTMLElement | null>(null)

const toggleStatusDropdown = () => {
  showStatusDropdown.value = !showStatusDropdown.value
}

const toggleStatus = (value: string) => {
  const idx = filters.status.indexOf(value)
  if (idx > -1) {
    filters.status.splice(idx, 1)
  } else {
    filters.status.push(value)
  }
}

const clearStatusFilter = () => {
  filters.status = []
}

const getStatusDisplay = computed(() => {
  if (filters.status.length === 0) return '状态'
  if (filters.status.length === 1) return filters.status[0]
  return `${filters.status.length}个状态`
})

// 点击外部关闭下拉
onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  loadData()
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

const handleClickOutside = (e: MouseEvent) => {
  if (statusDropdownRef.value && !statusDropdownRef.value.contains(e.target as Node)) {
    showStatusDropdown.value = false
  }
}

// 从API响应中提取数据数组（兼容分页格式和直接数组）
const extractRecords = (resp: any): any[] => {
  if (Array.isArray(resp)) return resp
  if (resp?.records) return resp.records
  if (resp?.data?.records) return resp.data.records
  return []
}

// 保存原始数据用于创建需求时查找ID
const rawBusinessLines = ref<any[]>([])
const rawProjects = ref<any[]>([])

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    // 并行加载业务线、项目、用户、需求
    const [businessLineResp, projectsResp, usersResp, requirementsResp] = await Promise.all([
      api.getBusinessLines({ size: 999 }),
      api.getProjects({ size: 999 }),
      api.getUsers({ size: 100 }),
      api.getRequirementOverview({ size: 200 })
    ])

    const businessLineData = extractRecords(businessLineResp)
    const projectsData = extractRecords(projectsResp)
    allUsers.value = extractRecords(usersResp)
    const requirementsData = extractRecords(requirementsResp)
    const overviewPayload = requirementsResp as WorkItemOverviewResponse
    analysis.value = overviewPayload?.analysis ?? emptyAnalysis()

    // 保存原始数据
    rawBusinessLines.value = businessLineData
    rawProjects.value = projectsData

    // 先构建需求列表（通过 parentId 推导主项目/子项目关系）
    tableData.value = requirementsData.map((req: any) => {
      const reqProject = projectsData.find((p: any) => p.id === req.projectId)
      const isSubProject = reqProject?.parentId != null
      const mainProject = isSubProject
        ? projectsData.find((p: any) => p.id === reqProject.parentId)
        : reqProject
      return {
      id: req.dataSource === 'YUNXIAO' ? req.recordKey : String(req.id ?? req.requirementId),
      projectId: req.projectId,
      assigneeId: req.assigneeId,
      normalizedStatus: req.normalizedStatus,
      dueDate: req.dueDate,
      overdueIncomplete: req.overdueIncomplete,
      overdueDays: req.overdueDays,
      recordKey: req.recordKey || `local:${req.id}`,
      dataSource: req.dataSource || 'LOCAL',
      readOnly: Boolean(req.readOnly),
      yunxiaoWorkitemId: req.yunxiaoWorkitemId,
      linkedYunxiaoWorkitemId: req.linkedYunxiaoWorkitemId,
      linkedYunxiaoSerialNumber: req.linkedYunxiaoSerialNumber,
      linkedYunxiaoStatus: req.linkedYunxiaoStatus,
      reqNo: req.requirementNo || req.reqNo || req.serialNumber || '',
      title: req.title,
      project: req.projectNames?.join(' / ') || mainProject?.name || reqProject?.name || '',
      subProject: isSubProject ? (reqProject?.name || '') : '',
      businessLine: businessLineData.find((bl: any) => bl.id === req.businessLineId)?.name || '',
      type: req.type || '项目需求',
      status: req.status || '待评估',
      statusClass: getStatusClass(req.status),
      priority: req.priority || '-',
      priorityClass: getPriorityClass(req.priority),
      owner: req.assigneeName || req.ownerName || req.creatorName || (allUsers.value.find((u: any) => u.id === req.creatorId)?.realName) || '',
      createdAt: req.createdAt?.split('T')[0] || req.createTime?.split('T')[0] || '',
      source: req.businessSource || req.source || '内部',
      requester: req.customerContactName || req.requester || '',
      expectDate: req.expectedOnlineDate || req.expectDate || '',
      description: req.description || '',
      attachments: [],
      logs: [],
      evaluation: undefined,
      designWorks: [],
      tasks: []
      }
    })

    // 转换业务线数据，使用需求数量（而非项目数量）
    businessLines.value = businessLineData.map((bl: any) => ({
      id: bl.id,
      name: bl.name,
      totalCount: requirementsData.filter((r: any) => r.businessLineId === bl.id).length,
      projects: projectsData
        .filter((p: any) => p.businessLineId === bl.id && !p.parentId)
        .map((p: any) => {
          const subProjects = projectsData.filter((sp: any) => sp.parentId === p.id)
          const subProjectIds = subProjects.map((sp: any) => sp.id)
          const reqCount = requirementsData.filter((r: any) =>
            r.projectId === p.id || subProjectIds.includes(r.projectId)
          ).length
          return {
            id: p.id,
            name: p.name,
            logo: getProjectLogo(p.name),
            count: reqCount,
            subProjects: subProjects.map((sp: any) => ({
              id: sp.id,
              name: sp.name,
              count: requirementsData.filter((r: any) => r.projectId === sp.id).length
            }))
          }
        })
    }))
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

const getProjectLogo = (name: string): string => {
  return (name || '项').charAt(0).toUpperCase()
}

const getStatusClass = (status: string): string => {
  const statusMap: Record<string, string> = {
    '待评估': 'pending',
    '评估中': 'evaluating',
    '已拒绝': 'rejected',
    '待设计': 'pending',
    '设计中': 'designing',
    '待确认': 'pending',
    '开发中': 'developing',
    '测试中': 'testing',
    '待上线': 'online',
    '已上线': 'online',
    '已交付': 'online',
    '已验收': 'online'
  }
  return statusMap[status] || 'pending'
}

const getPriorityClass = (priority: string): string => {
  const priorityMap: Record<string, string> = {
    '高': 'high',
    '中': 'medium',
    '低': 'low'
  }
  return priorityMap[priority] || 'medium'
}

const businessLines = ref<any[]>([])

const selectedBusiness = ref('')
const selectedProjects = ref<string[]>([])
const selectedSubProjects = ref<string[]>([])

const visibleProjects = computed(() => {
  if (!selectedBusiness.value) {
    return businessLines.value.flatMap(bl => bl.projects)
  }
  const bl = businessLines.value.find(b => b.name === selectedBusiness.value)
  return bl ? bl.projects : []
})

const logoColors: Record<string, string> = {
  '皇家宠物': '#FEF3C7',
  '飞鹤': '#FEE2E2',
  'speedo': '#DBEAFE',
  '佳贝艾特': '#D1FAE5',
  '逢时': '#E0E7FF',
  '黄天鹅': '#FEF9C3'
}

const tableData = ref<Requirement[]>([])

const analysisSections = computed(() => [
  { key: 'status' as const, title: '状态分布', rows: analysis.value.statusDistribution },
  { key: 'project' as const, title: '项目分布', rows: analysis.value.projectDistribution },
  { key: 'source' as const, title: '数据来源', rows: analysis.value.sourceDistribution },
  { key: 'priority' as const, title: '优先级分布', rows: analysis.value.priorityDistribution }
])

const filteredData = computed(() => {
  return tableData.value.filter(item => {
    if (filters.dataSource && item.dataSource !== filters.dataSource) return false
    if (filters.status.length > 0 && !filters.status.includes(item.status)) return false
    if (filters.type && item.type !== filters.type) return false
    if (filters.priority && item.priority !== filters.priority) return false
    if (filters.search && !item.title.toLowerCase().includes(filters.search.toLowerCase()) && !item.reqNo.toLowerCase().includes(filters.search.toLowerCase())) return false

    if (selectedBusiness.value && item.businessLine !== selectedBusiness.value) return false
    if (selectedProjects.value.length > 0 && !selectedProjects.value.includes(item.project)) return false
    // 选中子项目时，只显示属于该子项目的需求（不再让无子项目的需求通过）
    if (selectedSubProjects.value.length > 0 && !(item.subProject && selectedSubProjects.value.includes(item.subProject))) return false

    return true
  })
})

const kanbanColumns = computed(() => {
  const statuses = ['待评估', '评估中', '待设计', '设计中', '待确认', '开发中', '测试中', '待上线', '已上线', '已交付', '已验收']
  return statuses.map(status => ({
    name: status,
    status,
    cards: filteredData.value.filter(i => i.status === status)
  })).filter(col => col.cards.length > 0)
})

const inProgressCount = computed(() => {
  return filteredData.value.filter(i => ['评估中', '设计中', '开发中', '测试中'].includes(i.status)).length
})

const pendingCount = computed(() => {
  return filteredData.value.filter(i => i.status === '待评估').length
})

const riskCount = computed(() => {
  return filteredData.value.filter(i => i.priority === '高' && ['待评估', '评估中'].includes(i.status)).length
})

const selectAll = () => {
  selectedBusiness.value = ''
  selectedProjects.value = []
  selectedSubProjects.value = []
}

const selectBusiness = (name: string) => {
  if (selectedBusiness.value === name) {
    selectedBusiness.value = ''
  } else {
    selectedBusiness.value = name
    selectedProjects.value = []
  }
  selectedSubProjects.value = []
}

const toggleProject = (name: string) => {
  const idx = selectedProjects.value.indexOf(name)
  if (idx > -1) {
    selectedProjects.value.splice(idx, 1)
  } else {
    selectedProjects.value.push(name)
  }
}

const toggleSubProject = (name: string) => {
  const idx = selectedSubProjects.value.indexOf(name)
  if (idx > -1) {
    selectedSubProjects.value.splice(idx, 1)
  } else {
    selectedSubProjects.value.push(name)
  }
}

const toggleFilter = (key: 'status' | 'type' | 'priority', value: string) => {
  if (key === 'status') {
    const idx = filters.status.indexOf(value)
    if (idx > -1) {
      filters.status.splice(idx, 1)
    } else {
      filters.status.push(value)
    }
  } else {
    filters[key] = filters[key] === value ? '' : value
  }
}

const handleAnalysisSelect = (section: string, item: WorkItemDistributionItem) => {
  if (section === 'status') {
    const matchingStatuses = tableData.value
      .filter(requirement => requirement.normalizedStatus === item.key)
      .map(requirement => requirement.status)
    filters.status = Array.from(new Set(matchingStatuses))
  }
  if (section === 'project') {
    const matching = tableData.value.find(requirement => String(requirement.projectId) === item.key)
    selectedProjects.value = matching?.project ? [matching.project] : []
    selectedSubProjects.value = matching?.subProject ? [matching.subProject] : []
  }
  if (section === 'source') filters.dataSource = item.key as 'LOCAL' | 'YUNXIAO'
  if (section === 'priority') filters.priority = item.key === '未设置' ? '' : item.key
}

const clearAllFilters = () => {
  selectedBusiness.value = ''
  selectedProjects.value = []
  selectedSubProjects.value = []
  filters.status = []
  filters.dataSource = ''
  filters.type = ''
  filters.priority = ''
  filters.search = ''
}

// Single/double click handling
let clickTimer: ReturnType<typeof setTimeout> | null = null
let clickId: string | null = null

const handleRowClick = (id: string) => {
  if (clickTimer && clickId === id) {
    clearTimeout(clickTimer)
    clickTimer = null
    clickId = null
    handleRowDoubleClick(id)
  } else {
    clickId = id
    clickTimer = setTimeout(() => {
      goToDetail(id)
      clickTimer = null
      clickId = null
    }, 250)
  }
}

const handleRowDoubleClick = (id: string) => {
  const requirement = tableData.value.find(item => item.id === id)
  if (requirement?.readOnly) {
    goToDetail(id)
    return
  }
  const localId = id.startsWith('local:') ? id.slice('local:'.length) : id
  window.open(`/requirements-standalone/${localId}`, '_blank')
}

const goToDetail = (id: string) => {
  const req = tableData.value.find(item => item.id === id)
  if (req) {
    currentRequirement.value = req
    showDetailDrawer.value = true
  }
}

const openInNewPage = () => {
  if (currentRequirement.value && !currentRequirement.value.readOnly) {
    const id = currentRequirement.value.id.startsWith('local:')
      ? currentRequirement.value.id.slice('local:'.length)
      : currentRequirement.value.id
    window.open(`/requirements-standalone/${id}`, '_blank')
  }
}

// Detail drawer computed
const showEvaluation = computed(() => {
  return ['待评估', '评估中', '已拒绝'].includes(currentRequirement.value?.status || '')
})

const showTasks = computed(() => {
  return ['开发中', '测试中', '待上线', '已上线'].includes(currentRequirement.value?.status || '')
})

const statusFlowSteps = [
  { status: '待评估', label: '待评估' },
  { status: '评估中', label: '评估中' },
  { status: '待设计', label: '待设计' },
  { status: '设计中', label: '设计中' },
  { status: '待确认', label: '待确认' },
  { status: '开发中', label: '开发中' },
  { status: '测试中', label: '测试中' },
  { status: '待上线', label: '待上线' },
  { status: '已上线', label: '已上线' },
  { status: '已交付', label: '已交付' },
  { status: '已验收', label: '已验收' }
]

const lifecycleOrder = ['待评估', '评估中', '待设计', '设计中', '待确认', '开发中', '测试中', '待上线', '已上线', '已交付', '已验收']

const isStepCompleted = (stepStatus: string) => {
  const currentStatus = currentRequirement.value?.status
  if (currentStatus === '已拒绝') {
    return lifecycleOrder.indexOf(stepStatus) < lifecycleOrder.indexOf('评估中')
  }
  const currentIndex = lifecycleOrder.indexOf(currentStatus || '')
  const stepIndex = lifecycleOrder.indexOf(stepStatus)
  return stepIndex < currentIndex
}

const getCurrentActions = () => {
  return getRequirementStageActions({
    status: currentRequirement.value?.status,
    type: currentRequirement.value?.type,
    hasEvaluation: currentRequirement.value?.status === '评估中'
  })
}

const executeAction = (_type: string) => {
  if (!currentRequirement.value) return

  const targetUrl = `/requirements-standalone/${currentRequirement.value.id}`
  window.open(targetUrl, '_blank')
}

const openItemDetail = (item: Requirement) => {
  if (item.readOnly) {
    currentRequirement.value = item
    showDetailDrawer.value = true
    return
  }
  const id = item.id.startsWith('local:') ? item.id.slice('local:'.length) : item.id
  window.open(`/requirements-standalone/${id}`, '_blank')
}

const getRowActions = (item: Requirement) => item.readOnly ? [] : getRequirementStageActions({
  status: item.status,
  type: item.type,
  hasEvaluation: item.status === '评估中'
})

const withRowAction = async (key: string, runner: () => Promise<void>) => {
  actionLoading.value = key
  try {
    await runner()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败，请重试')
  } finally {
    actionLoading.value = ''
  }
}

const isActionBusy = (itemId: string, actionType: string) => {
  return actionLoading.value === `${actionType}-${itemId}`
    || actionLoading.value === `plan-${itemId}`
    || actionLoading.value === `eval-${itemId}`
    || actionLoading.value === `task-${itemId}`
    || actionLoading.value === `confirm-${itemId}`
    || actionLoading.value === `deliver-${itemId}`
    || actionLoading.value === `accept-${itemId}`
}

const confirmRowAction = async (message: string) => {
  try {
    await ElMessageBox.confirm(message, '提示', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}

const openDesignPlannerForRequirement = async (item: Requirement) => {
  plannerRequirement.value = item
  try {
    plannerPlans.value = await api.getDesignWorkLogs(Number(item.id))
  } catch {
    plannerPlans.value = []
  }
  showDesignPlanner.value = true
}

const saveDesignPlanner = async (items: Array<{
  workType: string
  designerId: number
  estimatedHours?: number
  plannedCompletedAt?: string
}>) => {
  if (!plannerRequirement.value) return
  const requirementId = Number(plannerRequirement.value.id)

  await withRowAction(`plan-${plannerRequirement.value.id}`, async () => {
    const existingByType = new Map((plannerPlans.value || []).map((log: any) => [log.workType, log]))
    const selectedTypes = new Set(items.map(item => item.workType))

    for (const item of items) {
      const existing = existingByType.get(item.workType)
      if (existing) {
        await api.updateDesignWorkLog(existing.id, {
          designerId: item.designerId,
          estimatedHours: item.estimatedHours,
          plannedCompletedAt: item.plannedCompletedAt,
          status: existing.status || '待开始'
        })
      } else {
        await api.createDesignWorkLog({
          requirementId,
          workType: item.workType,
          designerId: item.designerId,
          estimatedHours: item.estimatedHours,
          plannedCompletedAt: item.plannedCompletedAt,
          status: '待开始'
        })
      }
    }

    for (const log of plannerPlans.value || []) {
      if (!selectedTypes.has(log.workType)) {
        await api.deleteDesignWorkLog(log.id)
      }
    }

    showDesignPlanner.value = false
    plannerRequirement.value = null
    plannerPlans.value = []
    await loadData()
    ElMessage.success('设计规划已保存')
  })
}

const runSimpleStageAction = async (item: Requirement, action: string, message: string, success: string) => {
  if (!await confirmRowAction(message)) return
  await withRowAction(`${action}-${item.id}`, async () => {
    await api.executeRequirementStageAction(Number(item.id), action)
    await loadData()
    ElMessage.success(success)
  })
}

const runBuDecision = async (item: Requirement, decision: string, success: string) => {
  if ((decision === '通过' || decision === '转产品需求') && item.status === '评估中') {
    const existingPlans = await api.getDesignWorkLogs(Number(item.id)).catch(() => [])
    if (!existingPlans.length) {
      await openDesignPlannerForRequirement(item)
      ElMessage.warning('请先配置设计规划')
      return
    }
  }

  if (!await confirmRowAction(`确认执行“${decision}”吗？`)) return
  await withRowAction(`${decision}-${item.id}`, async () => {
    await api.submitBuDecision({ requirementId: Number(item.id), decision, decisionReason: '' })
    await loadData()
    ElMessage.success(success)
  })
}

const runAddTask = async (item: Requirement) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入任务标题', '添加任务', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：联调订单接口'
    })
    if (!value?.trim()) return

    await withRowAction(`task-${item.id}`, async () => {
      await api.createTask({
        requirementId: Number(item.id),
        title: value.trim(),
        assigneeId: currentUserId.value,
        createdBy: currentUserId.value,
        taskType: '开发任务'
      })
      await loadData()
      ElMessage.success('任务已创建')
    })
  } catch {
    // 用户取消
  }
}

const executeRowAction = async (item: Requirement, action: string) => {
  currentRequirement.value = item

  switch (action) {
    case 'submit_eval':
      evaluationForm.isFeasible = true
      evaluationForm.estimatedWorkload = ''
      evaluationForm.estimatedCost = ''
      evaluationForm.estimatedOnlineDate = ''
      showEvaluationModal.value = true
      return
    case 'plan_design':
      await openDesignPlannerForRequirement(item)
      return
    case 'start_eval':
      await runSimpleStageAction(item, action, '确认开始评估吗？', '需求已进入评估中')
      return
    case 'reject':
      if (item.status === '评估中') {
        await runBuDecision(item, '拒绝', '需求已标记为已拒绝')
        return
      }
      await runSimpleStageAction(item, action, '确认标记为已拒绝吗？', '需求已标记为已拒绝')
      return
    case 'approve':
      await runBuDecision(item, '通过', '评估通过，需求已进入待设计')
      return
    case 'convert':
      await runBuDecision(item, '转产品需求', '需求已转为产品需求并进入待设计')
      return
    case 'start_design': {
      const existingPlans = await api.getDesignWorkLogs(Number(item.id)).catch(() => [])
      if (!existingPlans.length) {
        await openDesignPlannerForRequirement(item)
        ElMessage.warning('请先配置设计规划')
        return
      }
      await runSimpleStageAction(item, action, '确认进入设计阶段吗？', '已进入设计中')
      return
    }
    case 'customer_confirm':
      await withRowAction(`confirm-${item.id}`, async () => {
        await api.createRequirementConfirmation({
          requirementId: Number(item.id),
          confirmationType: '客户确认',
          confirmedBy: currentUserId.value,
          confirmationNotes: ''
        })
        await loadData()
        ElMessage.success('客户确认完成')
      })
      return
    case 'internal_confirm':
      await withRowAction(`confirm-${item.id}`, async () => {
        await api.createRequirementConfirmation({
          requirementId: Number(item.id),
          confirmationType: '内部确认',
          confirmedBy: currentUserId.value,
          confirmationNotes: ''
        })
        await loadData()
        ElMessage.success('内部确认完成')
      })
      return
    case 'add_task':
      await runAddTask(item)
      return
    case 'start_test':
      await runSimpleStageAction(item, action, '确认提测吗？', '需求已进入测试中')
      return
    case 'test_pass':
      await runSimpleStageAction(item, action, '确认测试通过吗？', '测试通过，需求已进入待上线')
      return
    case 'test_fail':
      await runSimpleStageAction(item, action, '确认测试失败并退回开发吗？', '需求已退回开发中')
      return
    case 'go_online':
      await runSimpleStageAction(item, action, '确认上线吗？', '需求已确认上线')
      return
    case 'deliver':
      await withRowAction(`deliver-${item.id}`, async () => {
        await api.createRequirementDelivery({
          requirementId: Number(item.id),
          deliveredBy: currentUserId.value,
          deliveryNotes: ''
        })
        await loadData()
        ElMessage.success('已完成交付')
      })
      return
    case 'accept':
      await withRowAction(`accept-${item.id}`, async () => {
        await api.acceptRequirementDelivery(Number(item.id), {
          acceptedBy: currentUserId.value,
          acceptanceNotes: ''
        })
        await loadData()
        ElMessage.success('已完成验收')
      })
      return
  }
}

const submitEvaluation = async () => {
  if (!currentRequirement.value) return
  if (!evaluationForm.estimatedWorkload) {
    ElMessage.warning('请输入预估工时')
    return
  }
  if (currentRequirement.value.type === '项目需求' && !evaluationForm.estimatedCost) {
    ElMessage.warning('项目需求请输入预估报价')
    return
  }

  await withRowAction(`eval-${currentRequirement.value.id}`, async () => {
    await api.submitRequirementEvaluation({
      requirementId: Number(currentRequirement.value!.id),
      isFeasible: evaluationForm.isFeasible ? 1 : 0,
      estimatedWorkload: Number(evaluationForm.estimatedWorkload),
      estimatedCost: currentRequirement.value!.type === '项目需求' ? Number(evaluationForm.estimatedCost) : undefined,
      suggestProduct: 0
    })
    showEvaluationModal.value = false
    await loadData()
    ElMessage.success('评估已提交')
  })
}

const createRequirement = async () => {
  if (!newRequirement.title) {
    ElMessage.warning('请输入需求标题')
    return
  }
  if (!newRequirement.project) {
    ElMessage.warning('请选择所属项目')
    return
  }

  try {
    // 查找项目ID和业务线ID
    const project = rawProjects.value.find((p: any) => p.name === newRequirement.project)
    const bl = rawBusinessLines.value.find((b: any) => b.name === newRequirement.businessLine)
    const subProject = newRequirement.subProject
      ? rawProjects.value.find((p: any) => p.name === newRequirement.subProject)
      : null

    // 项目需求验证客户联系人
    if (newRequirement.type === '项目需求' && !newRequirement.customerContactId) {
      ElMessage.warning('项目需求必须选择客户联系人')
      return
    }

    // 调用后端API创建需求
    const apiData: any = {
      title: newRequirement.title,
      type: newRequirement.type,
      priority: newRequirement.priority,
      status: '待评估',
      source: newRequirement.source,
      description: sanitizeHtml(newRequirement.description || (editorRef.value?.innerHTML || '')),
      projectId: subProject?.id || project?.id || null,
      businessLineId: bl?.id || project?.businessLineId || null,
      expectedOnlineDate: newRequirement.expectDate || null,
      customerContactId: newRequirement.type === '项目需求' ? newRequirement.customerContactId : null
    }

    await api.createRequirement(apiData)

    showCreateModal.value = false

    // 重置表单
    newRequirement.title = ''
    newRequirement.businessLine = ''
    newRequirement.project = ''
    newRequirement.subProject = ''
    newRequirement.source = '内部'
    newRequirement.requester = ''
    newRequirement.customerContactId = undefined
    newRequirement.type = '产品需求'
    newRequirement.priority = '中'
    newRequirement.expectDate = ''
    newRequirement.owner = ''
    newRequirement.description = ''
    customerContacts.value = []
    if (editorRef.value) {
      editorRef.value.innerHTML = ''
    }

    // 重新加载数据以显示新创建的需求
    await loadData()
  } catch (error) {
    console.error('创建需求失败:', error)
    ElMessage.error('创建需求失败，请重试')
  }
}

watch(
  () => newRequirement.type,
  async type => {
    if (type === '项目需求') {
      newRequirement.requester = ''
      if (newRequirement.project) {
        const project = rawProjects.value.find((item: any) => item.name === newRequirement.project)
        await loadCustomerContacts(project?.id)
      }
      return
    }

    newRequirement.customerContactId = undefined
    customerContacts.value = []
  }
)

const getStatusBadgeClass = (status: string) => {
  const map: Record<string, string> = {
    '待评估': 'gray', '评估中': 'blue', '待设计': 'yellow', '设计中': 'green',
    '待确认': 'yellow', '开发中': 'red', '测试中': 'purple', '待上线': 'purple',
    '已上线': 'green', '已交付': 'green', '已验收': 'green', '已拒绝': 'gray'
  }
  return map[status] || 'gray'
}
</script>

<template>
  <div class="requirements-page">
    <!-- 页面标题 + 操作按钮 -->
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">需求列表</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ filteredData.length }}</span>
            <span class="stat-text">条</span>
          </span>
          <span class="stat-divider">|</span>
          <span class="inline-stat">
            <span class="stat-num green">{{ inProgressCount }}</span>
            <span class="stat-text">进行中</span>
          </span>
          <span class="stat-divider">|</span>
          <span class="inline-stat">
            <span class="stat-num yellow">{{ pendingCount }}</span>
            <span class="stat-text">待评估</span>
          </span>
          <span class="stat-divider" v-if="riskCount > 0">|</span>
          <span class="inline-stat" v-if="riskCount > 0">
            <span class="stat-num red">{{ riskCount }}</span>
            <span class="stat-text">逾期</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <div class="view-toggle">
          <button class="view-btn" :class="{ active: viewMode === 'table' }" @click="viewMode = 'table'">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="3" width="18" height="18" rx="2"/>
              <path d="M3 9h18M9 21V9"/>
            </svg>
            列表
          </button>
          <button class="view-btn" :class="{ active: viewMode === 'kanban' }" @click="viewMode = 'kanban'">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="3" width="5" height="18" rx="1"/>
              <rect x="10" y="3" width="5" height="12" rx="1"/>
              <rect x="17" y="3" width="5" height="15" rx="1"/>
            </svg>
            看板
          </button>
        </div>
        <button class="btn btn-default">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="7 10 12 15 17 10"/>
            <line x1="12" y1="15" x2="12" y2="3"/>
          </svg>
          导出
        </button>
        <button class="btn btn-primary" @click="showCreateModal = true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          新建需求
        </button>
      </div>
    </div>

    <WorkItemAnalysisPanel
      title="需求结构分析"
      subtitle="从状态、项目、来源和优先级识别需求结构与交付压力"
      :analysis="analysis"
      :sections="analysisSections"
      @select="handleAnalysisSelect"
    />

    <section class="requirement-detail-heading"><div><h2>需求明细</h2><p>保留完整业务操作，并支持从分析结果快速下钻</p></div></section>

    <!-- 项目选择器 -->
    <div class="project-selector">
      <div class="business-line-row">
        <div class="business-tabs">
          <button class="business-tab" :class="{ active: !selectedBusiness && selectedProjects.length === 0 }" @click="selectAll">全部</button>
          <button v-for="bl in businessLines" :key="bl.name" class="business-tab" :class="{ active: selectedBusiness === bl.name }" @click="selectBusiness(bl.name)">
            {{ bl.name }}
            <span style="color: var(--gray-400); margin-left: 4px;">({{ bl.totalCount }})</span>
          </button>
        </div>
        <input type="text" class="search-input" v-model="filters.search" placeholder="搜索需求...">
      </div>

      <!-- 筛选栏 -->
      <div class="filter-row">
        <div class="filter-pills source-filter" aria-label="需求来源筛选">
          <button class="filter-pill" :class="{ active: !filters.dataSource }" @click="filters.dataSource = ''">全部来源</button>
          <button class="filter-pill" :class="{ active: filters.dataSource === 'LOCAL' }" @click="filters.dataSource = 'LOCAL'">本地</button>
          <button class="filter-pill" :class="{ active: filters.dataSource === 'YUNXIAO' }" @click="filters.dataSource = 'YUNXIAO'">云效</button>
          <button class="filter-pill reset" @click="clearAllFilters">重置</button>
        </div>
        <!-- 状态下拉多选 -->
        <div class="filter-dropdown" ref="statusDropdownRef">
          <button class="filter-dropdown-btn" :class="{ active: filters.status.length > 0 }" @click="toggleStatusDropdown">
            {{ getStatusDisplay }}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>
          <div class="filter-dropdown-menu" v-if="showStatusDropdown">
            <div class="filter-dropdown-header">
              <span>选择状态</span>
              <button class="filter-dropdown-clear" @click="clearStatusFilter" v-if="filters.status.length > 0">清除</button>
            </div>
            <div class="filter-dropdown-options">
              <label v-for="s in statusOptions" :key="s.value" class="filter-dropdown-option">
                <input type="checkbox" :checked="filters.status.includes(s.value)" @change="toggleStatus(s.value)">
                <span class="status-dot" :class="s.class"></span>
                <span>{{ s.label }}</span>
              </label>
            </div>
          </div>
        </div>
        <div class="filter-pills">
          <span class="filter-label">类型</span>
          <button v-for="t in typeOptions" :key="t.value" class="filter-pill" :class="{ active: filters.type === t.value, [t.class || '']: true }" @click="toggleFilter('type', t.value)">
            {{ t.label }}
          </button>
        </div>
        <div class="filter-pills">
          <span class="filter-label">优先级</span>
          <button v-for="p in priorityOptions.slice(1)" :key="p.value" class="filter-pill" :class="{ active: filters.priority === p.value, [p.class || '']: true }" @click="toggleFilter('priority', p.value)">
            {{ p.label }}
          </button>
        </div>
      </div>

      <!-- 项目网格 -->
      <div class="project-grid">
        <div v-for="project in visibleProjects" :key="project.name" class="project-card" :class="{ selected: selectedProjects.includes(project.name) }" @click="toggleProject(project.name)">
          <div class="project-logo" :style="{ background: logoColors[project.name] || '#F1F5F9' }">
            {{ project.logo }}
          </div>
          <div class="project-info">
            <span class="project-card-name">
              {{ project.name }}
              <span class="project-card-count">({{ project.count }})</span>
            </span>
            <div class="project-card-sub" v-if="project.subProjects && project.subProjects.length">
              <span v-for="sub in project.subProjects" :key="sub.id || sub.name" class="sub-tag" :class="{ selected: selectedSubProjects.includes(sub.name) }" @click.stop="toggleSubProject(sub.name)">
                {{ sub.name }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 表格视图 -->
    <div class="table-card" v-if="viewMode === 'table'">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 100px">需求编号</th>
            <th>需求标题</th>
            <th style="width: 140px">所属项目</th>
            <th style="width: 80px">类型</th>
            <th style="width: 100px">状态</th>
            <th style="width: 70px">优先级</th>
            <th style="width: 80px">负责人</th>
            <th style="width: 100px">创建时间</th>
            <th style="width: 120px">计划完成</th>
            <th style="width: 240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredData" :key="item.recordKey || item.id" @click="handleRowClick(item.id)" @dblclick="handleRowDoubleClick(item.id)" style="cursor:pointer">
            <td><span class="req-no">{{ item.reqNo }}</span></td>
            <td class="highlight">
              <div class="requirement-title-cell">
                <span>{{ item.title }}</span>
                <span v-if="item.dataSource === 'YUNXIAO'" class="source-badge yunxiao">云效</span>
                <span v-else class="source-badge local">本地</span>
                <span v-if="item.linkedYunxiaoSerialNumber" class="linked-badge">已关联 {{ item.linkedYunxiaoSerialNumber }}</span>
              </div>
            </td>
            <td style="white-space: nowrap;">
              <span class="project-tag">{{ item.project }}</span>
              <span v-if="item.subProject" style="color: var(--gray-400);"> - {{ item.subProject }}</span>
            </td>
            <td style="font-size: 13px; white-space: nowrap;">{{ item.type }}</td>
            <td style="white-space: nowrap;"><span :class="['status-badge', getStatusBadgeClass(item.status)]">{{ item.status }}</span></td>
            <td style="white-space: nowrap;"><span :class="['priority-text', item.priorityClass]">{{ item.priority }}</span></td>
            <td style="font-size: 13px;">{{ item.owner }}</td>
            <td><span class="created-date">{{ item.createdAt || '—' }}</span></td>
            <td>
              <div class="due-date-cell" :class="{ overdue: item.overdueIncomplete }">
                <span>{{ item.dueDate || item.expectDate || '未设置计划' }}</span>
                <small v-if="item.overdueIncomplete" class="overdue-pill">超期 {{ item.overdueDays }} 天</small>
              </div>
            </td>
            <td>
              <div class="row-action-bar" @click.stop>
                <button class="mini-link" @click.stop="openItemDetail(item)">详情</button>
                <button
                  v-for="action in getRowActions(item)"
                  :key="`${item.id}-${action.type}`"
                  :class="['row-action-btn', action.class]"
                  :disabled="isActionBusy(item.id, action.type)"
                  @click.stop="executeRowAction(item, action.type)"
                >
                  {{ action.label }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 看板视图 -->
    <div class="kanban-board" v-if="viewMode === 'kanban'">
      <div class="kanban-column" v-for="column in kanbanColumns" :key="column.status">
        <div class="kanban-header">
          <div class="kanban-title">{{ column.name }}</div>
          <div class="kanban-count">{{ column.cards.length }}</div>
        </div>
        <div class="kanban-cards">
          <div class="kanban-card" v-for="card in column.cards" :key="card.id" @click="handleRowClick(card.id)" @dblclick="handleRowDoubleClick(card.id)">
            <div class="kanban-card-no">{{ card.reqNo }}</div>
            <div class="kanban-card-title">{{ card.title }}</div>
            <div class="kanban-card-footer">
              <span class="kanban-card-project">{{ card.project }}</span>
              <span class="kanban-card-sub" v-if="card.subProject">{{ card.subProject }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建需求弹窗 -->
    <div class="modal-overlay" v-if="showCreateModal" @click.self="showCreateModal = false">
      <div class="modal create-modal">
        <div class="modal-header">
          <h3 class="modal-title">新建需求</h3>
          <button class="modal-close" @click="showCreateModal = false">×</button>
        </div>
        <div class="modal-body">
          <!-- 左侧表单 -->
          <div class="form-left">
            <!-- 需求标题 -->
            <div class="form-group">
              <label class="form-label">需求标题 <span class="required">*</span></label>
              <input type="text" class="form-input" v-model="newRequirement.title" placeholder="请输入需求标题">
            </div>

            <!-- 项目选择 -->
            <div class="form-group">
              <label class="form-label">所属项目 <span class="required">*</span></label>
              <div class="project-selector-modal">
                <div class="business-tabs-modal">
                  <button
                    class="business-tab-modal"
                    :class="{ active: newRequirement.businessLine === '' }"
                    @click="newRequirement.businessLine = ''; newRequirement.project = ''; newRequirement.subProject = ''"
                  >全部</button>
                  <button
                    v-for="bl in businessLines"
                    :key="bl.name"
                    class="business-tab-modal"
                    :class="{ active: newRequirement.businessLine === bl.name }"
                    @click="newRequirement.businessLine = bl.name; newRequirement.project = ''; newRequirement.subProject = ''"
                  >{{ bl.name }}</button>
                </div>
                <div class="project-cards-modal">
                  <div
                    v-for="project in modalProjects"
                    :key="project.name"
                    class="project-card-modal"
                    :class="{ selected: newRequirement.project === project.name }"
                    @click="newRequirement.project = project.name; newRequirement.subProject = ''; newRequirement.customerContactId = undefined; loadCustomerContacts(project.id)"
                  >
                    <span class="project-name">{{ project.name }}</span>
                    <span v-if="project.subProjects && project.subProjects.length" class="sub-count">{{ project.subProjects.length }}个子项目</span>
                    <div v-if="project.subProjects && project.subProjects.length && newRequirement.project === project.name" class="sub-tags-modal">
                      <span
                        v-for="sub in project.subProjects"
                        :key="sub.id || sub.name"
                        class="sub-tag-modal"
                        :class="{ selected: newRequirement.subProject === sub.name }"
                        @click.stop="newRequirement.subProject = newRequirement.subProject === sub.name ? '' : sub.name"
                      >{{ sub.name }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 需求描述 -->
            <div class="form-group">
              <label class="form-label">需求描述</label>
              <div class="rich-editor">
                <div class="editor-toolbar">
                  <button type="button" @click="execCommand('bold')" title="加粗"><b>B</b></button>
                  <button type="button" @click="execCommand('italic')" title="斜体"><i>I</i></button>
                  <button type="button" @click="execCommand('underline')" title="下划线"><u>U</u></button>
                  <span class="toolbar-divider"></span>
                  <button type="button" @click="execCommand('insertUnorderedList')" title="无序列表">• 列表</button>
                  <button type="button" @click="execCommand('insertOrderedList')" title="有序列表">1. 列表</button>
                </div>
                <div
                  class="editor-content"
                  contenteditable="true"
                  ref="editorRef"
                  @input="newRequirement.description = ($event.target as HTMLElement).innerHTML"
                ></div>
              </div>
            </div>
          </div>

          <!-- 右侧属性 -->
          <div class="form-right">
            <div class="form-group spotlight-group">
              <label class="form-label">需求类型 <span class="required">*</span></label>
              <div class="radio-group vertical">
                <label class="radio-item" :class="{ active: newRequirement.type === '产品需求' }">
                  <input type="radio" v-model="newRequirement.type" value="产品需求">
                  <span>产品需求</span>
                </label>
                <label class="radio-item" :class="{ active: newRequirement.type === '项目需求' }">
                  <input type="radio" v-model="newRequirement.type" value="项目需求">
                  <span>项目需求</span>
                </label>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">需求来源</label>
              <div class="radio-group">
                <label class="radio-item" :class="{ active: newRequirement.source === '内部' }">
                  <input type="radio" v-model="newRequirement.source" value="内部">
                  <span>内部</span>
                </label>
                <label class="radio-item" :class="{ active: newRequirement.source === '外部' }">
                  <input type="radio" v-model="newRequirement.source" value="外部">
                  <span>外部</span>
                </label>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">{{ actorFieldLabel }} <span v-if="isProjectRequirement" class="required">*</span></label>
              <template v-if="isProjectRequirement">
                <div class="field-shell select-shell">
                  <select class="form-select polished-select" v-model="newRequirement.customerContactId">
                    <option :value="undefined">请选择客户信息</option>
                    <option v-for="c in customerContacts" :key="c.id" :value="c.id">{{ c.name }}{{ c.company ? ` - ${c.company}` : '' }}</option>
                  </select>
                </div>
                <span v-if="customerContacts.length === 0 && !loadingContacts" class="form-tip">该项目暂无客户联系人，请先在系统中添加</span>
              </template>
              <input v-else type="text" class="form-input polished-input" v-model="newRequirement.requester" placeholder="请输入提出人">
            </div>

            <div class="form-group">
              <label class="form-label">优先级</label>
              <div class="radio-group">
                <label class="radio-item priority-high" :class="{ active: newRequirement.priority === '高' }">
                  <input type="radio" v-model="newRequirement.priority" value="高">
                  <span>高</span>
                </label>
                <label class="radio-item priority-medium" :class="{ active: newRequirement.priority === '中' }">
                  <input type="radio" v-model="newRequirement.priority" value="中">
                  <span>中</span>
                </label>
                <label class="radio-item priority-low" :class="{ active: newRequirement.priority === '低' }">
                  <input type="radio" v-model="newRequirement.priority" value="低">
                  <span>低</span>
                </label>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">期望上线时间</label>
              <div class="field-shell date-shell">
                <input type="date" class="form-input polished-input date-input" v-model="newRequirement.expectDate">
              </div>
            </div>

            <div class="form-group">
              <label class="form-label">负责人</label>
              <div class="field-shell select-shell">
                <select class="form-select polished-select" v-model="newRequirement.owner">
                  <option value="">请选择负责人</option>
                  <option v-for="u in allUsers" :key="u.id" :value="u.realName">{{ u.realName }}</option>
                </select>
              </div>
            </div>

          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="showCreateModal = false">取消</button>
          <button class="btn btn-primary" @click="createRequirement">创建需求</button>
        </div>
      </div>
    </div>

    <!-- 需求详情抽屉 -->
    <div class="detail-drawer" v-if="showDetailDrawer" @click.self="showDetailDrawer = false">
      <div class="drawer-panel">
        <div class="drawer-header">
          <div class="drawer-title">
            <span class="req-no">{{ currentRequirement?.reqNo }}</span>
            <span :class="['status-badge', getStatusBadgeClass(currentRequirement?.status || '')]">{{ currentRequirement?.status }}</span>
          </div>
          <div class="drawer-actions">
            <button v-if="!currentRequirement?.readOnly" class="btn btn-sm btn-default" @click="openInNewPage">新窗口打开</button>
            <button class="drawer-close" @click="showDetailDrawer = false">×</button>
          </div>
        </div>
        <div class="drawer-body">
          <!-- 状态流转进度 -->
          <div v-if="!currentRequirement?.readOnly" class="status-flow">
            <div v-for="(step, index) in statusFlowSteps" :key="step.status" class="status-flow-item" :class="{ completed: isStepCompleted(step.status), current: currentRequirement?.status === step.status }">
              <div class="status-dot">{{ index + 1 }}</div>
              <div class="status-label">{{ step.label }}</div>
            </div>
          </div>

          <!-- 操作面板 -->
          <div v-if="currentRequirement?.readOnly" class="readonly-note">只读数据，以云效为准</div>

          <div class="action-panel" v-if="!currentRequirement?.readOnly && getCurrentActions().length > 0">
            <div class="action-panel-title">可执行操作</div>
            <div class="action-buttons">
              <button v-for="action in getCurrentActions()" :key="action.type" :class="['action-btn', action.class]" @click="executeAction(action.type)">
                {{ action.label }}
              </button>
            </div>
          </div>

          <!-- 基本信息 -->
          <div class="detail-section">
            <h3 class="detail-section-title">基本信息</h3>
            <div class="detail-row">
              <div class="detail-item full">
                <label>需求标题</label>
                <div class="detail-value">{{ currentRequirement?.title }}</div>
              </div>
            </div>
            <div class="detail-row two-cols">
              <div class="detail-item">
                <label>所属业务线</label>
                <div class="detail-value">{{ currentRequirement?.businessLine }}</div>
              </div>
              <div class="detail-item">
                <label>所属项目</label>
                <div class="detail-value">{{ currentRequirement?.project }}<span v-if="currentRequirement?.subProject" class="sub-name"> - {{ currentRequirement?.subProject }}</span></div>
              </div>
            </div>
            <div class="detail-row two-cols">
              <div class="detail-item">
                <label>需求类型</label>
                <div class="detail-value">{{ currentRequirement?.type }}</div>
              </div>
              <div class="detail-item">
                <label>优先级</label>
                <div class="detail-value"><span :class="['priority-text', currentRequirement?.priorityClass]">{{ currentRequirement?.priority }}</span></div>
              </div>
            </div>
            <div class="detail-row two-cols">
              <div class="detail-item">
                <label>需求来源</label>
                <div class="detail-value">{{ currentRequirement?.source || '内部' }}</div>
              </div>
              <div class="detail-item">
                <label>提出人 / 联系人</label>
                <div class="detail-value">{{ currentRequirement?.requester || currentRequirement?.owner || '-' }}</div>
              </div>
            </div>
            <div class="detail-row two-cols">
              <div class="detail-item">
                <label>负责人</label>
                <div class="detail-value">{{ currentRequirement?.owner || '-' }}</div>
              </div>
              <div class="detail-item">
                <label>计划完成</label>
                <div class="detail-value">{{ currentRequirement?.dueDate || currentRequirement?.expectDate || '未设置' }}</div>
              </div>
            </div>
          </div>

          <!-- 评估信息 -->
          <div class="detail-section" v-if="showEvaluation">
            <h3 class="detail-section-title">评估信息</h3>
            <div class="evaluation-info">
              <div class="evaluation-item">
                <span class="evaluation-label">技术可行性</span>
                <span class="evaluation-value" :class="currentRequirement?.evaluation?.isFeasible ? 'success' : 'danger'">
                  {{ currentRequirement?.evaluation?.isFeasible ? '可行' : '不可行' }}
                </span>
              </div>
              <div class="evaluation-item">
                <span class="evaluation-label">预估工时</span>
                <span class="evaluation-value">{{ currentRequirement?.evaluation?.estimatedWorkload || '-' }}</span>
              </div>
              <div class="evaluation-item" v-if="currentRequirement?.type === '项目需求'">
                <span class="evaluation-label">预估报价</span>
                <span class="evaluation-value">{{ currentRequirement?.evaluation?.estimatedCost || '-' }}</span>
              </div>
              <div class="evaluation-item">
                <span class="evaluation-label">预估上线</span>
                <span class="evaluation-value">{{ currentRequirement?.evaluation?.estimatedOnlineDate || '-' }}</span>
              </div>
              <div class="evaluation-item">
                <span class="evaluation-label">评估人</span>
                <span class="evaluation-value">{{ currentRequirement?.evaluation?.evaluator || '-' }}</span>
              </div>
            </div>
          </div>

          <!-- 开发任务 -->
          <div class="detail-section" v-if="showTasks">
            <h3 class="detail-section-title">开发任务</h3>
            <div class="task-list">
              <div class="task-item" v-for="task in currentRequirement?.tasks" :key="task.id">
                <div class="task-checkbox" :class="{ done: task.status === '已完成' }">
                  <svg v-if="task.status === '已完成'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                    <polyline points="20 6 9 17 4 12"/>
                  </svg>
                </div>
                <div class="task-info">
                  <div class="task-title" :class="{ done: task.status === '已完成' }">{{ task.title }}</div>
                  <div class="task-meta">{{ task.type }} | {{ task.estimatedHours || '-' }}h</div>
                </div>
                <div class="task-assignee">{{ task.assignee?.charAt(0) }}</div>
              </div>
              <div v-if="!currentRequirement?.tasks?.length" class="no-data">暂无任务</div>
            </div>
          </div>

          <!-- 需求描述 -->
          <div class="detail-section">
            <h3 class="detail-section-title">需求描述</h3>
            <div class="detail-description" v-html="safeCurrentRequirementDescription"></div>
          </div>

          <!-- 附件 -->
          <div class="detail-section">
            <h3 class="detail-section-title">附件</h3>
            <div class="detail-attachments">
              <div class="attachment-item" v-for="att in currentRequirement?.attachments" :key="att.id">
                <span class="attachment-icon">{{ att.type === 'image' ? '🖼️' : '📄' }}</span>
                <span class="attachment-name">{{ att.name }}</span>
                <span class="attachment-size">{{ att.size }}</span>
              </div>
              <div v-if="!currentRequirement?.attachments?.length" class="no-data">暂无附件</div>
            </div>
          </div>

          <!-- 活动日志 -->
          <div class="detail-section">
            <h3 class="detail-section-title">活动日志</h3>
            <div class="timeline">
              <div class="timeline-item" v-for="log in currentRequirement?.logs" :key="log.id">
                <div class="timeline-dot"></div>
                <div class="timeline-content">
                  <div class="timeline-text">{{ log.action }}</div>
                  <div class="timeline-meta">
                    <span class="timeline-user">{{ log.user }}</span>
                    <span class="timeline-time">{{ log.time }}</span>
                  </div>
                </div>
              </div>
              <div v-if="!currentRequirement?.logs?.length" class="no-data">暂无日志记录</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 评估表单弹窗 -->
    <div class="modal-overlay" v-if="showEvaluationModal" @click.self="showEvaluationModal = false">
      <div class="modal" style="width: 480px;">
        <div class="modal-header">
          <h3 class="modal-title">提交评估</h3>
          <button class="modal-close" @click="showEvaluationModal = false">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">技术可行性 <span class="required">*</span></label>
            <div class="radio-group">
              <label class="radio-item" :class="{ active: evaluationForm.isFeasible }">
                <input type="radio" v-model="evaluationForm.isFeasible" :value="true">
                <span>可行</span>
              </label>
              <label class="radio-item" :class="{ active: !evaluationForm.isFeasible }">
                <input type="radio" v-model="evaluationForm.isFeasible" :value="false">
                <span>不可行</span>
              </label>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">预估工时 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <input type="number" class="form-input" v-model="evaluationForm.estimatedWorkload" placeholder="请输入" min="1" style="flex: 1;">
              <span style="color: var(--gray-500); font-size: 13px;">人天</span>
            </div>
          </div>
          <div class="form-group" v-if="currentRequirement?.type === '项目需求'">
            <label class="form-label">预估报价 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <span style="color: var(--gray-500); font-size: 13px;">¥</span>
              <input type="number" class="form-input" v-model="evaluationForm.estimatedCost" placeholder="请输入" min="0" style="flex: 1;">
              <span style="color: var(--gray-500); font-size: 13px;">元</span>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">预估上线时间 <span class="required">*</span></label>
            <input type="date" class="form-input" v-model="evaluationForm.estimatedOnlineDate">
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="showEvaluationModal = false">取消</button>
          <button class="btn btn-primary" @click="submitEvaluation">提交评估</button>
        </div>
      </div>
    </div>

    <RequirementDesignPlannerDialog
      v-model="showDesignPlanner"
      :users="allUsers"
      :plans="plannerPlans"
      @save="saveDesignPlanner"
    />
  </div>
</template>

<style scoped>
.requirements-page {
  width: 100%;
  min-width: 0;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.title-with-stats {
  display: flex;
  align-items: center;
  gap: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.inline-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 16px;
  border-left: 1px solid var(--gray-200);
}

.inline-stat {
  display: flex;
  align-items: center;
  gap: 4px;
}

.stat-num {
  font-size: 16px;
  font-weight: 700;
  color: var(--gray-800);
}

.stat-num.green { color: var(--success); }
.stat-num.yellow { color: var(--warning); }
.stat-num.red { color: var(--danger); }

.stat-text {
  font-size: 13px;
  color: var(--gray-500);
}

.stat-divider {
  color: var(--gray-300);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.view-toggle {
  display: flex;
  background: var(--gray-100);
  border-radius: var(--radius-md);
  padding: 3px;
}

.view-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: none;
  background: transparent;
  border-radius: var(--radius-sm);
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
  box-shadow: var(--shadow-sm);
}

.view-btn svg {
  width: 16px;
  height: 16px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.15s ease;
}

.btn svg {
  width: 16px;
  height: 16px;
}

.btn-primary {
  background: var(--primary);
  color: white;
}

.btn-primary:hover {
  background: var(--primary-dark);
}

.btn-default {
  background: white;
  color: var(--gray-700);
  border: 1px solid var(--gray-200);
}

.btn-default:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.btn-sm {
  padding: 6px 12px;
  font-size: 13px;
}

/* 项目选择器 */
.project-selector {
  background: #FFFFFF;
  border-radius: var(--radius-md);
  padding: 12px 16px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.business-line-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.business-tabs {
  display: flex;
  gap: 4px;
}

.business-tab {
  padding: 5px 12px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-500);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.business-tab:hover {
  color: var(--gray-700);
  background: var(--gray-100);
}

.business-tab.active {
  color: var(--primary);
  background: var(--primary-light);
}

.search-input {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 13px;
  outline: none;
  width: 180px;
}

.search-input:focus {
  border-color: var(--primary);
}

.filter-row {
  display: flex;
  flex-wrap: nowrap;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--gray-100);
  overflow: visible;
  position: relative;
  z-index: 50;
}

.filter-pills {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.filter-label {
  font-size: 11px;
  color: var(--gray-500);
  margin-right: 2px;
}

.filter-pill {
  padding: 3px 8px;
  border-radius: 14px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid var(--gray-200);
  background: #FFFFFF;
  color: var(--gray-600);
  cursor: pointer;
  transition: all 0.15s ease;
}

.filter-pill:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.filter-pill.active {
  border-color: var(--primary);
  background: var(--primary);
  color: white;
}

.filter-pill.active.yellow { background: var(--warning); border-color: var(--warning); }
.filter-pill.active.blue { background: var(--primary); border-color: var(--primary); }
.filter-pill.active.green { background: var(--success); border-color: var(--success); }
.filter-pill.active.red { background: var(--danger); border-color: var(--danger); }
.filter-pill.active.purple { background: #9333EA; border-color: #9333EA; }
.filter-pill.active.cyan { background: var(--info); border-color: var(--info); }
.filter-pill.active.gray { background: var(--gray-500); border-color: var(--gray-500); }

/* 下拉多选 */
.filter-dropdown {
  position: relative;
  flex-shrink: 0;
}

.filter-dropdown-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 14px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid var(--gray-200);
  background: #FFFFFF;
  color: var(--gray-600);
  cursor: pointer;
  transition: all 0.15s ease;
}

.filter-dropdown-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.filter-dropdown-btn.active {
  border-color: var(--primary);
  background: var(--primary-light);
  color: var(--primary);
}

.filter-dropdown-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  min-width: 200px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  z-index: 1000;
}

.filter-dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--gray-100);
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-700);
}

.filter-dropdown-clear {
  font-size: 11px;
  color: var(--primary);
  background: none;
  border: none;
  cursor: pointer;
}

.filter-dropdown-options {
  max-height: 240px;
  overflow-y: auto;
  padding: 4px 0;
}

.filter-dropdown-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12px;
  color: var(--gray-700);
}

.filter-dropdown-option:hover {
  background: var(--gray-50);
}

.filter-dropdown-option input {
  accent-color: var(--primary);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.yellow { background: var(--warning); }
.status-dot.blue { background: #3b82f6; }
.status-dot.green { background: var(--success); }
.status-dot.red { background: var(--danger); }
.status-dot.purple { background: #9333EA; }
.status-dot.cyan { background: var(--info); }
.status-dot.gray { background: var(--gray-400); }

.project-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.project-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
  padding: 6px 10px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
  background: #FFFFFF;
}

.project-card:hover {
  border-color: var(--primary);
  background: var(--primary-light);
}

.project-card.selected {
  border-color: var(--primary);
  background: var(--primary-light);
}

.project-logo {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.project-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.project-card-name {
  overflow: hidden;
  color: var(--gray-800);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-card-count {
  font-size: 10px;
  color: var(--gray-400);
}

.project-card-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.sub-tag {
  font-size: 10px;
  color: var(--gray-500);
  background: var(--gray-100);
  padding: 1px 5px;
  border-radius: 3px;
  cursor: pointer;
}

.sub-tag:hover {
  background: var(--gray-200);
}

.sub-tag.selected {
  background: var(--primary);
  color: white;
}

/* 表格 */
.table-card {
  background: #FFFFFF;
  border-radius: var(--radius-md);
  overflow: auto;
  box-shadow: var(--shadow-sm);
  max-height: calc(100vh - 380px);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  padding: 10px 12px;
  background: var(--gray-50);
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-600);
  text-align: left;
  border-bottom: 1px solid var(--gray-100);
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-table td {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--gray-700);
  border-bottom: 1px solid var(--gray-50);
  vertical-align: middle;
}

.data-table tbody tr:hover {
  background: var(--gray-50);
}

.requirement-detail-heading { margin: 20px 0 10px; }
.requirement-detail-heading h2 { margin: 0; font-size: 16px; letter-spacing: 0; }
.requirement-detail-heading p { margin: 3px 0 0; color: var(--gray-500); font-size: 12px; }

.row-action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.mini-link,
.row-action-btn {
  border: none;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.mini-link {
  background: var(--gray-100);
  color: var(--gray-700);
}

.row-action-btn.primary {
  background: rgba(79, 70, 229, 0.12);
  color: var(--primary);
}

.row-action-btn.secondary {
  background: var(--gray-100);
  color: var(--gray-700);
}

.row-action-btn.danger {
  background: rgba(220, 38, 38, 0.12);
  color: var(--danger);
}

.mini-link:disabled,
.row-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.req-no {
  font-size: 12px;
  color: var(--gray-400);
  font-family: monospace;
}

.highlight {
  font-weight: 500;
  color: var(--gray-800);
}

.created-date { color: #8a919b; font-size: 12px; font-variant-numeric: tabular-nums; }
.due-date-cell { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; color: #3f4650; font-size: 12px; font-variant-numeric: tabular-nums; }
.due-date-cell.overdue > span { color: #8d3e36; font-weight: 600; }
.overdue-pill { display: inline-flex; align-items: center; min-height: 20px; padding: 2px 7px; border: 1px solid #efc2ba; border-radius: 3px; background: #fff0ed; color: #a33f35; font-size: 10px; font-weight: 700; line-height: 1; white-space: nowrap; }
.requirement-title-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.source-badge,
.linked-badge {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.source-badge.local { color: #285fae; background: #e8f1ff; }
.source-badge.yunxiao,
.linked-badge { color: #765514; background: #fff3d1; }

.readonly-note {
  margin-bottom: 16px;
  padding: 9px 11px;
  border-left: 3px solid #d5a136;
  background: #fff8e5;
  color: #73561c;
  font-size: 13px;
}

.project-tag {
  display: inline-block;
  padding: 2px 10px;
  background: var(--primary-light);
  color: var(--primary);
  border-radius: 4px;
  font-size: 12px;
}

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.status-badge.gray { background: var(--gray-100); color: var(--gray-600); }
.status-badge.blue { background: #dbeafe; color: #1d4ed8; }
.status-badge.yellow { background: #fef3c7; color: #b45309; }
.status-badge.green { background: #d1fae5; color: #047857; }
.status-badge.red { background: #fee2e2; color: #dc2626; }
.status-badge.purple { background: #e9d5ff; color: #7c3aed; }
.status-badge.cyan { background: #cffafe; color: #0891b2; }

.priority-text {
  font-weight: 600;
  white-space: nowrap;
}

.priority-text.high { color: var(--danger); }
.priority-text.medium { color: var(--warning); }
.priority-text.low { color: var(--gray-400); }

/* 看板 */
.kanban-board {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 16px;
}

.kanban-column {
  flex: 0 0 260px;
  background: var(--gray-100);
  border-radius: var(--radius-md);
  padding: 12px;
  max-height: calc(100vh - 380px);
  display: flex;
  flex-direction: column;
}

.kanban-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding: 0 4px;
}

.kanban-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-800);
}

.kanban-count {
  width: 22px;
  height: 22px;
  border-radius: var(--radius-sm);
  background: var(--gray-200);
  color: var(--gray-600);
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.kanban-cards {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kanban-card {
  background: #FFFFFF;
  border-radius: var(--radius-sm);
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: var(--shadow-sm);
}

.kanban-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.kanban-card-no {
  font-size: 10px;
  color: var(--gray-400);
  font-family: monospace;
  margin-bottom: 4px;
}

.kanban-card-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-800);
  line-height: 1.4;
  margin-bottom: 8px;
}

.kanban-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
}

.kanban-card-project {
  font-size: 10px;
  color: var(--primary);
  background: var(--primary-light);
  padding: 2px 6px;
  border-radius: 3px;
}

.kanban-card-sub {
  font-size: 10px;
  color: var(--gray-500);
  background: var(--gray-100);
  padding: 2px 6px;
  border-radius: 3px;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #fff;
  border-radius: var(--radius-lg);
  max-width: 90vw;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal.create-modal {
  width: 900px;
  height: min(760px, 88vh);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-200);
  flex-shrink: 0;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--gray-800);
}

.modal-close {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--gray-500);
  font-size: 24px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-close:hover {
  background: var(--gray-100);
  color: var(--gray-700);
}

.modal-body {
  padding: 20px;
  display: flex;
  gap: 20px;
  flex: 1;
  overflow: auto;
  align-items: stretch;
}

.form-left {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.form-right {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid var(--gray-200);
  background: var(--gray-50);
  flex-shrink: 0;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-600);
}

.required {
  color: var(--danger);
}

.form-input,
.form-textarea {
  padding: 8px 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 14px;
  outline: none;
}

.form-input:focus,
.form-textarea:focus {
  border-color: var(--primary);
}

.form-textarea {
  resize: vertical;
  min-height: 80px;
}

.polished-input {
  height: 44px;
  border-radius: 14px;
  border-color: rgba(148, 163, 184, 0.22);
  background: linear-gradient(180deg, #ffffff, #f8fafc);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.field-shell {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 46px;
  border-radius: 15px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: linear-gradient(180deg, #ffffff, #f8fafc);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
  overflow: hidden;
}

.field-shell::after {
  content: '';
  position: absolute;
  right: 14px;
  width: 16px;
  height: 16px;
  opacity: 0.55;
  pointer-events: none;
  background-repeat: no-repeat;
  background-position: center;
}

.select-shell::after {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%23475569' stroke-width='1.8'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
}

.date-shell::after {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%23475569' stroke-width='1.8'%3E%3Crect x='3' y='4' width='18' height='18' rx='2'/%3E%3Cline x1='16' y1='2' x2='16' y2='6'/%3E%3Cline x1='8' y1='2' x2='8' y2='6'/%3E%3Cline x1='3' y1='10' x2='21' y2='10'/%3E%3C/svg%3E");
}

.spotlight-group {
  padding: 12px;
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(79, 70, 229, 0.08), rgba(79, 70, 229, 0.02));
  border: 1px solid rgba(99, 102, 241, 0.12);
}

.form-tip {
  display: block;
  font-size: 12px;
  color: var(--warning, #b45309);
  margin-top: 6px;
}

.radio-group {
  display: flex;
  gap: 6px;
}

.radio-group.vertical {
  flex-direction: column;
}

.radio-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 12px;
  color: var(--gray-600);
  transition: all 0.15s ease;
}

.radio-item input {
  display: none;
}

.radio-item:hover {
  border-color: var(--gray-300);
}

.radio-item.active {
  border-color: var(--primary);
  background: var(--primary-light);
  color: var(--primary);
}

.radio-item.priority-high.active {
  border-color: var(--danger);
  background: #FEE2E2;
  color: var(--danger);
}

.radio-item.priority-medium.active {
  border-color: var(--warning);
  background: #FEF3C7;
  color: var(--warning);
}

.radio-item.priority-low.active {
  border-color: var(--gray-500);
  background: var(--gray-100);
  color: var(--gray-600);
}

/* 抽屉 */
.detail-drawer {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: flex-end;
  z-index: 1000;
}

.drawer-panel {
  width: 600px;
  max-width: 100%;
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  animation: slideInRight 0.3s ease;
}

@keyframes slideInRight {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-200);
  flex-shrink: 0;
}

.drawer-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.drawer-title .req-no {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
  font-family: monospace;
}

.drawer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-close {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--gray-500);
  font-size: 24px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.drawer-close:hover {
  background: var(--gray-100);
  color: var(--gray-700);
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.detail-section {
  margin-bottom: 24px;
}

.detail-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--gray-100);
}

.detail-row {
  margin-bottom: 12px;
}

.detail-row.two-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-item.full {
  grid-column: 1 / -1;
}

.detail-item label {
  font-size: 12px;
  color: var(--gray-500);
}

.detail-value {
  font-size: 13px;
  color: var(--gray-800);
}

.sub-name {
  color: var(--gray-400);
}

.detail-description {
  font-size: 13px;
  color: var(--gray-700);
  line-height: 1.6;
  padding: 12px;
  background: var(--gray-50);
  border-radius: var(--radius-md);
}

.detail-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attachment-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
}

.attachment-icon {
  font-size: 18px;
}

.attachment-name {
  flex: 1;
  font-size: 13px;
  color: var(--gray-700);
}

.attachment-size {
  font-size: 12px;
  color: var(--gray-400);
}

.no-data {
  font-size: 13px;
  color: var(--gray-400);
  text-align: center;
  padding: 20px;
}

/* 状态流转 */
.status-flow {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 16px 0;
  overflow-x: auto;
  margin-bottom: 16px;
}

.status-flow-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 70px;
  position: relative;
}

.status-flow-item:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 14px;
  left: calc(50% + 18px);
  width: calc(100% - 36px);
  height: 2px;
  background: var(--gray-200);
}

.status-flow-item.completed:not(:last-child)::after {
  background: var(--success);
}

.status-flow-item.current:not(:last-child)::after {
  background: linear-gradient(to right, var(--primary) 50%, var(--gray-200) 50%);
}

.status-dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--gray-200);
  color: var(--gray-500);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  position: relative;
  z-index: 1;
}

.status-flow-item.completed .status-dot {
  background: var(--success);
  color: white;
}

.status-flow-item.current .status-dot {
  background: var(--primary);
  color: white;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.2);
}

.status-label {
  font-size: 11px;
  color: var(--gray-500);
  text-align: center;
  white-space: nowrap;
}

.status-flow-item.current .status-label {
  color: var(--primary);
  font-weight: 600;
}

.status-flow-item.completed .status-label {
  color: var(--success);
}

/* 操作面板 */
.action-panel {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 16px;
  margin-bottom: 16px;
}

.action-panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 12px;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.action-btn {
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.15s ease;
}

.action-btn.primary {
  background: var(--primary);
  color: white;
}

.action-btn.primary:hover {
  background: #4338CA;
}

.action-btn.secondary {
  background: white;
  color: var(--gray-700);
  border: 1px solid var(--gray-200);
}

.action-btn.secondary:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.action-btn.danger {
  background: #FEE2E2;
  color: var(--danger);
}

.action-btn.danger:hover {
  background: #FECACA;
}

/* 评估信息 */
.evaluation-info {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 16px;
}

.evaluation-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--gray-100);
}

.evaluation-item:last-child {
  border-bottom: none;
}

.evaluation-label {
  font-size: 13px;
  color: var(--gray-500);
}

.evaluation-value {
  font-size: 13px;
  color: var(--gray-800);
  font-weight: 500;
}

.evaluation-value.success { color: var(--success); }
.evaluation-value.danger { color: var(--danger); }

/* 任务列表 */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
}

.task-checkbox {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 2px solid var(--gray-300);
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-checkbox.done {
  background: var(--success);
  border-color: var(--success);
  color: white;
}

.task-info {
  flex: 1;
  min-width: 0;
}

.task-title {
  font-size: 13px;
  color: var(--gray-800);
}

.task-title.done {
  text-decoration: line-through;
  color: var(--gray-400);
}

.task-meta {
  font-size: 11px;
  color: var(--gray-400);
}

.task-assignee {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--primary-light);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
}

/* 时间线 */
.timeline {
  position: relative;
  padding-left: 20px;
}

.timeline::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--gray-200);
}

.timeline-item {
  position: relative;
  padding-bottom: 16px;
}

.timeline-item:last-child {
  padding-bottom: 0;
}

.timeline-dot {
  position: absolute;
  left: -17px;
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  border: 2px solid #fff;
}

.timeline-content {
  background: var(--gray-50);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}

.timeline-text {
  font-size: 13px;
  color: var(--gray-700);
  margin-bottom: 4px;
}

.timeline-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--gray-400);
}

.timeline-user {
  color: var(--primary);
}

/* 项目选择器样式 */
.project-selector-modal {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 8px;
  max-height: 130px;
  overflow-y: auto;
}

.business-tabs-modal {
  display: flex;
  gap: 4px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.business-tab-modal {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-600);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.business-tab-modal:hover {
  background: var(--gray-200);
}

.business-tab-modal.active {
  background: var(--primary);
  color: white;
}

.project-cards-modal {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 100px;
  overflow-y: auto;
}

.project-card-modal {
  padding: 6px 10px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
  background: #fff;
  min-width: 80px;
}

.project-card-modal:hover {
  border-color: var(--primary);
}

.project-card-modal.selected {
  border-color: var(--primary);
  background: var(--primary-light);
}

.project-card-modal .project-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-800);
  display: block;
}

.project-card-modal .sub-count {
  font-size: 10px;
  color: var(--gray-400);
}

.sub-tags-modal {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.sub-tag-modal {
  padding: 2px 6px;
  background: var(--gray-100);
  border-radius: 4px;
  font-size: 10px;
  color: var(--gray-600);
  cursor: pointer;
}

.sub-tag-modal:hover {
  background: var(--gray-200);
}

.sub-tag-modal.selected {
  background: var(--primary);
  color: white;
}

/* 富文本编辑器 */
.rich-editor {
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-md);
  overflow: hidden;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 280px;
  background: #fff;
}

.form-left .form-group:last-child {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.editor-toolbar {
  display: flex;
  gap: 4px;
  padding: 8px;
  background: var(--gray-50);
  border-bottom: 1px solid var(--gray-200);
}

.editor-toolbar button {
  padding: 4px 8px;
  border: 1px solid var(--gray-200);
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s ease;
}

.editor-toolbar button:hover {
  background: var(--gray-100);
}

.toolbar-divider {
  width: 1px;
  background: var(--gray-200);
  margin: 0 4px;
}

.editor-content {
  flex: 1;
  min-height: 220px;
  max-height: 320px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.6;
  overflow-y: auto;
  outline: none;
  overflow-wrap: anywhere;
}

.editor-content:empty:before {
  content: '请输入需求描述...';
  color: var(--gray-400);
}

.editor-content :deep(img) {
  display: block;
  max-width: 100%;
  width: auto !important;
  height: auto !important;
  max-height: 220px;
  margin: 12px 0;
  border-radius: 12px;
  object-fit: contain;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.12);
}

/* 小上传区域 */
.upload-area-sm {
  border: 1.5px dashed var(--gray-200);
  border-radius: var(--radius-md);
  padding: 12px;
  text-align: center;
  color: var(--gray-400);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.upload-area-sm:hover {
  border-color: var(--primary);
  background: var(--primary-light);
  color: var(--primary);
}

.upload-area-sm svg {
  width: 20px;
  height: 20px;
}

.upload-area-sm span {
  font-size: 11px;
}

/* 下拉选择框 */
.form-select {
  width: 100%;
  height: 44px;
  padding: 0 42px 0 14px;
  border: none;
  border-radius: 15px;
  font-size: 13px;
  color: var(--gray-700);
  background: transparent;
  -webkit-appearance: none;
  -moz-appearance: none;
  appearance: none;
  cursor: pointer;
  outline: none;
}

.form-select,
.date-input {
  position: relative;
  z-index: 1;
}

.field-shell:focus-within {
  border-color: rgba(79, 70, 229, 0.28);
  box-shadow: 0 0 0 4px rgba(79, 70, 229, 0.08), 0 12px 28px rgba(79, 70, 229, 0.12);
}

.date-input::-webkit-calendar-picker-indicator {
  opacity: 0;
  cursor: pointer;
  position: absolute;
  inset: 0;
  width: 100%;
}

@media (max-width: 768px) {
  .content-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .title-with-stats,
  .page-actions {
    width: 100%;
    max-width: 100%;
    flex-wrap: wrap;
  }

  .title-with-stats {
    gap: 10px;
  }

  .inline-stats {
    flex-wrap: wrap;
    padding-left: 10px;
  }

  .business-line-row {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .business-tabs {
    max-width: 100%;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .business-tab {
    flex: 0 0 auto;
    white-space: nowrap;
  }

  .search-input {
    width: 100%;
  }

  .filter-row {
    flex-wrap: wrap;
    gap: 8px;
  }

  .filter-pills {
    max-width: 100%;
    flex: 0 1 auto;
    flex-wrap: wrap;
  }
}

@media (max-width: 1080px) {
  .modal.create-modal {
    width: min(960px, 94vw);
    height: min(860px, 92vh);
  }

  .modal-body {
    flex-direction: column;
  }

  .form-right {
    width: 100%;
  }
}
</style>
