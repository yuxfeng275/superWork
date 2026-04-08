<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const isCollapsed = ref(false)

const navItems = [
  {
    section: '工作台',
    items: [
      { path: '/', icon: 'HomeFilled', label: '首页' },
      { path: '/requirements', icon: 'Document', label: '需求管理', badge: 12 },
      { path: '/tasks', icon: 'Finished', label: '任务管理' },
      { path: '/projects', icon: 'Folder', label: '项目管理' }
    ]
  },
  {
    section: '数据分析',
    items: [
      { path: '/statistics', icon: 'DataAnalysis', label: '数据统计' }
    ]
  },
  {
    section: '系统',
    items: [
      { path: '/organization', icon: 'Organization', label: '组织架构' },
      { path: '/system/users', icon: 'User', label: '用户管理' },
      { path: '/system/roles', icon: 'Lock', label: '角色管理' },
      { path: '/system/menus', icon: 'Menu', label: '菜单管理' },
      { path: '/system/workflow', icon: 'Connection', label: '工作流配置' }
    ]
  }
]

const isActive = (path: string) => {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: isCollapsed }">
      <!-- Logo -->
      <div class="sidebar-header">
        <a href="/" class="sidebar-logo">
          <div class="sidebar-logo-icon">📋</div>
          <span class="sidebar-logo-text">BU管理系统</span>
        </a>
        <button class="sidebar-toggle" @click="isCollapsed = !isCollapsed">
          <el-icon v-if="isCollapsed"><Expand /></el-icon>
          <el-icon v-else><Fold /></el-icon>
        </button>
      </div>

      <!-- 用户信息 -->
      <div class="sidebar-user">
        <div class="user-avatar">{{ authStore.user?.realName?.charAt(0) || '用户' }}</div>
        <div class="user-info">
          <div class="user-name">{{ authStore.user?.realName || '未登录' }}</div>
          <div class="user-role">{{ authStore.user?.role || '-' }}</div>
        </div>
      </div>

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <div v-for="section in navItems" :key="section.section" class="nav-section">
          <div class="nav-section-title">{{ section.section }}</div>
          <router-link
            v-for="item in section.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
          >
            <span class="nav-item-icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="nav-item-text">{{ item.label }}</span>
            <span v-if="item.badge" class="nav-item-badge">{{ item.badge }}</span>
          </router-link>
        </div>
      </nav>

      <!-- 退出登录 -->
      <div class="sidebar-footer">
        <button class="logout-btn" @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </button>
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
          <button class="header-action">
            <el-icon><Bell /></el-icon>
            <span class="badge"></span>
          </button>
          <div class="user-avatar sm">{{ authStore.user?.realName?.charAt(0) || '用户' }}</div>
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
  overflow: hidden;
}

/* 侧边栏 */
.sidebar {
  width: var(--sidebar-width);
  background: #fff;
  border-right: 1px solid var(--gray-200);
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
}

.sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
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
  font-size: 18px;
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
  padding: 24px;
  overflow-y: auto;
}
</style>
