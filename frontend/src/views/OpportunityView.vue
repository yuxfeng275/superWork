<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, List, MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '@/utils/api'
import type { SalesOpportunityFollowUp, SalesOpportunityFollowUpPayload } from '@/utils/api'
import { useAuthStore } from '@/stores/auth'

type OpportunityType = '线索' | '商机'
type OpportunityStatus = '初步接触' | '需求确认' | '商务谈判' | '方案报价' | '已成交' | '已流失'

interface Opportunity {
  id: number
  name: string
  customer: string
  type: OpportunityType
  status: OpportunityStatus
  amount: number
  owner: string
  businessLine: string
  nextFollowUp: string
  createdAt: string
  probability: number
  expectedClose?: string
  source?: string
  note?: string
}

interface SupportWorkLog {
  id: number
  opportunityId: number
  supportDate: string
  supporter: string
  hours: number
  supportType: string
  content: string
  createdAt?: string
}

const seed: Opportunity[] = [
  { id: 128, name: '皇家项目-全渠道云升级', customer: '皇家宠物食品', type: '商机', status: '商务谈判', amount: 580, owner: '张群成', businessLine: '全渠道云', nextFollowUp: '今天 14:00', createdAt: '2024-03-15', probability: 70 },
  { id: 127, name: '飞鹤-SCRM系统采购', customer: '飞鹤乳业', type: '商机', status: '方案报价', amount: 320, owner: '姜涛', businessLine: '全渠道云', nextFollowUp: '明天 10:00', createdAt: '2024-03-12', probability: 50 },
  { id: 126, name: '佳贝艾特-会员通项目', customer: '佳贝艾特', type: '线索', status: '初步接触', amount: 150, owner: '李明', businessLine: '会员通', nextFollowUp: '3天后', createdAt: '2024-03-10', probability: 20 },
  { id: 125, name: '海普诺凯-数据中台项目', customer: '海普诺凯', type: '商机', status: '已成交', amount: 420, owner: '姜涛', businessLine: '数据中台', nextFollowUp: '已成交', createdAt: '2024-02-28', probability: 100 },
  { id: 124, name: 'Speedo-电商平台咨询', customer: 'Speedo中国', type: '线索', status: '需求确认', amount: 80, owner: '王芳', businessLine: '电商云', nextFollowUp: '下周一', createdAt: '2024-03-08', probability: 40 },
  { id: 123, name: '黄天鹅-私域运营营销系统', customer: '黄天鹅食品', type: '商机', status: '已流失', amount: 200, owner: '姜涛', businessLine: '全渠道云', nextFollowUp: '竞品中标', createdAt: '2024-02-15', probability: 0 },
  { id: 122, name: '逢时-海外业务线拓展', customer: '逢时集团', type: '线索', status: '初步接触', amount: 260, owner: '姜涛', businessLine: '海外产品', nextFollowUp: '5天后', createdAt: '2024-03-05', probability: 20 },
  { id: 121, name: 'SAAS平台-企业版续签', customer: '某知名美妆品牌', type: '商机', status: '商务谈判', amount: 180, owner: '于峰', businessLine: 'SAAS', nextFollowUp: '已逾期2天', createdAt: '2024-02-20', probability: 70 }
]
void seed

const authStore = useAuthStore()
const rows = ref<Opportunity[]>([])
const viewMode = ref<'list' | 'board'>('list')
const quickFilter = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const followVisible = ref(false)
const worklogVisible = ref(false)
const editingId = ref<number | null>(null)
const selectedOpportunity = ref<Opportunity | null>(null)
const draggingRowId = ref<number | null>(null)
const supportWorklogs = ref<SupportWorkLog[]>([])
const followUps = ref<SalesOpportunityFollowUp[]>([])
const followUpsLoading = ref(false)
const followSaving = ref(false)
const isCompactList = ref(false)
const filters = reactive({ keyword: '', type: '', status: '', owner: '', businessLine: '', amount: '' })
const form = reactive<Opportunity>({ id: 0, name: '', customer: '', type: '商机', status: '需求确认', amount: 0, owner: '', businessLine: '', nextFollowUp: '', createdAt: '', probability: 30, expectedClose: '', source: '', note: '' })
const followForm = reactive<SalesOpportunityFollowUpPayload>({
  followUpAt: '',
  follower: '',
  content: '',
  status: '需求确认',
  nextFollowUp: '',
  probability: 30
})
const worklogForm = reactive({ supportDate: '', supporter: '', hours: 1, supportType: '方案支持', content: '' })
const statusOptions: OpportunityStatus[] = ['初步接触', '需求确认', '方案报价', '商务谈判', '已成交', '已流失']
const probabilityPresets = [0, 25, 50, 75, 100]
const owners = ['张群成', '姜涛', '李明', '王芳', '于峰']
const fallbackBusinessLines = ['全渠道云', '会员通', '数据中台', '电商云', '海外产品', 'SAAS']
const businessLines = ref<string[]>([...fallbackBusinessLines])
const customerOptions = ref<string[]>([])
let compactListMedia: MediaQueryList | undefined

const syncCompactList = (event?: MediaQueryListEvent) => {
  isCompactList.value = event?.matches ?? compactListMedia?.matches ?? false
}

const filteredRows = computed(() => rows.value.filter(row => {
  const text = `${row.name}${row.customer || ''}${row.owner || ''}`
  return (!filters.keyword || text.includes(filters.keyword))
    && (!filters.type || row.type === filters.type)
    && (!filters.status || row.status === filters.status)
    && (!filters.owner || row.owner === filters.owner)
    && (!filters.businessLine || row.businessLine === filters.businessLine)
    && (!filters.amount || (filters.amount === 'high' ? row.amount >= 500 : row.amount < 500))
    && (!quickFilter.value
      || (quickFilter.value === 'today' && row.nextFollowUp?.includes('今天'))
      || (quickFilter.value === 'high' && row.amount >= 500)
      || (quickFilter.value === 'overdue' && row.nextFollowUp?.includes('逾期'))
      || (quickFilter.value === 'mine' && row.owner === owners[0]))
}))

