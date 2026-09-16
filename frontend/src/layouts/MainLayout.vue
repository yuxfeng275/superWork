<script setup lang="ts">
import { computed, getCurrentInstance, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { api } from '@/utils/api'
import type { AiNotice } from '@/types/ai-agent'
import { getRoleLabel, hasRoleAccess, type RoleAccess } from '@/constants/roles'
import type { MenuTreeNode } from '@/types/kpi'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const app = getCurrentInstance()?.appContext.app

const isCollapsed = ref(false)
const requirementBadge = ref<number | null>(null)

/** 站内通知 */
const notices = ref<AiNotice[]>([])
const unreadCount = ref(0)
const noticeLoading = ref(false)
const noticePopoverVisible = ref(false)
let unreadTimer: number | null = null

const loadNotices = async () => {
  noticeLoading.value = true
  try {
    notices.value = await api.getAiNotices()
  } catch {
    notices.value = []
  } finally {
    noticeLoading.value = false
  }
}

const loadUnreadCount = async () => {
  try {
    unreadCount.value = (await api.getAiNoticeUnreadCount()).count ?? 0
  } catch {
    unreadCount.value = 0
  }
}

const onNoticePopoverShow = () => {
  void loadNotices()
}

/** 去处理：标记已读 → 跳转（OA 待办深链带 prefill）→ 刷新未读数 */
const handleNoticeAction = async (notice: AiNotice) => {
  try {
    await api.markAiNoticeRead(notice.kind, notice.date)
  } catch {
    // 已读失败不阻断跳转
  }
  noticePopoverVisible.value = false
  if (notice.link) {
    const prefillByKind: Record<string, string> = {
      WORKLOG_MISSING: '帮我分析一下我最近几个月的工时填报情况',
      WORKTIME_MONTH_MISSING: '帮我分析一下我最近几个月的工时填报情况'
    }
    const query = notice.link === '/ai-assistant'
      ? { prefill: prefillByKind[notice.kind] || '我的OA待办事项' } : {}
    void router.push({ path: notice.link, query })
  }
  void loadUnreadCount()
}

onBeforeUnmount(() => {
  if (unreadTimer !== null) window.clearInterval(unreadTimer)
})

interface NavItem {
  key?: string
  path: string
  icon: string
  label: string
  badge?: number
  access?: RoleAccess
  requiresKeyMatterAccess?: boolean
  children?: NavItem[]
}

interface NavSection {
  section: string
  icon?: string
  items: NavItem[]
}

/** 内置默认菜单：仅在用户无任何菜单授权记录时回退（防止角色未配置锁死） */
const defaultNavItems: NavSection[] = [
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
    section: '基础数据',
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
      { path: '/revenue/worktime', icon: 'Timer', label: '工时 & 成本', access: 'management' },
      { path: '/revenue/delivery', icon: 'TrendCharts', label: '交付与利润', access: 'management' },
      { path: '/kpi-report', icon: 'DataLine', label: 'KPI周报', access: 'management' },
      { path: '/bl-profit', icon: 'DataAnalysis', label: '业务线利润', access: 'management' },
      {
        path: '',
        icon: 'Setting',
        label: '配置',
        access: 'management',
        children: [
          { path: '/revenue/import', icon: 'Upload', label: '数据导入', access: 'management' },
          { path: '/revenue/pending', icon: 'Link', label: '待映射与销售项目', access: 'management' }
        ]
      }
    ]
  },
  {
    section: '系统',
    items: [
      { path: '/system/users', icon: 'User', label: '用户管理', access: 'management' },
      { path: '/system/roles', icon: 'Lock', label: '角色管理', access: 'management' },
      { path: '/system/menus', icon: 'Menu', label: '菜单管理', access: 'management' },
      { path: '/system/workflow', icon: 'Connection', label: '工作流配置', access: 'management' },
      { path: '/system/configs', icon: 'Setting', label: '配置管理', access: 'management' },
      { path: '/system/connectors', icon: 'Connection', label: '连接器管理', access: 'management' }
    ]
  }
]
// 菜单授权：角色管理配置的菜单权限从后端读取；角色无任何授权时回退岗位默认
const menuAuth = ref<{ allowed: Set<string>; managed: Set<string> } | null>(null)

