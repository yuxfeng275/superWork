<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface WorkflowConfig {
  id: number
  requirementType: string
  currentStatus: string
  nextStatus: string
  transitionName?: string
  sortOrder?: number
  createdAt?: string
}

const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()
const tableData = ref<WorkflowConfig[]>([])
const form = ref({
  id: undefined as number | undefined,
  requirementType: '',
  currentStatus: '',
  nextStatus: '',
  transitionName: '',
  sortOrder: 0
})

const rules = {
  requirementType: [{ required: true, message: '请输入需求类型', trigger: 'blur' }],
  currentStatus: [{ required: true, message: '请输入当前状态', trigger: 'blur' }],
  nextStatus: [{ required: true, message: '请输入下一状态', trigger: 'blur' }]
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await api.getWorkflowConfigs()
    tableData.value = Array.isArray(data) ? data : []
  } catch (error) {
    console.error('加载工作流配置失败:', error)
    ElMessage.error('加载工作流配置失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  form.value = { id: undefined, requirementType: '', currentStatus: '', nextStatus: '', transitionName: '', sortOrder: 0 }
  isEdit.value = false
  dialogVisible.value = true
}

const handleEdit = (row: WorkflowConfig) => {
  form.value = {
    id: row.id,
    requirementType: row.requirementType,
    currentStatus: row.currentStatus,
    nextStatus: row.nextStatus,
    transitionName: row.transitionName || '',
    sortOrder: row.sortOrder || 0
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: WorkflowConfig) => {
  try {
    await ElMessageBox.confirm(`确定要删除该配置吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.deleteWorkflowConfig(row.id)
    ElMessage.success('删除成功')
    loadData()
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
      if (isEdit.value) {
        await api.updateWorkflowConfig(form.value.id!, form.value)
        ElMessage.success('更新成功')
      } else {
        await api.createWorkflowConfig(form.value)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadData()
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
  <div class="workflow-view">
    <div class="page-header">
      <div>
        <h2 class="page-title">工作流配置</h2>
        <p class="page-subtitle">管理需求状态流转规则。</p>
      </div>
      <button class="btn btn-primary" @click="handleAdd">
        <el-icon><Plus /></el-icon>
        新增规则
      </button>
    </div>

    <div class="card">
      <el-table :data="tableData" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="requirementType" label="需求类型" min-width="120" />
        <el-table-column prop="currentStatus" label="当前状态" min-width="120" />
        <el-table-column prop="nextStatus" label="下一状态" min-width="120" />
        <el-table-column prop="transitionName" label="流转名称" min-width="120" />
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑工作流规则' : '新增工作流规则'"
      width="500px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="需求类型" prop="requirementType">
          <el-input v-model="form.requirementType" placeholder="如: 产品需求" />
        </el-form-item>
        <el-form-item label="当前状态" prop="currentStatus">
          <el-input v-model="form.currentStatus" placeholder="如: 评估中" />
        </el-form-item>
        <el-form-item label="下一状态" prop="nextStatus">
          <el-input v-model="form.nextStatus" placeholder="如: 设计中" />
        </el-form-item>
        <el-form-item label="流转名称">
          <el-input v-model="form.transitionName" placeholder="按钮文字" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
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
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}
.page-subtitle {
  margin: 6px 0 0;
  color: var(--gray-500);
  font-size: 13px;
}
.card {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px;
}
</style>
