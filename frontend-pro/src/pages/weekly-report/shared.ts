import dayjs, { type Dayjs } from 'dayjs';
import type {
  WeeklyReportStatus,
  WeeklyReportVO,
} from '@/services/superwork/api';

export const statusMeta: Record<
  WeeklyReportStatus,
  { label: string; color: string }
> = {
  PENDING: { label: '待生成', color: 'default' },
  GENERATING: { label: '生成中', color: 'processing' },
  DRAFT: { label: '草稿', color: 'warning' },
  CONFIRMED: { label: '已确认', color: 'success' },
  PUBLISHED: { label: '已发布', color: 'blue' },
  GENERATION_FAILED: { label: '生成失败', color: 'error' },
};

export const statusOf = (status: WeeklyReportStatus) =>
  statusMeta[status] || statusMeta.PENDING;

/** 任意日期 → 所在周的周一（YYYY-MM-DD） */
export const toMonday = (value: Dayjs | Date | string) => {
  const date = dayjs(value);
  const day = date.day() === 0 ? 7 : date.day();
  return date.subtract(day - 1, 'day').format('YYYY-MM-DD');
};

export const rangeText = (row: WeeklyReportVO) =>
  `${row.weekStartDate.slice(5).replace('-', '.')} – ${row.periodEndDate
    .slice(5)
    .replace('-', '.')}`;

export const fmtWan = (value?: number | null) =>
  value == null ? '—' : (value / 10000).toFixed(1);
