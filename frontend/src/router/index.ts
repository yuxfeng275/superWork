import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false }
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
        component: () => import('@/views/RequirementDetailView.vue'),
        meta: { title: '需求详情' }
      },
      {
        path: 'tasks',
        name: 'Tasks',
        component: () => import('@/views/TasksView.vue'),
        meta: { title: '任务管理' }
      },
      {
        path: 'statistics',
        name: 'Statistics',
        component: () => import('@/views/StatisticsView.vue'),
        meta: { title: '数据统计' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/ProjectView.vue'),
        meta: { title: '项目管理' }
      },
      {
        path: 'organization',
        name: 'Organization',
        component: () => import('@/views/OrganizationView.vue'),
        meta: { title: '组织架构' }
      },
      {
        path: 'system/users',
        name: 'SystemUsers',
        component: () => import('@/views/SystemUserView.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'system/roles',
        name: 'SystemRoles',
        component: () => import('@/views/SystemRoleView.vue'),
        meta: { title: '角色管理' }
      },
      {
        path: 'system/menus',
        name: 'SystemMenus',
        component: () => import('@/views/SystemMenuView.vue'),
        meta: { title: '菜单管理' }
      },
      {
        path: 'system/workflow',
        name: 'SystemWorkflow',
        component: () => import('@/views/SystemWorkflowView.vue'),
        meta: { title: '工作流配置' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')

  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/')
  } else {
    next()
  }
})

export default router
