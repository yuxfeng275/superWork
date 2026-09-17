<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '@/utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ElTree } from 'element-plus'
import {
  POSITION_ROLE_BADGE_CLASS,
  POSITION_ROLE_OPTIONS,
  getPositionRole
} from '@/constants/roles'
import type { PositionRoleOption } from '@/constants/roles'

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

interface BusinessLineOption {
  id: number
  name: string
}

interface ProjectOption {
  id: number
  name: string
  businessLineId: number
}

interface DefaultRoleRow {
  preset: PositionRoleOption
  role?: Role
}

const DATA_SCOPE_OPTIONS = [
  { value: 'ALL', label: '全部数据可见' },
  { value: 'BU_LINE', label: '按业务线' },
  { value: 'PROJECT', label: '按项目/部门' },
  { value: 'SELF', label: '仅本人数据' }
]

const positionRoleOrder = new Map(POSITION_ROLE_OPTIONS.map((role, index) => [role.value, index]))

const roles = ref<Role[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
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

const canonicalRoleCodes = new Set(POSITION_ROLE_OPTIONS.map(role => role.value))

const roleByCode = computed(() => new Map(roles.value.map(role => [role.code, role])))

const defaultRoleRows = computed<DefaultRoleRow[]>(() => {
  return POSITION_ROLE_OPTIONS.map(preset => ({
    preset,
    role: roleByCode.value.get(preset.value)
  }))
})

const legacyRoles = computed(() => roles.value.filter(role => !canonicalRoleCodes.has(role.code)))

const rolePositionGroups = computed(() => {
  return [
    { value: 'management', label: '管理序列' },
    { value: 'execution', label: '执行序列' }
  ].map(group => {
    const rows = defaultRoleRows.value.filter(row => row.preset.category === group.value)
    return {
      ...group,
      rows,
      activeCount: rows.filter(row => row.role?.status === 1).length
    }
  })
})

const defaultRoleStats = computed(() => ({
  total: POSITION_ROLE_OPTIONS.length,
  management: POSITION_ROLE_OPTIONS.filter(role => role.category === 'management').length,
  execution: POSITION_ROLE_OPTIONS.filter(role => role.category === 'execution').length
}))

const getRoleCategoryLabel = (code: string) => {
  return getPositionRole(code)?.categoryLabel ?? '自定义角色'
}

const getRoleBadgeClass = (code: string) => {
  return POSITION_ROLE_BADGE_CLASS[code] ?? 'gray'
}

// ---- Authorization Dialog ----
const authDialogVisible = ref(false)
const currentRoleId = ref<number | null>(null)
const currentRoleName = ref('')
const menuTree = ref<Menu[]>([])
const allPermissions = ref<Permission[]>([])
const authLoading = ref(false)
const businessLines = ref<BusinessLineOption[]>([])
const projects = ref<ProjectOption[]>([])

const checkedMenuKeys = ref<number[]>([])
const menuTreeRef = ref<InstanceType<typeof ElTree> | null>(null)
const focusedMenuId = ref<number | null>(null)
const menuButtonStates = ref<Map<number, Set<number>>>(new Map())
const dataScope = ref('SELF')
const dataScopeValue = ref('')
const selectedBusinessLineIds = ref<number[]>([])
const selectedProjectIds = ref<number[]>([])

// 全部菜单 ID（用于默认展开与全选）；初始勾选快照（用于变更预览）
const allMenuIds = computed(() => {
  const ids: number[] = []
  const walk = (nodes: Menu[]) => nodes.forEach(node => { ids.push(node.id); if (node.children?.length) walk(node.children) })
  walk(menuTree.value)
  return ids
})
const initialMenuKeys = ref<number[]>([])

const checkAllMenus = () => {
  const keys = [...allMenuIds.value]
  checkedMenuKeys.value = keys
  menuTreeRef.value?.setCheckedKeys(keys)
}

const clearAllMenus = () => {
  checkedMenuKeys.value = []
  menuTreeRef.value?.setCheckedKeys([])
}

// el-tree 没有 checkedKeys prop / update:checkedKeys 事件，v-model:checked-keys 无效，
// 必须通过 check 事件把树的勾选状态同步回 checkedMenuKeys
const handleTreeCheck = (_data: Menu, { checkedKeys }: { checkedKeys: Array<string | number> }) => {
  checkedMenuKeys.value = checkedKeys.map(Number)
}

// 树中的叶子菜单 id（不含分组父级）。回显时只把这些 id 交给 setCheckedKeys：
// el-tree 在 check-strictly=false 下会把“传入的父级 id”当作整棵子树勾选（_setCheckedKeys
// 对父级做 deep check，并把其所有后代放入 cache 阻止清除），导致已移除的同级子菜单被
// 级联勾回（表现为保存后重开仍残留旧勾选）。只回显叶子后，父级全选/半选由 el-tree 依据
// 叶子勾选自动推导，与后端存储的 menuIds 完全一致。
const leafMenuKeys = (ids: number[]): number[] => {
  const leafIds = new Set<number>()
  const collect = (nodes: Menu[]) => {
    nodes.forEach(node => {
      if (node.children?.length) collect(node.children)
      else leafIds.add(node.id)
    })
  }
  collect(menuTree.value)
  return ids.filter(id => leafIds.has(id))
}

const menuNameById = (id: number): string => {
  for (const menu of menuTree.value) {
    if (menu.id === id) return menu.name
    for (const child of menu.children || []) {
      if (child.id === id) return `${menu.name}/${child.name}`
    }
  }
  return String(id)
}

const authChangeSummary = (nextMenuIds: Set<number>): string => {
  const initial = new Set(initialMenuKeys.value)
  const added = [...nextMenuIds].filter(id => !initial.has(id)).map(menuNameById)
  const removed = [...initial].filter(id => !nextMenuIds.has(id)).map(menuNameById)
  const parts: string[] = []
  if (added.length) parts.push(`新增 ${added.length} 项：${added.join('、')}`)
  if (removed.length) parts.push(`移除 ${removed.length} 项：${removed.join('、')}`)
  return parts.join('<br/>')
}

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
        parent.children.push(node)
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

const availableProjects = computed(() => {
  if (selectedBusinessLineIds.value.length === 0) return projects.value
  return projects.value.filter(project => selectedBusinessLineIds.value.includes(project.businessLineId))
})

const toggleBusinessLineScope = (id: number) => {
  const index = selectedBusinessLineIds.value.indexOf(id)
  if (index >= 0) selectedBusinessLineIds.value.splice(index, 1)
  else selectedBusinessLineIds.value.push(id)
}

const toggleProjectScope = (id: number) => {
  const index = selectedProjectIds.value.indexOf(id)
  if (index >= 0) selectedProjectIds.value.splice(index, 1)
  else selectedProjectIds.value.push(id)
}

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

const parseScopeValues = (value: string) => {
  return value
    .split(',')
    .map(item => Number(item.trim()))
    .filter(item => Number.isFinite(item))
}

const loadRoles = async () => {
  loading.value = true
  try {
    const data = await api.getRoles()
    const list = Array.isArray(data) ? data : []
    roles.value = [...list].sort((left, right) => {
      const leftOrder = positionRoleOrder.get(left.code) ?? Number.MAX_SAFE_INTEGER
      const rightOrder = positionRoleOrder.get(right.code) ?? Number.MAX_SAFE_INTEGER
      if (leftOrder !== rightOrder) return leftOrder - rightOrder
      return left.id - right.id
    })
  } catch (error) {
    console.error('加载角色失败:', error)
    ElMessage.error('加载角色失败')
  } finally {
    loading.value = false
  }
}

const handleEdit = (row: Role) => {
  form.value = { id: row.id, code: row.code, name: row.name, description: row.description || '', status: row.status }
  dialogVisible.value = true
}

const handleInitializeDefaultRole = async (preset: PositionRoleOption) => {
  try {
    await api.createRole({
      code: preset.value,
      name: preset.label,
      description: preset.description,
      status: 1
    })
    ElMessage.success('默认角色已初始化')
    loadRoles()
  } catch (error) {
    console.error('初始化默认角色失败:', error)
    ElMessage.error('初始化失败')
  }
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
      await api.updateRole(form.value.id!, { name: form.value.name, description: form.value.description, status: form.value.status })
      ElMessage.success('更新成功')
      dialogVisible.value = false
      loadRoles()
    } catch (error) {
      ElMessage.error('更新失败')
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
    const [menus, permissions, auth, businessLinePayload, projectPayload] = await Promise.all([
      api.getMenus(),
      api.getPermissions(),
      api.getRoleAuthorization(row.id),
      api.getBusinessLines({ page: 1, size: 999 }),
      api.getProjects({ page: 1, size: 999 })
    ])

    const menuList = Array.isArray(menus) ? menus : []
    const allMenusFlat = menuList.map((m: any) => ({
      ...m,
      parentId: m.parentId ?? 0
    }))
    menuTree.value = buildMenuTree(allMenusFlat)
    allPermissions.value = Array.isArray(permissions) ? permissions : []

    checkedMenuKeys.value = auth.menuIds || []
    initialMenuKeys.value = [...checkedMenuKeys.value]
    // 等待 el-tree 应用新 data 后，通过实例方法回显勾选（v-model:checked-keys 对 el-tree 无效）。
    // 注意只回显叶子节点：直接回显父级 id 会让 el-tree 级联勾选整棵子树，残留已移除的同级菜单。
    await nextTick()
    menuTreeRef.value?.setCheckedKeys(leafMenuKeys(checkedMenuKeys.value))

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
    businessLines.value = Array.isArray(businessLinePayload?.records) ? businessLinePayload.records : Array.isArray(businessLinePayload) ? businessLinePayload : []
    projects.value = Array.isArray(projectPayload?.records) ? projectPayload.records : Array.isArray(projectPayload) ? projectPayload : []

    if (dataScope.value === 'BU_LINE') {
      selectedBusinessLineIds.value = parseScopeValues(dataScopeValue.value)
      selectedProjectIds.value = []
    } else if (dataScope.value === 'PROJECT') {
      selectedProjectIds.value = parseScopeValues(dataScopeValue.value)
      selectedBusinessLineIds.value = []
    } else {
      selectedBusinessLineIds.value = []
      selectedProjectIds.value = []
    }

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
    const finalMenuIds = new Set<number>(checkedMenuKeys.value)
    for (const mid of checkedMenuKeys.value) {
      findParentMenuIds(mid, menuTree.value).forEach(id => finalMenuIds.add(id))
    }

    const summary = authChangeSummary(finalMenuIds)
    if (summary) {
      try {
        await ElMessageBox.confirm(summary, '确认以下授权变更', {
          confirmButtonText: '确认保存',
          cancelButtonText: '继续修改',
          dangerouslyUseHTMLString: true,
          type: 'info'
        })
      } catch {
        return
      }
    }

    // Collect all button permission IDs from cache
    const allPermIds = new Set<number>()
    menuButtonStates.value.forEach(s => s.forEach(id => allPermIds.add(id)))

    if (dataScope.value === 'BU_LINE') {
      dataScopeValue.value = selectedBusinessLineIds.value.join(',')
    } else if (dataScope.value === 'PROJECT') {
      dataScopeValue.value = selectedProjectIds.value.join(',')
    } else {
      dataScopeValue.value = ''
    }

    await api.assignRoleAuthorization(
      currentRoleId.value,
      Array.from(finalMenuIds),
      Array.from(allPermIds),
      dataScope.value,
      dataScopeValue.value
    )
    ElMessage.success('授权配置已保存')
    initialMenuKeys.value = [...checkedMenuKeys.value]
    authDialogVisible.value = false
  } catch (error) {
    console.error('保存授权失败:', error)
    ElMessage.error('保存授权失败')
  } finally {
    authLoading.value = false
  }
}

