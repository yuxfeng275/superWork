<script setup lang="ts">
import { ref, computed, reactive, onMounted, onUnmounted } from 'vue'
import type { Requirement } from '@/types/requirement'
import { api } from '@/utils/api'

const viewMode = ref<'table' | 'kanban'>('table')
const showCreateModal = ref(false)
const showDetailDrawer = ref(false)
const showEvaluationModal = ref(false)
const currentRequirement = ref<Requirement | null>(null)
const loading = ref(false)

const evaluationForm = reactive({
  isFeasible: true,
  estimatedWorkload: '',
  estimatedCost: '',
  estimatedOnlineDate: ''
})

const newRequirement = reactive({
  title: '',
  businessLine: '',
  project: '',
  subProject: '',
  source: '内部',
  requester: '',
  type: '产品需求',
  priority: '中',
  expectDate: '',
  owner: '',
  description: ''
})

const editorRef = ref<HTMLElement | null>(null)

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

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    // 加载业务线和项目
    const businessLineData = await api.getBusinessLines()
    const projectsData = await api.getProjects()

    // 转换业务线数据
    businessLines.value = businessLineData.map((bl: any) => ({
      name: bl.name,
      totalCount: projectsData.filter((p: any) => p.businessLineId === bl.id).length,
      projects: projectsData
        .filter((p: any) => p.businessLineId === bl.id && !p.parentId)
        .map((p: any) => ({
          name: p.name,
          logo: getProjectLogo(p.name),
          count: projectsData.filter((sp: any) => sp.parentId === p.id).length,
          subProjects: projectsData
            .filter((sp: any) => sp.parentId === p.id)
            .map((sp: any) => sp.name)
        }))
    }))

    // 加载需求列表
    const requirementsData = await api.getRequirements()
    tableData.value = requirementsData.records.map((req: any) => ({
      id: String(req.id),
      reqNo: req.reqNo,
      title: req.title,
      project: projectsData.find((p: any) => p.id === req.projectId)?.name || '',
      subProject: '',
      businessLine: businessLineData.find((bl: any) => bl.id === req.businessLineId)?.name || '',
      type: req.type,
      status: req.status,
      statusClass: getStatusClass(req.status),
      priority: req.priority,
      priorityClass: getPriorityClass(req.priority),
      owner: req.creatorName || '',
      createdAt: req.createdAt?.split('T')[0] || '',
      source: req.source || '内部',
      requester: req.customerContactName || '',
      expectDate: req.expectedOnlineDate || '',
      description: req.description || '',
      attachments: [],
      logs: [],
      evaluation: undefined,
      designWorks: [],
      tasks: []
    }))
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

