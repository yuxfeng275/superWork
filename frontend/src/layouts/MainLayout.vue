<script setup lang="ts">
import { computed, getCurrentInstance, onBeforeUnmount, onMounted, ref } from 'vue'
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
  path: string
  icon: string
  label: string
  badge?: number
  access?: RoleAccess
  requiresKeyMatterAccess?: boolean
  /** 三级菜单：当该项为分组节点时，groupLabel 为分组标题，children 为子项 */
  groupLabel?: string
  children?: NavItem[]
}

interface NavSection {
  section: string
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
      { path: '/revenue', icon: 'Coin', label: '营收管理', access: 'management' },
      { path: '/kpi-report', icon: 'DataLine', label: 'KPI周报', access: 'management' }
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
      { path: '/ai-connectors', icon: 'Connection', label: 'AI 连接器', access: 'management' }
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

/** 动态菜单 → 侧边栏分区结构。支持三级：二级分组（有 children 的路由容器）展开为嵌套组。 */
const dynamicNavItems = computed<NavSection[]>(() =>
  menuTree.value
    .map(node => {
      const items: NavItem[] = (node.children ?? [])
        .filter(child => child.path || (child.children && child.children.length > 0))
        .map(child => {
          // 二级分组节点：有 children 且其中有真实路径的子项 → 展开为嵌套组
          const grandChildren = (child.children ?? []).filter(gc => gc.path)
          if (grandChildren.length > 0) {
            return {
              path: child.path || '',
              icon: resolveIcon(child.icon),
              label: child.name,
              groupLabel: child.name,
              children: grandChildren.map(gc => ({
                path: gc.path === '/home' ? '/' : (gc.path as string),
                icon: resolveIcon(gc.icon),
                label: gc.name,
                requiresKeyMatterAccess: gc.path === '/key-matters' ? true : undefined
              }))
            }
          }
          // 叶子节点
          return {
            path: child.path === '/home' ? '/' : (child.path as string),
            icon: resolveIcon(child.icon),
            label: child.name,
            requiresKeyMatterAccess: child.path === '/key-matters' ? true : undefined
          }
        })
      return { section: node.name, items }
    })
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

const visibleNavItems = computed(() => {
  // 动态模式：后端授权树直接驱动，仅保留大事儿管理的领域准入叠加
  if (menuTree.value.length > 0) {
    return dynamicNavItems.value
      .map(section => ({
        ...section,
        items: section.items.filter(item =>
          !item.requiresKeyMatterAccess || authStore.keyMatterAccess?.canAccess === true)
      }))
      .filter(section => section.items.length > 0)
  }
  // 回退模式：内置默认菜单 + 岗位默认 + 授权叠加（历史行为）
  return defaultNavItems
    .map(section => ({
      ...section,
      items: section.items.filter(item =>
        (!item.access || hasRoleAccess(authStore.user?.role, item.access))
        && menuAuthorized(item.path)
        && (!item.requiresKeyMatterAccess || authStore.keyMatterAccess?.canAccess === true)
      )
    }))
    .filter(section => section.items.length > 0)
})

/** el-menu 的 default-active：以当前路由路径为激活项 */
const activeMenuIndex = computed(() => {
  const p = route.path
  if (p === '/') return '/home'
  return p
})

/** 一级分区图标映射 */
const sectionIcons: Record<string, string> = {
  '工作台': 'HomeFilled',
  '销售管理': 'Connection',
  '数据分析': 'DataAnalysis',
  '基础数据': 'Collection',
  '系统': 'Setting'
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
        <el-menu
          :default-active="activeMenuIndex"
          :collapse="isCollapsed"
          router
          class="sidebar-menu"
        >
          <template v-for="section in visibleNavItems" :key="section.section">
            <el-sub-menu
              v-if="section.items.length > 0"
              :index="section.section"
              class="nav-section-group"
            >
              <template #title>
                <el-icon v-if="sectionIcons[section.section]"><component :is="sectionIcons[section.section]" /></el-icon>
                <span>{{ section.section }}</span>
              </template>
              <template v-for="item in section.items" :key="item.path || item.label">
                <!-- 二级分组 -->
                <el-sub-menu v-if="item.children && item.children.length > 0" :index="item.label" class="nav-sub-group">
                  <template #title>
                    <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
                    <span>{{ item.label }}</span>
                  </template>
                  <el-menu-item
                    v-for="sub in item.children"
                    :key="sub.path"
                    :index="sub.path"
                  >
                    <template v-if="sub.path === '/requirements' && requirementBadge !== null">
                      <el-badge :value="requirementBadge" :max="99" class="nav-badge-item">
                        <span>{{ sub.label }}</span>
                      </el-badge>
                    </template>
                    <template v-else>
                      {{ sub.label }}
                    </template>
                  </el-menu-item>
                </el-sub-menu>
                <!-- 叶子节点 -->
                <el-menu-item v-else :index="item.path">
                  <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
                  <template v-if="item.path === '/requirements' && requirementBadge !== null">
                    <el-badge :value="requirementBadge" :max="99" class="nav-badge-item">
                      <span>{{ item.label }}</span>
                    </el-badge>
                  </template>
                  <template v-else>
                    {{ item.label }}
                  </template>
                </el-menu-item>
              </template>
            </el-sub-menu>
          </template>
        </el-menu>
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
/* ═══════════════════════════════════════
   Layout
   ═══════════════════════════════════════ */
.layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

/* ═══════════════════════════════════════
   侧边栏 — 有赞风格 56px 图标栏
   ═══════════════════════════════════════ */
.sidebar {
  width: 56px;
  flex: 0 0 56px;
  background: #f7f8fa;
  border-right: 1px solid #ebedf0;
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 100;
}
.sidebar-header {
  width: 100%;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sidebar-logo { display: flex; align-items: center; text-decoration: none; }
.sidebar-logo-icon {
  width: 34px; height: 34px;
  border-radius: 8px;
  background: #1677ff; color: #fff;
  display: flex; align-items: center; justify-content: center;
}
.sidebar-logo-icon svg { width: 16px; height: 16px; }

/* 图标列 */
.sidebar-icons {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 0;
  overflow-y: auto;
}
.icon-btn {
  width: 40px; height: 40px;
  border: none; border-radius: 8px;
  background: transparent; color: #646566;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
  position: relative; flex-shrink: 0;
}
.icon-btn:hover { background: #e8e8ea; color: #323233; }
.icon-btn.active { background: #e8f4ff; color: #1677ff; }
.icon-btn.active::before {
  content: '';
  position: absolute; left: 0; top: 8px; bottom: 8px;
  width: 3px; border-radius: 0 2px 2px 0;
  background: #1677ff;
}
.sidebar-footer { padding-bottom: 8px; }

/* ═══════════════════════════════════════
   弹出面板
   ═══════════════════════════════════════ */
.flyout-panel {
  position: fixed; left: 56px; top: 0; bottom: 0;
  width: 220px;
  background: #fff;
  border-right: 1px solid #ebedf0;
  box-shadow: 2px 0 12px rgba(0,0,0,0.06);
  z-index: 99;
  display: flex; flex-direction: column; overflow: hidden;
}
.flyout-title {
  height: 56px; line-height: 56px;
  padding: 0 20px;
  font-size: 15px; font-weight: 600; color: #323233;
  border-bottom: 1px solid #ebedf0;
  flex-shrink: 0;
}
.flyout-menu {
  flex: 1; overflow-y: auto; padding: 8px 0;
}

.flyout-group-label {
  font-size: 12px; font-weight: 600; color: #969799;
  padding: 12px 20px 4px; letter-spacing: 0.3px;
}

.flyout-item {
  display: flex; align-items: center; gap: 10px;
  height: 40px; padding: 0 20px;
  font-size: 14px; color: #323233;
  text-decoration: none; cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4,0,0.2,1);
  position: relative;
}
.flyout-item:hover { background: #f2f3f5; }
.flyout-item-child { padding-left: 36px; font-size: 13px; color: #646566; }
.flyout-item.is-active { background: #e8f4ff; color: #1677ff; font-weight: 500; }
.flyout-item.is-active::before {
  content: '';
  position: absolute; left: 0; top: 6px; bottom: 6px;
  width: 3px; border-radius: 0 2px 2px 0;
  background: #1677ff;
}

.flyout-badge {
  margin-left: auto;
  background: #ee0a24; color: #fff;
  font-size: 10px; font-weight: 600;
  padding: 1px 6px; border-radius: 10px;
  min-width: 18px; text-align: center; line-height: 16px;
}

/* 面板过渡 */
.flyout-enter-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.flyout-leave-active { transition: opacity 0.1s ease, transform 0.1s ease; }
.flyout-enter-from { opacity: 0; transform: translateX(-8px); }
.flyout-leave-to   { opacity: 0; transform: translateX(-8px); }

/* ═══════════════════════════════════════
   主内容区
   ═══════════════════════════════════════ */
.main {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column;
  overflow: hidden; background: var(--gray-50);
}
.top-header {
  height: 64px; background: #fff;
  border-bottom: 1px solid var(--gray-200);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px;
}
.header-title { font-size: 16px; font-weight: 600; color: var(--gray-800); }
.header-right { display: flex; align-items: center; gap: 12px; }
.header-user { display: flex; align-items: center; gap: 10px; padding-left: 12px; border-left: 1px solid var(--gray-200); }
.header-user-info { min-width: 72px; }
.header-user-info .user-name,
.header-user-info .user-role { white-space: nowrap; }

.user-avatar.sm { width: 32px; height: 32px; font-size: 12px; }
.user-avatar { width: 40px; height: 40px; border-radius: var(--radius-md); background: linear-gradient(135deg,var(--primary),var(--primary-dark)); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 600; flex-shrink: 0; }
.user-name { font-size: 14px; font-weight: 600; color: var(--gray-800); }
.user-role { font-size: 12px; color: var(--gray-500); }

.header-action {
  width: 40px; height: 40px; border-radius: var(--radius-md);
  border: none; background: transparent; color: var(--gray-500);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  position: relative;
}
.header-action:hover { background: var(--gray-100); color: var(--gray-700); }
.header-action .badge { position: absolute; top: 6px; right: 6px; width: 8px; height: 8px; background: var(--danger); border-radius: 50%; }

.notice-panel { margin: -12px; }
.notice-panel-header { padding: 12px 16px; font-size: 14px; font-weight: 600; color: var(--gray-800); border-bottom: 1px solid var(--gray-200); }
.notice-empty { padding: 32px 0; text-align: center; color: var(--gray-500); font-size: 13px; }
.notice-item { padding: 10px 16px; border-bottom: 1px solid var(--gray-100); }
.notice-item:last-child { border-bottom: none; }
.notice-item.read .notice-title,
.notice-item.read .notice-body { color: var(--gray-400); font-weight: 400; }
.notice-title { font-size: 13px; font-weight: 600; color: var(--gray-800); }
.notice-body { margin-top: 2px; font-size: 12px; color: var(--gray-500); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.notice-actions { margin-top: 4px; text-align: right; }

.content { flex: 1; min-width: 0; padding: 24px; overflow: auto; }

/* 大事儿演示模式 */
:global(body.key-matters-presentation) { overflow: hidden; }
:global(body.key-matters-presentation .layout) { height: 100dvh; }
:global(body.key-matters-presentation .sidebar),
:global(body.key-matters-presentation .top-header) { display: none; }
:global(body.key-matters-presentation .main) { width: 100%; min-width: 0; }
:global(body.key-matters-presentation .content) { height: 100dvh; padding: 0; overflow: hidden; }
:global(body.key-matters-presentation .key-matters-page) { height: 100dvh; overflow: hidden; }
:global(body.key-matters-presentation .key-matters-page > .page-toolbar) { display: none; }

@media (max-width: 720px) {
  .top-header { padding: 0 12px; }
  .header-user-info { display: none; }
  .header-user { padding-left: 8px; }
  .content { padding: 12px; }
}
</style>