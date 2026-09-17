<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Requirement } from '@/types/requirement'
import RequirementDesignPlannerDialog from '@/components/RequirementDesignPlannerDialog.vue'
import { api } from '@/utils/api'
import { getRequirementStageActions, type RequirementStageAction } from '@/utils/requirement-stage'
import { sanitizeHtml } from '@/utils/sanitize-html'

interface DesignWorkItem {
  id: number
  workType: string
  type: string
  designer?: string
  estimatedHours?: number
  actualHours?: number
  plannedCompletedAt?: string
  status: 'pending' | 'in-progress' | 'done'
  statusText: string
  rawStatus: string
}

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const actionLoading = ref('')
const loadError = ref('')
const users = ref<any[]>([])
const requirementEvaluation = ref<any | null>(null)
const designWorkLogs = ref<any[]>([])
const requirementConfirmation = ref<any | null>(null)
const requirementDelivery = ref<any | null>(null)
const requirementTasks = ref<any[]>([])
const showDesignPlanner = ref(false)

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

const lifecycleOrder = [
  '待评估', '评估中', '待设计', '设计中', '待确认',
  '开发中', '测试中', '待上线', '已上线', '已交付', '已验收'
]

const createEmptyRequirement = (): Requirement => ({
  id: '',
  reqNo: '',
  title: '加载中...',
  project: '-',
  subProject: '',
  businessLine: '-',
  type: '产品需求',
  status: '待评估',
  statusClass: 'pending',
  priority: '中',
  priorityClass: 'medium',
  owner: '-',
  createdAt: '-',
  source: '内部',
  requester: '-',
  expectDate: '',
  description: '',
  attachments: [],
  logs: [],
  evaluation: undefined,
  designWorks: [],
  tasks: []
})

const requirement = ref<Requirement>(createEmptyRequirement())
const safeRequirementDescription = computed(() =>
  sanitizeHtml(requirement.value.description || '暂无描述')
)

const showEvaluationModal = ref(false)
const evaluationForm = reactive({
  isFeasible: true,
  feasibilityDesc: '',
  estimatedWorkload: '',
  estimatedCost: ''
})

const extractRecords = (payload: any): any[] => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.records)) return payload.records
  if (Array.isArray(payload?.data?.records)) return payload.data.records
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const safeRequest = async <T>(request: Promise<T>): Promise<T | null> => {
  try {
    return await request
  } catch {
    return null
  }
}

const formatDate = (value?: string) => {
  if (!value) return '-'
  return value.split('T')[0] || value
}

const formatDateTime = (value?: string) => {
  if (!value) return ''
  return value.replace('T', ' ').slice(0, 16)
}

const getStoredUser = () => {
  try {
    return JSON.parse(localStorage.getItem('user') || '{}')
  } catch {
    return {}
  }
}

const currentUser = computed(() => getStoredUser())
const currentUserId = computed(() => Number(currentUser.value?.id) || 1)

const getRequirementId = () => {
  const requirementId = Number(route.params.id)
  if (!Number.isFinite(requirementId)) {
    throw new Error('需求 ID 无效')
  }
  return requirementId
}

const getUserName = (userId?: number | null) => {
  if (!userId) return '-'
  const matched = users.value.find(item => item.id === userId)
  return matched?.realName || matched?.username || String(userId)
}

const mapEvaluation = (evaluation: any) => {
  if (!evaluation) return undefined
  return {
    isFeasible: evaluation.isFeasible === 1,
    estimatedWorkload: evaluation.estimatedWorkload ? `${evaluation.estimatedWorkload}人天` : '-',
    estimatedCost: evaluation.estimatedCost ? `¥${evaluation.estimatedCost}` : undefined,
    estimatedOnlineDate: requirement.value.expectDate || '-',
    evaluator: getUserName(evaluation.evaluatorId)
  }
}

const toDesignWorkStatus = (value?: string) => {
  if (value === '已完成') return 'done'
  if (value === '进行中') return 'in-progress'
  return 'pending'
}

const designWorkItems = computed<DesignWorkItem[]>(() => {
  return designWorkLogs.value.map(log => ({
    id: log.id,
    workType: log.workType,
    type: log.workType === '技术方案设计' ? '技术方案' : log.workType,
    designer: getUserName(log.designerId),
    estimatedHours: log.estimatedHours,
    actualHours: log.actualHours,
    plannedCompletedAt: formatDate(log.plannedCompletedAt),
    rawStatus: log.status || '待开始',
    statusText: log.status || '待开始',
    status: toDesignWorkStatus(log.status)
  }))
})

const existingDesignPlans = computed(() => {
  return designWorkLogs.value.map(log => ({
    workType: log.workType,
    designerId: log.designerId,
    estimatedHours: log.estimatedHours,
    plannedCompletedAt: log.plannedCompletedAt
  }))
})

