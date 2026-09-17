<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  POSITION_ROLE_BADGE_CLASS,
  POSITION_ROLE_OPTIONS,
  getRoleLabel
} from '@/constants/roles'

interface User {
  id: number
  username: string
  realName: string
  email?: string
  phone?: string
  role: string
  status?: string | number | null
  createTime?: string
}

interface RoleOption {
  value: string
  label: string
  category?: string
  categoryLabel?: string
  description: string
  legacyFrom?: string
}

const users = ref<User[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()
const roleSectionRefs = ref<Record<string, HTMLElement | null>>({})

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

const roles: RoleOption[] = POSITION_ROLE_OPTIONS
const ROLE_BADGE_CLASS = POSITION_ROLE_BADGE_CLASS

const extractUsers = (payload: any): User[] => {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.records)) return payload.records
  if (Array.isArray(payload?.data?.records)) return payload.data.records
  if (Array.isArray(payload?.data)) return payload.data
  return []
}

const sortUsers = (list: User[]) => {
  return [...list].sort((left, right) => {
    const leftName = (left.realName || left.username || '').localeCompare(right.realName || right.username || '', 'zh-CN')
    if (leftName !== 0) return leftName
    return left.id - right.id
  })
}

const getRoleName = (code: string) => {
  return getRoleLabel(code)
}

const getStatusMeta = (status: User['status']) => {
  const normalized = String(status ?? '').trim().toLowerCase()
  if (!normalized || normalized === '1' || normalized === 'enabled' || normalized === 'active') {
    return { label: '启用', className: 'success' }
  }
  if (normalized === '0' || normalized === 'disabled' || normalized === 'inactive') {
    return { label: '停用', className: 'danger' }
  }
  return { label: normalized, className: 'gray' }
}

const getAvatarText = (user: User) => {
  const source = (user.realName || user.username || 'U').trim()
  return source.slice(-2).toUpperCase()
}

