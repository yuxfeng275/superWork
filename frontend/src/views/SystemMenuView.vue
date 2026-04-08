<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/utils/api'
import { ElMessage } from 'element-plus'

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
  createdAt?: string
  createTime?: string
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

const menus = ref<Menu[]>([])
const permissions = ref<Permission[]>([])
const loading = ref(false)

const loadMenus = async () => {
  loading.value = true
  try {
    const data = await api.getMenus()
    menus.value = Array.isArray(data)
      ? data.map((menu: any) => ({
          ...menu,
          parentId: menu.parentId ?? 0,
          createdAt: menu.createdAt || menu.createTime || ''
        }))
      : []
  } catch (error) {
    console.error('加载菜单失败:', error)
    ElMessage.error('加载菜单失败')
  } finally {
    loading.value = false
  }
}

const loadPermissions = async () => {
  try {
    const data = await api.getPermissions()
    permissions.value = Array.isArray(data) ? data : []
  } catch (error) {
    console.error('加载权限失败:', error)
    ElMessage.error('加载权限失败')
  }
}

const menuTree = computed(() => {
  const nodeMap = new Map<number, Menu & { children: Menu[] }>()
  const roots: Array<Menu & { children: Menu[] }> = []

  menus.value.forEach(menu => {
    nodeMap.set(menu.id, {
      ...menu,
      children: []
    })
  })

  nodeMap.forEach(node => {
    const parentId = node.parentId ?? 0
    const parent = parentId > 0 ? nodeMap.get(parentId) : undefined
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  })

  const sortNodes = (nodes: Array<Menu & { children: Menu[] }>) => {
    nodes.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    nodes.forEach(node => sortNodes(node.children as Array<Menu & { children: Menu[] }>))
  }

  sortNodes(roots)
  return roots
})

const menuPermissionCount = computed(() => {
  const countMap = new Map<number, number>()
  permissions.value.forEach(permission => {
    if (permission.menuId) {
      countMap.set(permission.menuId, (countMap.get(permission.menuId) || 0) + 1)
    }
  })
  return countMap
})

const totalMenus = computed(() => menus.value.length)
const visibleMenus = computed(() => menus.value.filter(menu => (menu.visible ?? 1) === 1).length)

const getStatusLabel = (status?: number): string => (status === 1 ? '启用' : '禁用')
const getStatusTagType = (status?: number): string => (status === 1 ? 'success' : 'info')
const getVisibleLabel = (visible?: number): string => (visible === 1 ? '显示' : '隐藏')
const getVisibleTagType = (visible?: number): string => (visible === 1 ? 'primary' : 'info')

const refresh = async () => {
  await Promise.all([loadMenus(), loadPermissions()])
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <div class="system-menu-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">菜单管理</h2>
        <p class="page-subtitle">基于后端真实字段构建树形菜单视图，并聚合展示菜单权限信息。</p>
      </div>
      <el-button @click="refresh">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-label">菜单总数</div>
        <div class="stat-value">{{ totalMenus }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已显示菜单</div>
        <div class="stat-value">{{ visibleMenus }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">权限总数</div>
        <div class="stat-value">{{ permissions.length }}</div>
      </div>
    </div>

    <div class="card">
      <el-table
        :data="menuTree"
        v-loading="loading"
        stripe
        row-key="id"
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="name" label="菜单名称" min-width="180">
          <template #default="{ row }">
            <div class="menu-name">
              <span class="menu-icon">{{ row.icon || '-' }}</span>
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由路径" min-width="180">
          <template #default="{ row }">
            <code v-if="row.path" class="code-pill">{{ row.path }}</code>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="component" label="组件" min-width="160">
          <template #default="{ row }">
            <span v-if="row.component">{{ row.component }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="visible" label="显示" width="90">
          <template #default="{ row }">
            <el-tag :type="getVisibleTagType(row.visible)" size="small">
              {{ getVisibleLabel(row.visible) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="90" />
        <el-table-column label="权限数" width="100">
          <template #default="{ row }">
            {{ menuPermissionCount.get(row.id) || 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="180">
          <template #default="{ row }">
            {{ row.createdAt || row.createTime || '-' }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card permissions-card">
      <div class="section-title">权限聚合</div>
      <div v-if="permissions.length" class="permission-grid">
        <div v-for="permission in permissions" :key="permission.id" class="permission-item">
          <div class="permission-name">{{ permission.name }}</div>
          <div class="permission-code">{{ permission.code }}</div>
          <div class="permission-meta">
            <span>{{ permission.type || '-' }}</span>
            <span>菜单 {{ permission.menuId ?? '-' }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty-state">
        当前没有可展示的权限数据。
      </div>
    </div>
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

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card,
.card {
  background: white;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.stat-card {
  padding: 16px 20px;
}

.stat-label {
  color: var(--gray-500);
  font-size: 13px;
}

.stat-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 700;
  color: var(--gray-800);
}

.card {
  padding: 20px;
}

.menu-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.menu-icon {
  font-size: 12px;
  color: var(--gray-500);
  background: var(--gray-100);
  border-radius: 999px;
  padding: 2px 8px;
}

.code-pill,
.permission-code {
  font-size: 12px;
  background: var(--gray-100);
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--gray-700);
}

.muted {
  color: var(--gray-400);
}

.permissions-card {
  margin-top: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--gray-800);
  margin-bottom: 16px;
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.permission-item {
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  padding: 12px;
  background: linear-gradient(180deg, #fff, #fafafa);
}

.permission-name {
  font-weight: 600;
  color: var(--gray-800);
}

.permission-meta {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  color: var(--gray-500);
  font-size: 12px;
}

.empty-state {
  color: var(--gray-500);
  font-size: 13px;
}

@media (max-width: 960px) {
  .stats-row {
    grid-template-columns: 1fr;
  }
}
</style>
