<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface BusinessLine {
  id: number
  name: string
  status: number
}

interface User {
  id: number
  realName: string
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
  userId: number
  role: string
  user?: User
}

// State
const treeData = ref<ProjectTreeNode[]>([])
const businessLines = ref<BusinessLine[]>([])
const allUsers = ref<User[]>([])
const loading = ref(false)
const dialogMainProjects = ref<ProjectTreeNode[]>([])

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

// Filter tree by name client-side
const filteredTree = computed(() => {
  if (!filterName.value.trim()) return treeData.value
  const keyword = filterName.value.trim().toLowerCase()
  const filterNodes = (nodes: ProjectTreeNode[]): ProjectTreeNode[] => {
    return nodes.reduce<ProjectTreeNode[]>((acc, node) => {
      const matchSelf = node.name.toLowerCase().includes(keyword) || node.code.toLowerCase().includes(keyword)
      const filteredChildren = filterNodes(node.children ?? [])
      if (matchSelf || filteredChildren.length > 0) {
        acc.push({ ...node, children: filteredChildren })
      }
      return acc
    }, [])
  }
  return filterNodes(treeData.value)
})

const getManagerName = (managerId: number | null | undefined) => {
  if (managerId == null) return '—'
  return allUsers.value.find(u => u.id === managerId)?.realName ?? '—'
}

const getBusinessLineName = (businessLineId: number) => {
  return businessLines.value.find(bl => bl.id === businessLineId)?.name ?? '—'
}

const STATUS_TAG: Record<number, 'success' | 'info'> = { 1: 'success', 0: 'info' }
const STATUS_LABEL: Record<number, string> = { 1: '进行中', 0: '已结束' }

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
    status: 1
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
  drawerProject.value = node
  drawerVisible.value = true
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

onMounted(loadData)
</script>

<template>
  <div class="project-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">项目管理</h2>
        <p class="page-subtitle">查看和管理所有项目及子项目层级结构。</p>
      </div>
      <button class="btn btn-primary" @click="openAdd()">
        <el-icon><Plus /></el-icon>
        新增项目
      </button>
    </div>

    <!-- Filter Bar -->
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
        <el-input
          v-model="filterName"
          placeholder="搜索项目名称或编码"
          clearable
          style="width: 220px"
          @keyup.enter="applyFilter"
          @clear="resetFilter"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="applyFilter">搜索</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
    </div>

    <!-- Tree Table -->
    <div class="table-section">
      <el-table
        :data="filteredTree"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        default-expand-all
        style="width: 100%"
      >
        <el-table-column prop="name" label="项目名称" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrawer(row)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="130">
          <template #default="{ row }">{{ row.code || '—' }}</template>
        </el-table-column>
        <el-table-column label="业务线" width="150">
          <template #default="{ row }">{{ getBusinessLineName(row.businessLineId) }}</template>
        </el-table-column>
        <el-table-column label="层级" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 1 ? 'primary' : 'warning'">
              {{ row.level === 1 ? '主项目' : '子项目' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="项目经理" width="120">
          <template #default="{ row }">{{ getManagerName(row.managerId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] ?? 'info'" size="small">
              {{ STATUS_LABEL[row.status] ?? '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.level === 1"
              link
              type="primary"
              @click="openAdd(row)"
            >新增子项目</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
          >
            <el-option label="无（主项目）" :value="null" />
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
        <el-form-item label="项目经理">
          <el-select v-model="projectForm.managerId" clearable placeholder="请选择" style="width: 100%">
            <el-option v-for="u in allUsers" :key="u.id" :label="u.realName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="projectForm.status"
            :active-value="1"
            :inactive-value="0"
            active-text="进行中"
            inactive-text="已结束"
          />
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
          <el-descriptions-item label="项目经理">{{ getManagerName(drawerProject.managerId) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="STATUS_TAG[drawerProject.status] ?? 'info'" size="small">
              {{ STATUS_LABEL[drawerProject.status] ?? '—' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="member-section">
          <h4 class="section-title">项目成员</h4>
          <el-table
            :data="drawerMembers"
            v-loading="drawerLoading"
            size="small"
            border
          >
            <el-table-column label="成员" prop="userId">
              <template #default="{ row }">
                {{ allUsers.find(u => u.id === row.userId)?.realName ?? `用户${row.userId}` }}
              </template>
            </el-table-column>
            <el-table-column label="角色" prop="role" />
          </el-table>
          <el-empty v-if="!drawerLoading && drawerMembers.length === 0" description="暂无成员" :image-size="60" />
        </div>
      </div>
    </el-drawer>
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

.filter-section {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 16px 20px;
  margin-bottom: 16px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.table-section {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px;
  margin-bottom: 16px;
}

.detail-desc {
  margin-bottom: 24px;
}

.member-section {
  margin-top: 8px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-700);
  margin: 0 0 12px;
}
</style>