const summary = computed(() => ({
  total: rows.value.length,
  progressing: rows.value.filter(row => !['已成交', '已流失'].includes(row.status)).length,
  stages: rows.value.filter(row => row.type === '商机').length,
  won: rows.value.filter(row => row.status === '已成交').length,
  lost: rows.value.filter(row => row.status === '已流失').length,
  totalAmount: rows.value.reduce((sum, row) => sum + Number(row.amount || 0), 0),
  wonAmount: rows.value.filter(row => row.status === '已成交').reduce((sum, row) => sum + Number(row.amount || 0), 0)
}))

const supportSummary = computed(() => {
  const totalHours = supportWorklogs.value.reduce((sum, item) => sum + Number(item.hours || 0), 0)
  return { totalHours, count: supportWorklogs.value.length }
})

const localDateTimeValue = (date = new Date()) => {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 19)
}

const formatFollowUpTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 16) : '未记录时间'

const currentFollowerName = () => {
  if (authStore.user?.realName) return authStore.user.realName
  try {
    return JSON.parse(localStorage.getItem('user') || '{}').realName || ''
  } catch {
    return ''
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    rows.value = await api.getSalesOpportunities()
  } catch {
    ElMessage.error('商机数据加载失败')
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  Object.assign(filters, { keyword: '', type: '', status: '', owner: '', businessLine: '', amount: '' })
  quickFilter.value = ''
}

const applyQuickFilter = (value: string) => {
  quickFilter.value = quickFilter.value === value ? '' : value
}

const selectBusinessLineFilter = (value: string) => {
  filters.businessLine = filters.businessLine === value ? '' : value
}

const selectFormBusinessLine = (value: string) => {
  form.businessLine = form.businessLine === value ? '' : value
}

const openCreate = () => {
  editingId.value = null
  Object.assign(form, { id: 0, name: '', customer: '', type: '商机', status: '需求确认', amount: 0, owner: '', businessLine: '', nextFollowUp: '', createdAt: new Date().toISOString().slice(0, 10), probability: 30, expectedClose: '', source: '', note: '' })
  dialogVisible.value = true
}

const openEdit = (row: Opportunity) => {
  editingId.value = row.id
  Object.assign(form, { ...row, amount: Number(row.amount || 0), probability: Number(row.probability ?? 30) })
  dialogVisible.value = true
}

const save = async () => {
  if (!form.name.trim()) return ElMessage.warning('请填写商机名称')
  const payload = { ...form, id: undefined, createdAt: undefined, customer: form.customer?.trim() || null, owner: form.owner?.trim() || null, businessLine: form.businessLine?.trim() || null }
  try {
    if (editingId.value) await api.updateSalesOpportunity(editingId.value, payload)
    else await api.createSalesOpportunity(payload)
    dialogVisible.value = false
    await loadRows()
    ElMessage.success(editingId.value ? '商机已更新' : '商机已创建')
  } catch {
    ElMessage.error('商机保存失败')
  }
}

const remove = async (row: Opportunity) => {
  await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '删除商机', { type: 'warning' })
  try {
    await api.deleteSalesOpportunity(row.id)
    await loadRows()
    ElMessage.success('商机已删除')
  } catch {
    ElMessage.error('商机删除失败')
  }
}

const loadSupportWorklogs = async (opportunityId: number) => {
  try {
    supportWorklogs.value = await api.getSalesOpportunitySupportWorklogs(opportunityId)
  } catch {
    supportWorklogs.value = []
    ElMessage.error('售前支持工时加载失败')
  }
}

const loadFollowUps = async (opportunityId: number) => {
  followUpsLoading.value = true
  try {
    followUps.value = await api.getSalesOpportunityFollowUps(opportunityId)
  } catch {
    followUps.value = []
    ElMessage.error('跟进记录加载失败')
  } finally {
    followUpsLoading.value = false
  }
}

const openDetail = async (row: Opportunity) => {
  selectedOpportunity.value = row
  detailVisible.value = true
  await Promise.all([loadSupportWorklogs(row.id), loadFollowUps(row.id)])
}

const openFollow = async (row: Opportunity) => {
  selectedOpportunity.value = row
  Object.assign(followForm, {
    followUpAt: localDateTimeValue(),
    follower: currentFollowerName() || row.owner || '',
    content: '',
    status: row.status,
    nextFollowUp: row.nextFollowUp || '',
    probability: Number(row.probability ?? 30)
  })
  followVisible.value = true
  await loadFollowUps(row.id)
}

const saveFollow = async () => {
  if (!selectedOpportunity.value || followSaving.value) return
  if (!followForm.follower.trim()) return ElMessage.warning('请填写跟进人')
  if (!followForm.content.trim()) return ElMessage.warning('请填写跟进情况')
  const targetId = selectedOpportunity.value.id
  const payload: SalesOpportunityFollowUpPayload = {
    ...followForm,
    follower: followForm.follower.trim(),
    content: followForm.content.trim(),
    nextFollowUp: followForm.nextFollowUp?.trim() || undefined
  }
  followSaving.value = true
  try {
    await api.createSalesOpportunityFollowUp(targetId, payload)
    await Promise.all([loadRows(), loadFollowUps(targetId)])
    const refreshed = rows.value.find(item => item.id === targetId)
    if (refreshed) {
      refreshed.status = followForm.status as OpportunityStatus
      refreshed.probability = followForm.probability
      refreshed.nextFollowUp = followForm.nextFollowUp || ''
      selectedOpportunity.value = refreshed
    }
    followForm.followUpAt = localDateTimeValue()
    followForm.content = ''
    ElMessage.success('跟进记录已添加')
  } catch {
    ElMessage.error('跟进记录保存失败')
  } finally {
    followSaving.value = false
  }
}

