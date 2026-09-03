<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { api } from '@/utils/api'
import { getRoleLabel, hasRoleAccess, type RoleAccess } from '@/constants/roles'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const isCollapsed = ref(false)
const requirementBadge = ref<number | null>(null)

interface NavItem {
  path: string
  icon: string
  label: string
  badge?: number
  access?: RoleAccess
  requiresKeyMatterAccess?: boolean
}

interface NavSection {
  section: string
  items: NavItem[]
}

const navItems: NavSection[] = [
  {
    section: '工作台',
    items: [
      { path: '/', icon: 'HomeFilled', label: '首页' },
      { path: '/requirements', icon: 'Document', label: '需求管理' },
      { path: '/tasks', icon: 'Finished', label: '任务管理' },
      { path: '/defects', icon: 'CircleCloseFilled', label: '缺陷管理' },
      { path: '/emails', icon: 'Message', label: '邮件管理' },
      { path: '/ai-assistant', icon: 'ChatDotRound', label: 'AI 助手' },
      {
        path: '/key-matters',
        icon: 'Flag',
        label: '大事儿管理',
        requiresKeyMatterAccess: true
      }
    ]
  },
  {
    section: '基础分类',
    items: [
      { path: '/business-lines', icon: 'Collection', label: '业务线管理' },
      { path: '/projects', icon: 'Folder', label: '项目管理', access: 'project' },
      { path: '/customers', icon: 'UserFilled', label: '客户信息管理', access: 'customer' }
    ]
  },
  {
    section: '销售管理',
    items: [
      { path: '/opportunities', icon: 'Connection', label: '线索商机管理' }
    ]
  },
  {
    section: '数据分析',
    items: [
      { path: '/statistics', icon: 'DataAnalysis', label: 'BU驾驶舱', access: 'management' },
      { path: '/revenue', icon: 'Coin', label: '营收管理', access: 'management' }
    ]
  },
  {
    section: '系统',
    items: [
      { path: '/system/users', icon: 'User', label: '用户管理', access: 'management' },
      { path: '/system/roles', icon: 'Lock', label: '角色管理', access: 'management' },
      { path: '/system/menus', icon: 'Menu', label: '菜单管理', access: 'management' },
      { path: '/system/workflow', icon: 'Connection', label: '工作流配置', access: 'management' },
      { path: '/system/configs', icon: 'Setting', label: '配置管理', access: 'management' }
    ]
  }
]
// 菜单授权：角色管理配置的菜单权限从后端读取；角色无任何授权时回退岗位默认
const menuAuth = ref<{ allowed: Set<string>; managed: Set<string> } | null>(null)

const loadMenuAuth = async () => {
  try {
    const payload = await api.getMyMenus()
    const paths = Array.isArray(payload?.paths) ? payload.paths : []
    if (paths.length === 0) return   // 无授权记录 → 保持岗位默认，避免锁死
    menuAuth.value = {
      allowed: new Set(paths),
      managed: new Set(Array.isArray(payload?.managedPaths) ? payload.managedPaths : [])
    }
  } catch {
    menuAuth.value = null
  }
}

const menuPathAlias = (path: string) => path === '/' ? '/home' : path

const menuAuthorized = (path: string) => {
  if (!menuAuth.value) return true
  const alias = menuPathAlias(path)
  if (!menuAuth.value.managed.has(alias)) return true   // 未纳管菜单（如营收管理）不受授权影响
  return menuAuth.value.allowed.has(alias)
}


const visibleNavItems = computed(() =>
  navItems
    .map(section => ({
      ...section,
      items: section.items.filter(item =>
        (!item.access || hasRoleAccess(authStore.user?.role, item.access))
        && menuAuthorized(item.path)
        && (!item.requiresKeyMatterAccess || authStore.keyMatterAccess?.canAccess === true)
      )
    }))
    .filter(section => section.items.length > 0)
)

const isActive = (path: string) => {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}

