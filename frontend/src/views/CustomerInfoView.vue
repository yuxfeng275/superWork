<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/utils/api'

interface BusinessLine {
  id: number
  name: string
}

interface Project {
  id: number
  businessLineId: number
  name: string
  code?: string
}

interface CustomerContact {
  id: number
  projectId: number
  name: string
  company?: string
  position?: string
  phone?: string
  email?: string
  isActive: number
  createdAt?: string
  updatedAt?: string
}

const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const businessLines = ref<BusinessLine[]>([])
const projects = ref<Project[]>([])
const contacts = ref<CustomerContact[]>([])

const filters = ref({
  businessLineId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  name: '',
  isActive: undefined as number | undefined
})

const form = ref({
  id: undefined as number | undefined,
  businessLineId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  name: '',
  company: '',
  position: '',
  phone: '',
  email: '',
  isActive: 1
})

const rules = {
  businessLineId: [{ required: true, message: '请选择业务线', trigger: 'change' }],
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
  name: [{ required: true, message: '请输入客户姓名', trigger: 'blur' }]
}

const extractRecords = <T>(payload: any): T[] => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.records)) return payload.records
  if (Array.isArray(payload?.data?.records)) return payload.data.records
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const projectMap = computed(() => {
  return new Map(projects.value.map(project => [project.id, project]))
})

const filteredProjects = computed(() => {
  if (!filters.value.businessLineId) return projects.value
  return projects.value.filter(project => project.businessLineId === filters.value.businessLineId)
})

const formProjects = computed(() => {
  if (!form.value.businessLineId) return projects.value
  return projects.value.filter(project => project.businessLineId === form.value.businessLineId)
})

const activeCount = computed(() => contacts.value.filter(item => item.isActive === 1).length)

const getBusinessLineName = (projectId: number) => {
  const project = projectMap.value.get(projectId)
  if (!project) return '—'
  return businessLines.value.find(item => item.id === project.businessLineId)?.name ?? '—'
}

const getProjectName = (projectId: number) => {
  return projectMap.value.get(projectId)?.name ?? '—'
}

const formatDateTime = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

const loadBaseData = async () => {
  const [businessLinePayload, projectPayload] = await Promise.all([
    api.getBusinessLines({ page: 1, size: 200 }),
    api.getProjects({ page: 1, size: 200 })
  ])

  businessLines.value = extractRecords<BusinessLine>(businessLinePayload)
  projects.value = extractRecords<Project>(projectPayload)
}

const loadContacts = async () => {
  loading.value = true
  try {
    const payload = await api.getCustomerContactPage({
      page: 1,
      size: 200,
      projectId: filters.value.projectId,
      name: filters.value.name || undefined,
      isActive: filters.value.isActive
    })

    let rows = extractRecords<CustomerContact>(payload)
    if (filters.value.businessLineId) {
      rows = rows.filter(row => projectMap.value.get(row.projectId)?.businessLineId === filters.value.businessLineId)
    }
    contacts.value = rows
  } catch (error) {
    console.error('加载客户信息失败:', error)
    ElMessage.error('加载客户信息失败')
  } finally {
    loading.value = false
  }
}

const refresh = async () => {
  try {
    await loadBaseData()
    await loadContacts()
  } catch (error) {
    ElMessage.error('加载基础数据失败')
  }
}

const resetFilters = () => {
  filters.value = {
    businessLineId: undefined,
    projectId: undefined,
    name: '',
    isActive: undefined
  }
  loadContacts()
}

const openCreateDialog = () => {
  form.value = {
    id: undefined,
    businessLineId: undefined,
    projectId: undefined,
    name: '',
    company: '',
    position: '',
    phone: '',
    email: '',
    isActive: 1
  }
  isEdit.value = false
  dialogVisible.value = true
}

