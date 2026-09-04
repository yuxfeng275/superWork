import type { SystemConfigGroup, SystemConfigGroupSummary, SystemConfigTestResult } from '@/types/system-config'
import type { WorkItemOverviewItem, WorkItemOverviewParams, WorkItemOverviewResponse } from '@/types/work-item'
import type {
  DeliveryContractBatch,
  DeliveryContractImportResult,
  DeliveryMappedContract,
  DeliveryOtherCost,
  DeliveryPendingContract,
  DeliveryPlan,
  DeliverySummary,
  RevenueCellDetail,
  RevenueCostEntry,
  RevenueEstimateEntry,
  RevenueImportBatch,
  RevenueImportResult,
  RevenueMatrix,
  RevenueOpportunityOption,
  RevenueSalesProject,
  RevenueWorklogEntry
} from '@/types/revenue'
import type {
  EmailAccount,
  EmailAccountPayload,
  EmailConnectionTestResult,
  EmailDailyDigest,
  EmailMessageDetail,
  EmailMessagePage,
  EmailMessageQuery,
  EmailInterpretation,
  EmailProjectGroup,
  EmailSenderCompanyGroup,
  EmailGroupingJobStatus,
  EmailSyncStatus,
  EmailWeComMapping,
} from '@/types/email'
import type { AiAgentMessage, AiAgentSession, AiAgentSessionSummary, AiAgentStreamEvent } from '@/types/ai-agent'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: string
}

export class ApiRequestError extends Error {
  readonly status: number
  readonly code?: number

  constructor(message: string, status: number, code?: number) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
  }
}

type ApiPayload<T> = ApiResponse<T> | T

interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userInfo: {
    id: number
    username: string
    realName: string
    role: string
    email: string
    phone: string
  }
}

interface RegisterRequest {
  username: string
  password: string
  realName: string
  role: string
  email?: string
  phone?: string
}

export interface BuDirectionPayload {
  code: string
  name: string
  objective?: string
  ownerId?: number
  startDate: string
  endDate: string
  status: string
  sortOrder: number
  projectIds: number[]
  milestones: Array<{
    id?: number
    name: string
    dueDate: string
    status: string
    sortOrder: number
  }>
}

export interface YunxiaoProjectMapping {
  id: number
  projectId: number
  yunxiaoProjectId: string
  workitemTypeId?: string
  category: string
  syncEnabled: number
  lastSyncedAt?: string
  lastSyncStatus?: string
  lastSyncError?: string
}

export interface YunxiaoProjectOption {
  id: string
  name: string
  customCode?: string
  status?: string
}

export interface YunxiaoMemberOption {
  userId: string
  memberId?: string
  name: string
  email?: string
  status?: string
}

export interface YunxiaoUserMapping {
  id: number
  userId: number
  yunxiaoUserId: string
  syncEnabled: number
}

export interface YunxiaoProjectMappingPayload {
  projectId?: number
  yunxiaoProjectId: string
  workitemTypeId?: string
  category: string
  syncEnabled: number
}

export interface YunxiaoUserMappingPayload {
  userId?: number
  yunxiaoUserId: string
  syncEnabled: number
}

export interface YunxiaoConfigPayload {
  enabled: boolean
  edition: 'center' | 'region'
  baseUrl: string
  organizationId?: string
  token?: string
}

export interface YunxiaoConnectionTestResult {
  success: boolean
  userId?: string
  userName?: string
  email?: string
  message: string
  testedAt: string
}

// ==================== OA 集成 (Seeyon) 类型 ====================

export interface SeeyonOaConfigPayload {
  enabled: boolean
  baseUrl: string
  username?: string
  password?: string
  token?: string
}

export interface SeeyonOaConnectionTestResult {
  success: boolean
  userName?: string
  memberName?: string
  message: string
  testedAt: string
}

export interface SeeyonOaMemberOption {
  id: string
  name: string
  loginName: string
  departmentName: string
  email: string
  mobile: string
  enabled: boolean
}

export interface SeeyonOaDepartmentOption {
  id: string
  name: string
  parentId: string
  parentName: string
  sortOrder: number
  enabled: boolean
}

export interface KeyMatterAccess {
  canAccess: boolean
  canManageAll: boolean
  canFeedbackOwn: boolean
  canCreateOwn?: boolean
}

const DENIED_KEY_MATTER_ACCESS: KeyMatterAccess = {
  canAccess: false,
  canManageAll: false,
  canFeedbackOwn: false,
  canCreateOwn: false
}

function normalizeKeyMatterAccess(value: unknown): KeyMatterAccess {
  if (!value || typeof value !== 'object') return { ...DENIED_KEY_MATTER_ACCESS }

  const record = value as Record<string, unknown>
  if (
    typeof record.canAccess !== 'boolean'
    || typeof record.canManageAll !== 'boolean'
    || typeof record.canFeedbackOwn !== 'boolean'
    || (record.canCreateOwn !== undefined && typeof record.canCreateOwn !== 'boolean')
  ) {
    return { ...DENIED_KEY_MATTER_ACCESS }
  }

  return {
    canAccess: record.canAccess,
    canManageAll: record.canManageAll,
    canFeedbackOwn: record.canFeedbackOwn,
    canCreateOwn: record.canCreateOwn === true
  }
}

export interface BuKeyMatterParticipant {
  userId: number
  username: string
  realName: string
}

export interface BuKeyMatterWeeklyUpdate {
  id: number
  weekStartDate: string
  status: string
  progress: number
  progressSummary: string
  issues?: string
  nextWeekPlan?: string
  supportNeeded?: string
  createdBy?: number
  createdAt?: string
  updatedAt?: string
}

export interface BuKeyMatter {
  id: number
  title: string
  description?: string
  projectId?: number
  projectName?: string
  projectRootId?: number
  projectRootName?: string
  ownerId: number
  ownerName?: string
  participants?: BuKeyMatterParticipant[]
  priority: string
  status: string
  progress: number
  startDate: string
  plannedCompletionDate: string
  completedAt?: string
  sortOrder: number
  overdue: boolean
  currentWeekUpdated: boolean
  latestUpdate?: BuKeyMatterWeeklyUpdate
  currentWeekUpdate?: BuKeyMatterWeeklyUpdate
  weeklyUpdates: BuKeyMatterWeeklyUpdate[]
  createdAt?: string
  updatedAt?: string
}

export interface BuKeyMatterPayload {
  title: string
  description?: string
  projectId?: number
  ownerId: number
  participantIds?: number[]
  priority: string
  status: string
  progress: number
  startDate: string
  plannedCompletionDate: string
  sortOrder: number
}

export interface BuKeyMatterWeeklyUpdatePayload {
  status: string
  progress: number
  progressSummary: string
  issues?: string
  nextWeekPlan?: string
  supportNeeded?: string
}