const taskItems = computed(() => {
  return requirementTasks.value.map(task => ({
    id: task.id,
    title: task.title,
    type: task.taskType || '开发任务',
    assignee: getUserName(task.assigneeId),
    estimatedHours: task.estimatedHours,
    status: task.status || '待开始'
  }))
})

const buildActivityLogs = (detail: any) => {
  const logs = [
    {
      id: `requirement-${detail.id}`,
      action: '需求已创建',
      user: getUserName(detail.creatorId),
      time: formatDateTime(detail.createdAt),
      sortValue: detail.createdAt || ''
    }
  ]

  if (requirementEvaluation.value?.evaluatedAt) {
    logs.push({
      id: `evaluation-${requirementEvaluation.value.id || detail.id}`,
      action: '提交了评估信息',
      user: getUserName(requirementEvaluation.value.evaluatorId),
      time: formatDateTime(requirementEvaluation.value.evaluatedAt),
      sortValue: requirementEvaluation.value.evaluatedAt
    })
  }

  if (requirementEvaluation.value?.decisionAt) {
    logs.push({
      id: `decision-${requirementEvaluation.value.id || detail.id}`,
      action: `BU 决策：${requirementEvaluation.value.decision}`,
      user: getUserName(requirementEvaluation.value.decisionBy),
      time: formatDateTime(requirementEvaluation.value.decisionAt),
      sortValue: requirementEvaluation.value.decisionAt
    })
  }

  designWorkLogs.value.forEach((log: any) => {
    if (log?.createdAt) {
      logs.push({
        id: `design-plan-${log.id}`,
        action: `配置设计规划：${log.workType}`,
        user: getUserName(log.designerId),
        time: formatDateTime(log.createdAt),
        sortValue: log.createdAt
      })
    }

    if (log?.completedAt) {
      logs.push({
        id: `design-complete-${log.id}`,
        action: `${log.workType}已完成`,
        user: getUserName(log.designerId),
        time: formatDateTime(log.completedAt),
        sortValue: log.completedAt
      })
    }
  })

  if (requirementConfirmation.value?.confirmedAt) {
    logs.push({
      id: `confirm-${requirementConfirmation.value.id || detail.id}`,
      action: `完成${requirementConfirmation.value.confirmationType}`,
      user: getUserName(requirementConfirmation.value.confirmedBy),
      time: formatDateTime(requirementConfirmation.value.confirmedAt),
      sortValue: requirementConfirmation.value.confirmedAt
    })
  }

  if (requirementDelivery.value?.deliveredAt) {
    logs.push({
      id: `delivery-${requirementDelivery.value.id || detail.id}`,
      action: '完成需求交付',
      user: getUserName(requirementDelivery.value.deliveredBy),
      time: formatDateTime(requirementDelivery.value.deliveredAt),
      sortValue: requirementDelivery.value.deliveredAt
    })
  }

  if (requirementDelivery.value?.acceptedAt) {
    logs.push({
      id: `accept-${requirementDelivery.value.id || detail.id}`,
      action: '完成需求验收',
      user: getUserName(requirementDelivery.value.acceptedBy),
      time: formatDateTime(requirementDelivery.value.acceptedAt),
      sortValue: requirementDelivery.value.acceptedAt
    })
  }

  requirementTasks.value.forEach((task: any) => {
    if (!task?.createdAt) return
    logs.push({
      id: `task-${task.id}`,
      action: `创建任务：${task.title}`,
      user: getUserName(task.assigneeId),
      time: formatDateTime(task.createdAt),
      sortValue: task.createdAt
    })
  })

  return logs
    .sort((left, right) => String(right.sortValue).localeCompare(String(left.sortValue)))
    .map(({ sortValue, ...rest }) => rest)
}

