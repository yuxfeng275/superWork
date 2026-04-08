<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface User {
  id: number
  username: string
  realName: string
  email: string
  phone: string
  role: string
  status: string
  createTime: string
}

const users = ref<User[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const form = ref({
  id: undefined as number | undefined,
  username: '',
  password: '',
  realName: '',
  email: '',
  phone: '',
  role: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const roles = [
  { value: 'BU_ADMIN', label: 'BU管理员' },
  { value: 'PM', label: '项目经理' },
  { value: 'TECH_MANAGER', label: '技术经理' },
  { value: 'PRODUCT', label: '产品经理' },
  { value: 'UI_DESIGN', label: 'UI设计' },
  { value: 'DEVELOPER', label: '开发' },
  { value: 'TESTER', label: '测试' }
]

const loadUsers = async () => {
  loading.value = true
  try {
    const data = await api.getUsers({ size: 100 })
    users.value = data.records || data || []
  } catch (error) {
    console.error('加载用户失败:', error)
    ElMessage.error('加载用户失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  form.value = {
    id: undefined,
    username: '',
    password: '',
    realName: '',
    email: '',
    phone: '',
    role: ''
  }
  isEdit.value = false
  dialogVisible.value = true
}

const handleEdit = (row: User) => {
  form.value = {
    id: row.id,
    username: row.username,
    password: '',
    realName: row.realName,
    email: row.email,
    phone: row.phone,
    role: row.role
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: User) => {
  try {
    await ElMessageBox.confirm(`确定要删除用户「${row.realName}」吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.deleteUser(row.id)
    ElMessage.success('删除成功')
    loadUsers()
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
        const updateData: Record<string, any> = { ...form.value }
        if (!updateData.password) {
          delete updateData.password
        }
        await api.updateUser(form.value.id!, updateData)
        ElMessage.success('更新成功')
      } else {
        if (!form.value.password) {
          ElMessage.warning('请输入密码')
          return
        }
        await api.createUser(form.value)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadUsers()
    } catch (error) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
}

onMounted(() => {
  loadUsers()
})
</script>

<template>
  <div class="system-user-page">
    <div class="page-header">
      <h2 class="page-title">用户管理</h2>
      <button class="btn btn-primary" @click="handleAdd">
        <el-icon><Plus /></el-icon>
        新增用户
      </button>
    </div>

    <div class="card">
      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="realName" label="真实姓名" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column prop="role" label="角色">
          <template #default="{ row }">
            <el-tag v-if="row.role === 'BU_ADMIN'" type="danger">BU管理员</el-tag>
            <el-tag v-else-if="row.role === 'PM'" type="warning">项目经理</el-tag>
            <el-tag v-else-if="row.role === 'TECH_MANAGER'" type="success">技术经理</el-tag>
            <el-tag v-else-if="row.role === 'PRODUCT'">产品经理</el-tag>
            <el-tag v-else-if="row.role === 'UI_DESIGN'" type="info">UI设计</el-tag>
            <el-tag v-else-if="row.role === 'DEVELOPER'" type="primary">开发</el-tag>
            <el-tag v-else-if="row.role === 'TESTER'" type="warning">测试</el-tag>
            <el-tag v-else>{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑用户' : '新增用户'"
      width="500px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" :prop="isEdit ? '' : 'password'">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
          <span v-if="isEdit" class="form-tip">留空则不修改密码</span>
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="item in roles"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
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
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
  margin: 0;
}

.card {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px;
}

.form-tip {
  font-size: 12px;
  color: var(--gray-400);
  margin-top: 4px;
}
</style>