const loadRequirementBadge = async () => {
  try {
    const payload = await api.getRequirements({ page: 1, size: 1 })
    const total = payload?.total ?? payload?.data?.total
    if (typeof total === 'number') {
      requirementBadge.value = total
      return
    }

    const records = Array.isArray(payload?.records)
      ? payload.records
      : Array.isArray(payload?.data?.records)
        ? payload.data.records
        : Array.isArray(payload)
          ? payload
          : []
    requirementBadge.value = records.length
  } catch {
    requirementBadge.value = null
  }
}

onMounted(() => {
  void Promise.allSettled([
    loadRequirementBadge(),
    authStore.loadKeyMatterAccess(),
    loadMenuAuth()
  ])
})
</script>

<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: isCollapsed }">
      <!-- Logo -->
      <div class="sidebar-header">
        <a href="/" class="sidebar-logo">
          <div class="sidebar-logo-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="8" y="2" width="8" height="4" rx="1" />
              <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
              <path d="m9 12 2 2 4-4" />
            </svg>
          </div>
          <span class="sidebar-logo-text">BU管理系统</span>
        </a>
        <button class="sidebar-toggle" type="button" :aria-label="isCollapsed ? '展开侧边栏' : '收起侧边栏'" @click="isCollapsed = !isCollapsed">
          <el-icon v-if="isCollapsed"><Expand /></el-icon>
          <el-icon v-else><Fold /></el-icon>
        </button>
      </div>

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <div v-for="section in visibleNavItems" :key="section.section" class="nav-section">
          <div class="nav-section-title">{{ section.section }}</div>
          <router-link
            v-for="item in section.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            :aria-label="item.label"
            :title="item.label"
          >
            <span class="nav-item-icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="nav-item-text">{{ item.label }}</span>
            <span v-if="item.path === '/requirements' ? requirementBadge !== null : item.badge" class="nav-item-badge">
              {{ item.path === '/requirements' ? requirementBadge : item.badge }}
            </span>
          </router-link>
        </div>
      </nav>

    </aside>

    <!-- 主内容区 -->
    <main class="main">
      <!-- 顶部导航 -->
      <header class="top-header">
        <div class="header-left">
          <h1 class="header-title">{{ route.meta.title || '页面标题' }}</h1>
        </div>
        <div class="header-right">
          <button class="header-action">
            <el-icon><Bell /></el-icon>
            <span class="badge"></span>
          </button>
          <div class="header-user">
            <div class="user-avatar sm">{{ authStore.user?.realName?.charAt(0) || '用户' }}</div>
            <div class="header-user-info">
              <div class="user-name">{{ authStore.user?.realName || '未登录' }}</div>
              <div class="user-role">{{ getRoleLabel(authStore.user?.role) }}</div>
            </div>
            <button class="header-logout" type="button" aria-label="退出登录" @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              <span>退出</span>
            </button>
          </div>
        </div>
      </header>

      <!-- 内容区域 -->
      <div class="content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

/* 侧边栏 */
.sidebar {
  width: var(--sidebar-width);
  flex: 0 0 var(--sidebar-width);
  background: #fff;
  border-right: 1px solid var(--gray-200);
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
}

.sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
  flex-basis: var(--sidebar-collapsed-width);
}

.sidebar.collapsed .sidebar-logo-text,
.sidebar.collapsed .user-info,
.sidebar.collapsed .nav-section-title,
.sidebar.collapsed .nav-item-text,
.sidebar.collapsed .nav-item-badge,
.sidebar.collapsed .logout-btn span {
  display: none;
}

.sidebar-header {
  height: 64px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--gray-200);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  text-decoration: none;
  color: var(--gray-800);
}

.sidebar-logo-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sidebar-logo-icon svg {
  width: 18px;
  height: 18px;
}