const loadRequirement = async () => {
  let requirementId = 0

  try {
    requirementId = getRequirementId()
  } catch (error) {
    loadError.value = (error as Error).message
    requirement.value = createEmptyRequirement()
    return
  }

  loading.value = true
  loadError.value = ''

  try {
    const [detail, businessLinePayload, projectPayload, userPayload, evaluation, designLogs, confirmation, delivery, tasks] = await Promise.all([
      api.getRequirementById(requirementId),
      api.getBusinessLines({ page: 1, size: 999 }),
      api.getProjects({ page: 1, size: 999 }),
      api.getUsers({ page: 1, size: 200 }),
      safeRequest(api.getRequirementEvaluation(requirementId)),
      safeRequest(api.getDesignWorkLogs(requirementId)),
      safeRequest(api.getRequirementConfirmation(requirementId)),
      safeRequest(api.getRequirementDelivery(requirementId)),
      safeRequest(api.getRequirementTasks(requirementId))
    ])

    const businessLines = extractRecords(businessLinePayload)
    const projects = extractRecords(projectPayload)
    users.value = extractRecords(userPayload)
    requirementEvaluation.value = evaluation
    designWorkLogs.value = Array.isArray(designLogs) ? designLogs : []
    requirementConfirmation.value = confirmation
    requirementDelivery.value = delivery
    requirementTasks.value = Array.isArray(tasks) ? tasks : []

    const currentProject = projects.find((item: any) => item.id === detail.projectId)
    const parentProject = currentProject?.parentId ? projects.find((item: any) => item.id === currentProject.parentId) : null
    const primaryProject = parentProject || currentProject
    const projectContacts = primaryProject?.id ? await api.getCustomerContacts(primaryProject.id) : []

    const creator = users.value.find((item: any) => item.id === detail.creatorId)
    const contact = detail.customerContactId
      ? projectContacts.find((item: any) => item.id === detail.customerContactId)
      : null

    requirement.value = {
      id: String(detail.id),
      reqNo: detail.reqNo || `REQ-${detail.id}`,
      title: detail.title || '未命名需求',
      project: primaryProject?.name || '未关联项目',
      subProject: currentProject && currentProject.parentId ? currentProject.name : '',
      businessLine: businessLines.find((item: any) => item.id === detail.businessLineId)?.name || '-',
      type: detail.type || '产品需求',
      status: detail.status || '待评估',
      statusClass: getStatusBadgeClass(detail.status || '待评估'),
      priority: detail.priority || '中',
      priorityClass: detail.priority === '高' ? 'high' : detail.priority === '低' ? 'low' : 'medium',
      owner: creator?.realName || creator?.username || '-',
      createdAt: formatDate(detail.createdAt),
      source: detail.source || '内部',
      requester: contact?.name || creator?.realName || '-',
      expectDate: detail.expectedOnlineDate || detail.estimatedOnlineDate || '',
      description: detail.description || '暂无描述',
      attachments: [],
      logs: [],
      evaluation: undefined,
      designWorks: [],
      tasks: []
    }

    requirement.value.evaluation = mapEvaluation(evaluation)
    requirement.value.designWorks = designWorkItems.value as any
    requirement.value.tasks = taskItems.value as any
    requirement.value.logs = buildActivityLogs(detail) as any
  } catch (error) {
    console.error('加载需求详情失败:', error)
    loadError.value = '加载需求详情失败'
    requirement.value = createEmptyRequirement()
  } finally {
    loading.value = false
  }
}

const isStepCompleted = (stepStatus: string) => {
  if (requirement.value.status === '已拒绝') {
    return lifecycleOrder.indexOf(stepStatus) < lifecycleOrder.indexOf('评估中')
  }
  const currentIndex = lifecycleOrder.indexOf(requirement.value.status)
  const stepIndex = lifecycleOrder.indexOf(stepStatus)
  return stepIndex < currentIndex
}

const showEvaluation = computed(() => {
  return Boolean(requirementEvaluation.value) || ['待评估', '评估中', '已拒绝'].includes(requirement.value.status)
})

const showDesign = computed(() => {
  return Boolean(designWorkLogs.value.length) || ['待设计', '设计中', '待确认', '开发中', '测试中', '待上线', '已上线', '已交付', '已验收'].includes(requirement.value.status)
})

const showTasks = computed(() => {
  return taskItems.value.length > 0 || ['开发中', '测试中', '待上线', '已上线', '已交付', '已验收'].includes(requirement.value.status)
})

const getCurrentActions = (): RequirementStageAction[] => getRequirementStageActions({
  status: requirement.value.status,
  type: requirement.value.type,
  hasEvaluation: Boolean(requirementEvaluation.value)
})

const withAction = async (type: string, runner: () => Promise<void>) => {
  actionLoading.value = type
  try {
    await runner()
  } catch (error) {
    const message = error instanceof Error ? error.message : '操作失败，请重试'
    ElMessage.error(message)
  } finally {
    actionLoading.value = ''
  }
}

const confirmAction = async (message: string, title = '提示') => {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}

const handleSimpleStageAction = async (action: string, successMessage: string, message: string) => {
  if (!await confirmAction(message)) return

  await withAction(action, async () => {
    await api.executeRequirementStageAction(getRequirementId(), action)
    await loadRequirement()
    ElMessage.success(successMessage)
  })
}

const handleBuDecision = async (decision: string, successMessage: string) => {
  if (!await confirmAction(`确认执行“${decision}”吗？`)) return

  await withAction(decision, async () => {
    await api.submitBuDecision({
      requirementId: getRequirementId(),
      decision,
      decisionReason: ''
    })
    await loadRequirement()
    ElMessage.success(successMessage)
  })
}

