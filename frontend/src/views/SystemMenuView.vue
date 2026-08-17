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

const totalMenus = computed(() => menus.value.length)
const visibleMenus = computed(() => menus.value.filter(menu => (menu.visible ?? 1) === 1).length)

const refresh = async () => {
  await Promise.all([loadMenus(), loadPermissions()])
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <div class="system-menu-page">
    <!-- 页面标题 -->
    <div class="content-header">
      <div class="title-with-stats">
        <h2 class="page-title">菜单管理</h2>
        <div class="inline-stats">
          <span class="stat-chip">
            <span class="stat-dot blue"></span>
            <span class="stat-num">{{ totalMenus }}</span>
            <span class="stat-text">个菜单</span>
          </span>
          <span class="stat-chip">
            <span class="stat-dot green"></span>
            <span class="stat-num">{{ visibleMenus }}</span>
            <span class="stat-text">已显示</span>
          </span>
          <span class="stat-chip">
            <span class="stat-dot purple"></span>
            <span class="stat-num">{{ permissions.length }}</span>
            <span class="stat-text">个权限</span>
          </span>
        </div>
      </div>
      <button class="btn btn-default" @click="refresh">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="1 4 1 10 7 10"/>
          <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
        </svg>
        刷新
      </button>
    </div>

    <!-- 菜单树卡片 -->
    <div v-loading="loading" class="menu-tree-wrapper">
      <el-empty v-if="!loading && menuTree.length === 0" description="暂无菜单数据" style="padding: 60px 0" />
      <div v-for="menu in menuTree" :key="menu.id" class="menu-root-item">
        <!-- 顶级菜单 -->
        <div class="menu-row root">
          <div class="menu-row-left">
            <div class="menu-icon-box root-icon">
              <svg v-if="!menu.icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
              <span v-else class="icon-text">{{ menu.icon }}</span>
            </div>
            <div class="menu-info">
              <span class="menu-name-text">{{ menu.name }}</span>
              <code v-if="menu.path" class="route-tag">{{ menu.path }}</code>
            </div>
          </div>
          <div class="menu-row-right">
            <span :class="['badge', (menu.visible ?? 1) === 1 ? 'badge-blue' : 'badge-gray']">
              {{ (menu.visible ?? 1) === 1 ? '显示' : '隐藏' }}
            </span>
            <span :class="['badge', (menu.status ?? 1) === 1 ? 'badge-green' : 'badge-gray']">
              {{ (menu.status ?? 1) === 1 ? '启用' : '禁用' }}
            </span>
            <span class="sort-num">{{ menu.sortOrder ?? 0 }}</span>
            <div class="perm-tags">
              <span
                v-for="perm in permissions.filter(p => p.menuId === menu.id)"
                :key="perm.id"
                class="perm-tag"
                :title="perm.description"
              >{{ perm.name }}</span>
              <span v-if="!permissions.filter(p => p.menuId === menu.id).length" class="no-perm">—</span>
            </div>
          </div>
        </div>

        <!-- 子菜单 -->
        <div v-if="menu.children && menu.children.length" class="menu-children">
          <div v-for="child in menu.children" :key="child.id" class="menu-row child">
            <div class="menu-row-left">
              <div class="child-indent">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
              </div>
              <div class="menu-icon-box child-icon">
                <svg v-if="!child.icon" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/></svg>
                <span v-else class="icon-text">{{ child.icon }}</span>
              </div>
              <div class="menu-info">
                <span class="menu-name-text child-name">{{ child.name }}</span>
                <code v-if="child.path" class="route-tag">{{ child.path }}</code>
              </div>
            </div>
            <div class="menu-row-right">
              <span :class="['badge', (child.visible ?? 1) === 1 ? 'badge-blue' : 'badge-gray']">
                {{ (child.visible ?? 1) === 1 ? '显示' : '隐藏' }}
              </span>
              <span :class="['badge', (child.status ?? 1) === 1 ? 'badge-green' : 'badge-gray']">
                {{ (child.status ?? 1) === 1 ? '启用' : '禁用' }}
              </span>
              <span class="sort-num">{{ child.sortOrder ?? 0 }}</span>
              <div class="perm-tags">
                <span
                  v-for="perm in permissions.filter(p => p.menuId === child.id)"
                  :key="perm.id"
                  class="perm-tag"
                  :title="perm.description"
                >{{ perm.name }}</span>
                <span v-if="!permissions.filter(p => p.menuId === child.id).length" class="no-perm">—</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 未关联菜单的权限 -->
    <div v-if="permissions.filter(p => !p.menuId).length" class="orphan-perms-card">
      <div class="orphan-title">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
        未绑定菜单的权限
      </div>
      <div class="orphan-perm-list">
        <span v-for="perm in permissions.filter(p => !p.menuId)" :key="perm.id" class="orphan-perm-tag">
          <span class="orphan-name">{{ perm.name }}</span>
          <code class="orphan-code">{{ perm.code }}</code>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.system-menu-page { width: 100%; min-width: 0; }
/* ========== 页面头部 ========== */
.content-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.title-with-stats { display: flex; align-items: center; gap: 16px; }
.page-title { font-size: 20px; font-weight: 600; color: var(--gray-800); margin: 0; }
.inline-stats { display: flex; align-items: center; gap: 8px; padding-left: 16px; border-left: 1px solid var(--gray-200); }
.stat-chip { display: flex; align-items: center; gap: 5px; background: var(--gray-50); border: 1px solid var(--gray-100); border-radius: 20px; padding: 4px 10px; }
.stat-dot { width: 7px; height: 7px; border-radius: 50%; }
.stat-dot.blue { background: #3b82f6; }
.stat-dot.green { background: #10b981; }
.stat-dot.purple { background: #8b5cf6; }
.stat-num { font-size: 14px; font-weight: 700; color: var(--gray-800); }
.stat-text { font-size: 12px; color: var(--gray-500); }

/* ========== 按钮 ========== */
.btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border-radius: var(--radius-md); font-size: 14px; font-weight: 500; cursor: pointer; border: none; transition: all 0.15s ease; }
.btn svg { width: 16px; height: 16px; }
.btn-default { background: white; color: var(--gray-700); border: 1px solid var(--gray-200); }
.btn-default:hover { border-color: var(--primary); color: var(--primary); }

/* ========== 菜单树 ========== */
.menu-tree-wrapper { display: flex; flex-direction: column; gap: 10px; }

.menu-root-item { background: #fff; border-radius: 12px; box-shadow: var(--shadow-sm); border: 1px solid var(--gray-100); overflow: hidden; }

.menu-row { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; gap: 12px; }
.menu-row.root { background: linear-gradient(90deg, #f8fafc, #fff); border-bottom: 1px solid var(--gray-100); }
.menu-row.child { border-bottom: 1px solid var(--gray-50); }
.menu-row.child:last-child { border-bottom: none; }
.menu-row.child:hover { background: var(--gray-50); }

.menu-row-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.menu-row-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

.menu-icon-box { display: flex; align-items: center; justify-content: center; border-radius: 8px; flex-shrink: 0; }
.root-icon { width: 32px; height: 32px; background: linear-gradient(135deg, #3b82f6, #6366f1); color: white; }
.child-icon { width: 24px; height: 24px; background: var(--gray-100); color: var(--gray-500); }
.icon-text { font-size: 13px; }

.child-indent { color: var(--gray-300); flex-shrink: 0; margin-left: 8px; }

.menu-info { display: flex; align-items: center; gap: 8px; min-width: 0; }
.menu-name-text { font-size: 14px; font-weight: 600; color: var(--gray-800); white-space: nowrap; }
.child-name { font-size: 13px; font-weight: 500; color: var(--gray-700); }
.route-tag { font-size: 11px; background: var(--gray-100); color: #4f46e5; padding: 2px 8px; border-radius: 6px; font-family: monospace; white-space: nowrap; }

.badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 500; white-space: nowrap; }
.badge-green { background: #d1fae5; color: #047857; }
.badge-blue { background: #dbeafe; color: #1d4ed8; }
.badge-gray { background: var(--gray-100); color: var(--gray-500); }

.sort-num { font-size: 12px; color: var(--gray-400); width: 24px; text-align: center; }

.perm-tags { display: flex; flex-wrap: wrap; gap: 4px; max-width: 280px; }
.perm-tag { display: inline-block; background: #ede9fe; color: #5b21b6; font-size: 11px; padding: 2px 8px; border-radius: 6px; white-space: nowrap; cursor: default; }
.perm-tag:hover { background: #ddd6fe; }
.no-perm { color: var(--gray-300); font-size: 12px; }

/* ========== 未绑定权限 ========== */
.orphan-perms-card { margin-top: 16px; background: #fffbeb; border: 1px solid #fde68a; border-radius: 12px; padding: 16px 20px; }
.orphan-title { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: #92400e; margin-bottom: 12px; }
.orphan-title svg { color: #f59e0b; flex-shrink: 0; }
.orphan-perm-list { display: flex; flex-wrap: wrap; gap: 8px; }
.orphan-perm-tag { display: inline-flex; align-items: center; gap: 6px; background: white; border: 1px solid #fde68a; border-radius: 8px; padding: 4px 10px; }
.orphan-name { font-size: 13px; font-weight: 500; color: var(--gray-700); }
.orphan-code { min-width: 0; overflow: hidden; font-size: 11px; background: #fef3c7; color: #92400e; padding: 1px 6px; border-radius: 4px; font-family: monospace; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 768px) {
  .content-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .title-with-stats {
    width: 100%;
    min-width: 0;
    flex-wrap: wrap;
    gap: 10px;
  }

  .inline-stats {
    max-width: 100%;
    flex-wrap: wrap;
    padding-left: 0;
    border-left: 0;
  }

  .menu-row {
    align-items: stretch;
    flex-direction: column;
  }

  .menu-info,
  .menu-row-right {
    flex-wrap: wrap;
  }

  .menu-row-right,
  .perm-tags {
    max-width: 100%;
  }

  .route-tag {
    min-width: 0;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .orphan-perm-tag {
    min-width: 0;
    max-width: 100%;
  }
}
</style>