onMounted(() => { loadRoles() })

watch(dataScope, scope => {
  if (scope === 'BU_LINE') {
    selectedProjectIds.value = []
  }
  if (scope === 'PROJECT') {
    selectedBusinessLineIds.value = []
  }
  if (scope === 'ALL' || scope === 'SELF') {
    selectedBusinessLineIds.value = []
    selectedProjectIds.value = []
  }
})
</script>

<template>
  <div class="system-role-page">
    <!-- 页面标题 + 操作按钮 -->
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">角色管理</h2>
        <div class="inline-stats">
          <span class="inline-stat">
            <span class="stat-num">{{ defaultRoleStats.total }}</span>
            <span class="stat-text">个默认角色</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ defaultRoleStats.management }}</span>
            <span class="stat-text">管理序列</span>
          </span>
          <span class="inline-stat">
            <span class="stat-num">{{ defaultRoleStats.execution }}</span>
            <span class="stat-text">执行序列</span>
          </span>
        </div>
      </div>
    </div>

    <div class="position-overview">
      <div
        v-for="group in rolePositionGroups"
        :key="group.value"
        class="position-sequence"
      >
        <div class="position-sequence-head">
          <span class="sequence-title">{{ group.label }}</span>
          <span class="sequence-count">{{ group.activeCount }}/{{ group.rows.length }} 已启用</span>
        </div>
        <div class="position-role-list">
          <div
            v-for="row in group.rows"
            :key="row.preset.value"
            class="position-role-card"
            :class="{ inactive: row.role?.status === 0, pending: !row.role }"
          >
            <div class="position-role-main">
              <div class="role-card-title">
                <span :class="['status-badge', getRoleBadgeClass(row.preset.value)]">{{ row.preset.label }}</span>
                <span class="role-code">{{ row.preset.value }}</span>
              </div>
              <p class="position-role-copy">{{ row.preset.description }}</p>
            </div>
            <div class="position-role-side">
              <span :class="['status-badge', row.role ? (row.role.status === 1 ? 'green' : 'gray') : 'yellow']">
                {{ row.role ? (row.role.status === 1 ? '启用' : '禁用') : '待初始化' }}
              </span>
              <div class="action-links role-card-actions">
                <template v-if="row.role">
                  <span class="action-link primary" @click="openAuthDialog(row.role)">配置授权</span>
                  <span class="action-link primary" @click="handleEdit(row.role)">维护信息</span>
                </template>
                <span v-else class="action-link primary" @click="handleInitializeDefaultRole(row.preset)">初始化</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="legacyRoles.length" class="legacy-role-panel table-card">
      <div class="legacy-role-head">
        <div>
          <h3>历史兼容角色</h3>
          <p>仅用于历史数据兼容，新建用户请使用上方默认岗位角色。</p>
        </div>
        <span class="sequence-count">{{ legacyRoles.length }} 个</span>
      </div>
      <el-table :data="legacyRoles" v-loading="loading" class="unified-table"
        :header-cell-style="{ background: 'var(--gray-50)', color: 'var(--gray-600)', fontWeight: 600, fontSize: '12px', borderBottom: '1px solid var(--gray-100)', padding: '10px 12px' }"
        :cell-style="{ fontSize: '13px', color: 'var(--gray-700)', padding: '10px 12px', borderBottom: '1px solid var(--gray-50)' }"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="code" label="角色编码" min-width="150" />
        <el-table-column prop="name" label="角色名称" min-width="150" />
        <el-table-column label="岗位序列" width="120">
          <template #default="{ row }">
            <span :class="['status-badge', getRoleBadgeClass(row.code)]">
              {{ getRoleCategoryLabel(row.code) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <span :class="['status-badge', row.status === 1 ? 'green' : 'gray']">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="action-links">
              <span class="action-link primary" @click="openAuthDialog(row)">配置授权</span>
              <span class="action-link primary" @click="handleEdit(row)">编辑</span>
              <span class="action-link danger" @click="handleDelete(row)">删除</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Role CRUD Dialog -->
    <el-dialog v-model="dialogVisible" title="维护默认角色" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="角色编码" prop="code">
          <el-input v-model="form.code" disabled placeholder="如: SOLUTION_MANAGER" />
        </el-form-item>
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" disabled placeholder="如: 解决方案经理" />
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
    <el-dialog v-model="authDialogVisible" :title="`配置授权 - ${currentRoleName}`" width="1000px" destroy-on-close>
      <div v-loading="authLoading" class="auth-layout">
        <div class="auth-columns">
          <div class="auth-menu-col auth-menu">
            <div class="menu-col-header">
              <span>菜单权限</span>
              <span class="menu-col-actions">
                <el-button link type="primary" size="small" aria-label="菜单全选" @click="checkAllMenus">全选</el-button>
                <el-button link size="small" aria-label="菜单清空" @click="clearAllMenus">清空</el-button>
              </span>
            </div>
            <el-tree ref="menuTreeRef" :data="menuTree" :props="{ label: 'name', children: 'children' }" show-checkbox node-key="id" :default-expanded-keys="allMenuIds" @node-click="handleNodeClick" @check="handleTreeCheck" :check-strictly="false" />
          </div>
          <div class="auth-right-col">
            <div class="panel-section auth-buttons">
              <div class="section-header">按钮权限</div>
              <template v-if="focusedMenuId">
                <div class="btn-header">
                  <span class="btn-menu-name button-menu-name">{{ menuTree.flatMap(menu => [menu, ...(menu.children || [])]).find(menu => menu.id === focusedMenuId)?.name ?? '' }}</span>
                  <span class="btn-count">{{ currentMenuButtons.length }} 个操作权限</span>
                </div>
                <div class="btn-list">
                  <div v-for="perm in currentMenuButtons" :key="perm.id" class="btn-item" :class="{ 'btn-checked': isButtonChecked(perm.id) }" @click="toggleButton(perm.id)">
                    <el-checkbox :model-value="isButtonChecked(perm.id)">
                      <span class="perm-name">{{ perm.name }}</span>
                      <span class="perm-code">{{ perm.code }}</span>
                    </el-checkbox>
                    <el-tag v-if="perm.type" size="small" :type="perm.type === 'button' ? 'warning' : perm.type === 'api' ? 'danger' : 'info'">{{ perm.type }}</el-tag>
                  </div>
                </div>
              </template>
              <div v-else class="empty-area"><p>请点击左侧菜单节点</p><p>查看该菜单下的操作权限</p></div>
            </div>
            <div class="panel-section section-divider">
              <div class="section-header">数据范围</div>
              <div class="ds-form">
                <el-radio-group v-model="dataScope" class="ds-radio-group">
                  <el-radio v-for="opt in DATA_SCOPE_OPTIONS" :key="opt.value" :label="opt.value">{{ opt.label }}</el-radio>
                </el-radio-group>
                <div v-if="dataScope === 'BU_LINE'" class="ds-value-row">
                  <span class="ds-label">业务线：</span>
                  <div class="scope-choice-grid">
                    <button
                      v-for="item in businessLines"
                      :key="item.id"
                      type="button"
                      class="scope-choice"
                      :class="{ active: selectedBusinessLineIds.includes(item.id) }"
                      @click="toggleBusinessLineScope(item.id)"
                    >
                      {{ item.name }}
                    </button>
                  </div>
                </div>
                <div v-else-if="dataScope === 'PROJECT'" class="ds-value-row">
                  <span class="ds-label">项目：</span>
                  <div class="scope-choice-grid">
                    <button
                      v-for="item in availableProjects"
                      :key="item.id"
                      type="button"
                      class="scope-choice"
                      :class="{ active: selectedProjectIds.includes(item.id) }"
                      @click="toggleProjectScope(item.id)"
                    >
                      {{ item.name }}
                    </button>
                  </div>
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
.system-role-page { width: 100%; min-width: 0; }
/* ========== 页面头部 ========== */
.content-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.title-with-stats { display: flex; align-items: center; gap: 16px; }
.page-title { font-size: 20px; font-weight: 600; color: var(--gray-800); margin: 0; }
.inline-stats { display: flex; align-items: center; gap: 8px; padding-left: 16px; border-left: 1px solid var(--gray-200); }
.inline-stat { display: flex; align-items: center; gap: 4px; }
.stat-num { font-size: 16px; font-weight: 700; color: var(--gray-800); }
.stat-text { font-size: 13px; color: var(--gray-500); }

/* ========== 按钮 ========== */
.btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border-radius: var(--radius-md); font-size: 14px; font-weight: 500; cursor: pointer; border: none; transition: all 0.15s ease; }
.btn svg { width: 16px; height: 16px; }
.btn-primary { background: var(--primary); color: white; }
.btn-primary:hover { background: var(--primary-dark); }

/* ========== 岗位概览 ========== */
.position-overview { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.position-sequence { min-width: 0; background: #fff; border: 1px solid var(--gray-100); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 14px; }
.position-sequence-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.sequence-title { font-size: 14px; font-weight: 700; color: var(--gray-800); }
.sequence-count { font-size: 12px; color: var(--gray-500); }
.position-role-list { min-width: 0; display: grid; grid-template-columns: 1fr; gap: 8px; }
.position-role-card { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 14px; padding: 12px; border: 1px solid var(--gray-100); border-radius: var(--radius-sm); background: #fff; }
.position-role-card.inactive { background: var(--gray-50); }
.position-role-card.pending { border-style: dashed; background: var(--gray-50); }
.position-role-main { min-width: 0; }
.role-card-title { display: flex; min-width: 0; align-items: center; gap: 8px; margin-bottom: 6px; }
.role-code { min-width: 0; flex: 1; overflow: hidden; color: var(--gray-400); font-size: 11px; font-family: monospace; text-overflow: ellipsis; white-space: nowrap; }
.position-role-copy { min-width: 0; color: var(--gray-500); font-size: 12px; line-height: 1.45; margin: 0; }
.position-role-side { display: flex; align-items: center; gap: 12px; }
.role-card-actions { min-width: 120px; justify-content: flex-end; }

/* ========== 表格 ========== */
.table-card { background: #FFFFFF; border-radius: var(--radius-md); overflow: auto; box-shadow: var(--shadow-sm); max-height: calc(100vh - 240px); }
.legacy-role-panel { margin-top: 16px; }
.legacy-role-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 16px; border-bottom: 1px solid var(--gray-100); }
.legacy-role-head h3 { margin: 0 0 4px; font-size: 14px; color: var(--gray-800); }
.legacy-role-head p { margin: 0; color: var(--gray-500); font-size: 12px; }
.unified-table :deep(.el-table__header-wrapper th) { background: var(--gray-50) !important; font-size: 12px !important; font-weight: 600 !important; color: var(--gray-600) !important; border-bottom: 1px solid var(--gray-100) !important; }
.unified-table :deep(.el-table__body-wrapper td) { font-size: 13px !important; color: var(--gray-700) !important; border-bottom: 1px solid var(--gray-50) !important; }
.unified-table :deep(.el-table__body-wrapper tr:hover > td) { background: var(--gray-50) !important; }
.unified-table :deep(.el-table__border-left-patch), .unified-table :deep(.el-table__inner-wrapper::before) { display: none !important; }

/* ========== 状态标签 ========== */
.status-badge { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 11px; font-weight: 500; white-space: nowrap; }
.status-badge.gray { background: var(--gray-100); color: var(--gray-600); }
.status-badge.green { background: #d1fae5; color: #047857; }
.status-badge.blue { background: #dbeafe; color: #1d4ed8; }
.status-badge.yellow { background: #fef3c7; color: #b45309; }
.status-badge.red { background: #fee2e2; color: #dc2626; }
.status-badge.purple { background: #e9d5ff; color: #7c3aed; }

/* ========== 操作链接 ========== */
.action-links { display: flex; align-items: center; gap: 12px; }
.action-link { font-size: 13px; font-weight: 500; cursor: pointer; transition: opacity 0.15s ease; }
.action-link:hover { opacity: 0.8; }
.action-link.primary { color: var(--primary); }
.action-link.danger { color: var(--danger); }

/* ========== 授权弹窗 ========== */
.auth-layout { min-height: 520px; }
.auth-columns { display: flex; gap: 16px; min-height: 520px; }
.menu-col-header { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 600; color: var(--gray-700); margin-bottom: 8px; padding-left: 6px; }
.auth-menu-col { flex: 0 0 340px; overflow-y: auto; border: 1px solid var(--gray-200); border-radius: 8px; padding: 14px; }
.auth-right-col { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 0; min-height: 520px; }
.panel-section { flex-shrink: 0; padding: 12px 16px; }
.section-divider { border-top: 1px solid var(--gray-200); }
.section-header { font-size: 13px; font-weight: 600; color: var(--gray-600); margin-bottom: 10px; }
.btn-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.btn-menu-name { font-size: 14px; font-weight: 600; color: var(--gray-800); }
.btn-count { font-size: 12px; color: var(--gray-400); }
.btn-list { overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 8px; }
.btn-item { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-radius: 10px; cursor: pointer; transition: background 0.15s, border-color 0.15s; border: 1px solid transparent; }
.btn-item:hover { background: var(--gray-50); }
.btn-checked { background: var(--gray-50); border-color: rgba(79, 70, 229, 0.18); }
.btn-item :deep(.el-checkbox) { width: 100%; pointer-events: none; }
.btn-item :deep(.el-checkbox__input) { pointer-events: none; }
.btn-item :deep(.el-checkbox__label) { width: 100%; display: flex; align-items: center; gap: 8px; }
.perm-name { font-size: 13px; color: var(--gray-800); margin-right: 8px; }
.perm-code { font-size: 11px; color: var(--gray-400); font-family: monospace; }
.empty-area { text-align: center; color: var(--gray-400); font-size: 13px; margin-top: 60px; }
.empty-area p { margin: 4px 0; }
.ds-form { padding: 4px 0; }
.ds-radio-group { display: flex; flex-direction: row; flex-wrap: wrap; gap: 10px 18px; }
:deep(.ds-radio-group .el-radio) { margin-right: 6px; margin-bottom: 0; }
.ds-value-row { margin-top: 12px; display: flex; align-items: flex-start; gap: 10px; }
.ds-label { font-size: 13px; color: var(--gray-600); white-space: nowrap; }
.scope-choice-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.scope-choice { border: 1px solid var(--gray-200); border-radius: 999px; background: #fff; color: var(--gray-600); padding: 7px 12px; font-size: 12px; cursor: pointer; transition: all 0.15s ease; }
.scope-choice:hover { border-color: var(--primary); color: var(--primary); }
.scope-choice.active { background: var(--primary); border-color: var(--primary); color: #fff; }

@media (max-width: 768px) {
  .position-overview { grid-template-columns: minmax(0, 1fr); }
  .position-role-card { grid-template-columns: 1fr; }
  .position-role-side { justify-content: space-between; }
  .auth-columns { flex-direction: column; height: auto; }
  .auth-menu-col { flex: none; max-height: 280px; }
  .auth-right-col { max-height: 320px; }
}
</style>
