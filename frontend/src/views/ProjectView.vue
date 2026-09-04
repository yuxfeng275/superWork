<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit } from '@element-plus/icons-vue'
import { getRoleLabel, hasRoleAccess } from '@/constants/roles'

interface BusinessLine {
  id: number
  name: string
  status: number
}

interface User {
  id: number
  realName: string
  role?: string
}

interface ProjectTreeNode {
  id: number
  businessLineId: number
  parentId: number | null
  level: number
  name: string
  fullPath: string
  code: string
  managerId: number | null
  status: number
  children?: ProjectTreeNode[]
}

interface Member {
  id: number
  projectId: number
  userId: number
  username?: string
  realName?: string
  role: string
}

const storedRole = (() => {
  try {
    return JSON.parse(localStorage.getItem('user') || '{}')?.role as string | undefined
  } catch {
    return undefined
  }
})()
const canManageProjects = hasRoleAccess(storedRole, 'management')

// Add-member form state
const treeData = ref<ProjectTreeNode[]>([])
const businessLines = ref<BusinessLine[]>([])
const allUsers = ref<User[]>([])
const loading = ref(false)
const dialogMainProjects = ref<ProjectTreeNode[]>([])

// Add-member form state
const addMemberVisible = ref(false)
const addMemberLoading = ref(false)
const addMemberForm = ref({ userId: undefined as number | undefined, role: '' })

// Filters
const filterBusinessLineId = ref<number | undefined>(undefined)
const filterName = ref('')

// Project form dialog
const dialogVisible = ref(false)
const isEdit = ref(false)
const projectForm = ref({
  id: undefined as number | undefined,
  businessLineId: undefined as number | undefined,
  parentId: null as number | null,
  name: '',
  code: '',
  managerId: undefined as number | undefined,
  status: 1
})
const projectFormRef = ref()
const projectRules = {
  businessLineId: [{ required: true, message: '请选择业务线', trigger: 'change' }],
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入项目编码', trigger: 'blur' }]
}

const loadDialogProjects = async (businessLineId?: number) => {
  try {
    // Use tree endpoint (no filter) — top-level nodes are always root projects
    const resp: any = await api.getProjectTree()
    const all: ProjectTreeNode[] = Array.isArray(resp) ? resp : (resp?.data ?? [])
    dialogMainProjects.value = all.filter(
      p => (!businessLineId || p.businessLineId === businessLineId) && p.id !== projectForm.value.id
    )
  } catch {
    dialogMainProjects.value = []
  }
}

// Detail drawer
const drawerVisible = ref(false)
const drawerProject = ref<ProjectTreeNode | null>(null)
const drawerMembers = ref<Member[]>([])
const drawerLoading = ref(false)