export interface SalesOpportunityFollowUp {
  id: number
  opportunityId: number
  followUpAt: string
  follower: string
  content: string
  status: string
  probability: number
  nextFollowUp?: string
  createdAt?: string
}

export interface SalesOpportunityFollowUpPayload {
  followUpAt: string
  follower: string
  content: string
  status: string
  probability: number
  nextFollowUp?: string
}

class ApiService {
  private baseUrl = API_BASE_URL

  private getToken(): string | null {
    return localStorage.getItem('token')
  }

  private clearAuthAndRedirect(): void {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    localStorage.removeItem('refreshToken')

    if (window.location.pathname !== '/login') {
      window.location.replace('/login')
    }
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`
    const token = this.getToken()

    const headers: HeadersInit = options.body instanceof FormData
      ? { ...options.headers }
      : {
          'Content-Type': 'application/json',
          ...options.headers
        }

    if (token) {
      (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`
    }

    const response = await fetch(url, {
      ...options,
      headers
    })

    if (!response.ok) {
      const errorText = await response.text()
      let errorMessage = `HTTP error! status: ${response.status}`
      let errorCode: number | undefined
      try {
        const parsed: unknown = errorText ? JSON.parse(errorText) : null
        if (parsed && typeof parsed === 'object') {
          const errorBody = parsed as { message?: unknown; code?: unknown }
          if (typeof errorBody.message === 'string' && errorBody.message) {
            errorMessage = errorBody.message
          }
          if (typeof errorBody.code === 'number') errorCode = errorBody.code
        }
      } catch {
        if (errorText) errorMessage = errorText
      }

      if (response.status === 401) {
        this.clearAuthAndRedirect()
      }
      throw new ApiRequestError(errorMessage, response.status, errorCode)
    }

    const text = await response.text()

    if (!text) {
      return undefined as T
    }

    const result: ApiPayload<T> = JSON.parse(text)

    if (Array.isArray(result)) {
      return result as T
    }

    if (result && typeof result === 'object' && 'code' in result) {
      const wrapped = result as ApiResponse<T>
      if (wrapped.code !== 200) {
        if (wrapped.code === 401) this.clearAuthAndRedirect()
        throw new ApiRequestError(wrapped.message || 'Request failed', wrapped.code, wrapped.code)
      }
      return wrapped.data
    }