/** 动态菜单树（V57 起）：非空则完全以后端授权为准渲染侧边栏 */
const menuTree = ref<MenuTreeNode[]>([])

const resolveIcon = (name: string | null | undefined): string => {
  if (name && app && app.component(name)) return name
  return 'Menu'
}

/** 将后端菜单节点转换为可渲染的递归导航项。 */
const mapMenuNode = (node: MenuTreeNode): NavItem => ({
  key: String(node.id),
  path: node.path === '/home' ? '/' : (node.path || ''),
  icon: resolveIcon(node.icon),
  label: node.name,
  children: (node.children ?? [])
    .filter(child => Boolean(child.path) || Boolean(child.children?.length))
    .map(mapMenuNode),
  requiresKeyMatterAccess: node.path === '/key-matters' ? true : undefined
})

const filterKeyMatterItems = (items: NavItem[]): NavItem[] =>
  items.reduce<NavItem[]>((visible, item) => {
    if (item.requiresKeyMatterAccess && authStore.keyMatterAccess?.canAccess !== true) return visible

    const children = item.children ? filterKeyMatterItems(item.children) : []
    if (!item.path && children.length === 0) return visible

    visible.push({
      ...item,
      children: children.length > 0 ? children : undefined
    })
    return visible
  }, [])

/** 动态菜单树（V57 起）：非空则完全以后端授权为准渲染侧边栏。 */
const dynamicNavItems = computed<NavSection[]>(() =>
  menuTree.value
    .map(node => ({
      section: node.name,
      icon: resolveIcon(node.icon),
      items: filterKeyMatterItems(
        (node.children ?? [])
          .filter(child => Boolean(child.path) || Boolean(child.children?.length))
          .map(mapMenuNode)
      )
    }))
    .filter(section => section.items.length > 0)
)

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

const loadMenuTree = async () => {
  try {
    const tree = await api.getMyMenuTree()
    menuTree.value = Array.isArray(tree) ? tree : []
  } catch {
    menuTree.value = []
  }
}

const menuPathAlias = (path: string) => path === '/' ? '/home' : path

const menuAuthorized = (path: string) => {
  if (!menuAuth.value) return true
  const alias = menuPathAlias(path)
  if (!menuAuth.value.managed.has(alias)) return true   // 未纳管菜单不受授权影响
  return menuAuth.value.allowed.has(alias)
}

/** 默认菜单也走同一套递归过滤，避免回退模式绕过权限。 */
const filterDefaultItems = (items: NavItem[]): NavItem[] =>
  items.reduce<NavItem[]>((visible, item) => {
    const selfAllowed =
      (!item.access || hasRoleAccess(authStore.user?.role, item.access))
      && (!item.path || menuAuthorized(item.path))
      && (!item.requiresKeyMatterAccess || authStore.keyMatterAccess?.canAccess === true)
    const children = item.children ? filterDefaultItems(item.children) : []

    if (!selfAllowed && children.length === 0) return visible
    visible.push({
      ...item,
      children: children.length > 0 ? children : undefined
    })
    return visible
  }, [])

/** 一级分区图标映射。 */
const sectionIcons: Record<string, string> = {
  '工作台': 'HomeFilled',
  '销售管理': 'Connection',
  '数据分析': 'DataAnalysis',
  '基础数据': 'Collection',
  '基础分类': 'Collection',
  '系统': 'Setting'
}

/** 首页在任何授权模式下都独立于业务分区，单独置顶渲染。 */
const isHomeItem = (item: NavItem) => item.path === '/' || item.path === '/home'

/** 完整分区列表（含首页），动态模式与回退模式统一在此收口。 */
const allNavSections = computed<NavSection[]>(() => {
  // 动态模式：后端授权树直接驱动，仅保留大事儿管理的领域准入叠加。
  if (menuTree.value.length > 0) return dynamicNavItems.value

  // 回退模式：内置默认菜单 + 岗位默认 + 授权叠加（历史行为）。
  return defaultNavItems
    .map(section => ({
      ...section,
      icon: section.icon || sectionIcons[section.section] || 'Menu',
      items: filterDefaultItems(section.items)
    }))
    .filter(section => section.items.length > 0)
})