const formatDateTime = (value?: string) => {
  if (!value) return '暂无创建时间'
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

const roleGroups = computed(() => {
  const roleOrder = new Set(roles.map(role => role.value))
  const knownGroups = roles
    .map(role => ({
      ...role,
      users: sortUsers(users.value.filter(user => user.role === role.value))
    }))
    .filter(group => group.users.length > 0)

  const uncategorizedUsers = sortUsers(users.value.filter(user => !roleOrder.has(user.role)))
  if (uncategorizedUsers.length > 0) {
    knownGroups.push({
      value: 'UNKNOWN',
      label: '未归类角色',
      description: '以下成员角色尚未映射到前端字典。',
      users: uncategorizedUsers
    })
  }

  return knownGroups
})

const roleSummaries = computed(() => {
  return roles
    .map(role => ({
      ...role,
      count: users.value.filter(user => user.role === role.value).length
    }))
    .filter(role => role.count > 0)
})

const roleCategorySummaries = computed(() => {
  const categories = [
    { value: 'management', label: '管理序列' },
    { value: 'execution', label: '执行序列' }
  ]

  return categories.map(category => {
    const categoryRoles = roles.filter(role => role.category === category.value)
    const count = users.value.filter(user => categoryRoles.some(role => role.value === user.role)).length
    return {
      ...category,
      roleCount: categoryRoles.length,
      userCount: count
    }
  })
})

const activeUserCount = computed(() => {
  return users.value.filter(user => getStatusMeta(user.status).label === '启用').length
})

const setRoleSectionRef = (roleValue: string, element: any) => {
  roleSectionRefs.value[roleValue] = element as HTMLElement | null
}

const scrollToRoleGroup = (roleValue: string) => {
  roleSectionRefs.value[roleValue]?.scrollIntoView({
    behavior: 'smooth',
    block: 'start'
  })
}

const loadUsers = async () => {
  loading.value = true
  try {
    const data = await api.getUsers({ size: 100 })
    users.value = extractUsers(data)
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
    email: row.email ?? '',
    phone: row.phone ?? '',
    role: row.role
  }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: User) => {
  try {
    await ElMessageBox.confirm(`确定要删除用户「${row.realName || row.username}」吗？`, '提示', {
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
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">用户管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ users.length }}</span>
            <span class="stat-text">总成员</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ roleGroups.length }}</span>
            <span class="stat-text">角色分组</span>
          </span>
          <span
            v-for="category in roleCategorySummaries"
            :key="category.value"
            class="inline-stat category-stat"
          >
            <span class="stat-num">{{ category.userCount }}</span>
            <span class="stat-text">{{ category.label }}</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ activeUserCount }}</span>
            <span class="stat-text">启用中</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-primary" @click="handleAdd">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新增用户
        </button>
      </div>
    </div>

    <div v-if="roleSummaries.length" class="role-summary-strip">
      <div
        v-for="role in roleSummaries"
        :key="role.value"
        class="role-summary-chip"
        :data-role-summary="role.value"
        @click="scrollToRoleGroup(role.value)"
      >
        <span :class="['status-badge', ROLE_BADGE_CLASS[role.value] || 'gray']">{{ role.label }}</span>
        <span class="role-summary-count">{{ role.count }} 人</span>
      </div>
    </div>

    <div class="user-groups" v-loading="loading">
      <el-empty
        v-if="!loading && roleGroups.length === 0"
        description="暂无用户"
        style="padding: 72px 0"
      />

      <template v-else>
        <section
          v-for="group in roleGroups"
          :key="group.value"
          class="role-section"
          data-testid="user-role-group"
          :data-role-group="group.value"
          :ref="element => setRoleSectionRef(group.value, element)"
        >
          <div class="role-section-header">
            <div class="role-section-title">
              <span :class="['status-badge', ROLE_BADGE_CLASS[group.value] || 'gray']">
                {{ group.label }}
              </span>
              <div class="role-copy">
                <div class="role-title-row">
                  <span class="role-title">{{ group.label }}</span>
                  <span v-if="group.categoryLabel" class="role-sequence">{{ group.categoryLabel }}</span>
                  <span class="role-count">{{ group.users.length }} 人</span>
                </div>
                <p class="role-description">{{ group.description }}</p>
                <p v-if="group.legacyFrom" class="role-legacy">原岗位：{{ group.legacyFrom }}</p>
              </div>
            </div>
          </div>

          <div class="user-card-grid">
            <article
              v-for="user in group.users"
              :key="user.id"
              class="user-card"
              data-testid="user-card"
              tabindex="0"
            >
              <div class="user-card-quick-actions">
                <button class="quick-action-btn" type="button" @click.stop="handleEdit(user)">编辑</button>
                <button class="quick-action-btn danger" type="button" @click.stop="handleDelete(user)">删除</button>
              </div>

              <div class="user-card-compact">
                <div class="user-avatar">{{ getAvatarText(user) }}</div>
                <span class="user-name">{{ user.realName || user.username }}</span>
              </div>

              <div class="user-card-hover-panel" data-testid="user-hover-panel">
                <div class="hover-panel-header">
                  <div class="user-identity">
                    <div class="user-avatar small">{{ getAvatarText(user) }}</div>
                    <div class="user-basic">
                      <div class="user-name-row">
                        <span class="user-name">{{ user.realName || user.username }}</span>
                        <span :class="['status-badge', getStatusMeta(user.status).className]">
                          {{ getStatusMeta(user.status).label }}
                        </span>
                      </div>
                      <div class="user-account">@{{ user.username }}</div>
                    </div>
                  </div>
                </div>

                <div class="user-card-body">
                  <div class="info-item">
                    <span class="info-label">邮箱</span>
                    <span class="info-value">{{ user.email || '未填写' }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">手机号</span>
                    <span class="info-value">{{ user.phone || '未填写' }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">创建时间</span>
                    <span class="info-value">{{ formatDateTime(user.createTime) }}</span>
                  </div>
                </div>

                <div class="user-card-footer">
                  <span class="card-meta">{{ getRoleName(user.role) }} · ID #{{ user.id }}</span>
                  <div class="action-links">
                    <span class="action-link primary" @click="handleEdit(user)">编辑</span>
                    <span class="action-link danger" @click="handleDelete(user)">删除</span>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </section>
      </template>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="500px">
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
            <el-option v-for="item in roles" :key="item.value" :label="item.label" :value="item.value">
              <div class="role-option-row">
                <span>{{ item.label }}</span>
                <span>{{ item.categoryLabel }}</span>
              </div>
            </el-option>
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
.system-user-page {
  width: 100%;
  min-width: 0;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.title-with-stats {
  display: flex;
  align-items: center;
  gap: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-800);
}

.inline-stats {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding-left: 16px;
  border-left: 1px solid var(--gray-200);
}

.inline-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.category-stat {
  padding: 0 8px;
  border-radius: 999px;
  background: var(--gray-50);
}

.stat-num {
  font-size: 18px;
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

.role-summary-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 18px;
}

.role-summary-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid var(--gray-100);
  border-radius: 999px;
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
}

.role-summary-chip:hover {
  transform: translateY(-1px);
  border-color: rgba(79, 70, 229, 0.18);
  box-shadow: var(--shadow-md);
}

.role-summary-count {
  font-size: 12px;
  color: var(--gray-500);
}

.user-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.role-section {
  min-width: 0;
  background: #fff;
  border-radius: var(--radius-lg, 16px);
  border: 1px solid var(--gray-100);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.role-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 20px 0;
}

.role-section-title {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.role-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.role-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.role-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--gray-800);
}

.role-count {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--gray-100);
  font-size: 12px;
  color: var(--gray-500);
}

