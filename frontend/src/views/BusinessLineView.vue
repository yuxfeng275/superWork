<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/utils/api'

interface BusinessLine {
  id: number
  name: string
  description?: string
  status: number
  createdAt?: string
  updatedAt?: string
}

const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const businessLines = ref<BusinessLine[]>([])
const filters = ref({
  name: '',
  status: undefined as number | undefined
})

const form = ref({
  id: undefined as number | undefined,
  name: '',
  description: '',
  status: 1
})

const rules = {
  name: [{ required: true, message: '请输入业务线名称', trigger: 'blur' }]
}

const extractRecords = (payload: any): BusinessLine[] => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.records)) return payload.records
  if (Array.isArray(payload?.data?.records)) return payload.data.records
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const activeCount = computed(() => businessLines.value.filter(item => item.status === 1).length)
const inactiveCount = computed(() => businessLines.value.filter(item => item.status !== 1).length)

const loadBusinessLines = async () => {
  loading.value = true
  try {
    const payload = await api.getBusinessLines({
      page: 1,
      size: 200,
      name: filters.value.name || undefined,
      status: filters.value.status
    })
    businessLines.value = extractRecords(payload)
  } catch (error) {
    console.error('加载业务线失败:', error)
    ElMessage.error('加载业务线失败')
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  filters.value = {
    name: '',
    status: undefined
  }
  loadBusinessLines()
}

const openCreateDialog = () => {
  form.value = {
    id: undefined,
    name: '',
    description: '',
    status: 1
  }
  isEdit.value = false
  dialogVisible.value = true
}

const openEditDialog = (row: BusinessLine) => {
  form.value = {
    id: row.id,
    name: row.name,
    description: row.description || '',
    status: row.status ?? 1
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: BusinessLine) => {
  try {
    await ElMessageBox.confirm(`确定删除业务线「${row.name}」吗？`, '提示', {
      type: 'warning'
    })
    await api.deleteBusinessLine(row.id)
    ElMessage.success('删除成功')
    await loadBusinessLines()
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
      name: form.value.name,
      description: form.value.description,
      status: form.value.status
    }

    try {
      if (isEdit.value) {
        await api.updateBusinessLine(form.value.id!, payload)
        ElMessage.success('更新成功')
      } else {
        await api.createBusinessLine(payload)
        ElMessage.success('创建成功')
      }

      dialogVisible.value = false
      await loadBusinessLines()
    } catch (error) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
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

onMounted(loadBusinessLines)
</script>

<template>
  <div class="business-line-page">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">业务线管理</h2>
      </div>
      <button class="btn btn-primary" @click="openCreateDialog">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新增业务线
      </button>
    </div>

    <div class="filter-section">
      <div class="filter-bar">
        <div class="search-input-wrapper">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            v-model="filters.name"
            class="search-input"
            type="text"
            placeholder="搜索业务线名称"
            @keyup.enter="loadBusinessLines"
          >
        </div>
        <el-select
          v-model="filters.status"
          clearable
          placeholder="按状态筛选"
          style="width: 180px"
          @change="loadBusinessLines"
          @clear="loadBusinessLines"
        >
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <button class="btn btn-sm btn-default" @click="loadBusinessLines">搜索</button>
        <button class="btn btn-sm btn-default" @click="resetFilters">重置</button>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-card muted">
        <span class="summary-label">业务线总数</span>
        <span class="summary-value">{{ businessLines.length }}</span>
      </div>
      <div class="summary-card success">
        <span class="summary-label">启用中</span>
        <span class="summary-value">{{ activeCount }}</span>
      </div>
      <div class="summary-card neutral">
        <span class="summary-label">已禁用</span>
        <span class="summary-value">{{ inactiveCount }}</span>
      </div>
    </div>

    <div class="card-stage" v-loading="loading">
      <el-empty v-if="!loading && businessLines.length === 0" description="暂无业务线数据" style="padding: 72px 0" />
      <div v-else class="card-grid">
        <article
          v-for="row in businessLines"
          :key="row.id"
          class="business-card"
          data-testid="business-line-card"
        >
          <div class="card-top">
            <div class="card-identity">
              <span class="card-index">BU-{{ String(row.id).padStart(2, '0') }}</span>
              <h3 class="card-title">{{ row.name }}</h3>
            </div>
            <span :class="['status-badge', row.status === 1 ? 'green' : 'gray']">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </span>
          </div>

          <p class="card-description">
            {{ row.description || '当前业务线尚未补充描述信息。' }}
          </p>

          <div class="card-meta">
            <div class="meta-block">
              <span class="meta-label">最近更新</span>
              <span class="meta-value">{{ formatDateTime(row.updatedAt || row.createdAt) }}</span>
            </div>
            <div class="meta-block">
              <span class="meta-label">管理状态</span>
              <span class="meta-value">{{ row.status === 1 ? '稳定运行' : '待整理' }}</span>
            </div>
          </div>

          <div class="card-footer">
            <span class="action-link primary" @click="openEditDialog(row)">编辑</span>
            <span class="action-link danger" @click="handleDelete(row)">删除</span>
          </div>
        </article>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑业务线' : '新增业务线'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="业务线名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入业务线名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入业务线描述" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
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

.summary-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  padding: 16px 18px;
  border-radius: 18px;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.summary-card.muted {
  background: linear-gradient(135deg, #ffffff, #f8fafc);
  border: 1px solid var(--gray-100);
}

.summary-card.success {
  background: linear-gradient(135deg, #ecfdf5, #d1fae5);
}

.summary-card.neutral {
  background: linear-gradient(135deg, #f8fafc, #e5e7eb);
}

.summary-label {
  font-size: 12px;
  color: var(--gray-500);
}

.summary-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--gray-800);
}

.card-stage {
  min-height: 240px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.business-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 230px;
  padding: 18px;
  background:
    radial-gradient(circle at top right, rgba(59, 130, 246, 0.12), transparent 34%),
    linear-gradient(180deg, #ffffff, #f8fafc);
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 20px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.business-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 24px 46px rgba(15, 23, 42, 0.12);
  border-color: rgba(79, 70, 229, 0.22);
}

.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-identity {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-index {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.card-title {
  margin: 0;
  font-size: 20px;
  line-height: 1.2;
  color: var(--gray-800);
}

.card-description {
  margin: 0;
  color: var(--gray-600);
  font-size: 14px;
  line-height: 1.7;
  min-height: 72px;
}

.card-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: auto;
}

.meta-block {
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(148, 163, 184, 0.14);
}

.meta-label {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--gray-500);
}

.meta-value {
  display: block;
  font-size: 13px;
  color: var(--gray-700);
  line-height: 1.5;
}

.card-footer {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: 4px;
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

@media (max-width: 900px) {
  .summary-strip {
    grid-template-columns: 1fr;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }

  .card-meta {
    grid-template-columns: 1fr;
  }
}
</style>
