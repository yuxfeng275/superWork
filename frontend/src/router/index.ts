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
        path: 'ai-assistant',
        name: 'AiAssistant',
        component: () => import('@/views/AiAssistantView.vue'),
        meta: { title: 'AI 助手', requiresAuth: true }
      },
      {
        path: 'key-matters',
        name: 'KeyMatters',
        component: () => import('@/views/KeyMattersView.vue'),
        meta: { title: '大事儿管理', requiresKeyMatterAccess: true }
      },
      {
        path: 'todos',
        name: 'Todos',
        component: () => import('@/views/TodosView.vue'),
        meta: { title: '待办' }
      },
      {
        path: 'oa-affairs',
        name: 'OaAffairs',
        component: () => import('@/views/OaAffairsView.vue'),
        meta: { title: 'OA 待办', roleAccess: 'management' }
      },
      {
        path: 'weekly-report',
        name: 'WeeklyReport',
        component: () => import('@/views/WeeklyReportView.vue'),
        meta: { title: '周报中心', requiresAuth: true }
      },
      {
        path: 'schedule',
        name: 'Schedule',
        component: () => import('@/views/ScheduleView.vue'),
        meta: { title: '日程' }
      },
      {
        path: 'statistics',
        name: 'Statistics',
        component: () => import('@/views/StatisticsView.vue'),
        meta: { title: 'BU驾驶舱', roleAccess: 'management' }
      },
      {
        path: 'revenue',
        redirect: '/revenue/worktime'
      },
      {
        path: 'revenue/worktime',
        name: 'RevenueWorktime',
        component: () => import('@/views/RevenueView.vue'),
        meta: { title: '工时 & 成本', roleAccess: 'management', revenueTab: 'matrix' }
      },
      {
        path: 'revenue/delivery',
        name: 'RevenueDelivery',
        component: () => import('@/views/RevenueView.vue'),
        meta: { title: '交付与利润', roleAccess: 'management', revenueTab: 'delivery' }
      },
      {
        path: 'revenue/import',
        name: 'RevenueImport',
        component: () => import('@/views/RevenueView.vue'),
        meta: { title: '数据导入', roleAccess: 'management', revenueTab: 'import' }
      },
      {
        path: 'revenue/pending',
        name: 'RevenuePending',
        component: () => import('@/views/RevenueView.vue'),
        meta: { title: '待映射与销售项目', roleAccess: 'management', revenueTab: 'pending' }
      },
      {
        path: 'kpi-report',
        name: 'KpiReport',
        component: () => import('@/views/KpiReportView.vue'),
        meta: { title: 'KPI周报', roleAccess: 'management' }
      },
      {
        path: 'bl-profit',
        name: 'BusinessLineProfit',
        component: () => import('@/views/BusinessLineProfitView.vue'),
        meta: { title: '业务线利润', roleAccess: 'management' }
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
        path: 'quotation-policies',
        name: 'QuotationPolicies',
        component: () => import('@/views/QuotationPolicyView.vue'),
        meta: { title: '报价策略管理' }
      },
      {
        path: 'quotations',
        name: 'Quotations',
        component: () => import('@/views/QuotationView.vue'),
        meta: { title: '报价单管理' }
      },
      {
        path: 'quotations/:id',
        name: 'QuotationDetail',
        component: () => import('@/views/QuoteDetailView.vue'),
        meta: { title: '报价单详情' }
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
      },
      {
        path: 'system/connectors',
        name: 'SystemConnectors',
        component: () => import('@/views/ConnectorManageView.vue'),
        meta: { title: '连接器管理', roleAccess: 'management' }
      },
      {
        path: 'system/models',
        name: 'SystemModels',
        component: () => import('@/views/ModelManageView.vue'),
        meta: { title: '模型管理', roleAccess: 'management' }
      },
      {
        path: 'ai-connectors',
        redirect: '/system/connectors'
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
    if (access?.canAccess !== true) {
      return '/'
    }
  }

  return true
})

export default router