/** 独立的首页入口：取自授权后的分区数据，未授权时不渲染。 */
const homeNavItem = computed<NavItem | null>(() => {
  for (const section of allNavSections.value) {
    const home = section.items.find(isHomeItem)
    if (home) return home
  }
  return null
})

const visibleNavItems = computed<NavSection[]>(() =>
  allNavSections.value
    .map(section => ({ ...section, items: section.items.filter(item => !isHomeItem(item)) }))
    .filter(section => section.items.length > 0)
)

const pathMatches = (configuredPath: string, currentPath = route.path) => {
  if (!configuredPath) return false
  if (configuredPath === '/') return currentPath === '/'
  return currentPath === configuredPath || currentPath.startsWith(`${configuredPath}/`)
}

const hasActiveRoute = (items: NavItem[], currentPath = route.path): boolean =>
  items.some(item => pathMatches(item.path, currentPath) || (item.children ? hasActiveRoute(item.children, currentPath) : false))

const selectedSectionName = ref<string | null>(null)
const expandedGroups = ref<Record<string, boolean>>({})

/** 当前路由是否为独立首页。 */
const isHomeActive = computed(() => route.path === '/' || route.path === '/home')

/** 首页固定态：位于首页且用户未显式选择业务分区时，不展示二级面板。 */
const homePinned = ref(true)

const selectedSection = computed<NavSection | null>(() => {
  if (isHomeActive.value && homePinned.value) return null
  const sections = visibleNavItems.value
  return sections.find(section => section.section === selectedSectionName.value)
    || sections.find(section => hasActiveRoute(section.items))
    || sections[0]
    || null
})

watch(() => route.path, currentPath => {
  homePinned.value = currentPath === '/' || currentPath === '/home'
  const routeSection = visibleNavItems.value.find(section => hasActiveRoute(section.items, currentPath))
  if (routeSection) selectedSectionName.value = routeSection.section
})

const getSectionIcon = (section: NavSection) => section.icon || sectionIcons[section.section] || 'Menu'

const selectSection = (section: NavSection) => {
  homePinned.value = false
  selectedSectionName.value = section.section
  if (isCollapsed.value) isCollapsed.value = false
}

const goHome = () => {
  if (!homeNavItem.value) return
  homePinned.value = true
  selectedSectionName.value = null
  void router.push(homeNavItem.value.path || '/')
}


const navigateTo = (item: NavItem) => {
  if (item.path) void router.push(item.path)
}

const groupKey = (section: NavSection, item: NavItem) =>
  `${section.section}:${item.key || item.path || item.label}`

const isGroupExpanded = (section: NavSection, item: NavItem) =>
  expandedGroups.value[groupKey(section, item)] !== false

const toggleGroup = (section: NavSection, item: NavItem) => {
  const key = groupKey(section, item)
  expandedGroups.value[key] = !isGroupExpanded(section, item)
}