const getProjectLogo = (name: string): string => {
  const logos: Record<string, string> = {
    '皇家宠物': '🐾',
    '飞鹤': '🐄',
    'speedo': '🏊',
    '佳贝艾特': '🐑',
    '逢时': '🌊',
    '黄天鹅': '🥚'
  }
  return logos[name] || '📦'
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

const filteredData = computed(() => {
  return tableData.value.filter(item => {
    if (filters.status.length > 0 && !filters.status.includes(item.status)) return false
    if (filters.type && item.type !== filters.type) return false
    if (filters.priority && item.priority !== filters.priority) return false
    if (filters.search && !item.title.toLowerCase().includes(filters.search.toLowerCase()) && !item.reqNo.toLowerCase().includes(filters.search.toLowerCase())) return false

    if (selectedBusiness.value && item.businessLine !== selectedBusiness.value) return false
    if (selectedProjects.value.length > 0 && !selectedProjects.value.includes(item.project)) return false
    if (selectedSubProjects.value.length > 0 && item.subProject && !selectedSubProjects.value.includes(item.subProject)) return false

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

const clearAllFilters = () => {
  selectedBusiness.value = ''
  selectedProjects.value = []
  selectedSubProjects.value = []
  filters.status = []
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
  // 双击 - 打开新窗口
  window.open(`/requirements/${id}`, '_blank')
}

const goToDetail = (id: string) => {
  const req = tableData.value.find(item => item.id === id)
  if (req) {
    currentRequirement.value = req
    showDetailDrawer.value = true
  }
}

const openInNewPage = () => {
  if (currentRequirement.value) {
    window.open(`/requirements/${currentRequirement.value.id}`, '_blank')
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
  const status = currentRequirement.value?.status
  const type = currentRequirement.value?.type
  const actions: { type: string; label: string; class: string }[] = []

  switch (status) {
    case '待评估':
      actions.push({ type: 'start_eval', label: '开始评估', class: 'primary' }, { type: 'reject', label: '标记已拒绝', class: 'danger' })
      break
    case '评估中':
      actions.push({ type: 'submit_eval', label: '提交评估', class: 'primary' }, { type: 'reject', label: '标记已拒绝', class: 'danger' })
      break
    case '待设计':
      if (type === '项目需求') {
        actions.push({ type: 'start_design', label: '开始设计', class: 'primary' }, { type: 'convert', label: '转为产品需求', class: 'secondary' })
      } else {
        actions.push({ type: 'start_design', label: '开始设计', class: 'primary' })
      }
      break
    case '设计中':
      actions.push({ type: 'add_design', label: '添设计工作', class: 'secondary' }, { type: 'confirm', label: '确认完成', class: 'primary' })
      break
    case '待确认':
      actions.push({ type: 'customer_confirm', label: '客户确认', class: 'primary' }, { type: 'internal_confirm', label: '内部确认', class: 'secondary' })
      break
    case '开发中':
      actions.push({ type: 'add_task', label: '添加任务', class: 'secondary' }, { type: 'start_test', label: '提测', class: 'primary' })
      break
    case '测试中':
      actions.push({ type: 'test_pass', label: '测试通过', class: 'primary' }, { type: 'test_fail', label: '测试失败', class: 'danger' })
      break
    case '待上线':
      actions.push({ type: 'go_online', label: '确认上线', class: 'primary' })
      break
    case '已上线':
      if (type === '项目需求') {
        actions.push({ type: 'deliver', label: '确认交付', class: 'primary' })
      }
      break
    case '已交付':
      if (type === '项目需求') {
        actions.push({ type: 'accept', label: '确认验收', class: 'primary' })
      }
      break
  }
  return actions
}

const executeAction = (type: string) => {
  if (type === 'submit_eval') {
    evaluationForm.isFeasible = true
    evaluationForm.estimatedWorkload = ''
    evaluationForm.estimatedCost = ''
    evaluationForm.estimatedOnlineDate = ''
    showEvaluationModal.value = true
    return
  }
  alert(`执行操作: ${type}`)
}

const submitEvaluation = () => {
  if (!evaluationForm.estimatedWorkload) {
    alert('请输入预估工时')
    return
  }
  if (!evaluationForm.estimatedOnlineDate) {
    alert('请选择预估上线时间')
    return
  }

  if (currentRequirement.value && currentRequirement.value.type === '项目需求' && !evaluationForm.estimatedCost) {
    alert('项目需求请输入预估报价')
    return
  }

  currentRequirement.value!.evaluation = {
    isFeasible: evaluationForm.isFeasible,
    estimatedWorkload: evaluationForm.estimatedWorkload + '人天',
    estimatedCost: evaluationForm.estimatedCost ? '¥' + evaluationForm.estimatedCost : undefined,
    estimatedOnlineDate: evaluationForm.estimatedOnlineDate,
    evaluator: '当前用户'
  }

  const newLog = {
    id: String((currentRequirement.value?.logs?.length || 0) + 1),
    action: `评估信息已更新 - 预估工时：${evaluationForm.estimatedWorkload}人天`,
    user: '当前用户',
    time: new Date().toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).replace(/\//g, '-')
  }
  if (currentRequirement.value?.logs) {
    currentRequirement.value.logs.unshift(newLog)
  }

  showEvaluationModal.value = false
  alert('评估信息已提交')
}

const createRequirement = () => {
  if (!newRequirement.title) {
    alert('请输入需求标题')
    return
  }
  if (!newRequirement.project) {
    alert('请选择所属项目')
    return
  }

  const reqNo = 'REQ' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + String(tableData.value.length + 1).padStart(3, '0')
  const newReq: Requirement = {
    id: String(Date.now()),
    reqNo,
    title: newRequirement.title,
    project: newRequirement.project,
    subProject: newRequirement.subProject,
    businessLine: newRequirement.businessLine || '全渠道云鹿定制',
    type: newRequirement.type as '产品需求' | '项目需求',
    status: '待评估',
    statusClass: 'pending',
    priority: newRequirement.priority as '高' | '中' | '低',
    priorityClass: newRequirement.priority === '高' ? 'high' : newRequirement.priority === '中' ? 'medium' : 'low',
    owner: newRequirement.owner || '未分配',
    createdAt: new Date().toISOString().slice(0, 10),
    source: newRequirement.source as '内部' | '外部',
    requester: newRequirement.requester,
    expectDate: newRequirement.expectDate,
    description: newRequirement.description || '',
    attachments: [],
    logs: [{ id: '1', action: '需求已创建', user: '当前用户', time: new Date().toLocaleString('zh-CN') }],
    evaluation: undefined,
    designWorks: [],
    tasks: []
  }

  tableData.value.unshift(newReq)
  showCreateModal.value = false

  // 重置表单
  newRequirement.title = ''
  newRequirement.businessLine = ''
  newRequirement.project = ''
  newRequirement.subProject = ''
  newRequirement.source = '内部'
  newRequirement.requester = ''
  newRequirement.type = '产品需求'
  newRequirement.priority = '中'
  newRequirement.expectDate = ''
  newRequirement.owner = ''
  newRequirement.description = ''
  if (editorRef.value) {
    editorRef.value.innerHTML = ''
  }
}

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
        <div class="filter-pills">
          <button class="filter-pill" :class="{ active: filters.status.length === 0 && !filters.type && !filters.priority }" @click="clearAllFilters">所有需求</button>
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
              <span v-for="sub in project.subProjects" :key="sub" class="sub-tag" :class="{ selected: selectedSubProjects.includes(sub) }" @click.stop="toggleSubProject(sub)">
                {{ sub }}
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
            <th style="width: 100px">更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredData" :key="item.id" @click="handleRowClick(item.id)" @dblclick="handleRowDoubleClick(item.id)" style="cursor:pointer">
            <td><span class="req-no">{{ item.reqNo }}</span></td>
            <td class="highlight">{{ item.title }}</td>
            <td style="white-space: nowrap;">
              <span class="project-tag">{{ item.project }}</span>
              <span v-if="item.subProject" style="color: var(--gray-400);"> - {{ item.subProject }}</span>
            </td>
            <td style="font-size: 13px; white-space: nowrap;">{{ item.type }}</td>
            <td style="white-space: nowrap;"><span :class="['status-badge', getStatusBadgeClass(item.status)]">{{ item.status }}</span></td>
            <td style="white-space: nowrap;"><span :class="['priority-text', item.priorityClass]">{{ item.priority }}</span></td>
            <td style="font-size: 13px;">{{ item.owner }}</td>
            <td style="font-size: 13px;">{{ item.createdAt }}</td>
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
                    @click="newRequirement.project = project.name; newRequirement.subProject = ''"
                  >
                    <span class="project-name">{{ project.name }}</span>
                    <span v-if="project.subProjects && project.subProjects.length" class="sub-count">{{ project.subProjects.length }}个子项目</span>
                    <div v-if="project.subProjects && project.subProjects.length && newRequirement.project === project.name" class="sub-tags-modal">
                      <span
                        v-for="sub in project.subProjects"
                        :key="sub"
                        class="sub-tag-modal"
                        :class="{ selected: newRequirement.subProject === sub }"
                        @click.stop="newRequirement.subProject = newRequirement.subProject === sub ? '' : sub"
                      >{{ sub }}</span>
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
              <label class="form-label">提出人</label>
              <input type="text" class="form-input" v-model="newRequirement.requester" placeholder="请输入提出人">
            </div>

            <div class="form-group">
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
              <input type="date" class="form-input" v-model="newRequirement.expectDate">
            </div>

            <div class="form-group">
              <label class="form-label">负责人</label>
              <select class="form-select" v-model="newRequirement.owner">
                <option value="">请选择</option>
                <option value="张三">张三</option>
                <option value="李四">李四</option>
                <option value="王五">王五</option>
                <option value="赵六">赵六</option>
                <option value="钱七">钱七</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label">附件</label>
              <div class="upload-area-sm">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/>
                  <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                <span>上传附件</span>
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
            <button class="btn btn-sm btn-default" @click="openInNewPage">新窗口打开</button>
            <button class="drawer-close" @click="showDetailDrawer = false">×</button>
          </div>
        </div>
        <div class="drawer-body">
          <!-- 状态流转进度 -->
          <div class="status-flow">
            <div v-for="(step, index) in statusFlowSteps" :key="step.status" class="status-flow-item" :class="{ completed: isStepCompleted(step.status), current: currentRequirement?.status === step.status }">
              <div class="status-dot">{{ index + 1 }}</div>
              <div class="status-label">{{ step.label }}</div>
            </div>
          </div>

          <!-- 操作面板 -->
          <div class="action-panel" v-if="getCurrentActions().length > 0">
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
                <label>提出人</label>
                <div class="detail-value">{{ currentRequirement?.requester || '-' }}</div>
              </div>
            </div>
            <div class="detail-row two-cols">
              <div class="detail-item">
                <label>负责人</label>
                <div class="detail-value">{{ currentRequirement?.owner }}</div>
              </div>
              <div class="detail-item">
                <label>期望上线</label>
                <div class="detail-value">{{ currentRequirement?.expectDate || '-' }}</div>
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
            <div class="detail-description" v-html="currentRequirement?.description || '暂无描述'"></div>
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
  </div>
</template>

<style scoped>
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
  flex-direction: column;
  gap: 2px;
}

.project-card-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--gray-800);
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

.req-no {
  font-size: 12px;
  color: var(--gray-400);
  font-family: monospace;
}

.highlight {
  font-weight: 500;
  color: var(--gray-800);
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
  height: 700px;
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
  overflow-y: auto;
}

.form-left {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-right {
  width: 220px;
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
  min-height: 120px;
  max-height: 180px;
  display: flex;
  flex-direction: column;
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
  padding: 10px;
  font-size: 13px;
  line-height: 1.6;
  overflow-y: auto;
  outline: none;
}

.editor-content:empty:before {
  content: '请输入需求描述...';
  color: var(--gray-400);
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
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--gray-700);
  background: white;
  cursor: pointer;
  outline: none;
}

.form-select:focus {
  border-color: var(--primary);
}
</style>
