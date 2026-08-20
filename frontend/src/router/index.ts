import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { hasRoleAccess, type RoleAccess } from '@/constants/roles'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/requirements-standalone/:id',
    name: 'RequirementDetailStandalone',
    component: () => import('@/views/RequirementDetailView.vue'),
    meta: { requiresAuth: true, standalone: true }
  },
  {
    path: '/key-matters-meeting',
    name: 'KeyMattersMeeting',
    component: () => import('@/views/KeyMattersView.vue'),
    meta: { requiresAuth: true, standalone: true, requiresKeyMatterAccess: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'requirements',
        name: 'Requirements',
        component: () => import('@/views/RequirementsView.vue'),
        meta: { title: '需求管理' }
      },
      {
        path: 'requirements/:id',
        name: 'RequirementDetail',
        redirect: to => `/requirements-standalone/${to.params.id}`
      },
      {
        path: 'tasks',
        name: 'Tasks',
        component: () => import('@/views/TasksView.vue'),
        meta: { title: '任务管理' }
      },
      {
        path: 'defects',
        name: 'Defects',
        component: () => import('@/views/DefectsView.vue'),
        meta: { title: '缺陷管理' }
      },
      {
        path: 'emails',
        name: 'Emails',
        component: () => import('@/views/EmailManagementView.vue'),
        meta: { title: '邮件管理' }
      },
      {
        path: 'key-matters',
        name: 'KeyMatters',
        component: () => import('@/views/KeyMattersView.vue'),
        meta: { title: '大事儿管理', requiresKeyMatterAccess: true }
      },
      {
        path: 'statistics',
        name: 'Statistics',
        component: () => import('@/views/StatisticsView.vue'),
        meta: { title: 'BU驾驶舱', roleAccess: 'management' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/ProjectView.vue'),
        meta: { title: '项目管理', roleAccess: 'project' }
      },
      {
        path: 'business-lines',
        name: 'BusinessLines',
        component: () => import('@/views/BusinessLineView.vue'),
        meta: { title: '业务线管理' }
      },
      {
        path: 'organization',
        redirect: '/business-lines'
      },
      {
        path: 'customers',
        name: 'Customers',
        component: () => import('@/views/CustomerInfoView.vue'),
        meta: { title: '客户信息管理', roleAccess: 'customer' }
      },
      {
        path: 'opportunities',
        name: 'Opportunities',
        component: () => import('@/views/OpportunityView.vue'),
        meta: { title: '线索商机管理' }
      },
      {
        path: 'system/users',
        name: 'SystemUsers',
        component: () => import('@/views/SystemUserView.vue'),
        meta: { title: '用户管理', roleAccess: 'management' }
      },
      {
        path: 'system/roles',
        name: 'SystemRoles',
        component: () => import('@/views/SystemRoleView.vue'),
        meta: { title: '角色管理', roleAccess: 'management' }
      },
      {
        path: 'system/menus',
        name: 'SystemMenus',
        component: () => import('@/views/SystemMenuView.vue'),
        meta: { title: '菜单管理', roleAccess: 'management' }
      },
      {
        path: 'system/workflow',
        name: 'SystemWorkflow',
        component: () => import('@/views/SystemWorkflowView.vue'),
        meta: { title: '工作流配置', roleAccess: 'management' }
      },
      {
        path: 'system/configs',
        name: 'SystemConfigs',
        component: () => import('@/views/SystemConfigView.vue'),
        meta: { title: '配置管理', roleAccess: 'management' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach(async to => {
  const token = localStorage.getItem('token')

  if (to.meta.requiresAuth !== false && !token) {
    return '/login'
  }

  if (to.path === '/login' && token) {
    return '/'
  }

  if (to.meta.roleAccess) {
    try {
      const user = JSON.parse(localStorage.getItem('user') || '{}')
      if (!hasRoleAccess(user?.role, to.meta.roleAccess as RoleAccess)) {
        return '/'
      }
    } catch {
      return '/'
    }
  }

  if (to.meta.requiresKeyMatterAccess) {
    const access = await useAuthStore().loadKeyMatterAccess(true)
    if (!access.canAccess) {
      return '/'
    }
  }

  if (to.meta.allowedUsernames) {
    try {
      const user = JSON.parse(localStorage.getItem('user') || '{}')
      const allowedUsernames = to.meta.allowedUsernames as string[]
      if (!allowedUsernames.includes(user?.username)) {
        return '/'
      }
    } catch {
      return '/'
    }
  }

  return true
})

export default router