.role-sequence {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  background: #eef2ff;
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
}

.role-description {
  margin: 0;
  font-size: 13px;
  color: var(--gray-500);
}

.role-legacy {
  margin: 0;
  font-size: 12px;
  color: var(--gray-400);
}

.role-option-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.role-option-row span:last-child {
  color: var(--gray-400);
  font-size: 12px;
}

.user-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr));
  gap: 16px;
  padding: 20px;
}

.user-card {
  position: relative;
  min-width: 0;
  min-height: 92px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcff 100%);
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-md);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
  overflow: visible;
}

.user-card-quick-actions {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  gap: 8px;
  z-index: 3;
}

.quick-action-btn {
  border: none;
  border-radius: 999px;
  padding: 5px 10px;
  background: rgba(255, 255, 255, 0.95);
  color: var(--primary);
  font-size: 11px;
  font-weight: 600;
  box-shadow: 0 10px 20px rgba(15, 23, 42, 0.08);
  cursor: pointer;
}

.quick-action-btn.danger {
  color: var(--danger, #dc2626);
}

.user-card:hover,
.user-card:focus-within {
  border-color: rgba(79, 70, 229, 0.18);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
  z-index: 2;
}

.user-card-compact {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 92px;
  padding: 16px;
}

.user-identity {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}

.user-avatar.small {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  font-size: 13px;
}

.user-basic {
  min-width: 0;
  flex: 1;
}

.user-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.user-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-account {
  font-size: 12px;
  color: var(--gray-500);
}

.user-card-hover-panel {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, #f8faff 100%);
  border-radius: inherit;
  opacity: 0;
  visibility: hidden;
  transform: translateY(6px);
  pointer-events: none;
  transition: opacity 0.18s ease, transform 0.18s ease, visibility 0.18s ease;
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.12);
}

.user-card:hover .user-card-hover-panel,
.user-card:focus-within .user-card-hover-panel {
  opacity: 1;
  visibility: visible;
  transform: translateY(0);
  pointer-events: auto;
}

.hover-panel-header {
  display: flex;
  align-items: flex-start;
}

.user-card-body {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.info-item {
  display: grid;
  gap: 4px;
}

.info-label {
  font-size: 11px;
  color: var(--gray-400);
}

.info-value {
  font-size: 13px;
  color: var(--gray-700);
  word-break: break-all;
}

.user-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--gray-100);
}

.card-meta {
  font-size: 12px;
  color: var(--gray-400);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.status-badge.gray {
  background: var(--gray-100);
  color: var(--gray-600);
}

.status-badge.green,
.status-badge.success {
  background: #d1fae5;
  color: #047857;
}

.status-badge.blue {
  background: #dbeafe;
  color: #1d4ed8;
}

.status-badge.yellow {
  background: #fef3c7;
  color: #b45309;
}

.status-badge.red,
.status-badge.danger {
  background: #fee2e2;
  color: #dc2626;
}

.status-badge.purple {
  background: #e9d5ff;
  color: #7c3aed;
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
  color: var(--danger, #dc2626);
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--gray-400);
}

@media (max-width: 960px) {
  .content-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .title-with-stats {
    flex-direction: column;
    align-items: flex-start;
  }

  .inline-stats {
    padding-left: 0;
    border-left: none;
  }
}

@media (max-width: 640px) {
  .role-section-header,
  .user-card-grid {
    padding-left: 14px;
    padding-right: 14px;
  }

  .user-card-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .user-card {
    min-height: auto;
    overflow: hidden;
  }

  .user-card-compact {
    min-height: 76px;
    padding-top: 42px;
    padding-bottom: 10px;
  }

  .user-card-hover-panel {
    position: static;
    opacity: 1;
    visibility: visible;
    transform: none;
    pointer-events: auto;
    padding: 0 16px 16px;
    background: transparent;
    box-shadow: none;
    gap: 10px;
  }

  .hover-panel-header {
    display: none;
  }

  .user-card-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