const isNavItemActive = (item: NavItem) => pathMatches(item.path)

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
  void loadUnreadCount()
  unreadTimer = window.setInterval(() => void loadUnreadCount(), 60_000)
  void Promise.allSettled([
    loadRequirementBadge(),
    authStore.loadKeyMatterAccess(),
    loadMenuAuth(),
    loadMenuTree()
  ])
})
</script>
<template>
  <div class="layout">
    <!-- 千牛式双栏导航：左侧业务域，右侧二/三级菜单。 -->
    <aside class="sidebar" :class="{ collapsed: isCollapsed }">
      <div class="sidebar-header">
        <a href="/" class="sidebar-logo" aria-label="返回首页">
          <div class="sidebar-logo-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <rect x="8" y="2" width="8" height="4" rx="1" />
              <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
              <path d="m9 12 2 2 4-4" />
            </svg>
          </div>
          <span class="sidebar-logo-text">BU管理系统</span>
        </a>
        <div class="sidebar-header-actions">
          <span class="sidebar-ai-mode" aria-label="AI 模式">
            <el-icon><MagicStick /></el-icon>
            <span>AI模式</span>
          </span>
          <button
            class="sidebar-toggle"
            type="button"
            :aria-label="isCollapsed ? '展开侧边栏' : '收起侧边栏'"
            :title="isCollapsed ? '展开侧边栏' : '收起侧边栏'"
            @click="isCollapsed = !isCollapsed"
          >
            <el-icon v-if="isCollapsed"><Expand /></el-icon>
            <el-icon v-else><Fold /></el-icon>
          </button>
        </div>
      </div>

      <div class="sidebar-content">
        <nav class="primary-nav" aria-label="业务模块">
          <button
            v-if="homeNavItem"
            type="button"
            class="primary-nav-item"
            :class="{ active: isHomeActive }"
            :aria-current="isHomeActive ? 'page' : undefined"
            :title="homeNavItem.label"
            @click="goHome"
          >
            <span class="primary-nav-icon" aria-hidden="true">
              <el-icon><component :is="homeNavItem.icon" /></el-icon>
            </span>
            <span class="primary-nav-label">{{ homeNavItem.label }}</span>
          </button>
          <div v-if="homeNavItem" class="primary-nav-divider" aria-hidden="true"></div>
          <button
            v-for="section in visibleNavItems"
            :key="section.section"
            type="button"
            class="primary-nav-item"
            :class="{ active: selectedSection?.section === section.section }"
            :aria-current="selectedSection?.section === section.section ? 'page' : undefined"
            :title="section.section"
            @click="selectSection(section)"
          >
            <span class="primary-nav-icon" aria-hidden="true">
              <el-icon><component :is="getSectionIcon(section)" /></el-icon>
            </span>
            <span class="primary-nav-label">{{ section.section }}</span>
          </button>
        </nav>

        <section
          v-if="selectedSection && !isCollapsed"
          class="secondary-panel"
          :aria-label="`${selectedSection.section}菜单`"
        >
          <header class="secondary-header">
            <span>{{ selectedSection.section }}</span>
          </header>
          <nav class="secondary-nav" :aria-label="`${selectedSection.section}子菜单`">
            <template v-for="item in selectedSection.items" :key="item.key || item.path || item.label">
              <button
                v-if="item.children && item.children.length > 0"
                type="button"
                class="secondary-menu-group"
                :class="{ 'is-active': hasActiveRoute(item.children) }"
                :aria-expanded="isGroupExpanded(selectedSection, item)"
                @click="toggleGroup(selectedSection, item)"
              >
                <span>{{ item.label }}</span>
                <el-icon class="menu-chevron">
                  <ArrowUp v-if="isGroupExpanded(selectedSection, item)" />
                  <ArrowDown v-else />
                </el-icon>
              </button>

              <div
                v-if="item.children && item.children.length > 0"
                v-show="isGroupExpanded(selectedSection, item)"
                class="tertiary-menu"
              >
                <button
                  v-for="child in item.children"
                  :key="child.key || child.path || child.label"
                  type="button"
                  class="tertiary-menu-item"
                  :class="{ active: isNavItemActive(child) }"
                  :aria-current="isNavItemActive(child) ? 'page' : undefined"
                  @click="navigateTo(child)"
                >
                  <el-badge
                    v-if="child.path === '/requirements' && requirementBadge !== null"
                    :value="requirementBadge"
                    :max="99"
                    class="nav-badge-item"
                  >
                    <span>{{ child.label }}</span>
                  </el-badge>
                  <template v-else>{{ child.label }}</template>
                </button>
              </div>

              <button
                v-else
                type="button"
                class="secondary-menu-item"
                :class="{ active: isNavItemActive(item) }"
                :aria-current="isNavItemActive(item) ? 'page' : undefined"
                @click="navigateTo(item)"
              >
                <el-badge
                  v-if="item.path === '/requirements' && requirementBadge !== null"
                  :value="requirementBadge"
                  :max="99"
                  class="nav-badge-item"
                >
                  <span>{{ item.label }}</span>
                </el-badge>
                <template v-else>{{ item.label }}</template>
              </button>
            </template>
          </nav>
        </section>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main">
      <!-- 顶部导航 -->
      <header class="top-header">
        <div class="header-left">
          <h1 class="header-title">{{ route.meta.title || '页面标题' }}</h1>
        </div>
        <div class="header-right">
          <el-popover
            v-model:visible="noticePopoverVisible"
            trigger="click"
            :width="380"
            placement="bottom-end"
            @show="onNoticePopoverShow"
          >
            <template #reference>
              <button class="header-action" type="button" aria-label="通知">
                <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
                  <el-icon :size="18"><Bell /></el-icon>
                </el-badge>
              </button>
            </template>
            <div class="notice-panel">
              <div class="notice-panel-header">通知</div>
              <div v-if="noticeLoading" class="notice-empty">加载中…</div>
              <div v-else-if="notices.length === 0" class="notice-empty">暂无通知</div>
              <template v-else>
                <div
                  v-for="notice in notices"
                  :key="`${notice.kind}:${notice.date}`"
                  class="notice-item"
                  :class="{ read: notice.read }"
                >
                  <div class="notice-title">{{ notice.title }}</div>
                  <div v-if="notice.body" class="notice-body">{{ notice.body }}</div>
                  <div v-if="!notice.read && notice.link" class="notice-actions">
                    <el-button type="primary" link size="small" @click="handleNoticeAction(notice)">去处理</el-button>
                  </div>
                </div>
              </template>
            </div>
          </el-popover>
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
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

