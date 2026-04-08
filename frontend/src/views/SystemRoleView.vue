<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Role {
  id: number
  code: string
  name: string
  description?: string
  status: number
  createdAt?: string
}

interface Menu {
  id: number
  parentId: number | null
  name: string
  icon?: string
  path?: string
  component?: string
  sortOrder?: number
  visible?: number
  status?: number
  children?: Menu[]
}

interface Permission {
  id: number
  code: string
  name: string
  description?: string
  type?: string
  menuId?: number | null
}

const DATA_SCOPE_OPTIONS = [
  { value: 'ALL', label: '全部数据可见' },
  { value: 'BU_LINE', label: '按业务线' },
  { value: 'PROJECT', label: '按项目/部门' },
  { value: 'SELF', label: '仅本人数据' }
]

const roles = ref<Role[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()

const form = ref({
  id: undefined as number | undefined,
  code: '',
  name: '',
  description: '',
  status: 1
})

const rules = {
  code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}

// ---- Authorization Dialog ----
const authDialogVisible = ref(false)
const currentRoleId = ref<number | null>(null)
const currentRoleName = ref('')
const menuTree = ref<Menu[]>([])
const allPermissions = ref<Permission[]>([])
const authLoading = ref(false)

const checkedMenuKeys = ref<number[]>([])
const focusedMenuId = ref<number | null>(null)
const menuButtonStates = ref<Map<number, Set<number>>>(new Map())
const dataScope = ref('SELF')
const dataScopeValue = ref('')

// Build menu tree
const buildMenuTree = (menus: Menu[]): Menu[] => {
  const nodeMap = new Map<number, Menu & { children: Menu[] }>()
  const roots: Array<Menu & { children: Menu[] }> = []

  // First pass: create all nodes
  menus.forEach(menu => {
    const nodeId = menu.id ?? 0
    // Use a unique ID to avoid collisions (parentId=0 means root)
    nodeMap.set(nodeId, { ...menu, children: [] })
  })

  // Second pass: build hierarchy
  menus.forEach(menu => {
    const nodeId = menu.id ?? 0
    const parentId = menu.parentId ?? 0
    const node = nodeMap.get(nodeId)!

    if (parentId === 0) {
      roots.push(node)
    } else {
      const parent = nodeMap.get(parentId)
      if (parent) {
        parent.children.push(menu as Menu & { children: Menu[] })
      } else {
        // Parent not found, treat as root
        roots.push(node)
      }
    }
  })

  const sortNodes = (nodes: Menu[]) => {
    nodes.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    nodes.forEach(node => {
      if (node.children?.length) sortNodes(node.children)
    })
  }
  sortNodes(roots)
  return roots
}


// Permissions grouped by menu
const permissionsByMenu = computed(() => {
  const map = new Map<number, Permission[]>()
  allPermissions.value.forEach(p => {
    if (p.menuId != null) {
      if (!map.has(p.menuId)) map.set(p.menuId, [])
      map.get(p.menuId)!.push(p)
    }
  })
  return map
})

const currentMenuButtons = computed(() => {
  if (focusedMenuId.value == null) return []
  return permissionsByMenu.value.get(focusedMenuId.value) ?? []
})

const isButtonChecked = (permId: number) => {
  if (focusedMenuId.value == null) return false
  return menuButtonStates.value.get(focusedMenuId.value)?.has(permId) ?? false
}

const toggleButton = (permId: number) => {
  if (focusedMenuId.value == null) return
  const set = menuButtonStates.value.get(focusedMenuId.value) ?? new Set()
  if (set.has(permId)) set.delete(permId)
  else set.add(permId)
  menuButtonStates.value.set(focusedMenuId.value, set)
}

const handleNodeClick = (nodeData: Menu) => {
  focusedMenuId.value = nodeData.id
}

const loadRoles = async () => {
  loading.value = true
  try {
    const data = await api.getRoles()
    roles.value = Array.isArray(data) ? data : []
  } catch (error) {
    console.error('加载角色失败:', error)
    ElMessage.error('加载角色失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  form.value = { id: undefined, code: '', name: '', description: '', status: 1 }
  isEdit.value = false
  dialogVisible.value = true
}

const handleEdit = (row: Role) => {
  form.value = { id: row.id, code: row.code, name: row.name, description: row.description || '', status: row.status }
  isEdit.value = true
  dialogVisible.value = true
}

const handleDelete = async (row: Role) => {
  try {
    await ElMessageBox.confirm(`确定要删除角色「${row.name}」吗？关联的用户、菜单、权限关系将一并清除。`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.deleteRole(row.id)
    ElMessage.success('删除成功')
    loadRoles()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    try {
      if (isEdit.value) {
        await api.updateRole(form.value.id!, { name: form.value.name, description: form.value.description, status: form.value.status })
        ElMessage.success('更新成功')
      } else {
        await api.createRole({ code: form.value.code, name: form.value.name, description: form.value.description, status: form.value.status })
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadRoles()
    } catch (error) {
      ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    }
  })
}

const openAuthDialog = async (row: Role) => {
  currentRoleId.value = row.id
  currentRoleName.value = row.name
  authDialogVisible.value = true
  authLoading.value = true
  menuButtonStates.value = new Map()
  focusedMenuId.value = null
  checkedMenuKeys.value = []

  try {
    const [menus, permissions, auth] = await Promise.all([
      api.getMenus(),
      api.getPermissions(),
      api.getRoleAuthorization(row.id)
    ])

    const menuList = Array.isArray(menus) ? menus : []
    const allMenusFlat = menuList.map((m: any) => ({
      ...m,
      parentId: m.parentId ?? 0
    }))
    menuTree.value = buildMenuTree(allMenusFlat)
    allPermissions.value = Array.isArray(permissions) ? permissions : []

    checkedMenuKeys.value = auth.menuIds || []

    // Build button state map: group permission IDs by menu_id
    const permByMenu = new Map<number, number[]>()
    for (const permId of (auth.permissionIds || [])) {
      const perm = allPermissions.value.find(p => p.id === permId)
      if (perm?.menuId != null) {
        if (!permByMenu.has(perm.menuId)) permByMenu.set(perm.menuId, [])
        permByMenu.get(perm.menuId)!.push(permId)
      }
    }
    const buttonMap = new Map<number, Set<number>>()
    permByMenu.forEach((ids, mid) => buttonMap.set(mid, new Set(ids)))
    menuButtonStates.value = buttonMap

    // Restore data scope
    dataScope.value = auth.dataScope || 'SELF'
    dataScopeValue.value = auth.dataScopeValue || ''

    // Focus first checked menu
    if (checkedMenuKeys.value.length > 0) {
      focusedMenuId.value = checkedMenuKeys.value[0]
    }
  } catch (error) {
    console.error('加载授权数据失败:', error)
    ElMessage.error('加载授权数据失败')
  } finally {
    authLoading.value = false
  }
}

const findParentMenuIds = (menuId: number, tree: Menu[]): number[] => {
  const parents: number[] = []
  const find = (nodes: Menu[], targetId: number): boolean => {
    for (const node of nodes) {
      if (node.children?.some(c => c.id === targetId)) {
        parents.push(node.id)
        return true
      }
      if (node.children && find(node.children, targetId)) {
        parents.push(node.id)
        return true
      }
    }
    return false
  }
  find(tree, menuId)
  return parents
}

const handleSaveAuth = async () => {
  if (!currentRoleId.value) return
  authLoading.value = true
  try {
    // Collect all checked menu IDs + parent menus
    const allMenuIds = new Set<number>(checkedMenuKeys.value)
    for (const mid of checkedMenuKeys.value) {
      findParentMenuIds(mid, menuTree.value).forEach(id => allMenuIds.add(id))
    }

    // Collect all button permission IDs from cache
    const allPermIds = new Set<number>()
    menuButtonStates.value.forEach(s => s.forEach(id => allPermIds.add(id)))

    await api.assignRoleAuthorization(
      currentRoleId.value,
      Array.from(allMenuIds),
      Array.from(allPermIds),
      dataScope.value,
      dataScopeValue.value
    )
    ElMessage.success('授权配置已保存')
    authDialogVisible.value = false
  } catch (error) {
    console.error('保存授权失败:', error)
    ElMessage.error('保存授权失败')
  } finally {
    authLoading.value = false
  }
}

const getStatusLabel = (status: number) => status === 1 ? '启用' : '禁用'
const getStatusTagType = (status: number) => status === 1 ? 'success' : 'info'

onMounted(() => { loadRoles() })
</script>

<template>
  <div class="system-role-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-subtitle">管理角色菜单权限、按钮权限与数据范围。</p>
      </div>
      <button class="btn btn-primary" @click="handleAdd">
        <el-icon><Plus /></el-icon>
        新增角色
      </button>
    </div>

    <div class="card">
      <el-table :data="roles" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="code" label="角色编码" min-width="150" />
        <el-table-column prop="name" label="角色名称" min-width="150" />
        <el-table-column prop="description" label="描述" min-width="200" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAuthDialog(row)">配置授权</el-button>
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Role CRUD Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑角色' : '新增角色'"
      width="500px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="角色编码" prop="code">
          <el-input v-model="form.code" :disabled="isEdit" placeholder="如: PM_MANAGER" />
        </el-form-item>
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" placeholder="如: 项目经理" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="角色描述" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0"
                     active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- Authorization Dialog -->
    <el-dialog
      v-model="authDialogVisible"
      :title="`配置授权 - ${currentRoleName}`"
      width="1000px"
      destroy-on-close
    >
      <div v-loading="authLoading" class="auth-layout">
        <div class="auth-columns">
          <!-- Left: Menu Tree -->
          <div class="auth-menu-col">
            <div class="menu-col-header">菜单权限</div>
            <el-tree
              ref="menuTreeRef"
              :data="menuTree"
              :props="{ label: 'name', children: 'children' }"
              show-checkbox
              node-key="id"
              v-model:checked-keys="checkedMenuKeys"
              @node-click="handleNodeClick"
              :check-strictly="false"
            />
          </div>

          <!-- Right: Button Permissions + Data Scope -->
          <div class="auth-right-col">
            <!-- Button Permissions Section -->
            <div class="panel-section">
              <div class="section-header">按钮权限</div>
              <template v-if="focusedMenuId">
                <div class="btn-header">
                  <span class="btn-menu-name">
                    {{ allPermissions.find(p => p.menuId === focusedMenuId)?.name ?? '' }}
                  </span>
                  <span class="btn-count">{{ currentMenuButtons.length }} 个操作权限</span>
                </div>
                <div class="btn-list">
                  <div
                    v-for="perm in currentMenuButtons"
                    :key="perm.id"
                    class="btn-item"
                    :class="{ 'btn-checked': isButtonChecked(perm.id) }"
                    @click="toggleButton(perm.id)"
                  >
                    <el-checkbox
                      :model-value="isButtonChecked(perm.id)"
                      @click.stop
                    >
                      <span class="perm-name">{{ perm.name }}</span>
                      <span class="perm-code">{{ perm.code }}</span>
                    </el-checkbox>
                    <el-tag
                      v-if="perm.type"
                      size="small"
                      :type="perm.type === 'button' ? 'warning' : perm.type === 'api' ? 'danger' : 'info'"
                    >{{ perm.type }}</el-tag>
                  </div>
                </div>
              </template>
              <div v-else class="empty-area">
                <p>请点击左侧菜单节点</p>
                <p>查看该菜单下的操作权限</p>
              </div>
            </div>

            <!-- Data Scope Section -->
            <div class="panel-section section-divider">
              <div class="section-header">数据范围</div>
              <div class="ds-form">
                <el-radio-group v-model="dataScope" class="ds-radio-group">
                  <el-radio
                    v-for="opt in DATA_SCOPE_OPTIONS"
                    :key="opt.value"
                    :label="opt.value"
                  >{{ opt.label }}</el-radio>
                </el-radio-group>
                <div v-if="dataScope === 'BU_LINE'" class="ds-value-row">
                  <span class="ds-label">业务线 ID：</span>
                  <el-input v-model="dataScopeValue" placeholder="多个用逗号分隔，如 1,3,5" style="width: 240px" />
                </div>
                <div v-else-if="dataScope === 'PROJECT'" class="ds-value-row">
                  <span class="ds-label">项目 ID：</span>
                  <el-input v-model="dataScopeValue" placeholder="多个用逗号分隔，如 2,4,7" style="width: 240px" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="authDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveAuth" :loading="authLoading">保存</el-button>
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

/* Authorization Layout */
.auth-layout {
  min-height: 520px;
}

.auth-columns {
  display: flex;
  gap: 16px;
  min-height: 520px;
}

.menu-col-header {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-700);
  margin-bottom: 8px;
  padding-left: 6px;
}

.auth-menu-col {
  flex: 0 0 340px;
  overflow-y: auto;
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  padding: 14px;
}

.auth-right-col {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0;
  min-height: 520px;
}

.panel-section {
  flex-shrink: 0;
  padding: 12px 16px;
}

.section-divider {
  border-top: 1px solid var(--gray-200);
}

.section-header {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-600);
  margin-bottom: 10px;
}

/* Button List */
.btn-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.btn-menu-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
}

.btn-count {
  font-size: 12px;
  color: var(--gray-400);
}

.btn-list {
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.btn-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-item:hover {
  background: var(--gray-50);
}

.btn-checked {
  background: var(--gray-50);
}

.perm-name {
  font-size: 13px;
  color: var(--gray-800);
  margin-right: 8px;
}

.perm-code {
  font-size: 11px;
  color: var(--gray-400);
  font-family: monospace;
}

.empty-area {
  text-align: center;
  color: var(--gray-400);
  font-size: 13px;
  margin-top: 60px;
}

.empty-area p {
  margin: 4px 0;
}

/* Data Scope Form */
.ds-form {
  padding: 4px 0;
}

.ds-radio-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

:deep(.ds-radio-group .el-radio) {
  margin-right: 6px;
  margin-bottom: 0;
}

.ds-value-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.ds-label {
  font-size: 13px;
  color: var(--gray-600);
  white-space: nowrap;
}

@media (max-width: 768px) {
  .auth-columns {
    flex-direction: column;
    height: auto;
  }

  .auth-menu-col {
    flex: none;
    max-height: 280px;
  }

  .auth-right-col {
    max-height: 320px;
  }
}
</style>