const openDesignPlanner = () => {
  showDesignPlanner.value = true
}

const saveDesignPlanner = async (items: Array<{
  workType: string
  designerId: number
  estimatedHours?: number
  plannedCompletedAt?: string
}>) => {
  await withAction('plan_design', async () => {
    const existingByType = new Map(designWorkLogs.value.map(log => [log.workType, log]))
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
          requirementId: getRequirementId(),
          workType: item.workType,
          designerId: item.designerId,
          estimatedHours: item.estimatedHours,
          plannedCompletedAt: item.plannedCompletedAt,
          status: '待开始'
        })
      }
    }

    for (const log of designWorkLogs.value) {
      if (!selectedTypes.has(log.workType)) {
        await api.deleteDesignWorkLog(log.id)
      }
    }

    showDesignPlanner.value = false
    await loadRequirement()
    ElMessage.success('设计规划已保存')
  })
}

const handleStartDesign = async () => {
  if (!designWorkLogs.value.length) {
    showDesignPlanner.value = true
    ElMessage.warning('请先配置设计规划')
    return
  }
  if (!await confirmAction('确认进入设计阶段吗？')) return

  await withAction('start_design', async () => {
    await api.executeRequirementStageAction(getRequirementId(), 'start_design')
    await loadRequirement()
    ElMessage.success('已进入设计中')
  })
}

const handleConfirmation = async (confirmationType: string) => {
  if (!await confirmAction(`确认执行“${confirmationType}”吗？`)) return

  await withAction(confirmationType, async () => {
    await api.createRequirementConfirmation({
      requirementId: getRequirementId(),
      confirmationType,
      confirmedBy: currentUserId.value,
      confirmationNotes: ''
    })
    await loadRequirement()
    ElMessage.success('确认完成，需求已进入开发中')
  })
}

const handleDelivery = async () => {
  if (!await confirmAction('确认完成交付吗？')) return

  await withAction('deliver', async () => {
    await api.createRequirementDelivery({
      requirementId: getRequirementId(),
      deliveredBy: currentUserId.value,
      deliveryNotes: ''
    })
    await loadRequirement()
    ElMessage.success('已完成交付')
  })
}

const handleAccept = async () => {
  if (!await confirmAction('确认完成验收吗？')) return

  await withAction('accept', async () => {
    await api.acceptRequirementDelivery(getRequirementId(), {
      acceptedBy: currentUserId.value,
      acceptanceNotes: ''
    })
    await loadRequirement()
    ElMessage.success('已完成验收')
  })
}

const handleAddTask = async () => {
  try {
    const { value } = await ElMessageBox.prompt('请输入任务标题', '添加任务', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：联调订单接口'
    })

    if (!value?.trim()) return

    await withAction('add_task', async () => {
      await api.createTask({
        requirementId: getRequirementId(),
        title: value.trim(),
        assigneeId: currentUserId.value,
        createdBy: currentUserId.value,
        taskType: '开发任务'
      })
      await loadRequirement()
      ElMessage.success('任务已创建')
    })
  } catch {
    // 用户取消
  }
}

const executeAction = async (type: string) => {
  switch (type) {
    case 'submit_eval':
      evaluationForm.isFeasible = requirementEvaluation.value?.isFeasible !== 0
      evaluationForm.feasibilityDesc = requirementEvaluation.value?.feasibilityDesc || ''
      evaluationForm.estimatedWorkload = requirementEvaluation.value?.estimatedWorkload?.toString?.() || ''
      evaluationForm.estimatedCost = requirementEvaluation.value?.estimatedCost?.toString?.() || ''
      showEvaluationModal.value = true
      return
    case 'start_eval':
      await handleSimpleStageAction(type, '需求已进入评估中', '确认开始评估吗？')
      return
    case 'plan_design':
      openDesignPlanner()
      return
    case 'approve':
      if (!designWorkLogs.value.length) {
        showDesignPlanner.value = true
        ElMessage.warning('请先配置设计规划，再执行评估通过')
        return
      }
      await handleBuDecision('通过', '评估通过，需求已进入待设计')
      return
    case 'reject':
      if (requirement.value.status === '评估中' && requirementEvaluation.value) {
        await handleBuDecision('拒绝', '需求已标记为已拒绝')
        return
      }
      await handleSimpleStageAction(type, '需求已标记为已拒绝', '确认标记为已拒绝吗？')
      return
    case 'convert':
      await handleBuDecision('转产品需求', '需求已转为产品需求并进入待设计')
      return
    case 'start_design':
      await handleStartDesign()
      return
    case 'customer_confirm':
      await handleConfirmation('客户确认')
      return
    case 'internal_confirm':
      await handleConfirmation('内部确认')
      return
    case 'add_task':
      await handleAddTask()
      return
    case 'start_test':
      await handleSimpleStageAction(type, '需求已进入测试中', '确认提测吗？')
      return
    case 'test_pass':
      await handleSimpleStageAction(type, '测试通过，需求已进入待上线', '确认测试通过吗？')
      return
    case 'test_fail':
      await handleSimpleStageAction(type, '需求已退回开发中', '确认测试失败并退回开发吗？')
      return
    case 'go_online':
      await handleSimpleStageAction(type, '需求已确认上线', '确认上线吗？')
      return
    case 'deliver':
      await handleDelivery()
      return
    case 'accept':
      await handleAccept()
      return
  }
}