const openWorklog = async (row: Opportunity) => {
  selectedOpportunity.value = row
  Object.assign(worklogForm, { supportDate: new Date().toISOString().slice(0, 10), supporter: '', hours: 1, supportType: '方案支持', content: '' })
  worklogVisible.value = true
  await loadSupportWorklogs(row.id)
}

const handleRowAction = (command: string, row: Opportunity) => {
  if (command === 'follow') void openFollow(row)
  else if (command === 'worklog') void openWorklog(row)
  else if (command === 'edit') openEdit(row)
  else if (command === 'remove') void remove(row)
}

const saveWorklog = async () => {
  if (!selectedOpportunity.value) return
  if (!worklogForm.supporter.trim()) return ElMessage.warning('请填写支持人员')
  if (!worklogForm.hours || worklogForm.hours <= 0) return ElMessage.warning('请填写有效工时')
  if (!worklogForm.content.trim()) return ElMessage.warning('请填写支持内容')
  try {
    await api.createSalesOpportunitySupportWorklog(selectedOpportunity.value.id, { ...worklogForm, supporter: worklogForm.supporter.trim(), content: worklogForm.content.trim() })
    await loadSupportWorklogs(selectedOpportunity.value.id)
    Object.assign(worklogForm, { supportDate: new Date().toISOString().slice(0, 10), supporter: '', hours: 1, supportType: '方案支持', content: '' })
    ElMessage.success('售前支持工时已登记')
  } catch {
    ElMessage.error('售前支持工时登记失败')
  }
}

const startBoardDrag = (row: Opportunity, event: DragEvent) => {
  draggingRowId.value = row.id
  event.dataTransfer?.setData('text/plain', String(row.id))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

const finishBoardDrag = () => {
  draggingRowId.value = null
}

const dropToStatus = async (status: OpportunityStatus, event?: DragEvent) => {
  const droppedId = Number(event?.dataTransfer?.getData('text/plain') || draggingRowId.value)
  const row = rows.value.find(item => item.id === droppedId)
  draggingRowId.value = null
  if (!row || row.status === status) return
  const previousStatus = row.status
  row.status = status
  try {
    await api.updateSalesOpportunity(row.id, { ...row, id: undefined, createdAt: undefined })
    ElMessage.success(`已移动到「${status}」`)
  } catch {
    row.status = previousStatus
    ElMessage.error('状态更新失败')
  }
}

const statusClass = (status: OpportunityStatus) => ({ '初步接触': 'contact', '需求确认': 'confirm', '方案报价': 'quote', '商务谈判': 'negotiating', '已成交': 'won', '已流失': 'lost' }[status])

const formatCreatedAt = (value?: string) => {
  if (!value) return '-'
  const normalized = value.trim().replace('T', ' ')
  const match = normalized.match(/^(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}))?/)
  if (!match) return normalized
  return match[2] ? `${match[1]} ${match[2]}` : match[1]
}

onMounted(() => {
  compactListMedia = window.matchMedia('(max-width: 680px)')
  syncCompactList()
  compactListMedia.addEventListener('change', syncCompactList)
})

onBeforeUnmount(() => compactListMedia?.removeEventListener('change', syncCompactList))

onMounted(async () => {
  await loadRows()
  try {
    const [contacts, businessLinePayload] = await Promise.all([api.getCustomerContacts(), api.getBusinessLines({ page: 1, size: 500, status: 1 })])
    customerOptions.value = Array.from(new Set((contacts || []).map((item: any) => item.company || item.name).filter(Boolean)))
    const records = Array.isArray(businessLinePayload)
      ? businessLinePayload
      : businessLinePayload?.records || businessLinePayload?.data?.records || businessLinePayload?.data || []
    const names = records.map((item: any) => typeof item === 'string' ? item : item?.name).filter(Boolean)
    if (names.length) businessLines.value = Array.from(new Set(names))
  } catch {
    customerOptions.value = []
  }
})
</script>

