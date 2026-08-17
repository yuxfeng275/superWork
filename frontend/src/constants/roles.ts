export type PositionRoleCategory = 'management' | 'execution'

export interface PositionRoleOption {
  value: string
  label: string
  category: PositionRoleCategory
  categoryLabel: string
  description: string
  legacyFrom?: string
}

const MANAGEMENT = '管理序列'
const EXECUTION = '执行序列'

export const POSITION_ROLE_OPTIONS: PositionRoleOption[] = [
  {
    value: 'DIRECTOR',
    label: '总监',
    category: 'management',
    categoryLabel: MANAGEMENT,
    description: '部门第一负责人，对整体经营结果、能力建设质量负责'
  },
  {
    value: 'DEPUTY_DIRECTOR',
    label: '副总监',
    category: 'management',
    categoryLabel: MANAGEMENT,
    description: '协助总监统筹日常运营与专项体系建设'
  },
  {
    value: 'BUSINESS_OWNER',
    label: '经营负责人',
    category: 'management',
    categoryLabel: MANAGEMENT,
    description: '业务发展一号位，对业务域经营结果负责',
    legacyFrom: 'BU负责人、大项目经理'
  },
  {
    value: 'EFFECTIVENESS_OWNER',
    label: '成效负责人',
    category: 'management',
    categoryLabel: MANAGEMENT,
    description: '服务支撑一号位，对服务域成效结果负责',
    legacyFrom: 'BU负责人、大项目经理'
  },
  {
    value: 'SOLUTION_MANAGER',
    label: '解决方案经理',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责客户需求、方案、计划、实施复核与项目交付',
    legacyFrom: '项目经理/经理/主管、产品经理、前端工程师/测试工程师'
  },
  {
    value: 'TECH_ARCHITECT',
    label: '技术架构师',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责技术栈设计、规划、实施、升级与技术赋能',
    legacyFrom: '架构师、资深后端、资深数据工程师'
  },
  {
    value: 'FULL_STACK_ENGINEER',
    label: '全栈工程师',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责需求、方案、开发、自测、交付、监控维护完整链路',
    legacyFrom: '前端、后端、测试开发'
  },
  {
    value: 'QUALITY_ENGINEER',
    label: '质量工程师',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责测试体系搭建、验收执行与全链路质量保障',
    legacyFrom: '测试、项目经理'
  },
  {
    value: 'AI_OPERATIONS_ENGINEER',
    label: '智能运营工程师',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责业务运营、数据分析与 AI 工具落地推广',
    legacyFrom: 'BI、产品运营、数据产品'
  },
  {
    value: 'AI_CUSTOMER_SERVICE',
    label: '智能客服专员',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责全渠道客户咨询、反馈、报修、SLA 与知识库',
    legacyFrom: '客服'
  },
  {
    value: 'EXPERIENCE_CONTENT_DESIGNER',
    label: '体验与内容设计师',
    category: 'execution',
    categoryLabel: EXECUTION,
    description: '负责 UI、交互、平面与内容设计交付，沉淀设计规范',
    legacyFrom: '美工、前端体验、运营设计'
  }
]

export const POSITION_ROLE_LABELS = POSITION_ROLE_OPTIONS.reduce<Record<string, string>>((labels, role) => {
  labels[role.value] = role.label
  return labels
}, {})

export const LEGACY_ROLE_LABELS: Record<string, string> = {
  BU_ADMIN: 'BU管理员',
  PM: '项目经理',
  TECH_MANAGER: '技术经理',
  PRODUCT: '产品经理',
  PRODUCT_MANAGER: '产品经理',
  UI_DESIGN: 'UI设计',
  UI_DESIGNER: 'UI设计',
  DEVELOPER: '开发',
  TESTER: '测试'
}

export const POSITION_ROLE_BADGE_CLASS: Record<string, string> = {
  DIRECTOR: 'red',
  DEPUTY_DIRECTOR: 'yellow',
  BUSINESS_OWNER: 'green',
  EFFECTIVENESS_OWNER: 'blue',
  SOLUTION_MANAGER: 'purple',
  TECH_ARCHITECT: 'green',
  FULL_STACK_ENGINEER: 'blue',
  QUALITY_ENGINEER: 'yellow',
  AI_OPERATIONS_ENGINEER: 'green',
  AI_CUSTOMER_SERVICE: 'blue',
  EXPERIENCE_CONTENT_DESIGNER: 'purple',
  UNKNOWN: 'gray'
}

export const PROJECT_OWNER_ROLE_CODES = new Set([
  'DIRECTOR',
  'DEPUTY_DIRECTOR',
  'BUSINESS_OWNER',
  'EFFECTIVENESS_OWNER',
  'SOLUTION_MANAGER'
])

export const MANAGEMENT_ROLE_CODES = new Set([
  'DIRECTOR',
  'DEPUTY_DIRECTOR',
  'BUSINESS_OWNER',
  'EFFECTIVENESS_OWNER',
  'BU_ADMIN'
])

export type RoleAccess = 'management' | 'statistics' | 'project' | 'customer'

const ROLE_ACCESS_CODES: Record<RoleAccess, Set<string>> = {
  management: MANAGEMENT_ROLE_CODES,
  statistics: new Set([
    ...MANAGEMENT_ROLE_CODES,
    'SOLUTION_MANAGER',
    'TECH_ARCHITECT',
    'AI_OPERATIONS_ENGINEER'
  ]),
  project: new Set([
    ...MANAGEMENT_ROLE_CODES,
    'SOLUTION_MANAGER',
    'TECH_ARCHITECT',
    'FULL_STACK_ENGINEER',
    'QUALITY_ENGINEER',
    'AI_OPERATIONS_ENGINEER',
    'EXPERIENCE_CONTENT_DESIGNER'
  ]),
  customer: new Set([
    ...MANAGEMENT_ROLE_CODES,
    'SOLUTION_MANAGER',
    'TECH_ARCHITECT',
    'QUALITY_ENGINEER',
    'AI_OPERATIONS_ENGINEER',
    'AI_CUSTOMER_SERVICE',
    'EXPERIENCE_CONTENT_DESIGNER'
  ])
}

export const hasRoleAccess = (role: string | null | undefined, access: RoleAccess) =>
  Boolean(role && ROLE_ACCESS_CODES[access].has(role))

export const isManagementRole = (role?: string | null) =>
  hasRoleAccess(role, 'management')

export const WORKFLOW_ROLE_OPTIONS = [
  ...POSITION_ROLE_OPTIONS.map(role => role.label),
  '系统自动'
]

export const getPositionRole = (code?: string | null) => {
  if (!code) return undefined
  return POSITION_ROLE_OPTIONS.find(role => role.value === code)
}

export const getRoleLabel = (code?: string | null) => {
  if (!code) return '-'
  return POSITION_ROLE_LABELS[code] ?? LEGACY_ROLE_LABELS[code] ?? code
}

export const isProjectOwnerRole = (code?: string | null) => {
  return !!code && PROJECT_OWNER_ROLE_CODES.has(code)
}
