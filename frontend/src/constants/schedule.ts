import type { ScheduleSource } from '@/types/schedule'

/** 来源筛选值：ALL = 全部来源。 */
export type ScheduleSourceFilter = 'ALL' | ScheduleSource

export interface ScheduleSourceOption {
  value: ScheduleSourceFilter
  label: string
}

export const SCHEDULE_SOURCE_FILTERS: ScheduleSourceOption[] = [
  { value: 'ALL', label: '全部' },
  { value: 'MEETING', label: '会议' },
  { value: 'WECOM_SCHEDULE', label: '企微日程' },
  { value: 'WECOM_MEETING', label: '企微会议' }
]

/** 事件徽标文案。 */
export const SCHEDULE_SOURCE_LABELS: Record<ScheduleSource, string> = {
  MEETING: '本地会议',
  WECOM_SCHEDULE: '企微日程',
  WECOM_MEETING: '企微会议'
}

/** 来源配色（chip / 徽标 / 图例共用）：本地会议主色、企微日程绿、企微会议橙。 */
export const SCHEDULE_SOURCE_COLORS: Record<ScheduleSource, string> = {
  MEETING: 'var(--primary)',
  WECOM_SCHEDULE: 'var(--success)',
  WECOM_MEETING: 'var(--warning)'
}

/** 来源浅色底（日历 chip 背景）。 */
export const SCHEDULE_SOURCE_TINTS: Record<ScheduleSource, string> = {
  MEETING: 'var(--primary-light)',
  WECOM_SCHEDULE: '#ECFDF5',
  WECOM_MEETING: '#FFFBEB'
}

/** 来源枚举顺序（图例、「全部」筛选的取数集合共用）。 */
export const SCHEDULE_SOURCES: ScheduleSource[] = ['MEETING', 'WECOM_SCHEDULE', 'WECOM_MEETING']

/** 本地会议状态中文；未知取值原样展示。 */
export const MEETING_STATUS_LABELS: Record<string, string> = {
  UPLOADED: '已上传',
  TRANSCRIBING: '转写中',
  TRANSCRIBED: '已转写',
  SUMMARIZING: '总结中',
  SUMMARIZED: '已总结',
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  FAILED: '失败'
}
