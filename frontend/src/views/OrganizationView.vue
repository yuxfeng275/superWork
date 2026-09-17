<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface BusinessLine {
  id: number
  name: string
  description?: string
  status: number
  createdAt?: string
  updatedAt?: string
}

const businessLines = ref<BusinessLine[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

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

const totalActiveLines = computed(() => {
  return businessLines.value.filter(item => item.status === 1).length
})

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
    const data = await api.getBusinessLines({ size: 999 })
    businessLines.value = extractRecords(data)
  } catch (error) {
    console.error('加载业务线失败:', error)
    ElMessage.error('加载业务线失败')
  } finally {
    loading.value = false
  }
}

const openAdd = () => {
  form.value = {
    id: undefined,
    name: '',
    description: '',
    status: 1
  }
  isEdit.value = false
  dialogVisible.value = true
}

const handleEdit = (row: BusinessLine) => {
  form.value = {
    id: row.id,
    name: row.name,
    description: row.description || '',
    status: row.status
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: BusinessLine) => {
  try {
    await ElMessageBox.confirm(`确定要删除业务线「${row.name}」吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.deleteBusinessLine(row.id)
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

    try {
      const payload = {
        name: form.value.name,
        description: form.value.description || null,
        status: form.value.status
      }

      if (isEdit.value) {
        await api.updateBusinessLine(form.value.id!, payload)
        ElMessage.success('更新成功')
      } else {
        await api.createBusinessLine(payload)
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
  <div class="business-line-page">
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">业务线管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ businessLines.length }}</span>
            <span class="stat-text">条业务线</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ totalActiveLines }}</span>
            <span class="stat-text">启用中</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-primary" @click="openAdd">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新增业务线
        </button>
      </div>
    </div>

    <div class="table-card">
      <el-table
        :data="businessLines"
        v-loading="loading"
        class="unified-table"
        :header-cell-style="{ background: 'var(--gray-50)', color: 'var(--gray-600)', fontWeight: 600, fontSize: '12px', borderBottom: '1px solid var(--gray-100)', padding: '10px 12px' }"
        :cell-style="{ fontSize: '13px', color: 'var(--gray-700)', padding: '10px 12px', borderBottom: '1px solid var(--gray-50)' }"
      >
        <el-table-column prop="name" label="业务线名称" min-width="180" />
        <el-table-column prop="description" label="描述" min-width="260">
          <template #default="{ row }">
            {{ row.description || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span :class="['status-badge', row.status === 1 ? 'green' : 'gray']">
              {{ row.status === 1 ? '启用' : '停用' }}
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
      :title="isEdit ? '编辑业务线' : '新增业务线'"
      width="460px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="业务线名称" prop="name">
          <el-input v-model="form.name" placeholder="如：电商业务线" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="补充业务线职责、范围或负责人说明"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="停用"
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
