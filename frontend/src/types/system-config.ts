export interface SystemConfigGroupSummary {
  groupCode: string
  groupName: string
  description?: string
  itemCount: number
  configuredCount: number
}

export type SystemConfigValueType = 'STRING' | 'PASSWORD' | 'BOOLEAN' | 'URL' | 'NUMBER'

export interface SystemConfigItem {
  key: string
  name: string
  description?: string
  valueType: SystemConfigValueType
  value?: string
  sensitive: boolean
  configured: boolean
  required: boolean
  sortOrder: number
}

export interface SystemConfigGroup {
  groupCode: string
  groupName: string
  description?: string
  items: SystemConfigItem[]
}

export interface SystemConfigTestResult {
  success: boolean
  message: string
  testedAt: string
}