const loadData = async () => {
  loading.value = true
  try {
    const [treeResp, blResp, usersResp] = await Promise.all([
      api.getProjectTree(filterBusinessLineId.value),
      api.getBusinessLines({ size: 999 }),
      api.getUsers({ page: 1, size: 200 })
    ])
    treeData.value = Array.isArray(treeResp) ? treeResp : (treeResp?.data ?? [])
    const blRaw = blResp?.records ?? blResp?.data?.records ?? (Array.isArray(blResp) ? blResp : [])
    businessLines.value = blRaw
    const usersRaw = usersResp?.records ?? usersResp?.data?.records ?? (Array.isArray(usersResp) ? usersResp : [])
    allUsers.value = usersRaw
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const applyFilter = () => {
  loadData()
}

const resetFilter = () => {
  filterBusinessLineId.value = undefined
  filterName.value = ''
  loadData()
}

// 递归统计所有项目（含子项目）数量
const totalProjectCount = computed(() => {
  const countAll = (nodes: ProjectTreeNode[]): number =>
    nodes.reduce((acc, n) => acc + 1 + countAll(n.children ?? []), 0)
  return countAll(treeData.value)
})

// 卡片视图：按业务线分组，支持筛选
const cardGroups = computed(() => {
  const filterBl = filterBusinessLineId.value
  const filterN = filterName.value.trim().toLowerCase()
  return businessLines.value
    .filter(bl => !filterBl || bl.id === filterBl)
    .map(bl => {
      let projects = treeData.value.filter(p => p.businessLineId === bl.id)
      if (filterN) {
        projects = projects.filter(p =>
          p.name.toLowerCase().includes(filterN) ||
          (p.code || '').toLowerCase().includes(filterN) ||
          p.children?.some(c =>
            c.name.toLowerCase().includes(filterN) || (c.code || '').toLowerCase().includes(filterN)
          )
        )
      }
      return { ...bl, projects }
    })
    .filter(g => g.projects.length > 0)
})

const getManagerName = (managerId: number | null | undefined) => {
  if (managerId == null) return '—'
  return allUsers.value.find(u => u.id === managerId)?.realName ?? '—'
}

const getBusinessLineName = (businessLineId: number) => {
  return businessLines.value.find(bl => bl.id === businessLineId)?.name ?? '—'
}

const projectManagers = computed(() => {
  // 项目负责人可以由任意团队成员担任，角色仅用于下拉中的辅助提示。
  return allUsers.value
})

const STATUS_LABEL: Record<number, string> = { 0: '已结束', 1: '进行中', 2: '待开始', 3: '已交付', 4: '运维中' }
const STATUS_OPTIONS = [
  { value: 2, label: '待开始' },
  { value: 1, label: '进行中' },
  { value: 3, label: '已交付' },
  { value: 4, label: '运维中' },
  { value: 0, label: '已结束' }
]

// ---- CRUD ----
const openAdd = async (parentNode?: ProjectTreeNode) => {
  const blId = parentNode?.businessLineId
  projectForm.value = {
    id: undefined,
    businessLineId: blId,
    parentId: parentNode ? parentNode.id : null,
    name: '',
    code: '',
    managerId: undefined,
    status: 2
  }
  isEdit.value = false
  dialogVisible.value = true
  await loadDialogProjects(blId)
}

const openEdit = async (node: ProjectTreeNode) => {
  projectForm.value = {
    id: node.id,
    businessLineId: node.businessLineId,
    parentId: node.parentId,
    name: node.name,
    code: node.code,
    managerId: node.managerId ?? undefined,
    status: node.status
  }
  isEdit.value = true
  dialogVisible.value = true
  await loadDialogProjects(node.businessLineId)
}

const handleDelete = async (node: ProjectTreeNode) => {
  if (node.children && node.children.length > 0) {
    ElMessage.error('该项目存在子项目，无法删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确定要删除项目「${node.name}」吗？`, '提示', { type: 'warning' })
    await api.deleteProject(node.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

const submitProject = async () => {
  if (!projectFormRef.value) return
  await projectFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    const data = {
      businessLineId: projectForm.value.businessLineId!,
      parentId: projectForm.value.parentId ?? null,
      name: projectForm.value.name,
      code: projectForm.value.code,
      managerId: projectForm.value.managerId ?? null,
      status: projectForm.value.status
    }
    try {
      if (isEdit.value) {
        await api.updateProject(projectForm.value.id!, data)
        ElMessage.success('更新成功')
      } else {
        await api.createProject(data)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadData()
    } catch (e) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
}

// ---- Detail Drawer ----
const openDrawer = async (node: ProjectTreeNode) => {
  if ((node as any).isBusinessLine) return
  drawerProject.value = node
  drawerVisible.value = true
  addMemberVisible.value = false
  addMemberForm.value = { userId: undefined, role: '' }
  drawerMembers.value = []
  drawerLoading.value = true
  try {
    const members: any = await api.getProjectMembers(node.id)
    drawerMembers.value = Array.isArray(members) ? members : (members?.data ?? [])
  } catch (e) {
    drawerMembers.value = []
  } finally {
    drawerLoading.value = false
  }
}

const openAddInBL = async (blId: number) => {
  projectForm.value = { id: undefined, businessLineId: blId, parentId: null, name: '', code: '', managerId: undefined, status: 1 }
  isEdit.value = false
  dialogVisible.value = true
  await loadDialogProjects(blId)
}

const loadDrawerMembers = async () => {
  if (!drawerProject.value) return
  drawerLoading.value = true
  try {
    const members: any = await api.getProjectMembers(drawerProject.value.id)
    drawerMembers.value = Array.isArray(members) ? members : (members?.data ?? [])
  } catch (e) {
    drawerMembers.value = []
  } finally {
    drawerLoading.value = false
  }
}

const submitAddMember = async () => {
  if (!addMemberForm.value.userId || !drawerProject.value) return
  addMemberLoading.value = true
  try {
    await api.addProjectMember({
      projectId: drawerProject.value.id,
      userId: addMemberForm.value.userId,
      role: addMemberForm.value.role || undefined
    })
    ElMessage.success('添加成功')
    addMemberVisible.value = false
    addMemberForm.value = { userId: undefined, role: '' }
    await loadDrawerMembers()
  } catch (e) {
    ElMessage.error('添加失败')
  } finally {
    addMemberLoading.value = false
  }
}

const handleRemoveMember = async (member: Member) => {
  if (!drawerProject.value) return
  try {
    await ElMessageBox.confirm(`确定移除成员「${member.realName || member.username || '该用户'}」吗？`, '提示', { type: 'warning' })
    await api.removeProjectMember(drawerProject.value.id, member.userId)
    ElMessage.success('移除成功')
    await loadDrawerMembers()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('移除失败')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="project-page">
    <!-- 页面标题 + 操作按钮 -->
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">项目管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ totalProjectCount }}</span>
            <span class="stat-text">个项目</span>
          </span>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn btn-default" @click="resetFilter">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="1 4 1 10 7 10"/>
            <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
          </svg>
          重置
        </button>
        <button v-if="canManageProjects" class="btn btn-primary" @click="openAdd()">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          新增项目
        </button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-section">
      <div class="filter-bar">
        <el-select
          v-model="filterBusinessLineId"
          clearable
          placeholder="按业务线筛选"
          style="width: 180px"
          @change="applyFilter"
          @clear="applyFilter"
        >
          <el-option v-for="bl in businessLines" :key="bl.id" :label="bl.name" :value="bl.id" />
        </el-select>
        <div class="search-input-wrapper">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            type="text"
            class="search-input"
            v-model="filterName"
            placeholder="搜索项目名称或编码"
            @keyup.enter="applyFilter"
          >
        </div>
        <button class="btn btn-sm btn-default" @click="applyFilter">搜索</button>
      </div>
    </div>

    <!-- 卡片视图：按业务线分组 -->
    <div class="project-content" v-loading="loading">
      <el-empty v-if="!loading && cardGroups.length === 0" description="暂无项目" style="padding: 60px 0" />
      <div v-for="group in cardGroups" :key="group.id" class="bl-section">
        <div class="bl-header">
          <div class="bl-header-left">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
            <span class="bl-title">{{ group.name }}</span>
            <span class="bl-badge">{{ group.projects.length }} 个项目</span>
          </div>
          <span v-if="canManageProjects" class="card-action primary" @click="openAddInBL(group.id)">+ 新增项目</span>
        </div>
        <div class="project-card-grid">
          <div v-for="proj in group.projects" :key="proj.id" class="project-card">
            <div class="card-top" @click="openDrawer(proj)">
              <div class="card-name-row">
                <span class="card-name">{{ proj.name }}</span>
                <span :class="['card-status-badge', `status-${proj.status}`]">
                  {{ STATUS_LABEL[proj.status] ?? '—' }}
                </span>
              </div>
              <div class="card-meta-row">
                <span class="card-meta-item">
                  <span class="card-meta-label">编码</span>
                  <code class="card-code">{{ proj.code || '—' }}</code>
                </span>
                <span class="card-meta-item">
                  <span class="card-meta-label">负责人</span>
                  <span class="card-meta-value">{{ getManagerName(proj.managerId) }}</span>
                </span>
              </div>
            </div>
            <div v-if="proj.children && proj.children.length" class="card-subs">
              <span class="sub-label">子项目</span>
              <span v-for="sub in proj.children" :key="sub.id" class="sub-chip">
                <button type="button" class="sub-chip-name" @click.stop="openDrawer(sub)">
                  {{ sub.name }}
                </button>
                <el-tooltip v-if="canManageProjects" content="编辑子项目" placement="top">
                  <button
                    type="button"
                    class="sub-chip-edit"
                    :aria-label="`编辑子项目 ${sub.name}`"
                    @click.stop="openEdit(sub)"
                  >
                    <el-icon><Edit /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip v-if="canManageProjects" content="删除子项目" placement="top">
                  <button
                    type="button"
                    class="sub-chip-edit sub-chip-delete"
                    :aria-label="`删除子项目 ${sub.name}`"
                    @click.stop="handleDelete(sub)"
                  >
                    <el-icon><Delete /></el-icon>
                  </button>
                </el-tooltip>
              </span>
            </div>
            <div v-if="canManageProjects" class="card-footer">
              <span class="card-action primary" @click.stop="openAdd(proj)">+ 子项目</span>
              <span class="card-action primary" @click.stop="openEdit(proj)">编辑</span>
              <span class="card-action danger" @click.stop="handleDelete(proj)">删除</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑项目' : (projectForm.parentId ? '新增子项目' : '新增项目')"
      width="500px"
    >
      <el-form ref="projectFormRef" :model="projectForm" :rules="projectRules" label-width="90px">
        <el-form-item label="业务线" prop="businessLineId">
          <el-select
            v-model="projectForm.businessLineId"
            clearable
            placeholder="请选择业务线"
            style="width: 100%"
            :disabled="!!projectForm.parentId"
          >
            <el-option v-for="bl in businessLines" :key="bl.id" :label="bl.name" :value="bl.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="父项目">
          <el-select
            v-model="projectForm.parentId"
            clearable
            placeholder="留空为主项目"
            style="width: 100%"
            :disabled="isEdit"
          >
            <el-option
              v-for="p in dialogMainProjects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="projectForm.name" placeholder="如：PMS系统" />
        </el-form-item>
        <el-form-item label="编码" prop="code">
          <el-input v-model="projectForm.code" placeholder="如：PMS" />
        </el-form-item>
        <el-form-item label="项目负责人">
          <el-select v-model="projectForm.managerId" clearable placeholder="请选择项目负责人" style="width: 100%">
            <el-option v-for="u in projectManagers" :key="u.id" :label="u.realName" :value="u.id">
              <div class="manager-option-row">
                <span>{{ u.realName }}</span>
                <span>{{ getRoleLabel(u.role) }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="projectForm.status" style="width: 100%">
            <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProject">确定</el-button>
      </template>
    </el-dialog>

    <!-- Detail Drawer -->
    <el-drawer
      v-model="drawerVisible"
      :title="drawerProject?.name ?? '项目详情'"
      size="420px"
      direction="rtl"
    >
      <div v-if="drawerProject">
        <el-descriptions :column="1" border size="small" class="detail-desc">
          <el-descriptions-item label="项目编码">{{ drawerProject.code || '—' }}</el-descriptions-item>
          <el-descriptions-item label="完整路径">{{ drawerProject.fullPath }}</el-descriptions-item>
          <el-descriptions-item label="业务线">{{ getBusinessLineName(drawerProject.businessLineId) }}</el-descriptions-item>
          <el-descriptions-item label="层级">{{ drawerProject.level === 1 ? '主项目' : '子项目' }}</el-descriptions-item>
          <el-descriptions-item label="项目负责人">{{ getManagerName(drawerProject.managerId) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <span :class="['status-badge', { green: drawerProject.status === 1, blue: drawerProject.status === 4, orange: drawerProject.status === 2, purple: drawerProject.status === 3, gray: drawerProject.status === 0 }]">
              {{ STATUS_LABEL[drawerProject.status] ?? '—' }}
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="member-section">
          <div class="section-header">
            <h4 class="section-title">项目成员</h4>
            <span v-if="canManageProjects && !addMemberVisible" class="action-link primary" style="font-size:13px;cursor:pointer" @click="addMemberVisible = true">+ 添加成员</span>
            <span v-else-if="canManageProjects && addMemberVisible" class="action-link" style="font-size:13px;cursor:pointer;color:var(--gray-400)" @click="addMemberVisible = false; addMemberForm = { userId: undefined, role: '' }">取消</span>
          </div>

          <!-- 添加成员表单 -->
          <div v-if="addMemberVisible" class="add-member-form">
            <el-select
              v-model="addMemberForm.userId"
              placeholder="选择用户"
              filterable
              style="flex:1"
              size="small"
            >
              <el-option
                v-for="u in allUsers.filter(u => !drawerMembers.some(m => m.userId === u.id))"
                :key="u.id"
                :label="u.realName"
                :value="u.id"
              />
            </el-select>
            <el-input
              v-model="addMemberForm.role"
              placeholder="角色（选填）"
              size="small"
              style="flex:1"
            />
            <el-button
              type="primary"
              size="small"
              :loading="addMemberLoading"
              :disabled="!addMemberForm.userId"
              @click="submitAddMember"
            >确定</el-button>
          </div>

          <el-table
            :data="drawerMembers"
            v-loading="drawerLoading"
            size="small"
            border
          >
            <el-table-column label="成员">
              <template #default="{ row }">
                {{ row.realName || allUsers.find(u => u.id === row.userId)?.realName || `用户${row.userId}` }}
              </template>
            </el-table-column>
            <el-table-column label="角色" prop="role" />
            <el-table-column v-if="canManageProjects" label="操作" width="60" align="center">
              <template #default="{ row }">
                <span class="action-link danger" style="font-size:12px;cursor:pointer" @click="handleRemoveMember(row)">移除</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!drawerLoading && drawerMembers.length === 0" description="暂无成员" :image-size="60" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.project-page {
  width: 100%;
  min-width: 0;
}

/* ========== 页面头部（与需求管理一致） ========== */
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

/* ========== 按钮（与需求管理一致） ========== */
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

/* ========== 筛选栏 ========== */
.filter-section {
  background: #FFFFFF;
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

/* ========== 卡片视图 ========== */
.project-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 业务线分区 */
.bl-section {
  background: #fff;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.bl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--gray-50);
  border-bottom: 1px solid var(--gray-100);
}

.bl-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--gray-600);
}

.bl-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
}

.bl-badge {
  font-size: 12px;
  color: var(--gray-400);
  background: var(--gray-100);
  padding: 2px 8px;
  border-radius: 10px;
}

/* 项目卡片网格 */
.project-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr));
  gap: 16px;
  padding: 16px;
}

/* 单个项目卡片 */
.project-card {
  min-width: 0;
  border: 1px solid var(--gray-100);
  border-radius: var(--radius-md);
  background: #fff;
  transition: box-shadow 0.15s ease, border-color 0.15s ease;
  display: flex;
  flex-direction: column;
}

.project-card:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
  border-color: var(--primary-light, #e0e7ff);
}

.card-top {
  padding: 14px 16px 10px;
  cursor: pointer;
  flex: 1;
}

.card-name-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
  line-height: 1.4;
}

.card-status-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 10px;
  white-space: nowrap;
}

.card-status-badge.status-1 { background: #d1fae5; color: #047857; }
.card-status-badge.status-0 { background: var(--gray-100); color: var(--gray-500); }
.card-status-badge.status-2 { background: #fef3c7; color: #92400e; }
.card-status-badge.status-3 { background: #ede9fe; color: #5b21b6; }
.card-status-badge.status-4 { background: #dbeafe; color: #1d4ed8; }

.card-meta-row {
  display: flex;
  gap: 16px;
}

.card-meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.card-meta-label {
  font-size: 11px;
  color: var(--gray-400);
}

.card-code {
  font-size: 11px;
  color: var(--gray-500);
  background: var(--gray-100);
  padding: 1px 5px;
  border-radius: 3px;
  font-family: monospace;
}

.card-meta-value {
  font-size: 12px;
  color: var(--gray-600);
}

/* 子项目区域 */
.card-subs {
  padding: 0 16px 10px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.sub-label {
  font-size: 11px;
  color: var(--gray-400);
  flex-shrink: 0;
}

.sub-chip {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  color: var(--primary);
  background: var(--primary-light, #eef2ff);
  border-radius: 4px;
  transition: background 0.1s;
  overflow: hidden;
}

.sub-chip:hover {
  background: #dbeafe;
}

.sub-chip-name,
.sub-chip-edit {
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.sub-chip-name {
  min-width: 0;
  padding: 3px 5px 3px 8px;
  font-size: 11px;
  line-height: 18px;
}

.sub-chip-edit {
  width: 24px;
  height: 24px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  border-left: 1px solid color-mix(in srgb, var(--primary) 16%, transparent);
  opacity: 0.72;
  transition: background 0.15s ease, opacity 0.15s ease;
}

.sub-chip-edit:hover {
  background: color-mix(in srgb, var(--primary) 10%, white);
  opacity: 1;
}

.sub-chip-delete:hover {
  background: #fef2f2;
  color: var(--danger, #dc2626);
  opacity: 1;
}
.sub-chip-name:focus-visible,
.sub-chip-edit:focus-visible {
  position: relative;
  z-index: 1;
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}

.sub-chip-edit .el-icon {
  font-size: 13px;
}

.manager-option-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.manager-option-row span:last-child {
  color: var(--gray-400);
  font-size: 12px;
}

/* 卡片底部操作 */
.card-footer {
  padding: 8px 16px;
  border-top: 1px solid var(--gray-50);
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-action {
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
}

.card-action:hover { opacity: 0.75; }
.card-action.primary { color: var(--primary); }
.card-action.danger  { color: var(--danger, #dc2626); }

/* 项目名称链接 */
.project-name-link {
  color: var(--primary);
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s ease;
}

.project-name-link:hover {
  color: var(--primary-dark);
}

/* 编码文本 */
.code-text {
  font-size: 12px;
  color: var(--gray-400);
  font-family: monospace;
}

/* 项目标签 */
.project-tag {
  display: inline-block;
  padding: 2px 10px;
  background: var(--primary-light);
  color: var(--primary);
  border-radius: 4px;
  font-size: 12px;
}

/* 状态角标 */
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
.status-badge.orange { background: #fef3c7; color: #92400e; }
.status-badge.purple { background: #ede9fe; color: #5b21b6; }
.status-badge.green { background: #d1fae5; color: #047857; }
.status-badge.red { background: #fee2e2; color: #dc2626; }
.status-badge.purple { background: #e9d5ff; color: #7c3aed; }

/* 操作链接 */
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

/* ========== 操作链接不换行 ========== */
.action-links {
  white-space: nowrap;
}

/* ========== 业务线行样式 ========== */
.project-table :deep(.bl-row) {
  background: var(--gray-50) !important;
}

.project-table :deep(.bl-row td) {
  background: var(--gray-50) !important;
  border-bottom: 1px solid var(--gray-200) !important;
}

.bl-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-700);
}

/* ========== 详情抽屉 ========== */
.detail-desc {
  margin-bottom: 24px;
}

.member-section {
  margin-top: 8px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-700);
  margin: 0;
}

.add-member-form {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px;
  background: var(--gray-50);
  border-radius: var(--radius-sm);
}
</style>
