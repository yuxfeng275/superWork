// 业务线利润报表（工时系统同步）类型定义
// 金额单位：元；比率：百分数（11.1 = 11.1%）；工时单位：人月。前端展示层负责换算为万元。

import type { WorktimeSyncLog } from './kpi'

export interface BizLineProfitRow {
  id?: number
  /** 归属月份 YYYY-MM；YTD 小计行为 "YTD"，总计行为 null */
  yearMonth: string | null
  worktimeBusinessLineId?: number | null
  worktimeBusinessLineName: string
  businessLineId?: number | null
  groupName?: string | null
  revenue: number | null
  smsCost?: number | null
  directCost?: number | null
  platformFee?: number | null
  compensation?: number | null
  outsourcing?: number | null
  softwareGift?: number | null
  totalHours: number | null
  hoursRatio?: number | null
  expense1?: number | null
  laborCost1?: number | null
  grossProfit: number | null
  grossProfitRate: number | null
  marketingCost?: number | null
  laborCost2Sales?: number | null
  laborCost3Backend?: number | null
  laborCost3Tech?: number | null
  laborCost3Rd?: number | null
  expense2?: number | null
  netProfit: number | null
  netProfitRate: number | null
  syncedAt?: string | null
}

export interface BizLineProfitLineGroup {
  businessLineId: number | null
  businessLineName: string
  groupName: string | null
  /** 月度行（按 yearMonth 升序） */
  months: BizLineProfitRow[]
  /** 该业务线 YTD 小计 */
  ytd: BizLineProfitRow
}

export interface BizLineProfitReport {
  year: number
  lines: BizLineProfitLineGroup[]
  /** 全业务线 YTD 总计 */
  totalYtd: BizLineProfitRow
}

export type { WorktimeSyncLog }