<template>
  <div class="opportunity-page">
    <section class="opportunity-summary" aria-label="商机概览">
      <article><span class="summary-icon blue"><el-icon><List /></el-icon></span><div><small>全部线索</small><strong>{{ summary.total }}</strong><em>↑ +12.5%</em><p>较上月新增 14 条</p></div></article>
      <article><span class="summary-icon indigo"><el-icon><List /></el-icon></span><div><small>跟进中</small><strong>{{ summary.progressing }}</strong><em>↑ +8.3%</em><p>预计金额 ¥{{ summary.totalAmount.toLocaleString() }}万</p></div></article>
      <article><span class="summary-icon amber"><el-icon><List /></el-icon></span><div><small>商机阶段</small><strong>{{ summary.stages }}</strong><em>↑ +15.2%</em><p>预计金额 ¥{{ summary.totalAmount.toLocaleString() }}万</p></div></article>
      <article><span class="summary-icon green"><el-icon><List /></el-icon></span><div><small>本月成交</small><strong>{{ summary.won }}</strong><em>↑ +23.1%</em><p>成交金额 ¥{{ summary.wonAmount.toLocaleString() }}万</p></div></article>
      <article><span class="summary-icon red"><el-icon><List /></el-icon></span><div><small>已流失</small><strong>{{ summary.lost }}</strong><em class="down">↓ -5.2%</em><p>流失率 {{ summary.total ? Math.round(summary.lost / summary.total * 1000) / 10 : 0 }}%</p></div></article>
    </section>

    <section class="opportunity-filter-panel">
      <div class="filter-grid">
        <el-input v-model="filters.keyword" placeholder="搜索线索名称、客户、负责人" :prefix-icon="Search" clearable />
        <el-select v-model="filters.type" placeholder="类型" clearable><el-option label="全部" value="" /><el-option label="线索" value="线索" /><el-option label="商机" value="商机" /></el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable><el-option label="全部状态" value="" /><el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" /></el-select>
        <el-select v-model="filters.owner" placeholder="负责人" clearable><el-option label="全部人员" value="" /><el-option v-for="item in owners" :key="item" :label="item" :value="item" /></el-select>
        <el-select v-model="filters.amount" placeholder="预计金额" clearable><el-option label="不限" value="" /><el-option label="500万以上" value="high" /><el-option label="500万以下" value="low" /></el-select>
      </div>
      <div class="business-tabs filter-business-tabs" aria-label="按业务线筛选">
        <button type="button" :class="{ active: !filters.businessLine }" @click="filters.businessLine = ''">全部业务线</button>
        <button v-for="item in businessLines" :key="item" type="button" :class="{ active: filters.businessLine === item }" :title="item" @click="selectBusinessLineFilter(item)">{{ item }}</button>
      </div>
      <div class="filter-actions"><el-button :icon="Refresh" @click="resetFilters">重置</el-button><el-button type="primary" :icon="Search">查询</el-button></div>
      <div class="view-actions">
        <el-radio-group v-model="viewMode" size="small"><el-radio-button value="list"><el-icon><List /></el-icon> 列表视图</el-radio-button><el-radio-button value="board">看板视图</el-radio-button></el-radio-group>
        <span class="quick-tabs"><button :class="{ active: quickFilter === 'mine' }" @click="applyQuickFilter('mine')">我的线索</button><button :class="{ active: quickFilter === 'today' }" @click="applyQuickFilter('today')">今日待跟进</button><button :class="{ active: quickFilter === 'high' }" @click="applyQuickFilter('high')">高价值商机</button><button :class="{ active: quickFilter === 'overdue' }" @click="applyQuickFilter('overdue')">即将逾期</button></span>
        <span class="spacer" />
        <el-button :icon="Download">导出</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建商机</el-button>
      </div>
    </section>

    <section v-if="viewMode === 'board'" class="opportunity-board" aria-label="商机看板">
      <article v-for="status in statusOptions" :key="status" class="board-column" @dragenter.prevent @dragover.prevent @drop.prevent="dropToStatus(status, $event)">
        <header><span class="status-pill" :class="statusClass(status)"><i />{{ status }}</span><strong>{{ filteredRows.filter(row => row.status === status).length }}</strong></header>
        <div class="board-stack">
          <div v-for="row in filteredRows.filter(item => item.status === status)" :key="row.id" class="board-card" :class="{ dragging: draggingRowId === row.id }" draggable="true" @dragstart="startBoardDrag(row, $event)" @dragend="finishBoardDrag">
            <small>{{ row.type }} · {{ row.businessLine || '未填写业务线' }}</small>
            <h3>{{ row.name }}</h3>
            <p>{{ row.customer || '未填写客户' }}</p>
            <div><strong>¥ {{ Number(row.amount || 0).toFixed(0) }}万</strong><span>{{ row.owner || '未填写' }}</span></div>
            <footer><span>{{ row.nextFollowUp || '待安排跟进' }}</span><el-button link type="primary" @click="openFollow(row)">跟进</el-button></footer>
          </div>
          <el-empty v-if="!filteredRows.some(row => row.status === status)" description="拖到这里改变状态" :image-size="36" />
        </div>
      </article>
    </section>

    <section v-else class="opportunity-table-panel" aria-label="商机列表">
      <el-table v-loading="loading" :data="filteredRows" row-key="id" class="opportunity-table" scrollbar-always-on>
        <el-table-column type="selection" width="48" />
        <el-table-column label="线索名称" min-width="260" sortable>
          <template #default="{ row }">
            <div class="opportunity-name">
              <i :class="`priority-${statusClass(row.status)}`" />
              <strong :title="row.name">{{ row.name }}</strong>
              <small>编号：LEAD-2024-00{{ row.id }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="customer" label="客户公司" min-width="140" />
        <el-table-column label="类型" width="88"><template #default="{ row }"><el-tag effect="light" :class="`type-${row.type === '商机' ? 'opportunity' : 'lead'}`">{{ row.type }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{ row }"><span class="status-pill" :class="statusClass(row.status)"><i />{{ row.status }}</span></template></el-table-column>
        <el-table-column label="预计金额" width="130" sortable><template #default="{ row }"><strong>¥ {{ Number(row.amount || 0).toFixed(2) }}万</strong></template></el-table-column>
        <el-table-column prop="owner" label="负责人" width="105" />
        <el-table-column prop="businessLine" label="业务线" min-width="190"><template #default="{ row }"><span class="line-chip" :title="row.businessLine">{{ row.businessLine || '未填写' }}</span></template></el-table-column>
        <el-table-column label="创建时间" width="150" sortable prop="createdAt"><template #default="{ row }"><time :datetime="row.createdAt">{{ formatCreatedAt(row.createdAt) }}</time></template></el-table-column>
        <el-table-column label="操作" :width="isCompactList ? 104 : 220" fixed="right">
          <template #default="{ row }">
            <div v-if="isCompactList" class="compact-row-actions">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-dropdown trigger="click" @command="handleRowAction($event, row)">
                <el-button link type="primary" aria-label="更多操作"><el-icon><MoreFilled /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="follow">跟进</el-dropdown-item>
                    <el-dropdown-item command="worklog">登记工时</el-dropdown-item>
                    <el-dropdown-item command="edit">编辑</el-dropdown-item>
                    <el-dropdown-item command="remove" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <div v-else class="desktop-row-actions">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openFollow(row)">跟进</el-button>
              <el-button link type="primary" @click="openWorklog(row)">工时</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <footer class="table-footer">共 {{ filteredRows.length }} 条记录<span>每页 8 条</span></footer>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑商机' : '新建商机'" width="640px" class="opportunity-dialog">
      <el-form label-position="top" class="opportunity-form">
        <el-form-item label="商机名称" required><el-input v-model="form.name" placeholder="请输入商机名称，如：XX公司-年度合作项目" /></el-form-item>
        <div class="form-two"><el-form-item label="客户公司"><el-select v-model="form.customer" filterable allow-create default-first-option clearable placeholder="可选择已有客户，也可手动输入"><el-option v-for="item in customerOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="商机阶段"><el-radio-group v-model="form.status" class="stage-tabs" size="small"><el-radio-button v-for="item in statusOptions" :key="item" :label="item">{{ item }}</el-radio-button></el-radio-group></el-form-item></div>
        <div class="form-two"><el-form-item label="预计金额"><el-input-number v-model="form.amount" :min="0" :controls="false" style="width:100%" /></el-form-item><el-form-item label="负责人"><el-input v-model="form.owner" placeholder="请输入负责人姓名（可不填）" /></el-form-item></div>
        <el-form-item label="所属业务线"><div class="business-tabs form-business-tabs" aria-label="选择所属业务线"><button type="button" :class="{ active: !form.businessLine }" @click="form.businessLine = ''">暂不选择</button><button v-for="item in businessLines" :key="item" type="button" :class="{ active: form.businessLine === item }" :title="item" @click="selectFormBusinessLine(item)">{{ item }}</button></div></el-form-item>
        <el-form-item label="成交概率"><div class="probability-field"><div class="probability-head"><div class="probability-bar" aria-hidden="true"><div class="probability-fill" :style="{ width: `${form.probability}%` }" /></div><output class="probability-value">{{ form.probability }}%</output></div><el-slider v-model="form.probability" :min="0" :max="100" :step="5" :show-tooltip="false" aria-label="成交概率" /><div class="probability-presets" aria-label="成交概率快捷选择"><button v-for="preset in probabilityPresets" :key="preset" type="button" :class="{ active: form.probability === preset }" @click="form.probability = preset">{{ preset }}%</button></div></div></el-form-item>
        <div class="form-two"><el-form-item label="预计成交日期"><el-date-picker v-model="form.expectedClose" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" /></el-form-item><el-form-item label="商机来源"><el-input v-model="form.source" placeholder="选择商机来源渠道" /></el-form-item></div>
        <el-form-item label="备注说明"><el-input v-model="form.note" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="补充客户背景、关键信息等..." /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">{{ editingId ? '保存修改' : '创建商机' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="商机详情" width="760px" class="opportunity-dialog">
      <div v-if="selectedOpportunity" class="detail-layout">
        <section class="detail-main">
          <h3>{{ selectedOpportunity.name }}</h3>
          <p>{{ selectedOpportunity.note || '暂无备注' }}</p>
          <div class="detail-grid">
            <span>客户公司<strong>{{ selectedOpportunity.customer || '未填写' }}</strong></span>
            <span>负责人<strong>{{ selectedOpportunity.owner || '未填写' }}</strong></span>
            <span>业务线<strong>{{ selectedOpportunity.businessLine || '未填写' }}</strong></span>
            <span>预计金额<strong>¥ {{ Number(selectedOpportunity.amount || 0).toFixed(2) }}万</strong></span>
            <span>下次跟进<strong>{{ selectedOpportunity.nextFollowUp || '待安排' }}</strong></span>
            <span>成交概率<strong>{{ selectedOpportunity.probability }}%</strong></span>
          </div>
        </section>
        <aside class="support-panel">
          <header><strong>售前支持</strong><span>{{ supportSummary.count }} 条 · {{ supportSummary.totalHours }}h</span></header>
          <div class="support-list">
            <p v-if="!supportWorklogs.length">暂无售前支持工时</p>
            <article v-for="item in supportWorklogs" :key="item.id"><strong>{{ item.supporter }} · {{ item.hours }}h</strong><span>{{ item.supportDate }} · {{ item.supportType }}</span><p>{{ item.content }}</p></article>
          </div>
        </aside>
        <section v-loading="followUpsLoading" class="follow-history detail-follow-history" aria-label="商机跟进历史">
          <header><strong>跟进历史</strong><span>{{ followUps.length }} 条记录</span></header>
          <ol v-if="followUps.length" class="follow-timeline">
            <li v-for="item in followUps" :key="item.id">
              <i aria-hidden="true" />
              <article>
                <header><strong>{{ item.follower }}</strong><time>{{ formatFollowUpTime(item.followUpAt) }}</time></header>
                <div class="follow-snapshot"><span>{{ item.status }}</span><span>{{ item.probability }}%</span><span>下次：{{ item.nextFollowUp || '待安排' }}</span></div>
                <p>{{ item.content }}</p>
              </article>
            </li>
          </ol>
          <p v-else class="empty-history">暂无跟进记录</p>
        </section>
      </div>
      <template #footer><el-button v-if="selectedOpportunity" @click="openWorklog(selectedOpportunity)">登记工时</el-button><el-button type="primary" @click="detailVisible=false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="followVisible" title="商机跟进记录" width="min(680px, 94vw)" class="opportunity-dialog follow-dialog">
      <el-form label-position="top" class="opportunity-form">
        <div class="form-two equal-columns">
          <el-form-item label="跟进时间" required><el-date-picker v-model="followForm.followUpAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" aria-label="跟进时间" placeholder="选择跟进时间" /></el-form-item>
          <el-form-item label="跟进人" required><el-input v-model="followForm.follower" aria-label="跟进人" placeholder="默认当前用户，可修改" /></el-form-item>
        </div>
        <el-form-item label="当前阶段"><el-radio-group v-model="followForm.status" class="stage-tabs" size="small"><el-radio-button v-for="item in statusOptions" :key="item" :label="item">{{ item }}</el-radio-button></el-radio-group></el-form-item>
        <div class="form-two equal-columns">
          <el-form-item label="下次跟进"><el-input v-model="followForm.nextFollowUp" placeholder="如：明天 10:00 / 下周三 / 已成交" /></el-form-item>
          <el-form-item label="成交概率"><div class="probability-field compact"><div class="probability-head"><div class="probability-bar" aria-hidden="true"><div class="probability-fill" :style="{ width: `${followForm.probability}%` }" /></div><output class="probability-value">{{ followForm.probability }}%</output></div><el-slider v-model="followForm.probability" :min="0" :max="100" :step="5" :show-tooltip="false" aria-label="成交概率" /></div></el-form-item>
        </div>
        <el-form-item label="跟进情况" required><el-input v-model="followForm.content" aria-label="跟进情况" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="记录客户反馈、推进动作、风险点和本次结论" /></el-form-item>
      </el-form>
      <section v-loading="followUpsLoading" class="follow-history" aria-label="历史跟进记录">
        <header><strong>历史跟进</strong><span>{{ followUps.length }} 条记录</span></header>
        <ol v-if="followUps.length" class="follow-timeline">
          <li v-for="item in followUps" :key="item.id">
            <i aria-hidden="true" />
            <article>
              <header><strong>{{ item.follower }}</strong><time>{{ formatFollowUpTime(item.followUpAt) }}</time></header>
              <div class="follow-snapshot"><span>{{ item.status }}</span><span>{{ item.probability }}%</span><span>下次：{{ item.nextFollowUp || '待安排' }}</span></div>
              <p>{{ item.content }}</p>
            </article>
          </li>
        </ol>
        <p v-else class="empty-history">暂无跟进记录</p>
      </section>
      <template #footer><el-button @click="followVisible=false">关闭</el-button><el-button type="primary" :loading="followSaving" @click="saveFollow">添加跟进记录</el-button></template>
    </el-dialog>

    <el-dialog v-model="worklogVisible" title="售前支持工时登记" width="620px" class="opportunity-dialog">
      <el-form label-position="top" class="opportunity-form">
        <div class="form-two"><el-form-item label="支持日期" required><el-date-picker v-model="worklogForm.supportDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" /></el-form-item><el-form-item label="支持人员" required><el-input v-model="worklogForm.supporter" placeholder="请输入支持人员" /></el-form-item></div>
        <div class="form-two"><el-form-item label="工时" required><el-input-number v-model="worklogForm.hours" :min="0.5" :step="0.5" :controls="false" style="width:100%" /></el-form-item><el-form-item label="支持类型"><el-select v-model="worklogForm.supportType"><el-option label="方案支持" value="方案支持" /><el-option label="客户交流" value="客户交流" /><el-option label="报价支持" value="报价支持" /><el-option label="投标支持" value="投标支持" /><el-option label="演示支持" value="演示支持" /></el-select></el-form-item></div>
        <el-form-item label="支持内容" required><el-input v-model="worklogForm.content" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="填写售前支持内容和产出" /></el-form-item>
      </el-form>
      <div class="support-history"><header>历史登记<span>{{ supportSummary.totalHours }}h</span></header><article v-for="item in supportWorklogs" :key="item.id"><strong>{{ item.supporter }} · {{ item.hours }}h</strong><span>{{ item.supportDate }} · {{ item.supportType }}</span><p>{{ item.content }}</p></article><p v-if="!supportWorklogs.length">暂无历史登记</p></div>
      <template #footer><el-button @click="worklogVisible=false">关闭</el-button><el-button type="primary" @click="saveWorklog">登记工时</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.opportunity-page { width:100%; min-width:0; color:#25324b; }
.opportunity-summary { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:16px; margin-bottom:24px; }
.opportunity-summary article { display:flex; gap:14px; min-width:0; min-height:112px; padding:18px; border:1px solid #e7ebf3; border-radius:14px; background:#fff; box-shadow:0 5px 18px rgb(30 41 80 / 5%); }
.opportunity-summary article > div { min-width:0; flex:1; overflow:hidden; }
.summary-icon { width:40px; height:40px; display:grid; place-items:center; border-radius:10px; flex:0 0 auto; }
.summary-icon.blue{color:#4f46e5;background:#eef2ff}.summary-icon.indigo{color:#2563eb;background:#eff6ff}.summary-icon.amber{color:#d97706;background:#fffbeb}.summary-icon.green{color:#059669;background:#ecfdf5}.summary-icon.red{color:#ef4444;background:#fef2f2}
.opportunity-summary small,.opportunity-summary p { display:block;color:#8492ab;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }
.opportunity-summary strong{font-size:26px;line-height:1.2;margin-right:9px}.opportunity-summary em{font-size:12px;color:#00b87a;font-style:normal;white-space:nowrap}.opportunity-summary em.down{color:#ef4444}.opportunity-summary p{margin:9px 0 0}
.opportunity-filter-panel,.opportunity-table-panel{min-width:0;max-width:100%;padding:18px;border:1px solid #e7ebf3;border-radius:14px;background:#fff;box-shadow:0 5px 18px rgb(30 41 80 / 4%)}
.opportunity-filter-panel{position:relative;margin-bottom:16px}
.filter-grid{display:grid;grid-template-columns:1.7fr repeat(4,1fr);gap:12px;padding-right:180px}
.filter-actions{position:absolute;right:18px;top:18px}
.business-tabs{display:flex;flex-wrap:wrap;gap:8px;width:100%}
.business-tabs button{max-width:100%;min-height:30px;padding:5px 12px;border:1px solid #e1e7f0;border-radius:7px;color:#52617b;background:#fff;font-size:12px;line-height:1.45;text-align:left;cursor:pointer}
.business-tabs button:hover,.business-tabs button.active{color:#4f46e5;border-color:#c7d2fe;background:#eef2ff}
.filter-business-tabs{margin-top:12px;padding-right:180px}
.form-business-tabs{padding-top:2px}
.view-actions{display:flex;align-items:center;gap:10px;margin-top:16px;padding-top:16px;border-top:1px solid #eff2f7}
.quick-tabs{display:flex;min-width:0;max-width:100%;gap:5px}.quick-tabs button{flex:0 0 auto;padding:8px 13px;border:0;border-radius:18px;color:#5f6f89;background:#f5f7fb;white-space:nowrap;cursor:pointer}.quick-tabs button.active{color:#4f46e5;background:#eef2ff}.view-actions .spacer{flex:1}
.opportunity-table-panel{padding:0;overflow:hidden}.opportunity-table{width:100%;max-width:100%}
.compact-row-actions,.desktop-row-actions{display:flex;align-items:center;justify-content:center}.compact-row-actions{gap:2px}.compact-row-actions :deep(.el-button + .el-button){margin-left:0}
.opportunity-name{position:relative;min-width:0;padding-left:14px}.opportunity-name i{position:absolute;left:0;top:3px;width:4px;height:34px;border-radius:3px;background:#aeb9cb}
.opportunity-name i.priority-negotiating,.opportunity-name i.priority-lost{background:#ef4444}.opportunity-name i.priority-quote{background:#f59e0b}.opportunity-name i.priority-contact{background:#10b981}
.opportunity-name strong,.opportunity-name small{display:block;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.opportunity-name small{margin-top:4px;color:#8b9ab1;font-size:11px}
.type-opportunity{color:#d97706!important;background:#fffbeb!important;border-color:#fde68a!important}.type-lead{color:#2563eb!important;background:#eff6ff!important;border-color:#bfdbfe!important}
.status-pill{display:inline-flex;align-items:center;gap:6px;padding:6px 10px;border-radius:17px;color:#52617b;background:#f1f5f9;font-size:12px;white-space:nowrap}.status-pill i{width:6px;height:6px;border-radius:50%;background:#94a3b8}
.status-pill.negotiating{color:#2563eb;background:#eff6ff}.status-pill.negotiating i,.status-pill.confirm i{background:#3b82f6}.status-pill.quote{color:#4f46e5;background:#eef2ff}.status-pill.quote i{background:#6366f1}.status-pill.won{color:#059669;background:#ecfdf5}.status-pill.won i{background:#10b981}.status-pill.lost{color:#ef4444;background:#fef2f2}.status-pill.lost i{background:#ef4444}
.line-chip{display:inline-block;max-width:100%;padding:5px 9px;border-radius:5px;color:#52617b;background:#f1f5f9;font-size:12px;white-space:normal;word-break:break-all;line-height:1.35}.table-footer{display:flex;justify-content:space-between;padding:15px 18px;color:#657691;font-size:12px}.table-footer span{color:#8b9ab1}
.form-two{display:grid;grid-template-columns:1.5fr 1fr;gap:14px}.stage-tabs{display:flex;flex-wrap:wrap;gap:6px;width:100%}.stage-tabs :deep(.el-radio-button__inner){border:1px solid #dfe5ef!important;border-radius:8px!important;box-shadow:none!important;padding:7px 10px;color:#657691}.stage-tabs :deep(.el-radio-button:first-child .el-radio-button__inner),.stage-tabs :deep(.el-radio-button:last-child .el-radio-button__inner){border-radius:8px!important}.stage-tabs :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner){color:#4f46e5;background:#eef2ff;border-color:#a5b4fc!important;box-shadow:none}
.opportunity-dialog :deep(.el-dialog__body){padding-top:8px}.opportunity-form :deep(.el-form-item){margin-bottom:14px}.opportunity-form :deep(.el-date-editor),.opportunity-form :deep(.el-select){width:100%}
.probability-field{width:100%;padding:2px 4px 0}.probability-head{display:grid;grid-template-columns:minmax(0,1fr) 48px;align-items:center;gap:12px}.probability-bar{height:10px;border-radius:999px;background:#edf0f6;overflow:hidden}.probability-fill{height:100%;border-radius:999px;background:#4f46e5;transition:width .12s ease}.probability-value{display:block;min-width:48px;padding:3px 8px;border:1px solid #c7d2fe;border-radius:6px;color:#4f46e5;background:#eef2ff;font-size:12px;font-weight:700;line-height:18px;text-align:center}.probability-field :deep(.el-slider){margin:3px 8px 0}.probability-field :deep(.el-slider__runway){margin:12px 0;background:#e5eaf3}.probability-field :deep(.el-slider__bar){background:#4f46e5}.probability-presets{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px;margin-top:2px}.probability-presets button{height:26px;padding:0 7px;border:1px solid #e1e7f0;border-radius:6px;color:#64748b;background:#fff;font-size:12px;cursor:pointer}.probability-presets button:hover,.probability-presets button.active{color:#4f46e5;border-color:#c7d2fe;background:#eef2ff}
:global(.el-select-dropdown__item){min-width:220px;white-space:nowrap}
.opportunity-board{display:grid;grid-template-columns:repeat(6,minmax(210px,1fr));gap:12px;overflow-x:auto;padding-bottom:8px}.board-column{min-height:420px;padding:12px;border:1px solid #e7ebf3;border-radius:12px;background:#f8fafc}.board-column>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.board-column>header>strong{color:#8391a7;font-size:13px}.board-stack{display:grid;gap:10px}.board-card{padding:13px;border:1px solid #e7ebf3;border-radius:10px;background:#fff;box-shadow:0 3px 10px rgb(30 41 80 / 4%);cursor:grab}.board-card.dragging{opacity:.55}.board-card>small,.board-card>p,.board-card>footer{color:#8391a7;font-size:11px}.board-card h3{margin:8px 0 5px;color:#25324b;font-size:13px;line-height:1.45}.board-card p{margin:0}.board-card>div{display:flex;justify-content:space-between;align-items:center;margin-top:12px}.board-card>div strong{font-size:13px}.board-card>div span{color:#52617b;font-size:12px}.board-card>footer{display:flex;justify-content:space-between;align-items:center;margin-top:11px;padding-top:9px;border-top:1px solid #eff2f7}
.detail-layout{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(240px,.9fr);gap:16px}.detail-main h3{margin:0 0 8px;font-size:18px}.detail-main>p{min-height:54px;margin:0 0 14px;color:#64748b;line-height:1.7}.detail-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.detail-grid span{display:grid;gap:6px;padding:11px;border:1px solid #e7ebf3;border-radius:8px;color:#8391a7;font-size:12px}.detail-grid strong{color:#25324b;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.support-panel,.support-history{border:1px solid #e7ebf3;border-radius:10px;background:#f8fafc}.support-panel{padding:12px}.support-panel header,.support-history header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;color:#25324b}.support-panel header span,.support-history header span{color:#64748b;font-size:12px}.support-list,.support-history{display:grid;gap:8px}.support-list article,.support-history article{padding:10px;border:1px solid #e7ebf3;border-radius:8px;background:#fff}.support-list article strong,.support-history article strong{display:block;font-size:13px}.support-list article span,.support-history article span{display:block;margin-top:4px;color:#8391a7;font-size:12px}.support-list article p,.support-history article p{margin:8px 0 0;color:#52617b;font-size:12px;line-height:1.6}.support-list>p,.support-history>p{margin:0;color:#8b9ab1;font-size:12px}.support-history{max-height:220px;margin-top:6px;padding:12px;overflow:auto}
.form-two.equal-columns{grid-template-columns:repeat(2,minmax(0,1fr))}
.probability-field.compact{padding-top:0}
.follow-history{padding:12px;border:1px solid #e7ebf3;border-radius:10px;background:#f8fafc}
.detail-follow-history{grid-column:1/-1}
.follow-history>header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}
.follow-history>header strong{color:#25324b;font-size:14px}.follow-history>header span{color:#8391a7;font-size:12px}
.follow-timeline{max-height:260px;overflow-y:auto;overscroll-behavior:contain;list-style:none;margin:0;padding:0 4px 0 0}
.follow-timeline li{position:relative;display:grid;grid-template-columns:18px minmax(0,1fr);gap:8px;padding:0 0 12px}
.follow-timeline li:last-child{padding-bottom:0}.follow-timeline li>i{position:relative;width:9px;height:9px;margin:6px 0 0 3px;border:2px solid #fff;border-radius:50%;background:#4f46e5;box-shadow:0 0 0 2px #c7d2fe;z-index:1}
.follow-timeline li:not(:last-child)::before{content:'';position:absolute;top:15px;bottom:0;left:7px;width:1px;background:#dbe3ef}
.follow-timeline article{min-width:0;padding:10px 12px;border:1px solid #e7ebf3;border-radius:8px;background:#fff}
.follow-timeline article>header{display:flex;align-items:center;justify-content:space-between;gap:10px}.follow-timeline article>header strong{color:#25324b;font-size:13px}.follow-timeline time{color:#8391a7;font-size:11px;white-space:nowrap}
.follow-snapshot{display:flex;flex-wrap:wrap;gap:6px;margin-top:7px}.follow-snapshot span{padding:3px 7px;border-radius:5px;color:#52617b;background:#f1f5f9;font-size:11px}
.follow-timeline article>p{margin:8px 0 0;color:#40506a;font-size:12px;line-height:1.65;white-space:pre-wrap;overflow-wrap:anywhere}.empty-history{margin:0;padding:14px;color:#8b9ab1;font-size:12px;text-align:center}
@media (max-width:1100px){.opportunity-summary{grid-template-columns:repeat(3,1fr)}.filter-grid{grid-template-columns:repeat(3,1fr);padding-right:0}.filter-actions{position:static;margin-top:12px}.filter-business-tabs{padding-right:0}.view-actions{flex-wrap:wrap}.detail-layout{grid-template-columns:1fr}}
@media (max-width:680px){.opportunity-summary{grid-template-columns:repeat(2,1fr)}.opportunity-summary article{padding:13px;min-height:96px}.summary-icon{width:32px;height:32px}.opportunity-summary strong{font-size:21px}.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.filter-grid>:first-child{grid-column:1/-1}.form-two,.form-two.equal-columns,.detail-grid{grid-template-columns:1fr}.quick-tabs{width:100%;overflow-x:auto}.view-actions .spacer{display:none}}
</style>