const updateDesignWorkStatus = async (workLogId: number, value: string) => {
  await withAction(`design-${workLogId}`, async () => {
    await api.updateDesignWorkLog(workLogId, { status: value })
    await loadRequirement()
    ElMessage.success('设计状态已更新')
  })
}

const handleDesignStatusChange = (workLogId: number, event: Event) => {
  const value = (event.target as HTMLSelectElement).value
  void updateDesignWorkStatus(workLogId, value)
}

const updateTaskStatus = async (taskId: number, status: string) => {
  await withAction(`task-${taskId}`, async () => {
    await api.updateTask(taskId, { status })
    await loadRequirement()
    ElMessage.success('任务状态已更新')
  })
}

const handleTaskStatusChange = (taskId: number, event: Event) => {
  const value = (event.target as HTMLSelectElement).value
  void updateTaskStatus(taskId, value)
}

const submitEvaluation = async () => {
  if (!evaluationForm.estimatedWorkload) {
    ElMessage.warning('请输入预估工时')
    return
  }
  if (requirement.value.type === '项目需求' && !evaluationForm.estimatedCost) {
    ElMessage.warning('项目需求请输入预估报价')
    return
  }

  await withAction('submit_eval', async () => {
    await api.submitRequirementEvaluation({
      requirementId: getRequirementId(),
      isFeasible: evaluationForm.isFeasible ? 1 : 0,
      feasibilityDesc: evaluationForm.feasibilityDesc,
      estimatedWorkload: Number(evaluationForm.estimatedWorkload),
      estimatedCost: requirement.value.type === '项目需求' ? Number(evaluationForm.estimatedCost) : undefined,
      suggestProduct: 0
    })

    showEvaluationModal.value = false
    await loadRequirement()
    ElMessage.success('评估已提交')
  })
}

const goBack = () => {
  if (route.meta.standalone) {
    window.history.length > 1 ? router.back() : router.push('/requirements')
    return
  }
  router.push('/requirements')
}

const getStatusBadgeClass = (status: string) => {
  const map: Record<string, string> = {
    '待评估': 'pending',
    '评估中': 'evaluating',
    '待设计': 'pending_design',
    '设计中': 'designing',
    '待确认': 'pending_confirm',
    '开发中': 'developing',
    '测试中': 'testing',
    '待上线': 'testing',
    '已上线': 'online',
    '已交付': 'delivered',
    '已验收': 'accepted',
    '已拒绝': 'rejected'
  }
  return map[status] || 'pending'
}

onMounted(() => {
  void loadRequirement()
})

watch(() => route.params.id, () => {
  void loadRequirement()
})
</script>