    return result as T
  }

  // Auth APIs
  async login(username: string, password: string): Promise<LoginResponse> {
    const response = await fetch(`${this.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })

    const result = await response.json()

    if (result.code !== 200) {
      throw new Error(result.message || 'Login failed')
    }

    return result.data
  }

  async register(data: RegisterRequest): Promise<LoginResponse> {
    return this.request<LoginResponse>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  // Business Line APIs
  async getBusinessLines(params?: { page?: number; size?: number; name?: string; status?: number }): Promise<any> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.name) searchParams.set('name', params.name)
    if (params?.status !== undefined) searchParams.set('status', String(params.status))
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/business-lines${query}`)
  }

  async createBusinessLine(data: any): Promise<any> {
    return this.request('/api/business-lines', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateBusinessLine(id: number, data: any): Promise<any> {
    return this.request(`/api/business-lines/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteBusinessLine(id: number): Promise<void> {
    return this.request(`/api/business-lines/${id}`, {
      method: 'DELETE'
    })
  }

  // Project APIs
  async assignEmailProject(id: number, projectId: number): Promise<void> {
    await this.request<void>(`/api/emails/messages/${id}/project`, {
      method: 'PUT',
      body: JSON.stringify({ projectId })
    })
  }

  async getProjects(params?: { businessLineId?: number; page?: number; size?: number; name?: string; status?: number }): Promise<any> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.businessLineId) searchParams.set('businessLineId', String(params.businessLineId))
    if (params?.name) searchParams.set('name', params.name)
    if (params?.status !== undefined) searchParams.set('status', String(params.status))
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/projects${query}`)
  }

  async getProjectById(id: number): Promise<any> {
    return this.request(`/api/projects/${id}`)
  }

  async getProjectTree(businessLineId?: number): Promise<any> {
    const query = businessLineId ? `?businessLineId=${businessLineId}` : ''
    return this.request(`/api/projects/tree${query}`)
  }

  async createProject(data: any): Promise<any> {
    return this.request('/api/projects', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateProject(id: number, data: any): Promise<any> {
    return this.request(`/api/projects/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteProject(id: number): Promise<void> {
    return this.request(`/api/projects/${id}`, {
      method: 'DELETE'
    })
  }

  // Requirement APIs
  async getRequirements(params?: {
    page?: number
    size?: number
    businessLineId?: number
    projectId?: number
    type?: string
    status?: string
    priority?: string
    title?: string
  }): Promise<any> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.businessLineId) searchParams.set('businessLineId', String(params.businessLineId))
    if (params?.projectId) searchParams.set('projectId', String(params.projectId))
    if (params?.type) searchParams.set('type', params.type)
    if (params?.status) searchParams.set('status', params.status)
    if (params?.priority) searchParams.set('priority', params.priority)
    if (params?.title) searchParams.set('title', params.title)

    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/requirements${query}`)
  }

  async getRequirementOverview(params?: WorkItemOverviewParams): Promise<WorkItemOverviewResponse> {
    const query = this.buildWorkItemOverviewQuery(params)
    return this.request(`/api/requirements/overview${query}`)
  }

  async getRequirementById(id: number): Promise<any> {
    return this.request(`/api/requirements/${id}`)
  }

  async createRequirement(data: any): Promise<any> {
    return this.request('/api/requirements', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRequirement(id: number, data: any): Promise<any> {
    return this.request(`/api/requirements/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteRequirement(id: number): Promise<void> {
    return this.request(`/api/requirements/${id}`, {
      method: 'DELETE'
    })
  }

  async executeRequirementStageAction(id: number, action: string): Promise<any> {
    return this.request(`/api/requirements/${id}/stage-actions`, {
      method: 'POST',
      body: JSON.stringify({ action })
    })
  }

  async getRequirementTransitionInfo(id: number): Promise<any> {
    return this.request(`/api/requirement-transitions/${id}`)
  }

  async submitRequirementEvaluation(data: {
    requirementId: number
    isFeasible: number
    feasibilityDesc?: string
    estimatedWorkload?: number
    estimatedCost?: number
    workBreakdown?: string
    suggestProduct?: number
  }): Promise<any> {
    return this.request('/api/requirement-evaluations', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async getRequirementEvaluation(requirementId: number): Promise<any> {
    return this.request(`/api/requirement-evaluations/by-requirement/${requirementId}`)
  }

  async submitBuDecision(data: {
    requirementId: number
    decision: string
    decisionReason?: string
  }): Promise<any> {
    return this.request('/api/bu-decisions', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async getRequirementDesign(requirementId: number): Promise<any> {
    return this.request(`/api/requirement-designs/${requirementId}`)
  }

  async createRequirementDesign(data: {
    requirementId: number
    prototypeStatus?: string
    uiStatus?: string
    techSolutionStatus?: string
  }): Promise<any> {
    return this.request('/api/requirement-designs', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRequirementDesign(requirementId: number, data: {
    prototypeStatus?: string
    uiStatus?: string
    techSolutionStatus?: string
  }): Promise<any> {
    return this.request(`/api/requirement-designs/${requirementId}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async getRequirementConfirmation(requirementId: number): Promise<any> {
    return this.request(`/api/requirement-confirmations/${requirementId}`)
  }

  async createRequirementConfirmation(data: {
    requirementId: number
    confirmationType: string
    confirmedBy: number
    confirmationNotes?: string
  }): Promise<any> {
    return this.request('/api/requirement-confirmations', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async getRequirementDelivery(requirementId: number): Promise<any> {
    return this.request(`/api/requirement-deliveries/${requirementId}`)
  }

  async getDesignWorkLogs(requirementId: number): Promise<any[]> {
    return this.request(`/api/design-work-logs/requirement/${requirementId}`)
  }

  async createDesignWorkLog(data: {
    requirementId: number
    workType: string
    designerId: number
    estimatedHours?: number
    workContent?: string
    plannedCompletedAt?: string
    status?: string
  }): Promise<any> {
    return this.request('/api/design-work-logs', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateDesignWorkLog(id: number, data: {
    actualHours?: number
    resultUrl?: string
    workContent?: string
    designerId?: number
    estimatedHours?: number
    plannedCompletedAt?: string
    status?: string
  }): Promise<any> {
    return this.request(`/api/design-work-logs/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteDesignWorkLog(id: number): Promise<void> {
    return this.request(`/api/design-work-logs/${id}`, {
      method: 'DELETE'
    })
  }

  async createRequirementDelivery(data: {
    requirementId: number
    deliveredBy: number
    deliveryNotes?: string
  }): Promise<any> {
    return this.request('/api/requirement-deliveries', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async acceptRequirementDelivery(requirementId: number, data: {
    acceptedBy: number
    acceptanceNotes?: string
  }): Promise<any> {
    return this.request(`/api/requirement-deliveries/${requirementId}/accept`, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async getRequirementTasks(requirementId: number): Promise<any[]> {
    return this.request(`/api/tasks/requirement/${requirementId}`)
  }

  async getTaskOverview(params?: {
    projectId?: number
    assigneeId?: number
    status?: string
    keyword?: string
  }): Promise<unknown> {
    const searchParams = new URLSearchParams()
    if (params?.projectId) searchParams.set('projectId', String(params.projectId))
    if (params?.assigneeId) searchParams.set('assigneeId', String(params.assigneeId))
    if (params?.status) searchParams.set('status', params.status)
    if (params?.keyword) searchParams.set('keyword', params.keyword)
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/tasks/overview${query}`)
  }

  async getDefectOverview(params?: WorkItemOverviewParams): Promise<WorkItemOverviewResponse> {
    const query = this.buildWorkItemOverviewQuery(params)
    return this.request(`/api/defects/overview${query}`)
  }

  async getYunxiaoWorkItem(id: string): Promise<WorkItemOverviewItem> {
    return this.request(`/api/yunxiao/workitems/${encodeURIComponent(id)}`)
  }

  private buildWorkItemOverviewQuery(params?: WorkItemOverviewParams): string {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.businessLineId) searchParams.set('businessLineId', String(params.businessLineId))
    if (params?.projectId) searchParams.set('projectId', String(params.projectId))
    if (params?.assigneeId) searchParams.set('assigneeId', String(params.assigneeId))
    if (params?.dataSource) searchParams.set('dataSource', params.dataSource)
    if (params?.normalizedStatus) searchParams.set('normalizedStatus', params.normalizedStatus)
    if (params?.type) searchParams.set('type', params.type)
    if (params?.priority) searchParams.set('priority', params.priority)
    if (params?.keyword) searchParams.set('keyword', params.keyword)
    const value = searchParams.toString()
    return value ? `?${value}` : ''
  }

  async getTask(id: number): Promise<any> {
    return this.request(`/api/tasks/${id}`)
  }

  async createTask(data: {
    requirementId: number
    title: string
    description?: string
    assigneeId?: number
    taskType?: string
    createdBy?: number
    estimatedHours?: number
  }): Promise<any> {
    return this.request('/api/tasks', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateTask(id: number, data: {
    title?: string
    description?: string
    assigneeId?: number
    estimatedHours?: number
    actualHours?: number
    status?: string
  }): Promise<any> {
    return this.request(`/api/tasks/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  // User APIs
  async getUsers(params?: {
    page?: number
    size?: number
    keyword?: string
    username?: string
    realName?: string
    role?: string
    status?: number
  }): Promise<any> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.keyword) searchParams.set('keyword', params.keyword)
    if (params?.username) searchParams.set('username', params.username)
    if (params?.realName) searchParams.set('realName', params.realName)
    if (params?.role) searchParams.set('role', params.role)
    if (params?.status !== undefined) searchParams.set('status', String(params.status))

    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/users${query}`)
  }

  async getUserById(id: number): Promise<any> {
    return this.request(`/api/users/${id}`)
  }

  async createUser(data: any): Promise<any> {
    return this.request('/api/users', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateUser(id: number, data: any): Promise<any> {
    return this.request(`/api/users/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteUser(id: number): Promise<void> {
    return this.request(`/api/users/${id}`, {
      method: 'DELETE'
    })
  }

  // System Management APIs (Menu, Role, Permission)
  async getMenus(): Promise<any[]> {
    return this.request('/api/system/menus')
  }

  async getMenusByRoleId(roleId: number): Promise<any[]> {
    return this.request(`/api/system/menus/role/${roleId}`)
  }

  async assignMenusToRole(roleId: number, menuIds: number[]): Promise<void> {
    return this.request('/api/system/menus/assign', {
      method: 'POST',
      body: JSON.stringify({ roleId, menuIds })
    })
  }

  async getRoles(): Promise<any[]> {
    return this.request('/api/system/roles')
  }

  async createRole(data: { code: string; name: string; description?: string; status?: number }): Promise<any> {
    return this.request('/api/system/roles', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRole(id: number, data: { name: string; description?: string; status?: number }): Promise<void> {
    return this.request(`/api/system/roles/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteRole(id: number): Promise<void> {
    return this.request(`/api/system/roles/${id}`, {
      method: 'DELETE'
    })
  }

  async getRolesByUserId(userId: number): Promise<any[]> {
    return this.request(`/api/system/roles/user/${userId}`)
  }

  async getPermissionsByRoleId(roleId: number): Promise<any[]> {
    return this.request(`/api/system/roles/${roleId}/permissions`)
  }

  async getRoleAuthorization(roleId: number): Promise<{ menuIds: number[]; permissionIds: number[]; dataScope?: string; dataScopeValue?: string }> {
    return this.request(`/api/system/roles/${roleId}/authorization`)
  }

  async assignPermissionsToRole(roleId: number, permissionIds: number[]): Promise<void> {
    return this.request('/api/system/roles/permissions/assign', {
      method: 'POST',
      body: JSON.stringify({ roleId, permissionIds })
    })
  }

  async assignRoleAuthorization(
    roleId: number,
    menuIds: number[],
    permissionIds: number[],
    dataScope?: string,
    dataScopeValue?: string
  ): Promise<void> {
    return this.request('/api/system/roles/authorization/assign', {
      method: 'POST',
      body: JSON.stringify({ roleId, menuIds, permissionIds, dataScope, dataScopeValue })
    })
  }

  async assignRolesToUser(userId: number, roleIds: number[]): Promise<void> {
    return this.request('/api/system/roles/user/assign', {
      method: 'POST',
      body: JSON.stringify({ userId, roleIds })
    })
  }

  async getPermissions(): Promise<any[]> {
    return this.request('/api/system/permissions')
  }

  // Workflow Config APIs
  async getWorkflowConfigs(): Promise<any[]> {
    return this.request('/api/workflow-configs')
  }

  async createWorkflowConfig(data: any): Promise<any> {
    return this.request('/api/workflow-configs', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateWorkflowConfig(id: number, data: any): Promise<any> {
    return this.request(`/api/workflow-configs/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteWorkflowConfig(id: number): Promise<void> {
    return this.request(`/api/workflow-configs/${id}`, {
      method: 'DELETE'
    })
  }

  async getWorkflowConfigsByType(requirementType: string): Promise<any[]> {
    return this.request(`/api/workflow-configs/type/${requirementType}`)
  }

  async getWorkflowStatusOptions(): Promise<Record<string, string[]>> {
    return this.request('/api/workflow-configs/meta/status-options')
  }

  async getNextStatuses(requirementType: string, currentStatus: string): Promise<string[]> {
    return this.request(`/api/workflow-configs/next-statuses?requirementType=${requirementType}&currentStatus=${currentStatus}`)
  }

  // Statistics APIs
  async getStatistics(): Promise<any> {
    return this.request('/api/statistics/dashboard')
  }

  // Customer Contact APIs
  async getCustomerContacts(projectId?: number): Promise<any[]> {
    const query = projectId ? `?projectId=${projectId}` : ''
    const result = await this.request<any>(`/api/customer-contacts${query}`)
    if (Array.isArray(result)) return result
    if (Array.isArray(result?.records)) return result.records
    if (Array.isArray(result?.data?.records)) return result.data.records
    if (Array.isArray(result?.data)) return result.data
    return []
  }

  async getCustomerContactPage(params?: {
    page?: number
    size?: number
    projectId?: number
    name?: string
    isActive?: number
  }): Promise<any> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.projectId) searchParams.set('projectId', String(params.projectId))
    if (params?.name) searchParams.set('name', params.name)
    if (params?.isActive !== undefined) searchParams.set('isActive', String(params.isActive))
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request(`/api/customer-contacts${query}`)
  }

  async createCustomerContact(data: any): Promise<any> {
    return this.request('/api/customer-contacts', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateCustomerContact(id: number, data: any): Promise<any> {
    return this.request(`/api/customer-contacts/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteCustomerContact(id: number): Promise<void> {
    return this.request(`/api/customer-contacts/${id}`, {
      method: 'DELETE'
    })
  }

  async getSalesOpportunities(params?: { keyword?: string; type?: string; status?: string; owner?: string; businessLine?: string }): Promise<any[]> {
    const query = new URLSearchParams()
    Object.entries(params || {}).forEach(([key, value]) => { if (value) query.set(key, value) })
    const result = await this.request<any>(`/api/sales-opportunities${query.toString() ? `?${query}` : ''}`)
    return Array.isArray(result) ? result : Array.isArray(result?.data) ? result.data : []
  }

  async createSalesOpportunity(data: any): Promise<any> { return this.request('/api/sales-opportunities', { method: 'POST', body: JSON.stringify(data) }) }
  async updateSalesOpportunity(id: number, data: any): Promise<any> { return this.request(`/api/sales-opportunities/${id}`, { method: 'PUT', body: JSON.stringify(data) }) }
  async deleteSalesOpportunity(id: number): Promise<void> { return this.request(`/api/sales-opportunities/${id}`, { method: 'DELETE' }) }
  async getSalesOpportunityFollowUps(id: number): Promise<SalesOpportunityFollowUp[]> {
    const result = await this.request<any>(`/api/sales-opportunities/${id}/follow-ups`)
    return Array.isArray(result) ? result : Array.isArray(result?.data) ? result.data : []
  }
  async createSalesOpportunityFollowUp(id: number, data: SalesOpportunityFollowUpPayload): Promise<SalesOpportunityFollowUp> {
    return this.request<SalesOpportunityFollowUp>(`/api/sales-opportunities/${id}/follow-ups`, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }
  async getSalesOpportunitySupportWorklogs(id: number): Promise<any[]> {
    const result = await this.request<any>(`/api/sales-opportunities/${id}/support-worklogs`)
    return Array.isArray(result) ? result : Array.isArray(result?.data) ? result.data : []
  }
  async createSalesOpportunitySupportWorklog(id: number, data: any): Promise<any> {
    return this.request(`/api/sales-opportunities/${id}/support-worklogs`, { method: 'POST', body: JSON.stringify(data) })
  }

  // Project Member APIs
  async getProjectMembers(projectId: number): Promise<any[]> {
    return this.request(`/api/project-members/by-project?projectId=${projectId}`)
  }

  async addProjectMember(data: { projectId: number; userId: number; role?: string }): Promise<any> {
    return this.request('/api/project-members', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async removeProjectMember(projectId: number, userId: number): Promise<void> {
    return this.request(`/api/project-members?projectId=${projectId}&userId=${userId}`, {
      method: 'DELETE'
    })
  }

  // BU execution dashboard
  async getBuDashboard<T>(params?: {
    startDate?: string
    endDate?: string
    planWindowWorkdays?: number
  }): Promise<T> {
    const searchParams = new URLSearchParams()
    if (params?.startDate) searchParams.set('startDate', params.startDate)
    if (params?.endDate) searchParams.set('endDate', params.endDate)
    if (params?.planWindowWorkdays) {
      searchParams.set('planWindowWorkdays', String(params.planWindowWorkdays))
    }
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request<T>(`/api/bu-dashboard${query}`)
  }

  async createBuDirection(data: BuDirectionPayload): Promise<unknown> {
    return this.request<unknown>('/api/bu-directions', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateBuDirection(id: number, data: BuDirectionPayload): Promise<unknown> {
    return this.request<unknown>(`/api/bu-directions/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteBuDirection(id: number): Promise<void> {
    return this.request(`/api/bu-directions/${id}`, {
      method: 'DELETE'
    })
  }

  async getKeyMatterAccess(): Promise<KeyMatterAccess> {
    const access = await this.request<unknown>('/api/key-matters/access')
    return normalizeKeyMatterAccess(access)
  }

  async getKeyMatters(params?: {
    keyword?: string
    status?: string
    priority?: string
    ownerId?: number
    projectId?: number
  }): Promise<BuKeyMatter[]> {
    const searchParams = new URLSearchParams()
    if (params?.keyword) searchParams.set('keyword', params.keyword)
    if (params?.status) searchParams.set('status', params.status)
    if (params?.priority) searchParams.set('priority', params.priority)
    if (params?.ownerId) searchParams.set('ownerId', String(params.ownerId))
    if (params?.projectId !== undefined) searchParams.set('projectId', String(params.projectId))
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request<BuKeyMatter[]>(`/api/key-matters${query}`)
  }

  async getKeyMatter(id: number): Promise<BuKeyMatter> {
    return this.request<BuKeyMatter>(`/api/key-matters/${id}`)
  }

  async getKeyMatterMeeting(weekStartDate: string): Promise<BuKeyMatter[]> {
    return this.request<BuKeyMatter[]>(
      `/api/key-matters/meeting?weekStartDate=${encodeURIComponent(weekStartDate)}`
    )
  }

  async createKeyMatter(data: BuKeyMatterPayload): Promise<BuKeyMatter> {
    return this.request<BuKeyMatter>('/api/key-matters', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateKeyMatter(id: number, data: BuKeyMatterPayload): Promise<BuKeyMatter> {
    return this.request<BuKeyMatter>(`/api/key-matters/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteKeyMatter(id: number): Promise<void> {
    return this.request(`/api/key-matters/${id}`, { method: 'DELETE' })
  }

  async upsertKeyMatterWeeklyUpdate(
    id: number,
    weekStartDate: string,
    data: BuKeyMatterWeeklyUpdatePayload
  ): Promise<BuKeyMatterWeeklyUpdate> {
    return this.request<BuKeyMatterWeeklyUpdate>(
      `/api/key-matters/${id}/weekly-updates/${weekStartDate}`,
      { method: 'PUT', body: JSON.stringify(data) }
    )
  }

  async deleteKeyMatterWeeklyUpdate(id: number, weekStartDate: string): Promise<void> {
    return this.request(
      `/api/key-matters/${id}/weekly-updates/${weekStartDate}`,
      { method: 'DELETE' }
    )
  }

  async getYunxiaoStatus<T>(): Promise<T> {
    return this.request<T>('/api/yunxiao/status')
  }

  async getYunxiaoAnalysis<T>(): Promise<T> {
    return this.request<T>('/api/yunxiao/analysis')
  }

  async updateYunxiaoConfig<T>(data: YunxiaoConfigPayload): Promise<T> {
    return this.request<T>('/api/yunxiao/config', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async testYunxiaoConnection(): Promise<YunxiaoConnectionTestResult> {
    return this.request<YunxiaoConnectionTestResult>('/api/yunxiao/connection-test', {
      method: 'POST'
    })
  }

  async getYunxiaoProjectMappings(): Promise<YunxiaoProjectMapping[]> {
    return this.request<YunxiaoProjectMapping[]>('/api/yunxiao/project-mappings')
  }

  async getYunxiaoProjects(): Promise<YunxiaoProjectOption[]> {
    return this.request<YunxiaoProjectOption[]>('/api/yunxiao/projects')
  }

  async getYunxiaoMembers(): Promise<YunxiaoMemberOption[]> {
    return this.request<YunxiaoMemberOption[]>('/api/yunxiao/members')
  }

  async saveYunxiaoProjectMapping(
    data: YunxiaoProjectMappingPayload
  ): Promise<YunxiaoProjectMapping> {
    return this.request<YunxiaoProjectMapping>('/api/yunxiao/project-mappings', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async deleteYunxiaoProjectMapping(id: number): Promise<void> {
    return this.request(`/api/yunxiao/project-mappings/${id}`, {
      method: 'DELETE'
    })
  }

  async getYunxiaoUserMappings(): Promise<YunxiaoUserMapping[]> {
    return this.request<YunxiaoUserMapping[]>('/api/yunxiao/user-mappings')
  }

  async saveYunxiaoUserMapping(data: YunxiaoUserMappingPayload): Promise<YunxiaoUserMapping> {
    return this.request<YunxiaoUserMapping>('/api/yunxiao/user-mappings', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async deleteYunxiaoUserMapping(id: number): Promise<void> {
    return this.request(`/api/yunxiao/user-mappings/${id}`, {
      method: 'DELETE'
    })
  }

  async syncYunxiao(): Promise<string[]> {
    return this.request('/api/yunxiao/sync', { method: 'POST' })
  }

  async saveYunxiaoWorklogExemption(data: {
    userId: number
    workDate: string
    reason: string
  }): Promise<unknown> {
    return this.request<unknown>('/api/yunxiao/worklog-exemptions', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  // Personal email management (all endpoints resolve ownership from JWT)
  async getEmailAccount(): Promise<EmailAccount> {
    return this.request<EmailAccount>('/api/emails/account')
  }

  async saveEmailAccount(data: EmailAccountPayload): Promise<EmailAccount> {
    return this.request<EmailAccount>('/api/emails/account', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async testEmailAccount(): Promise<EmailConnectionTestResult> {
    return this.request<EmailConnectionTestResult>('/api/emails/account/test', {
      method: 'POST'
    })
  }

  async removeEmailAccount(): Promise<void> {
    return this.request<void>('/api/emails/account', { method: 'DELETE' })
  }

  async getEmailMessages(params?: EmailMessageQuery): Promise<EmailMessagePage> {
    const searchParams = new URLSearchParams()
    if (params?.page) searchParams.set('page', String(params.page))
    if (params?.size) searchParams.set('size', String(params.size))
    if (params?.date) searchParams.set('date', params.date)
    if (params?.keyword) searchParams.set('keyword', params.keyword)
    if (params?.projectId) searchParams.set('projectId', String(params.projectId))
    if (params?.ungrouped) searchParams.set('ungrouped', 'true')
    if (params?.senderDomain) searchParams.set('senderDomain', params.senderDomain)
    const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
    return this.request<EmailMessagePage>(`/api/emails/messages${query}`)
  }

  async getEmailProjectGroups(): Promise<EmailProjectGroup[]> {
    return this.request<EmailProjectGroup[]>('/api/emails/project-groups')
  }

  async getEmailSenderCompanyGroups(): Promise<EmailSenderCompanyGroup[]> {
    return this.request<EmailSenderCompanyGroup[]>('/api/emails/sender-company-groups')
  }

  async startEmailGrouping(regroupAll = false): Promise<EmailGroupingJobStatus> {
    return this.request<EmailGroupingJobStatus>(`/api/emails/grouping?regroupAll=${regroupAll}`, { method: 'POST' })
  }

  async getEmailGroupingStatus(): Promise<EmailGroupingJobStatus> {
    return this.request<EmailGroupingJobStatus>('/api/emails/grouping/status')
  }

  async getEmailMessage(id: number): Promise<EmailMessageDetail> {
    return this.request<EmailMessageDetail>(`/api/emails/messages/${id}`)
  }

  async getEmailInterpretation(id: number): Promise<EmailInterpretation> {
    return this.request<EmailInterpretation>(`/api/emails/messages/${id}/interpretation`)
  }

  async generateEmailInterpretation(id: number): Promise<EmailInterpretation> {
    return this.request<EmailInterpretation>(`/api/emails/messages/${id}/interpretation`, { method: 'POST' })
  }

  async getEmailDigest(date: string): Promise<EmailDailyDigest> {
    return this.request<EmailDailyDigest>(`/api/emails/digests?date=${encodeURIComponent(date)}`)
  }

  async regenerateEmailDigest(date: string): Promise<EmailDailyDigest | EmailSyncStatus> {
    return this.request<EmailDailyDigest | EmailSyncStatus>(
      `/api/emails/digests/${encodeURIComponent(date)}/regenerate`,
      { method: 'POST' }
    )
  }

  async startEmailSync(): Promise<EmailSyncStatus> {
    return this.request<EmailSyncStatus>('/api/emails/sync', { method: 'POST' })
  }

  async getEmailSyncStatus(): Promise<EmailSyncStatus> {
    return this.request<EmailSyncStatus>('/api/emails/sync/status')
  }

  async getEmailWeComMapping(): Promise<EmailWeComMapping> {
    return this.request<EmailWeComMapping>('/api/emails/wecom-mapping')
  }

  async saveEmailWeComMapping(weComUserId: string, enabled = true): Promise<EmailWeComMapping> {
    return this.request<EmailWeComMapping>('/api/emails/wecom-mapping', {
      method: 'PUT',
      body: JSON.stringify({ weComUserId, enabled })
    })
  }

  async getSystemConfigGroups(): Promise<SystemConfigGroupSummary[]> {
    return this.request<SystemConfigGroupSummary[]>('/api/system/configs')
  }

  async getSystemConfigGroup(groupCode: string): Promise<SystemConfigGroup> {
    return this.request<SystemConfigGroup>(`/api/system/configs/${encodeURIComponent(groupCode)}`)
  }

  async saveSystemConfigGroup(groupCode: string, values: Record<string, string>): Promise<SystemConfigGroup> {
    return this.request<SystemConfigGroup>(`/api/system/configs/${encodeURIComponent(groupCode)}`, {
      method: 'PUT',
      body: JSON.stringify({ values })
    })
  }

  async testSystemConfigIntegration(groupCode: string, integration: string): Promise<SystemConfigTestResult> {
    return this.request<SystemConfigTestResult>(
      `/api/system/configs/${encodeURIComponent(groupCode)}/${encodeURIComponent(integration)}/test`,
      { method: 'POST' }
    )
  }

  // ==================== OA 集成 (Seeyon) ====================

  async getSeeyonOaStatus<T>(): Promise<T> {
    return this.request<T>('/api/seeyon-oa/status')
  }

  async updateSeeyonOaConfig<T>(data: SeeyonOaConfigPayload): Promise<T> {
    return this.request<T>('/api/seeyon-oa/config', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async testSeeyonOaConnection(): Promise<SeeyonOaConnectionTestResult> {
    return this.request<SeeyonOaConnectionTestResult>('/api/seeyon-oa/connection-test', {
      method: 'POST'
    })
  }

  async getSeeyonOaMembers(departmentId?: string): Promise<SeeyonOaMemberOption[]> {
    const query = departmentId ? `?departmentId=${encodeURIComponent(departmentId)}` : ''
    return this.request<SeeyonOaMemberOption[]>(`/api/seeyon-oa/members${query}`)
  }

  async getSeeyonOaDepartments(): Promise<SeeyonOaDepartmentOption[]> {
    return this.request<SeeyonOaDepartmentOption[]>('/api/seeyon-oa/departments')
  }

  async getSeeyonOaPendingAffairs(): Promise<any[]> {
    return this.request<any[]>('/api/seeyon-oa/affairs/pending')
  }

  async getSeeyonOaDoneAffairs(): Promise<any[]> {
    return this.request<any[]>('/api/seeyon-oa/affairs/done')
  }

  async syncSeeyonOa(): Promise<string[]> {
    return this.request<string[]>('/api/seeyon-oa/sync', { method: 'POST' })
  }

  // 当前用户菜单授权（角色管理配置生效）
  async getMyMenus(): Promise<{ paths: string[]; managedPaths: string[] }> {
    return this.request<{ paths: string[]; managedPaths: string[] }>('/api/auth/my-menus')
  }

  // Revenue management APIs（工时与成本）
  async getRevenueMatrix(year: number): Promise<RevenueMatrix> {
    return this.request<RevenueMatrix>(`/api/revenue/matrix?year=${year}`)
  }

  async getRevenueCellDetail(yearMonth: string, businessLineId: number, rowKey: string): Promise<RevenueCellDetail> {
    const query = new URLSearchParams({ yearMonth, businessLineId: String(businessLineId), rowKey })
    return this.request<RevenueCellDetail>(`/api/revenue/cell-detail?${query}`)
  }

  async importRevenueWorklog(file: File, yearMonth: string): Promise<RevenueImportResult> {
    const body = new FormData()
    body.append('file', file)
    return this.request<RevenueImportResult>(`/api/revenue/import/worklog?yearMonth=${yearMonth}`, {
      method: 'POST',
      body
    })
  }

  async importRevenueCost(file: File): Promise<RevenueImportResult> {
    const body = new FormData()
    body.append('file', file)
    return this.request<RevenueImportResult>('/api/revenue/import/cost', {
      method: 'POST',
      body
    })
  }

  async getRevenueImportBatches(importType?: string): Promise<RevenueImportBatch[]> {
    const query = importType ? `?importType=${encodeURIComponent(importType)}` : ''
    return this.request<RevenueImportBatch[]>(`/api/revenue/imports${query}`)
  }

  async closeRevenueMonth(yearMonth: string): Promise<void> {
    await this.request<void>(`/api/revenue/months/${yearMonth}/close`, { method: 'POST' })
  }

  async reopenRevenueMonth(yearMonth: string): Promise<void> {
    await this.request<void>(`/api/revenue/months/${yearMonth}/reopen`, { method: 'POST' })
  }

  async getRevenueEstimates(yearMonth?: string): Promise<RevenueEstimateEntry[]> {
    const query = yearMonth ? `?yearMonth=${encodeURIComponent(yearMonth)}` : ''
    return this.request<RevenueEstimateEntry[]>(`/api/revenue/estimates${query}`)
  }

  async createRevenueEstimate(data: Partial<RevenueEstimateEntry>): Promise<RevenueEstimateEntry> {
    return this.request<RevenueEstimateEntry>('/api/revenue/estimates', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRevenueEstimate(id: number, data: Partial<RevenueEstimateEntry>): Promise<RevenueEstimateEntry> {
    return this.request<RevenueEstimateEntry>(`/api/revenue/estimates/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteRevenueEstimate(id: number): Promise<void> {
    await this.request<void>(`/api/revenue/estimates/${id}`, { method: 'DELETE' })
  }

  async getRevenuePending(): Promise<{ worklog: RevenueWorklogEntry[]; cost: RevenueCostEntry[] }> {
    return this.request<{ worklog: RevenueWorklogEntry[]; cost: RevenueCostEntry[] }>('/api/revenue/pending')
  }

  async resolveRevenuePending(type: 'worklog' | 'cost', id: number, businessLineId: number, projectId?: number): Promise<void> {
    await this.request<void>(`/api/revenue/pending/${type}/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ businessLineId, projectId: projectId ?? null })
    })
  }

  async getRevenueSalesProjects(): Promise<RevenueSalesProject[]> {
    return this.request<RevenueSalesProject[]>('/api/revenue/sales-projects')
  }

  async bindRevenueSalesProject(id: number, opportunityId: number | null): Promise<void> {
    await this.request<void>(`/api/revenue/sales-projects/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ opportunityId })
    })
  }

  async getRevenueOpportunityOptions(): Promise<RevenueOpportunityOption[]> {
    return this.request<RevenueOpportunityOption[]>('/api/revenue/opportunity-options')
  }

  async createRevenueWorklogEntry(data: Partial<RevenueWorklogEntry>): Promise<RevenueWorklogEntry> {
    return this.request<RevenueWorklogEntry>('/api/revenue/worklog-entries', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRevenueWorklogEntry(id: number, data: Partial<RevenueWorklogEntry>): Promise<RevenueWorklogEntry> {
    return this.request<RevenueWorklogEntry>(`/api/revenue/worklog-entries/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteRevenueWorklogEntry(id: number): Promise<void> {
    await this.request<void>(`/api/revenue/worklog-entries/${id}`, { method: 'DELETE' })
  }

  async createRevenueCostEntry(data: Partial<RevenueCostEntry>): Promise<RevenueCostEntry> {
    return this.request<RevenueCostEntry>('/api/revenue/cost-entries', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateRevenueCostEntry(id: number, data: Partial<RevenueCostEntry>): Promise<RevenueCostEntry> {
    return this.request<RevenueCostEntry>(`/api/revenue/cost-entries/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteRevenueCostEntry(id: number): Promise<void> {
    await this.request<void>(`/api/revenue/cost-entries/${id}`, { method: 'DELETE' })
  }

  // ---------- 交付与利润（项目交付营收看板） ----------

  async getDeliverySummary(params: { year: number; includeEstimate?: boolean }): Promise<DeliverySummary> {
    const query = new URLSearchParams({
      year: String(params.year),
      includeEstimate: params.includeEstimate === false ? 'false' : 'true'
    })
    return this.request<DeliverySummary>(`/api/revenue/delivery/summary?${query}`)
  }

  async getDeliveryPlans(params?: { year?: number; businessLineId?: number; projectId?: number | null }): Promise<DeliveryPlan[]> {
    const searchParams = new URLSearchParams()
    if (params?.year != null) searchParams.set('year', String(params.year))
    if (params?.businessLineId != null) searchParams.set('businessLineId', String(params.businessLineId))
    if (params?.projectId != null) searchParams.set('projectId', String(params.projectId))
    const query = searchParams.toString()
    return this.request<DeliveryPlan[]>(`/api/revenue/delivery-plans${query ? `?${query}` : ''}`)
  }

  async createDeliveryPlansBatch(data: {
    businessLineId: number
    projectId?: number | null
    year: number
    rows: Array<{ yearMonth: string; amountYuan: number; personMonths: number }>
  }): Promise<DeliveryPlan[]> {
    return this.request<DeliveryPlan[]>('/api/revenue/delivery-plans/batch', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateDeliveryPlan(id: number, data: { yearMonth: string; amountYuan: number; personMonths: number }): Promise<DeliveryPlan> {
    return this.request<DeliveryPlan>(`/api/revenue/delivery-plans/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteDeliveryPlan(id: number): Promise<void> {
    await this.request<void>(`/api/revenue/delivery-plans/${id}`, { method: 'DELETE' })
  }

  async getOtherCosts(params?: { year?: number; businessLineId?: number; projectId?: number | null }): Promise<DeliveryOtherCost[]> {
    const searchParams = new URLSearchParams()
    if (params?.year != null) searchParams.set('year', String(params.year))
    if (params?.businessLineId != null) searchParams.set('businessLineId', String(params.businessLineId))
    if (params?.projectId != null) searchParams.set('projectId', String(params.projectId))
    const query = searchParams.toString()
    return this.request<DeliveryOtherCost[]>(`/api/revenue/other-costs${query ? `?${query}` : ''}`)
  }

  async createOtherCost(data: {
    yearMonth: string
    businessLineId: number
    projectId?: number | null
    costType: DeliveryOtherCost['costType']
    amountYuan: number
    note?: string
  }): Promise<DeliveryOtherCost> {
    return this.request<DeliveryOtherCost>('/api/revenue/other-costs', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  }

  async updateOtherCost(id: number, data: {
    yearMonth: string
    costType: DeliveryOtherCost['costType']
    amountYuan: number
    note?: string
  }): Promise<DeliveryOtherCost> {
    return this.request<DeliveryOtherCost>(`/api/revenue/other-costs/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  async deleteOtherCost(id: number): Promise<void> {
    await this.request<void>(`/api/revenue/other-costs/${id}`, { method: 'DELETE' })
  }

  async importDeliveryContracts(file: File): Promise<DeliveryContractImportResult> {
    const body = new FormData()
    body.append('file', file)
    return this.request<DeliveryContractImportResult>('/api/revenue/contracts/import', {
      method: 'POST',
      body
    })
  }

  async getPendingDeliveryContracts(): Promise<DeliveryPendingContract[]> {
    return this.request<DeliveryPendingContract[]>('/api/revenue/contracts/pending')
  }

  /** 待映射合同归属：projectId=落具体项目；否则 businessLineId=业务线级（不落项目，后端合同契约支持） */
  async resolvePendingDeliveryContract(id: number, projectId?: number | null, businessLineId?: number | null): Promise<void> {
    const body: Record<string, number> = {}
    if (projectId != null) body.projectId = projectId
    if (businessLineId != null) body.businessLineId = businessLineId
    await this.request<void>(`/api/revenue/contracts/pending/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify(body)
    })
  }

  async getDeliveryContractBatches(): Promise<DeliveryContractBatch[]> {
    return this.request<DeliveryContractBatch[]>('/api/revenue/contracts/batches')
  }

  async getMappedDeliveryContracts(year: number): Promise<DeliveryMappedContract[]> {
    return this.request<DeliveryMappedContract[]>(`/api/revenue/contracts/mapped?year=${year}`)
  }

  async updateDeliveryContractMapping(id: number, data: { businessLineId: number; projectId: number | null }): Promise<void> {
    await this.request<void>(`/api/revenue/contracts/${id}/mapping`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  }

  /** 项目历史完结单价（元/人月）；无历史或接口不可用时返回 null */
  async getDeliveryUnitPrice(projectId: number): Promise<number | null> {
    const payload = await this.request<unknown>(`/api/revenue/estimates/unit-price?projectId=${projectId}`)
    if (payload == null) return null
    if (typeof payload === 'number') return payload
    const record = payload as { unitPrice?: unknown; amount?: unknown; price?: unknown }
    const raw = record.unitPrice ?? record.price ?? record.amount
    const value = typeof raw === 'number' ? raw : Number(raw ?? NaN)
    return Number.isFinite(value) && value > 0 ? value : null
  }

  // AI 助手 (Pi Agent) APIs
  async getAiAgentSessions(): Promise<AiAgentSessionSummary[]> {
    return this.request<AiAgentSessionSummary[]>('/api/ai-agent/sessions')
  }

  async createAiAgentSession(payload?: { title?: string; provider?: string; model?: string }): Promise<AiAgentSession> {
    return this.request<AiAgentSession>('/api/ai-agent/sessions', {
      method: 'POST',
      body: JSON.stringify(payload ?? {})
    })
  }

  async getAiAgentSession(id: number): Promise<AiAgentSession> {
    return this.request<AiAgentSession>(`/api/ai-agent/sessions/${id}`)
  }

  async deleteAiAgentSession(id: number): Promise<void> {
    return this.request<void>(`/api/ai-agent/sessions/${id}`, {
      method: 'DELETE'
    })
  }

  /**
   * 发送消息并消费 SSE 流（POST + Authorization 头，EventSource 不可用）。
   * 每个事件回调一次 onEvent；流正常结束或 HTTP 层出错时 resolve/reject。
   * signal 用于中止请求（用户点击停止），中止时抛出 AbortError。
   */
  async streamAiAgentRun(
    sessionId: number,
    content: string,
    onEvent: (event: AiAgentStreamEvent) => void,
    signal?: AbortSignal
  ): Promise<void> {
    const url = `${this.baseUrl}/api/ai-agent/sessions/${sessionId}/messages`
    const token = this.getToken()
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) headers['Authorization'] = `Bearer ${token}`

    let response: Response
    try {
      response = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify({ content }),
        signal
      })
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') throw err
      throw new ApiRequestError(err instanceof Error ? err.message : '网络请求失败', 0)
    }

    if (!response.ok) {
      const errorText = await response.text()
      let errorMessage = `HTTP error! status: ${response.status}`
      let errorCode: number | undefined
      try {
        const parsed: unknown = errorText ? JSON.parse(errorText) : null
        if (parsed && typeof parsed === 'object') {
          const errorBody = parsed as { message?: unknown; code?: unknown }
          if (typeof errorBody.message === 'string' && errorBody.message) errorMessage = errorBody.message
          if (typeof errorBody.code === 'number') errorCode = errorBody.code
        }
      } catch {
        if (errorText) errorMessage = errorText
      }
      if (response.status === 401) this.clearAuthAndRedirect()
      throw new ApiRequestError(errorMessage, response.status, errorCode)
    }

    if (!response.body) {
      throw new ApiRequestError('当前浏览器不支持流式响应', 0)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    const dispatchBlock = (block: string) => {
      const event = parseSseEvent(block)
      if (event) onEvent(event)
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let separator: number
        while ((separator = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, separator)
          buffer = buffer.slice(separator + 2)
          dispatchBlock(block)
        }
      }
      // 处理流末尾不完整/无换行结尾的最后一个 block
      buffer += decoder.decode()
      if (buffer.trim()) dispatchBlock(buffer)
    } finally {
      reader.releaseLock()
    }
  }
}

/**
 * 解析单个 SSE block（event: 与 data: 行；data 仅单行 JSON）。
 * 事件名无法识别或 data 无法解析时返回 null（调用方忽略该块）。
 */
function parseSseEvent(block: string): AiAgentStreamEvent | null {
  let eventName = ''
  const dataLines: string[] = []
  for (const rawLine of block.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }
  if (!eventName || dataLines.length === 0) return null

  let payload: unknown
  try {
    payload = JSON.parse(dataLines.join('\n'))
  } catch {
    return null
  }
  const record = payload && typeof payload === 'object'
    ? (payload as Record<string, unknown>)
    : {}

  switch (eventName) {
    case 'run_start':
      return {
        type: 'run_start',
        runId: typeof record.runId === 'string' ? record.runId : ''
      }
    case 'message_start':
      return {
        type: 'message_start',
        index: typeof record.index === 'number' ? record.index : NaN
      }
    case 'message_delta': {
      const delta = record.delta && typeof record.delta === 'object'
        ? (record.delta as { type?: unknown; text?: unknown })
        : null
      if (!delta || (delta.type !== 'text_delta' && delta.type !== 'thinking_delta')) return null
      return {
        type: 'message_delta',
        index: typeof record.index === 'number' ? record.index : NaN,
        delta: {
          type: delta.type,
          text: typeof delta.text === 'string' ? delta.text : ''
        }
      }
    }
    case 'message_end': {
      const message = record.message && typeof record.message === 'object'
        ? (record.message as AiAgentMessage)
        : {}
      return { type: 'message_end', message }
    }
    case 'tool_execution_start':
      return {
        type: 'tool_execution_start',
        toolCallId: typeof record.toolCallId === 'string' ? record.toolCallId : '',
        toolName: typeof record.toolName === 'string' ? record.toolName : '未知工具',
        args: record.args
      }
    case 'tool_execution_end':
      return {
        type: 'tool_execution_end',
        toolCallId: typeof record.toolCallId === 'string' ? record.toolCallId : '',
        result: record.result,
        isError: record.isError === true
      }
    case 'run_end':
      return {
        type: 'run_end',
        newMessages: Array.isArray(record.newMessages) ? (record.newMessages as AiAgentMessage[]) : []
      }
    case 'error':
      return {
        type: 'error',
        code: typeof record.code === 'number' || typeof record.code === 'string' ? record.code : undefined,
        message: typeof record.message === 'string' && record.message ? record.message : '请求失败'
      }
    default:
      return null
  }
}

export const api = new ApiService()
export default api