.sidebar-logo-text {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.sidebar-toggle {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--gray-500);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sidebar-toggle:hover {
  background: var(--gray-100);
  color: var(--gray-700);
}

/* 用户信息 */
.sidebar-user {
  padding: 16px 20px;
  border-bottom: 1px solid var(--gray-200);
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-avatar.sm {
  width: 32px;
  height: 32px;
  font-size: 12px;
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
}

.user-role {
  font-size: 12px;
  color: var(--gray-500);
}

/* 导航菜单 */
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.nav-section {
  margin-bottom: 8px;
}

.nav-section-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--gray-400);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 8px 12px 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  color: var(--gray-600);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.nav-item:hover {
  background: var(--gray-100);
  color: var(--gray-800);
}

.nav-item.active {
  background: var(--primary-light);
  color: var(--primary);
}

.nav-item-icon {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-item-text {
  flex: 1;
  white-space: nowrap;
}

.nav-item-badge {
  background: var(--danger);
  color: white;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 10px;
  min-width: 18px;
  text-align: center;
}

/* 退出登录 */
.sidebar-footer {
  padding: 12px;
  border-top: 1px solid var(--gray-200);
}

.logout-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: none;
  background: transparent;
  color: var(--gray-500);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.logout-btn:hover {
  background: #FEE2E2;
  color: var(--danger);
}

/* 主内容区 */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--gray-50);
}

.top-header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid var(--gray-200);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--gray-800);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 12px;
  border-left: 1px solid var(--gray-200);
}

.header-user-info {
  min-width: 72px;
}

.header-user-info .user-name,
.header-user-info .user-role {
  white-space: nowrap;
}

.header-logout {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-sm);
  color: var(--gray-500);
  background: #fff;
  cursor: pointer;
  transition: all 0.15s ease;
}

.header-logout:hover {
  color: var(--danger);
  border-color: #fecaca;
  background: #fef2f2;
}

.header-action {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  border: none;
  background: transparent;
  color: var(--gray-500);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

.header-action:hover {
  background: var(--gray-100);
  color: var(--gray-700);
}

.header-action .badge {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 8px;
  height: 8px;
  background: var(--danger);
  border-radius: 50%;
}

.content {
  flex: 1;
  min-width: 0;
  padding: 24px;
  overflow: auto;
}

/* 大事儿周会演示：脱离后台壳层，恢复时由页面状态移除该标记。 */
:global(body.key-matters-presentation) {
  overflow: hidden;
}

:global(body.key-matters-presentation .layout) {
  height: 100dvh;
}

:global(body.key-matters-presentation .sidebar),
:global(body.key-matters-presentation .top-header) {
  display: none;
}

:global(body.key-matters-presentation .main) {
  width: 100%;
  min-width: 0;
}

:global(body.key-matters-presentation .content) {
  height: 100dvh;
  padding: 0;
  overflow: hidden;
}

:global(body.key-matters-presentation .key-matters-page) {
  height: 100dvh;
  overflow: hidden;
}

:global(body.key-matters-presentation .key-matters-page > .page-toolbar) {
  display: none;
}

@media (max-width: 1024px) {
  .sidebar {
    width: var(--sidebar-collapsed-width);
    min-width: var(--sidebar-collapsed-width);
    max-width: var(--sidebar-collapsed-width);
    flex-basis: var(--sidebar-collapsed-width);
  }

  .sidebar .sidebar-logo-text,
  .sidebar .nav-section-title,
  .sidebar .nav-item-text,
  .sidebar .nav-item-badge {
    display: none;
  }

  .sidebar-header {
    padding: 0 18px;
    justify-content: center;
  }

  .sidebar-toggle {
    display: none;
  }

  .sidebar-nav {
    padding: 8px;
  }

  .nav-item {
    justify-content: center;
  }
}

@media (max-width: 720px) {
  .top-header {
    padding: 0 12px;
  }

  .header-user-info {
    display: none;
  }

  .header-user {
    padding-left: 8px;
  }

  .header-logout span {
    display: none;
  }

  .content {
    padding: 12px;
  }
}
</style>