/* 千牛式导航：一级业务域窄栏 + 二/三级菜单面板。 */
.sidebar {
  width: var(--sidebar-width);
  flex: 0 0 var(--sidebar-width);
  min-width: 0;
  background: #f8f9fb;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.22s ease, flex-basis 0.22s ease;
}

.sidebar-header {
  height: 64px;
  flex: 0 0 64px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}

.sidebar-logo {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #202124;
  text-decoration: none;
}

.sidebar-logo-icon {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  border-radius: 9px;
  background: linear-gradient(135deg, #6366f1, #4338ca);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.sidebar-logo-icon svg {
  width: 17px;
  height: 17px;
}

.sidebar-logo-text {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-header-actions {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
}

.sidebar-ai-mode {
  height: 24px;
  padding: 0 7px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 1px solid #e9c94b;
  border-radius: 999px;
  background: #fff;
  color: #303030;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}

.sidebar-ai-mode :deep(.el-icon) {
  color: #d99a00;
  font-size: 12px;
}

.sidebar-toggle {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #737780;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}

.sidebar-toggle:hover {
  background: #eef0f3;
  color: #40434a;
}

.sidebar-toggle:focus-visible,
.primary-nav-item:focus-visible,
.secondary-menu-item:focus-visible,
.secondary-menu-group:focus-visible,
.tertiary-menu-item:focus-visible {
  outline: 2px solid #818cf8;
  outline-offset: -2px;
}

.sidebar-content {
  min-height: 0;
  flex: 1;
  display: flex;
  overflow: hidden;
}

.primary-nav {
  width: 112px;
  flex: 0 0 112px;
  padding: 14px 8px;
  overflow-x: hidden;
  overflow-y: auto;
  border-right: 1px solid #e9ebef;
}

.primary-nav-item {
  width: 100%;
  min-height: 40px;
  margin: 2px 0;
  padding: 0 6px;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #303238;
  font: inherit;
  font-size: 13px;
  line-height: 1.2;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}

.primary-nav-item:hover {
  background: #eef0f3;
}

.primary-nav-item.active {
  background: #e8e9ee;
  color: #4f46d8;
  font-weight: 600;
}

.primary-nav-icon {
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.primary-nav-icon :deep(.el-icon) {
  font-size: 16px;
}

.primary-nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.primary-nav-divider {
  height: 1px;
  margin: 6px 10px;
  background: #e5e7eb;
  flex: 0 0 auto;
}

.secondary-panel {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fafbfc;
}

.secondary-header {
  height: 56px;
  flex: 0 0 56px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  color: #202124;
  font-size: 15px;
  font-weight: 600;
}

.secondary-nav {
  min-height: 0;
  flex: 1;
  padding: 6px 8px 18px;
  overflow-x: hidden;
  overflow-y: auto;
}

.secondary-menu-item,
.secondary-menu-group {
  width: 100%;
  min-height: 38px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: #303238;
  font: inherit;
  font-size: 13px;
  line-height: 1.3;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}

.secondary-menu-item:hover,
.secondary-menu-group:hover {
  background: #f0f1f4;
}

.secondary-menu-item.active {
  background: #eef0ff;
  color: #4f46d8;
  font-weight: 600;
}

.secondary-menu-group.is-active {
  color: #4f46d8;
  font-weight: 600;
}

.menu-chevron {
  flex: 0 0 auto;
  color: #727680;
  font-size: 12px;
}

.tertiary-menu {
  margin: 1px 0 5px 14px;
  padding: 1px 0 1px 8px;
  border-left: 1px solid #e1e4e9;
}

.tertiary-menu-item {
  width: 100%;
  min-height: 36px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #3f4248;
  font: inherit;
  font-size: 13px;
  line-height: 1.3;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}

.tertiary-menu-item:hover {
  background: #f0f1f4;
}

.tertiary-menu-item.active {
  background: #eef0ff;
  color: #4f46d8;
  font-weight: 600;
}

.nav-badge-item {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
}

.nav-badge-item :deep(.el-badge__content) {
  background: #ee0a24;
  font-size: 10px;
  font-weight: 600;
  height: 16px;
  line-height: 16px;
  padding: 0 5px;
}

.sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
  flex-basis: var(--sidebar-collapsed-width);
}

.sidebar.collapsed .sidebar-header {
  height: auto;
  flex-basis: auto;
  padding: 12px 8px;
  flex-direction: column;
  gap: 10px;
}

.sidebar.collapsed .sidebar-logo-text,
.sidebar.collapsed .sidebar-ai-mode,
.sidebar.collapsed .secondary-panel {
  display: none;
}

.sidebar.collapsed .sidebar-header-actions {
  display: contents;
}

.sidebar.collapsed .sidebar-toggle {
  background: #eef0f3;
}

.sidebar.collapsed .primary-nav {
  width: 100%;
  flex-basis: 100%;
  padding: 12px 8px;
  border-right: none;
}

.sidebar.collapsed .primary-nav-item {
  justify-content: center;
  min-height: 44px;
  padding: 0;
}

.sidebar.collapsed .primary-nav-label {
  display: none;
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

.user-avatar {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #4338ca);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}

.user-avatar.sm {
  width: 32px;
  height: 32px;
  flex-basis: 32px;
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

/* 通知面板 */
.notice-panel {
  margin: -12px;
}

.notice-panel-header {
  padding: 12px 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-800);
  border-bottom: 1px solid var(--gray-200);
}

.notice-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--gray-500);
  font-size: 13px;
}

.notice-item {
  padding: 10px 16px;
  border-bottom: 1px solid var(--gray-100);
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-item.read .notice-title,
.notice-item.read .notice-body {
  color: var(--gray-400);
  font-weight: 400;
}

.notice-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-800);
}

.notice-body {
  margin-top: 2px;
  font-size: 12px;
  color: var(--gray-500);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notice-actions {
  margin-top: 4px;
  text-align: right;
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
    flex-basis: var(--sidebar-collapsed-width);
  }

  .sidebar-header {
    height: auto;
    flex-basis: auto;
    padding: 12px 8px;
    flex-direction: column;
    gap: 10px;
  }

  .sidebar-logo-text,
  .sidebar-ai-mode,
  .secondary-panel,
  .sidebar-toggle {
    display: none;
  }

  .primary-nav {
    width: 100%;
    flex-basis: 100%;
    padding: 12px 8px;
    border-right: none;
  }

  .primary-nav-item {
    justify-content: center;
    min-height: 44px;
    padding: 0;
  }

  .primary-nav-label {
    display: none;
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