<template>
  <div class="detail-page">
    <div v-if="loading" class="detail-loading">需求详情加载中...</div>
    <div v-else-if="loadError" class="detail-error">{{ loadError }}</div>
    <template v-else>
    <!-- 头部 -->
    <div class="detail-header">
      <div class="header-left">
        <button class="back-btn" @click="goBack">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M19 12H5M12 19l-7-7 7-7"/>
          </svg>
        </button>
        <div class="header-info">
          <span class="req-no">{{ requirement.reqNo }}</span>
          <h1 class="requirement-title">{{ requirement.title }}</h1>
          <div class="tag-list" style="margin-top: 8px;">
            <span class="tag-item">{{ requirement.project }}</span>
            <span v-if="requirement.subProject" class="tag-item">{{ requirement.subProject }}</span>
            <span class="tag-item">{{ requirement.businessLine }}</span>
            <span :class="['status-badge', getStatusBadgeClass(requirement.status)]">{{ requirement.status }}</span>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <button class="btn btn-default btn-sm">编辑</button>
        <button class="btn btn-primary btn-sm">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="7 10 12 15 17 10"/>
            <line x1="12" y1="15" x2="12" y2="3"/>
          </svg>
          导出
        </button>
      </div>
    </div>

    <!-- 状态流转 -->
    <div class="status-flow">
      <div
        v-for="(step, index) in statusFlowSteps"
        :key="step.status"
        class="status-flow-item"
        :class="{
          completed: isStepCompleted(step.status),
          current: requirement.status === step.status,
          rejected: step.status === '已拒绝' && requirement.status === '已拒绝'
        }"
      >
        <div class="status-dot">{{ index + 1 }}</div>
        <div class="status-label">{{ step.label }}</div>
      </div>
    </div>

    <!-- 操作面板 -->
    <div class="action-panel" v-if="getCurrentActions().length > 0">
      <div class="action-panel-title">可执行操作</div>
      <div class="action-buttons">
        <button
          v-for="action in getCurrentActions()"
          :key="action.type"
          :class="['action-btn', action.class]"
          :disabled="actionLoading === action.type"
          @click="executeAction(action.type)"
        >
          {{ action.label }}
        </button>
      </div>
    </div>

    <!-- 主内容 -->
    <div class="detail-body">
      <div class="detail-main">
        <!-- 评估信息 -->
        <div class="content-section" v-if="showEvaluation">
          <div class="content-section-header">
            <h3 class="content-section-title">评估信息</h3>
          </div>
          <div class="evaluation-info">
            <div class="evaluation-item">
              <span class="evaluation-label">技术可行性</span>
              <span class="evaluation-value" :class="requirement.evaluation?.isFeasible ? 'success' : 'danger'">
                {{ requirement.evaluation?.isFeasible ? '可行' : '不可行' }}
              </span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">预估工时</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedWorkload || '-' }}</span>
            </div>
            <div class="evaluation-item" v-if="requirement.type === '项目需求'">
              <span class="evaluation-label">预估报价</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedCost || '-' }}</span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">预估上线</span>
              <span class="evaluation-value">{{ requirement.evaluation?.estimatedOnlineDate || '-' }}</span>
            </div>
            <div class="evaluation-item">
              <span class="evaluation-label">评估人</span>
              <span class="evaluation-value">{{ requirement.evaluation?.evaluator || '-' }}</span>
            </div>
          </div>
        </div>

        <!-- 设计工作 -->
        <div class="content-section" v-if="showDesign">
          <div class="content-section-header">
            <h3 class="content-section-title">设计工作</h3>
          </div>
          <div class="design-works">
            <div class="design-work-item" v-for="work in designWorkItems" :key="work.type">
              <div class="design-work-info">
                <div class="design-work-type">{{ work.type }}</div>
                <div class="design-work-meta">
                  负责人：{{ work.designer || '-' }} ｜ 预估：{{ work.estimatedHours || '-' }}h ｜ 计划完成：{{ work.plannedCompletedAt || '-' }}
                </div>
              </div>
              <div class="design-work-actions">
                <select
                  v-if="requirement.status === '设计中'"
                  class="stage-select"
                  :value="work.rawStatus"
                  @change="handleDesignStatusChange(work.id, $event)"
                >
                  <option value="待开始">待开始</option>
                  <option value="进行中">进行中</option>
                  <option value="已完成">已完成</option>
                </select>
                <span v-else :class="['design-work-status', work.status]">{{ work.statusText }}</span>
              </div>
            </div>
            <div v-if="!designWorkItems.length" class="no-data">暂无设计工作，点击“开始设计”后生成</div>
          </div>
        </div>

        <!-- 开发任务 -->
        <div class="content-section" v-if="showTasks">
          <div class="content-section-header">
            <h3 class="content-section-title">开发任务</h3>
          </div>
          <div class="task-list">
            <div class="task-item" v-for="task in taskItems" :key="task.id">
              <div class="task-checkbox" :class="{ done: ['已完成', '已测试'].includes(task.status) }">
                <svg v-if="['已完成', '已测试'].includes(task.status)" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
              <div class="task-info">
                <div class="task-title" :class="{ done: ['已完成', '已测试'].includes(task.status) }">{{ task.title }}</div>
                <div class="task-meta">{{ task.type }} | {{ task.estimatedHours || '-' }}h | {{ task.assignee }}</div>
              </div>
              <div class="task-actions">
                <select
                  v-if="['开发中', '测试中'].includes(requirement.status)"
                  class="stage-select"
                  :value="task.status"
                  @change="handleTaskStatusChange(task.id, $event)"
                >
                  <option value="待开始">待开始</option>
                  <option value="进行中">进行中</option>
                  <option value="已完成">已完成</option>
                  <option value="已测试">已测试</option>
                </select>
                <div v-else class="task-assignee">{{ task.assignee?.charAt(0) }}</div>
              </div>
            </div>
            <div v-if="!taskItems.length" class="no-data">暂无任务，可通过“添加任务”开始拆解开发工作</div>
          </div>
        </div>

        <!-- 需求描述 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">需求描述</h3>
          </div>
          <div class="description-content" v-html="safeRequirementDescription"></div>
        </div>

        <!-- 附件 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">附件</h3>
            <span class="content-section-action">上传</span>
          </div>
          <div class="attachment-list" v-if="requirement.attachments && requirement.attachments.length">
            <div class="attachment-item" v-for="att in requirement.attachments" :key="att.id">
              <span class="attachment-icon">{{ att.type === 'image' ? '🖼️' : '📄' }}</span>
              <span class="attachment-name">{{ att.name }}</span>
              <span class="attachment-size">{{ att.size }}</span>
            </div>
          </div>
          <div v-else class="no-data">暂无附件</div>
        </div>

        <!-- 活动日志 -->
        <div class="content-section">
          <div class="content-section-header">
            <h3 class="content-section-title">活动日志</h3>
          </div>
          <div class="timeline" v-if="requirement.logs && requirement.logs.length">
            <div class="timeline-item" v-for="log in requirement.logs" :key="log.id">
              <div class="timeline-dot"></div>
              <div class="timeline-content">
                <div class="timeline-text">{{ log.action }}</div>
                <div class="timeline-meta">
                  <span class="timeline-user">{{ log.user }}</span>
                  <span>{{ log.time }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="no-data">暂无日志记录</div>
        </div>
      </div>

      <!-- 侧边栏 -->
      <div class="detail-sidebar">
        <div class="info-card">
          <div class="info-card-title">基本信息</div>
          <div class="info-item">
            <span class="info-label">需求类型</span>
            <span class="info-value">{{ requirement.type }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">优先级</span>
            <span class="info-value">
              <span :class="['priority-text', requirement.priorityClass]">{{ requirement.priority }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">负责人</span>
            <span class="info-value">{{ requirement.owner }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">期望上线</span>
            <span class="info-value">{{ requirement.expectDate || '-' }}</span>
          </div>
        </div>

        <div class="info-card">
          <div class="info-card-title">来源信息</div>
          <div class="info-item">
            <span class="info-label">需求来源</span>
            <span class="info-value">{{ requirement.source || '内部' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">提出人</span>
            <span class="info-value">{{ requirement.requester || '-' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">创建时间</span>
            <span class="info-value">{{ requirement.createdAt }}</span>
          </div>
        </div>

        <div class="info-card">
          <div class="info-card-title">项目信息</div>
          <div class="info-item">
            <span class="info-label">所属业务线</span>
            <span class="info-value">{{ requirement.businessLine }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">所属项目</span>
            <span class="info-value">{{ requirement.project }}</span>
          </div>
          <div class="info-item" v-if="requirement.subProject">
            <span class="info-label">子项目</span>
            <span class="info-value">{{ requirement.subProject }}</span>
          </div>
        </div>
      </div>
    </div>
    </template>

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
            <label class="form-label">可行性说明</label>
            <textarea
              class="form-textarea"
              v-model="evaluationForm.feasibilityDesc"
              placeholder="可填写技术可行性、风险、拆解说明"
            />
          </div>
          <div class="form-group">
            <label class="form-label">预估工时 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <input
                type="number"
                class="form-input"
                v-model="evaluationForm.estimatedWorkload"
                placeholder="请输入"
                min="1"
                style="flex: 1;"
              >
              <span style="color: var(--gray-500); font-size: 13px;">人天</span>
            </div>
          </div>
          <div class="form-group" v-if="requirement.type === '项目需求'">
            <label class="form-label">预估报价 <span class="required">*</span></label>
            <div style="display: flex; align-items: center; gap: 8px;">
              <span style="color: var(--gray-500); font-size: 13px;">¥</span>
              <input
                type="number"
                class="form-input"
                v-model="evaluationForm.estimatedCost"
                placeholder="请输入"
                min="0"
                style="flex: 1;"
              >
              <span style="color: var(--gray-500); font-size: 13px;">元</span>
            </div>
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
      :users="users"
      :plans="existingDesignPlans"
      @save="saveDesignPlanner"
    />
  </div>
</template>

<style scoped>
.detail-page {
  padding: 24px;
}

.detail-loading,
.detail-error {
  min-height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg);
  background: #fff;
  box-shadow: var(--shadow-sm);
  color: var(--gray-600);
  font-size: 15px;
}

.detail-error {
  color: var(--danger);
}

/* 头部 */
.detail-header {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px 24px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.back-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  border: none;
  background: var(--gray-100);
  color: var(--gray-600);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.back-btn:hover {
  background: var(--gray-200);
  color: var(--gray-800);
}

.header-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.req-no {
  font-size: 13px;
  color: var(--gray-500);
  font-family: monospace;
}

.requirement-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* 状态流转 */
.status-flow {
  display: flex;
  align-items: center;
  padding: 20px 24px;
  background: #fff;
  border-radius: var(--radius-lg);
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
  overflow-x: auto;
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

.status-flow-item.rejected .status-dot {
  background: var(--gray-300);
  color: var(--gray-500);
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
  border-radius: var(--radius-lg);
  padding: 16px 20px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
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

.action-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.action-btn.primary {
  background: var(--primary);
  color: white;
}

.action-btn.primary:hover {
  background: #4338CA;
}

.action-btn.secondary {
  background: #fff;
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

/* 主内容区 */
.detail-body {
  display: flex;
  gap: 16px;
}

.detail-main {
  flex: 1;
  min-width: 0;
}

.detail-sidebar {
  width: 300px;
  flex-shrink: 0;
}

/* 内容区块 */
.content-section {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.content-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--gray-100);
}

.content-section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.content-section-action {
  font-size: 13px;
  color: var(--primary);
  cursor: pointer;
}

.content-section-action:hover {
  text-decoration: underline;
}

/* 描述内容 */
.description-content {
  font-size: 14px;
  color: var(--gray-700);
  line-height: 1.7;
}

.description-content p {
  margin-bottom: 12px;
}

.description-content ul {
  padding-left: 20px;
  margin-bottom: 12px;
}

.description-content li {
  margin-bottom: 6px;
}

/* 附件 */
.attachment-list {
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
  cursor: pointer;
  transition: all 0.15s ease;
}

.attachment-item:hover {
  background: var(--gray-100);
}

.attachment-icon {
  font-size: 20px;
}

.attachment-name {
  flex: 1;
  font-size: 13px;
  color: var(--gray-800);
}

.attachment-size {
  font-size: 12px;
  color: var(--gray-400);
}

.no-data {
  font-size: 13px;
  color: var(--gray-400);
  text-align: center;
  padding: 24px;
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

/* 标签 */
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-item {
  padding: 4px 10px;
  background: var(--gray-100);
  border-radius: 12px;
  font-size: 12px;
  color: var(--gray-600);
}

/* 侧边信息卡片 */
.info-card {
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.info-card-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-500);
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 10px;
}

.info-item:last-child {
  margin-bottom: 0;
}

.info-label {
  font-size: 13px;
  color: var(--gray-500);
  flex-shrink: 0;
}

.info-value {
  font-size: 13px;
  color: var(--gray-800);
  text-align: right;
  word-break: break-all;
}

.priority-text.high { color: var(--danger); font-weight: 600; }
.priority-text.medium { color: var(--warning); font-weight: 600; }
.priority-text.low { color: var(--gray-500); }

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
.status-badge.pending { background: #FEF3C7; color: #B45309; }
.status-badge.evaluating { background: #DBEAFE; color: #1D4ED8; }
.status-badge.designing { background: #D1FAE5; color: #047857; }
.status-badge.developing { background: #FEE2E2; color: #DC2626; }
.status-badge.testing { background: #E9D5FF; color: #7C3AED; }
.status-badge.online { background: #D1FAE5; color: #047857; }
.status-badge.rejected { background: var(--gray-100); color: var(--gray-500); }
.status-badge.pending_design { background: #FEF3C7; color: #B45309; }
.status-badge.pending_confirm { background: #FEF3C7; color: #B45309; }
.status-badge.delivered { background: #D1FAE5; color: #047857; }
.status-badge.accepted { background: #D1FAE5; color: #047857; }

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

/* 设计工作 */
.design-works {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.design-work-item {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.design-work-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.design-work-type {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-800);
}

.design-work-meta {
  font-size: 12px;
  color: var(--gray-500);
}

.design-work-status {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.design-work-status.pending { background: var(--gray-100); color: var(--gray-600); }
.design-work-status.in-progress { background: #DBEAFE; color: #1D4ED8; }
.design-work-status.done { background: #D1FAE5; color: #047857; }

.design-work-actions,
.task-actions {
  display: flex;
  align-items: center;
}

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

.stage-select {
  min-width: 96px;
  padding: 6px 8px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--gray-700);
  font-size: 12px;
}

.stage-select:focus {
  outline: none;
  border-color: var(--primary);
}

/* 按钮 */
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

.btn-primary {
  background: var(--primary);
  color: white;
}

.btn-primary:hover {
  background: #4338CA;
}

.btn-default {
  background: #fff;
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

/* 弹窗样式 */
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

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-200);
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
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid var(--gray-200);
  background: var(--gray-50);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-700);
}

.required {
  color: var(--danger);
}

.form-input {
  padding: 8px 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 14px;
  outline: none;
}

.form-input:focus {
  border-color: var(--primary);
}

.form-textarea {
  min-height: 96px;
  padding: 8px 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 14px;
  resize: vertical;
  outline: none;
}

.form-textarea:focus {
  border-color: var(--primary);
}

.radio-group {
  display: flex;
  gap: 8px;
}

.radio-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  border: 1.5px solid var(--gray-200);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
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
</style>
