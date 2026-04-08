<script setup lang="ts">
import { ref } from 'vue'
import { onMounted } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface BusinessLine {
  id: number
  name: string
  description?: string
  status: number
}

interface Project {
  id: number
  businessLineId: number
  parentId: number | null
  level: number
  name: string
  fullPath: string
  code: string
  managerId: number | null
  status: number
  children?: Project[]
}

interface OrgRow {
  id: string | number
  type: 'business-line' | 'project'
  businessLineId: number | null
  parentId: number | null
  name: string
  description?: string
  code?: string
  managerId?: number | null
  status: number
  level: number
  children?: OrgRow[]
}

const tableData = ref<OrgRow[]>([])
const loading = ref(false)
const allUsers = ref<any[]>([])
const allBusinessLines = ref<BusinessLine[]>([])

// Business line dialog
const blDialogVisible = ref(false)
const isEditBL = ref(false)
const blForm = ref({ id: undefined as number | undefined, name: '', description: '', status: 1 })
const blFormRef = ref()
const blRules = {
  name: [{ required: true, message: '请输入业务线名称', trigger: 'blur' }]
}

// Project dialog
const projectDialogVisible = ref(false)
const isEditProject = ref(false)
const isGlobalProjectMode = ref(false)
const projectForm = ref({
  id: undefined as number | undefined,
  businessLineId: undefined as number | undefined,
  parentId: undefined as number | null | undefined,
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

// For parent project dropdown in project form
const projectsOfCurrentBL = ref<Project[]>([])

const buildTree = (businessLines: BusinessLine[], projects: Project[]): OrgRow[] => {
  const projId = (realId: number) => `proj-${realId}`
  return businessLines.map(bl => {
    // Find direct projects (parentId === null, i.e., main projects) for this BL
    const mainProjects = projects.filter(p => p.businessLineId === bl.id && p.parentId === null)
    const blChildren = mainProjects.map((proj: Project): OrgRow => {
      const subProjects = projects.filter(p => p.parentId === proj.id).map((cp: Project): OrgRow => ({
        id: projId(cp.id),
        type: 'project' as const,
        businessLineId: cp.businessLineId,
        parentId: cp.parentId,
        name: cp.name,
        code: cp.code,
        managerId: cp.managerId,
        status: cp.status,
        level: cp.level,
        children: []
      }))
      return {
        id: projId(proj.id),
        type: 'project' as const,
        businessLineId: proj.businessLineId,
        parentId: proj.parentId,
        name: proj.name,
        description: '',
        code: proj.code,
        managerId: proj.managerId,
        status: proj.status,
        level: proj.level,
        children: subProjects
      }
    })
    return {
      id: `bl-${bl.id}`,
      type: 'business-line' as const,
      businessLineId: bl.id,
      parentId: null,
      name: bl.name,
      description: bl.description,
      status: bl.status,
      level: 0,
      children: blChildren
    }
  })
}

const getProjectRealId = (rowId: string): number => {
  return parseInt(rowId.replace('proj-', ''), 10)
}

const getBLineId = (rowId: string): number => {
  return parseInt(rowId.replace('bl-', ''), 10)
}

const extractRecords = (resp: any): any[] => {
  if (Array.isArray(resp)) return resp
  if (resp?.records) return resp.records
  if (resp?.data?.records) return resp.data.records
  return []
}

const loadData = async () => {
  loading.value = true
  try {
    const [blResp, projResp, usersResp] = await Promise.all([
      api.getBusinessLines({ size: 999 }),
      api.getProjects({ size: 999 }),
      api.getUsers({ page: 1, size: 100 })
    ])
    const blData = extractRecords(blResp)
    allBusinessLines.value = blData
    const projData = extractRecords(projResp)
    tableData.value = buildTree(blData, projData)
    allUsers.value = extractRecords(usersResp)
  } catch (error) {
    console.error('加载数据失败:', error)
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const getManagerName = (managerId: number | null | undefined) => {
  if (managerId == null) return '—'
  const user = allUsers.value.find(u => u.id === managerId)
  return user?.realName ?? '—'
}

const STATUS_LABELS: Record<number, string> = { 1: '启用', 0: '禁用' }
const PROJECT_STATUS_LABELS: Record<number, string> = { 1: '进行中', 0: '已结束' }
const STATUS_TAG_TYPE: Record<number, string> = { 1: 'success', 0: 'info' }

// ---- Business Line CRUD ----
const openAddBL = () => {
  blForm.value = { id: undefined, name: '', description: '', status: 1 }
  isEditBL.value = false
  blDialogVisible.value = true
}
const handleEditBL = (row: OrgRow) => {
  blForm.value = { id: getBLineId(row.id as string), name: row.name, description: row.description || '', status: row.status }
  isEditBL.value = true
  blDialogVisible.value = true
}
const handleDeleteBL = async (row: OrgRow) => {
  try {
    const hasProjects = row.children && row.children.length > 0
    if (hasProjects) {
      ElMessage.error('该业务线存在关联项目，无法删除')
      return
    }
    await ElMessageBox.confirm(`确定要删除业务线「${row.name}」吗？`, '提示', { type: 'warning' })
    await api.deleteBusinessLine(getBLineId(row.id as string))
    ElMessage.success('删除成功')
    loadData()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}
const submitBL = async () => {
  if (!blFormRef.value) return
  await blFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    try {
      if (isEditBL.value) {
        await api.updateBusinessLine(getBLineId(blForm.value.id as any as string), {
          name: blForm.value.name,
          description: blForm.value.description,
          status: blForm.value.status
        })
        ElMessage.success('更新成功')
      } else {
        await api.createBusinessLine({
          name: blForm.value.name,
          description: blForm.value.description,
          status: blForm.value.status
        })
        ElMessage.success('创建成功')
      }
      blDialogVisible.value = false
      loadData()
    } catch (error) {
      ElMessage.error(isEditBL.value ? '更新失败' : '创建失败')
    }
  })
}

// ---- Project CRUD ----
const openAddProjectFromHeader = async () => {
  projectForm.value = {
    id: undefined,
    businessLineId: undefined,
    parentId: undefined,
    name: '',
    code: '',
    managerId: undefined,
    status: 1
  }
  isEditProject.value = false
  isGlobalProjectMode.value = true
  try {
    const projResp = await api.getProjects({ size: 999 })
    const allProj = extractRecords(projResp)
    projectsOfCurrentBL.value = allProj.filter((p: Project) => p.parentId == null)
  } catch (e) {
    console.error('[openAddProjectFromHeader] error:', e)
    projectsOfCurrentBL.value = []
  }
  projectDialogVisible.value = true
}
const openAddProject = async (parentBL: OrgRow, parentProject?: OrgRow) => {
  projectForm.value = {
    id: undefined,
    businessLineId: parentBL.businessLineId ?? undefined,
    parentId: parentProject ? getProjectRealId(parentProject.id as string) : undefined,
    name: '',
    code: '',
    managerId: undefined,
    status: 1
  }
  isEditProject.value = false
  isGlobalProjectMode.value = false
  // Load editable projects for parent dropdown (only main projects of this BL)
  try {
    const projResp = await api.getProjects({ size: 999 })
    const allProj = extractRecords(projResp)
    projectsOfCurrentBL.value = allProj.filter((p: Project) =>
      p.businessLineId === parentBL.businessLineId && p.parentId == null
    )
  } catch (e) {
    console.error('[openAddProject] error:', e)
    projectsOfCurrentBL.value = []
  }
  projectDialogVisible.value = true
}
const handleEditProject = async (row: OrgRow) => {
  const realId = getProjectRealId(row.id as string)
  projectForm.value = {
    id: realId,
    businessLineId: row.businessLineId ?? undefined,
    parentId: row.parentId ?? undefined,
    name: row.name,
    code: row.code || '',
    managerId: row.managerId ?? undefined,
    status: row.status
  }
  isEditProject.value = true
  try {
    const projResp = await api.getProjects({ size: 999 })
    const allProj = extractRecords(projResp)
    projectsOfCurrentBL.value = (allProj as Project[]).filter((p: Project) =>
      p.businessLineId === row.businessLineId && (p.parentId == null) && p.id !== realId
    )
  } catch {
    projectsOfCurrentBL.value = []
  }
  projectDialogVisible.value = true
}
const handleDeleteProject = async (row: OrgRow) => {
  try {
    if (row.children && row.children.length > 0) {
      ElMessage.error('该项目存在子项目，无法删除')
      return
    }
    await ElMessageBox.confirm(`确定要删除项目「${row.name}」吗？`, '提示', { type: 'warning' })
    await api.deleteProject(getProjectRealId(row.id as string))
    ElMessage.success('删除成功')
    loadData()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}
const submitProject = async () => {
  if (!projectFormRef.value) return
  await projectFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    try {
      const data = {
        businessLineId: projectForm.value.businessLineId!,
        parentId: projectForm.value.parentId ?? null,
        name: projectForm.value.name,
        code: projectForm.value.code,
        managerId: projectForm.value.managerId ?? null,
        status: projectForm.value.status
      }
      if (isEditProject.value) {
        await api.updateProject(projectForm.value.id!, data)
        ElMessage.success('更新成功')
      } else {
        await api.createProject(data)
        ElMessage.success('创建成功')
      }
      projectDialogVisible.value = false
      isGlobalProjectMode.value = false
      loadData()
    } catch (error) {
      ElMessage.error(isEditProject.value ? '更新失败' : '创建失败')
    }
  })
}

onMounted(() => { loadData() })
</script>

<template>
  <div class="organization-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">组织架构</h2>
        <p class="page-subtitle">管理业务线、项目（含父子层级）及项目团队。</p>
      </div>
      <button class="btn btn-primary" @click="openAddBL">
        <el-icon><Plus /></el-icon>
        新增业务线
      </button>
      <button class="btn btn-primary" @click="openAddProjectFromHeader">
        <el-icon><Plus /></el-icon>
        新增项目
      </button>
    </div>

    <div class="card">
      <el-table
        :data="tableData"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        default-expand-all
      >
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="编码" width="140">
          <template #default="{ row }">
            {{ row.type === 'project' ? (row.code || '—') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="项目经理" width="120">
          <template #default="{ row }">
            {{ row.type === 'project' ? getManagerName(row.managerId) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              :type="STATUS_TAG_TYPE[row.status] ?? 'info'"
              size="small"
            >
              {{ row.type === 'business-line' ? STATUS_LABELS[row.status] : PROJECT_STATUS_LABELS[row.status] ?? '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="200">
          <template #default="{ row }">
            {{ row.type === 'business-line' ? (row.description || '—') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="row.type === 'business-line'">
              <el-button link type="primary" @click="openAddProject(row)">新增项目</el-button>
              <el-button link type="primary" @click="handleEditBL(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteBL(row)">删除</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" @click="openAddProject(row, row)">子项目</el-button>
              <el-button link type="primary" @click="handleEditProject(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteProject(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Business Line Dialog -->
    <el-dialog
      v-model="blDialogVisible"
      :title="isEditBL ? '编辑业务线' : '新增业务线'"
      width="460px"
    >
      <el-form ref="blFormRef" :model="blForm" :rules="blRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="blForm.name" placeholder="如：全渠道云鹿SAAS" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="blForm.description" type="textarea" :rows="2" placeholder="业务线描述" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="blForm.status" :active-value="1" :inactive-value="0"
                     active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="blDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBL">确定</el-button>
      </template>
    </el-dialog>

    <!-- Project Dialog -->
    <el-dialog
      v-model="projectDialogVisible"
      :title="isEditProject ? '编辑项目' : '新增项目'"
      width="500px"
    >
      <el-form ref="projectFormRef" :model="projectForm" :rules="projectRules" label-width="90px">
        <el-form-item v-if="isGlobalProjectMode" label="业务线" prop="businessLineId">
          <el-select v-model="projectForm.businessLineId" clearable placeholder="请选择业务线" style="width: 100%">
            <el-option v-for="bl in allBusinessLines" :key="bl.id" :label="bl.name" :value="bl.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="业务线" prop="businessLineId">
          <el-input :value="tableData.find(r => r.type === 'business-line' && r.businessLineId === projectForm.businessLineId)?.name" disabled />
        </el-form-item>
        <el-form-item label="父项目">
          <el-select v-model="projectForm.parentId" clearable placeholder="留空为主项目" style="width: 100%">
            <el-option label="无（主项目）" :value="null" />
            <el-option
              v-for="p in projectsOfCurrentBL"
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
          <el-switch v-model="projectForm.status" :active-value="1" :inactive-value="0"
                     active-text="进行中" inactive-text="已结束" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProject">确定</el-button>
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
