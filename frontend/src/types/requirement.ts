// 需求状态枚举
export type RequirementStatus =
  | '待评估'
  | '评估中'
  | '已拒绝'
  | '待设计'
  | '设计中'
  | '待确认'
  | '开发中'
  | '测试中'
  | '待上线'
  | '已上线'
  | '已交付'
  | '已验收'

// 需求类型
export type RequirementType = '产品需求' | '项目需求'

// 优先级
export type Priority = '高' | '中' | '低'

// 需求来源
export type Source = '内部' | '外部'

export interface Evaluation {
  isFeasible: boolean
  estimatedWorkload: string
  estimatedCost?: string
  estimatedOnlineDate: string
  evaluator: string
}

export interface DesignWork {
  type: '原型设计' | 'UI设计' | '技术方案'
  estimatedHours?: number
  actualHours?: number
  designer?: string
  status: 'pending' | 'in-progress' | 'done'
  statusText: string
}

export interface Task {
  id: string
  title: string
  type: string
  assignee: string
  estimatedHours?: number
  status: '待开始' | '进行中' | '已完成' | '已测试'
}

export interface Attachment {
  id: string
  name: string
  size: string
  type: 'image' | 'doc'
  url?: string
}

export interface ActivityLog {
  id: string
  action: string
  user: string
  time: string
}

export interface Requirement {
  id: string
  reqNo: string
  title: string
  project: string
  subProject?: string
  businessLine: string
  type: RequirementType
  status: RequirementStatus
  statusClass: string
  priority: Priority
  priorityClass: string
  owner: string
  createdAt: string
  source: Source
  requester?: string
  expectDate?: string
  description?: string
  attachments?: Attachment[]
  logs?: ActivityLog[]
  evaluation?: Evaluation
  designWorks?: DesignWork[]
  tasks?: Task[]
}