const openEditDialog = (row: CustomerContact) => {
  const project = projectMap.value.get(row.projectId)
  form.value = {
    id: row.id,
    businessLineId: project?.businessLineId,
    projectId: row.projectId,
    name: row.name,
    company: row.company || '',
    position: row.position || '',
    phone: row.phone || '',
    email: row.email || '',
    isActive: row.isActive ?? 1
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: CustomerContact) => {
  try {
    await ElMessageBox.confirm(`确定删除客户信息「${row.name}」吗？`, '提示', {
      type: 'warning'
    })
    await api.deleteCustomerContact(row.id)
    ElMessage.success('删除成功')
    await loadContacts()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const submitForm = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    const payload = {
      projectId: form.value.projectId!,
      name: form.value.name,
      company: form.value.company,
      position: form.value.position,
      phone: form.value.phone,
      email: form.value.email,
      isActive: form.value.isActive
    }

    try {
      if (isEdit.value) {
        await api.updateCustomerContact(form.value.id!, payload)
        ElMessage.success('更新成功')
      } else {
        await api.createCustomerContact(payload)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      await loadContacts()
    } catch (error) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
}

watch(
  () => filters.value.businessLineId,
  businessLineId => {
    if (!businessLineId) return
    const currentProject = projectMap.value.get(filters.value.projectId || -1)
    if (currentProject && currentProject.businessLineId !== businessLineId) {
      filters.value.projectId = undefined
    }
  }
)

watch(
  () => form.value.businessLineId,
  businessLineId => {
    if (!businessLineId) return
    const currentProject = projectMap.value.get(form.value.projectId || -1)
    if (currentProject && currentProject.businessLineId !== businessLineId) {
      form.value.projectId = undefined
    }
  }
)

onMounted(refresh)
</script>

<template>
  <div class="customer-info-page">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">客户信息管理</h2>
        <div class="inline-stats">
          <span class="stat-chip">
            <span class="stat-dot blue"></span>
            <span class="stat-num">{{ contacts.length }}</span>
            <span class="stat-text">位联系人</span>
          </span>
          <span class="stat-chip">
            <span class="stat-dot green"></span>
            <span class="stat-num">{{ activeCount }}</span>
            <span class="stat-text">有效</span>
          </span>
        </div>
      </div>
      <button class="btn btn-primary" @click="openCreateDialog">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增客户信息
      </button>
    </div>

    <div class="filter-section">
      <div class="filter-bar">
        <el-select
          v-model="filters.businessLineId"
          clearable
          placeholder="按业务线筛选"
          style="width: 180px"
        >
          <el-option v-for="item in businessLines" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select
          v-model="filters.projectId"
          clearable
          placeholder="按项目筛选"
          style="width: 200px"
        >
          <el-option v-for="project in filteredProjects" :key="project.id" :label="project.name" :value="project.id" />
        </el-select>
        <div class="search-input-wrapper">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            v-model="filters.name"
            class="search-input"
            type="text"
            placeholder="搜索客户姓名"
            @keyup.enter="loadContacts"
          >
        </div>
        <el-select
          v-model="filters.isActive"
          clearable
          placeholder="按状态筛选"
          style="width: 160px"
        >
          <el-option label="有效" :value="1" />
          <el-option label="失效" :value="0" />
        </el-select>
        <button class="btn btn-sm btn-default" @click="loadContacts">搜索</button>
        <button class="btn btn-sm btn-default" @click="resetFilters">重置</button>
      </div>
    </div>

    <div class="table-card">
      <el-table :data="contacts" v-loading="loading" scrollbar-always-on>
        <el-table-column prop="name" label="客户姓名" min-width="140" />
        <el-table-column prop="company" label="公司" min-width="160">
          <template #default="{ row }">
            {{ row.company || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="position" label="职位" min-width="120">
          <template #default="{ row }">
            {{ row.position || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="所属业务线" min-width="160">
          <template #default="{ row }">
            {{ getBusinessLineName(row.projectId) }}
          </template>
        </el-table-column>
        <el-table-column label="所属项目" min-width="160">
          <template #default="{ row }">
            {{ getProjectName(row.projectId) }}
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="140">
          <template #default="{ row }">
            {{ row.phone || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180">
          <template #default="{ row }">
            {{ row.email || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :class="['status-badge', row.isActive === 1 ? 'green' : 'gray']">
              {{ row.isActive === 1 ? '有效' : '失效' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.updatedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="action-links">
              <span class="action-link primary" @click="openEditDialog(row)">编辑</span>
              <span class="action-link danger" @click="handleDelete(row)">删除</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && contacts.length === 0" description="暂无客户信息数据" style="padding: 48px 0" />
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑客户信息' : '新增客户信息'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="业务线" prop="businessLineId">
          <el-select v-model="form.businessLineId" clearable placeholder="请选择业务线" style="width: 100%">
            <el-option v-for="item in businessLines" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目" prop="projectId">
          <el-select v-model="form.projectId" clearable placeholder="请选择项目" style="width: 100%">
            <el-option v-for="project in formProjects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户姓名" prop="name">
          <el-input v-model="form.name" placeholder="请输入客户姓名" />
        </el-form-item>
        <el-form-item label="公司">
          <el-input v-model="form.company" placeholder="请输入公司名称" />
        </el-form-item>
        <el-form-item label="职位">
          <el-input v-model="form.position" placeholder="请输入职位" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.isActive" :active-value="1" :inactive-value="0" active-text="有效" inactive-text="失效" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.customer-info-page {
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

.stat-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--gray-50);
  border: 1px solid var(--gray-100);
}

.stat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.stat-dot.blue {
  background: #3b82f6;
}

.stat-dot.green {
  background: #10b981;
}

.stat-num {
  font-size: 14px;
  font-weight: 700;
  color: var(--gray-800);
}

.stat-text {
  font-size: 12px;
  color: var(--gray-500);
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
  color: #fff;
}

.btn-primary:hover {
  background: var(--primary-dark);
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

.filter-section {
  background: #fff;
  border-radius: var(--radius-md);
  padding: 12px 16px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.search-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--gray-400);
  pointer-events: none;
}

.search-input {
  height: 32px;
  padding: 0 12px 0 32px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  font-size: 13px;
  outline: none;
  width: 220px;
}

.search-input:focus {
  border-color: var(--primary);
}

.table-card {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  background: #fff;
  border-radius: var(--radius-md);
  padding: 12px;
  box-shadow: var(--shadow-sm);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
}

.status-badge.green {
  background: #d1fae5;
  color: #047857;
}

.status-badge.gray {
  background: var(--gray-100);
  color: var(--gray-600);
}

.action-links {
  display: flex;
  align-items: center;
  gap: 12px;
}

.action-link {
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.action-link.primary {
  color: var(--primary);
}

.action-link.danger {
  color: var(--danger);
}

@media (max-width: 768px) {
  .content-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .title-with-stats {
    width: 100%;
    min-width: 0;
    flex-wrap: wrap;
    gap: 10px;
  }

  .inline-stats {
    max-width: 100%;
    flex-wrap: wrap;
    padding-left: 0;
    border-left: 0;
  }

  .filter-bar :deep(.el-select),
  .search-input-wrapper,
  .search-input {
    width: 100% !important;
    min-width: 0;
    max-width: 100%;
  }
}
</style>
