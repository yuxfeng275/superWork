/** 日程中心（本地会议 + 企微日程聚合）类型。 */

/** 事件来源：本地会议 / 企微日程 / 企微会议（企微会议需「会议」品类授权，未授权时通常为空）。 */
export type ScheduleSource = 'MEETING' | 'WECOM_SCHEDULE'

/**
 * 统一日历事件。
 *
 * 时间字段一律是「墙上时间」字符串（yyyy-MM-dd HH:mm:ss），展示侧禁止用 new Date(str) 解析
 * （浏览器会按 UTC 解释而整体偏移时区），须按格式手工解析。
 */
export interface ScheduleEvent {
  /** "meeting:12" / "schedule:xxx@2026-09-01 09:30:00" / "wemeeting:yyy" */
  id: string
  source: ScheduleSource
  title: string
  /** 墙上时间 yyyy-MM-dd HH:mm:ss */
  start: string
  end: string
  /** 本地会议只有日期粒度（无起始时刻）→ true */
  allDay: boolean
  location?: string | null
  /** 本地会议为 null */
  organizer?: string | null
  calendarName?: string | null
  participants?: string[] | null
  /** 本地会议状态（UPLOADED/TRANSCRIBING/...），企微事件为 null */
  status?: string | null
  /** 本地会议 id（会议模块详情主键） */
  meetingId?: number | null
  scheduleId?: string | null
  /** 线上会议号（企微日程内嵌会议） */
  meetingCode?: string | null
  /** 入会链接 */
  meetingLink?: string | null
  /** 周期日程展开出的场次 */
  recurring?: boolean | null
  description?: string | null
}

/** 展示用视图模型：墙上时间已解析，日历分组/排序不再重复解析。 */
export interface ScheduleEventView {
  event: ScheduleEvent
  id: string
  source: ScheduleSource
  title: string
  /** 墙上日期 yyyy-MM-dd */
  dayKey: string
  /** '09:30' 或 '全天' */
  timeLabel: string
  /** '09:30 – 10:30' 或 '全天' */
  rangeLabel: string
  /** 当天内排序用秒数 */
  daySeconds: number
  allDay: boolean
  recurring: boolean
  participants: string[]
  /** 录音时长（秒，仅本地会议可按 end - start 还原；无时长或跨天为 null） */
  durationSeconds: number | null
}

/** 聚合查询入参：from/to 为闭区间（按天）。 */
export interface ScheduleEventsQuery {
  from: string
  to: string
  /** 需要的事件来源；缺省 = 全部 */
  sources?: ScheduleSource[]
}

/** 聚合响应。 */
export interface ScheduleEventsPayload {
  events: ScheduleEvent[]
  /** 企微侧提示（未授权 / 窗口越界），含 markdown 链接，需展示且链接可点 */
  hints: string[]
  rangeStart: string
  rangeEnd: string
}
