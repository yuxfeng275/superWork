<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface ProjectOption {
  id: number
  name: string
  code?: string
  fullPath?: string
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

const contacts = ref<CustomerContact[]>([])
const projects = ref<ProjectOption[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const filterProjectId = ref<number | undefined>(undefined)
const filterName = ref('')
const filterStatus = ref<number | undefined>(undefined)

const form = ref({
  id: undefined as number | undefined,
  projectId: undefined as number | undefined,
  name: '',
  company: '',
  position: '',
  phone: '',
  email: '',
  isActive: 1
})

const rules = {
  projectId: [{ required: true, message: '请选择所属项目', trigger: 'change' }],
  name: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }]
}

const extractRecords = <T>(payload: any): T[] => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.records)) return payload.records
  if (Array.isArray(payload?.data?.records)) return payload.data.records
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const activeCount = computed(() => contacts.value.filter(item => item.isActive === 1).length)

const projectMap = computed(() => {
  return new Map(projects.value.map(project => [project.id, project]))
})

const getProjectLabel = (projectId: number) => {
  const project = projectMap.value.get(projectId)
  if (!project) return '—'
  return project.fullPath || project.name
}

const formatDateTime = (value?: string) => {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

const loadData = async () => {
  loading.value = true
  try {
    const [contactsResp, projectsResp] = await Promise.all([
      api.getCustomerContactPage({
        page: 1,
        size: 999,
        projectId: filterProjectId.value,
        name: filterName.value.trim() || undefined,
        isActive: filterStatus.value
      }),
      api.getProjects({ page: 1, size: 999 })
    ])

    contacts.value = extractRecords<CustomerContact>(contactsResp)
    projects.value = extractRecords<ProjectOption>(projectsResp)
  } catch (error) {
    console.error('加载客户信息失败:', error)
    ElMessage.error('加载客户信息失败')
  } finally {
    loading.value = false
  }
}

const resetFilters = async () => {
  filterProjectId.value = undefined
  filterName.value = ''
  filterStatus.value = undefined
  await loadData()
}

const openAdd = () => {
  form.value = {
    id: undefined,
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

const handleEdit = (row: CustomerContact) => {
  form.value = {
    id: row.id,
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
    await ElMessageBox.confirm(`确定要删除客户联系人「${row.name}」吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.deleteCustomerContact(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    const payload = {
      projectId: form.value.projectId!,
      name: form.value.name,
      company: form.value.company || null,
      position: form.value.position || null,
      phone: form.value.phone || null,
      email: form.value.email || null,
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
      await loadData()
    } catch (error) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="customer-contact-page">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">客户信息管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ contacts.length }}</span>
            <span class="stat-text">位联系人</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ activeCount }}</span>
            <span class="stat-text">有效中</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-default" @click="resetFilters">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="1 4 1 10 7 10" />
            <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
          </svg>
          重置
        </button>
        <button class="btn btn-primary" @click="openAdd">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新增客户信息
        </button>
      </div>
    </div>

    <div class="filter-section">
      <div class="filter-bar">
        <el-select
          v-model="filterProjectId"
          clearable
          placeholder="按项目筛选"
          style="width: 220px"
          @change="loadData"
          @clear="loadData"
        >
          <el-option
            v-for="project in projects"
            :key="project.id"
            :label="project.fullPath || project.name"
            :value="project.id"
          />
        </el-select>
        <el-select
          v-model="filterStatus"
          clearable
          placeholder="联系人状态"
          style="width: 160px"
          @change="loadData"
          @clear="loadData"
        >
          <el-option label="有效" :value="1" />
          <el-option label="无效" :value="0" />
        </el-select>
        <div class="search-input-wrapper">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            v-model="filterName"
            type="text"
            class="search-input"
            placeholder="搜索联系人姓名"
            @keyup.enter="loadData"
          >
        </div>
        <button class="btn btn-sm btn-default" @click="loadData">搜索</button>
      </div>
    </div>

    <div class="table-card">
      <el-table
        :data="contacts"
        v-loading="loading"
        class="unified-table"
        :header-cell-style="{ background: 'var(--gray-50)', color: 'var(--gray-600)', fontWeight: 600, fontSize: '12px', borderBottom: '1px solid var(--gray-100)', padding: '10px 12px' }"
        :cell-style="{ fontSize: '13px', color: 'var(--gray-700)', padding: '10px 12px', borderBottom: '1px solid var(--gray-50)' }"
      >
        <el-table-column prop="name" label="联系人" min-width="120" />
        <el-table-column label="所属项目" min-width="220">
          <template #default="{ row }">
            {{ getProjectLabel(row.projectId) }}
          </template>
        </el-table-column>
        <el-table-column prop="company" label="公司" min-width="160">
          <template #default="{ row }">
            {{ row.company || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="position" label="职位" min-width="140">
          <template #default="{ row }">
            {{ row.position || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="150">
          <template #default="{ row }">
            {{ row.phone || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="200">
          <template #default="{ row }">
            {{ row.email || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :class="['status-badge', row.isActive === 1 ? 'green' : 'gray']">
              {{ row.isActive === 1 ? '有效' : '无效' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.updatedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <div class="action-links">
              <span class="action-link primary" @click="handleEdit(row)">编辑</span>
              <span class="action-link danger" @click="handleDelete(row)">删除</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑客户信息' : '新增客户信息'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="所属项目" prop="projectId">
          <el-select v-model="form.projectId" filterable placeholder="请选择所属项目" style="width: 100%">
            <el-option
              v-for="project in projects"
              :key="project.id"
              :label="project.fullPath || project.name"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人" prop="name">
          <el-input v-model="form.name" placeholder="请输入联系人姓名" />
        </el-form-item>
        <el-form-item label="公司">
          <el-input v-model="form.company" placeholder="请输入公司名称" />
        </el-form-item>
        <el-form-item label="职位">
          <el-input v-model="form.position" placeholder="请输入职位信息" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱地址" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.isActive"
            :active-value="1"
            :inactive-value="0"
            active-text="有效"
            inactive-text="无效"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
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

.stat-text {
  font-size: 13px;
  color: var(--gray-500);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-section {
  margin-bottom: 16px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.search-input-wrapper {
  position: relative;
  width: 240px;
}

.search-icon {
  position: absolute;
  left: 12px;
  top: 50%;
  width: 16px;
  height: 16px;
  color: var(--gray-400);
  transform: translateY(-50%);
}

.search-input {
  width: 100%;
  height: 40px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  padding: 0 12px 0 38px;
  font-size: 14px;
  color: var(--gray-700);
  background: #fff;
}

.search-input:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
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

.btn-sm {
  padding: 8px 14px;
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

.table-card {
  background: #fff;
  border-radius: var(--radius-md);
  overflow: auto;
  box-shadow: var(--shadow-sm);
}

.unified-table :deep(.el-table__header-wrapper th) {
  background: var(--gray-50) !important;
  font-size: 12px !important;
  font-weight: 600 !important;
  color: var(--gray-600) !important;
  border-bottom: 1px solid var(--gray-100) !important;
  padding: 10px 12px !important;
}

.unified-table :deep(.el-table__body-wrapper td) {
  font-size: 13px !important;
  color: var(--gray-700) !important;
  padding: 10px 12px !important;
  border-bottom: 1px solid var(--gray-50) !important;
}

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
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
  transition: opacity 0.15s ease;
}

.action-link:hover {
  opacity: 0.8;
}

.action-link.primary {
  color: var(--primary);
}

.action-link.danger {
  color: var(--danger);
}
</style>
