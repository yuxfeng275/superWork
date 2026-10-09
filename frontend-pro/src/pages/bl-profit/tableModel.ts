import type {
  BizLineProfitLineGroup,
  BizLineProfitRow,
} from '@/services/superwork/api';

export type BlViewMode = 'month' | 'H1' | 'H2' | 'YEAR';

/** 求和列。比率（毛利率/净利率/工时占比）不累加，聚合后按合计重算。 */
const SUM_FIELDS = [
  'revenue',
  'smsCost',
  'directCost',
  'platformFee',
  'compensation',
  'outsourcing',
  'softwareGift',
  'totalHours',
  'expense1',
  'laborCost1',
  'grossProfit',
  'netProfit',
] as const;

export interface BlMetrics {
  revenue: number | null;
  smsCost: number | null;
  directCost: number | null;
  platformFee: number | null;
  compensation: number | null;
  outsourcing: number | null;
  softwareGift: number | null;
  totalHours: number | null;
  /** 工时占比（百分数）。月度行取镜像原值；聚合行 = 本行工时 / 该公司月工时合计 */
  hoursRatio: number | null;
  expense1: number | null;
  laborCost1: number | null;
  grossProfit: number | null;
  grossProfitRate: number | null;
  netProfit: number | null;
  netProfitRate: number | null;
}

export interface BlFlatRow {
  key: string;
  periodKey: string;
  periodLabel: string;
  lineName: string;
  summary: boolean;
  metrics: BlMetrics;
  /** 仅期间组首行为组内行数，其余为 0（被上行合并） */
  spans: { period: number };
}

const emptyMetrics = (): BlMetrics => ({
  revenue: null,
  smsCost: null,
  directCost: null,
  platformFee: null,
  compensation: null,
  outsourcing: null,
  softwareGift: null,
  totalHours: null,
  hoursRatio: null,
  expense1: null,
  laborCost1: null,
  grossProfit: null,
  grossProfitRate: null,
  netProfit: null,
  netProfitRate: null,
});

const asNum = (value: number | null | undefined): number | null => {
  if (value == null) return null;
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
};

/** 百分数保留 2 位，四舍五入远离 0（与后端 HALF_UP 一致） */
const rate = (part: number | null, total: number | null): number | null => {
  if (part == null || total == null || total === 0) return null;
  const raw = (part / total) * 10000;
  const sign = raw < 0 ? -1 : 1;
  return (sign * Math.round(Math.abs(raw))) / 100;
};

const round1 = (value: number): number => {
  const sign = value < 0 ? -1 : 1;
  return (sign * Math.round(Math.abs(value) * 10)) / 10;
};

const metricsFromRow = (row: BizLineProfitRow): BlMetrics => ({
  revenue: asNum(row.revenue),
  smsCost: asNum(row.smsCost),
  directCost: asNum(row.directCost),
  platformFee: asNum(row.platformFee),
  compensation: asNum(row.compensation),
  outsourcing: asNum(row.outsourcing),
  softwareGift: asNum(row.softwareGift),
  totalHours: asNum(row.totalHours),
  hoursRatio: asNum(row.hoursRatio),
  expense1: asNum(row.expense1),
  laborCost1: asNum(row.laborCost1),
  grossProfit: asNum(row.grossProfit),
  grossProfitRate: asNum(row.grossProfitRate),
  netProfit: asNum(row.netProfit),
  netProfitRate: asNum(row.netProfitRate),
});

const accumulate = (target: BlMetrics, row: BizLineProfitRow) => {
  SUM_FIELDS.forEach((field) => {
    const value = asNum(row[field]);
    if (value == null) return;
    target[field] = (target[field] ?? 0) + value;
  });
};

/**
 * 工时占比的分母是公司全部业务线工时，接口只返回本 BU 管理线。
 * 用「工时 / (占比/100)」从占比非零的行反推该月公司工时，取工时最大的那行以降低舍入误差。
 */
const companyHoursByMonth = (lines: BizLineProfitLineGroup[]) => {
  const hours = new Map<string, number>();
  const sample = new Map<string, number>();
  lines.forEach((line) => {
    line.months.forEach((row) => {
      if (!row.yearMonth) return;
      const lineHours = asNum(row.totalHours);
      const ratio = asNum(row.hoursRatio);
      if (lineHours == null || ratio == null || lineHours <= 0 || ratio <= 0) return;
      const best = sample.get(row.yearMonth) ?? 0;
      if (lineHours <= best) return;
      sample.set(row.yearMonth, lineHours);
      hours.set(row.yearMonth, lineHours / (ratio / 100));
    });
  });
  return hours;
};

const periodHoursRatio = (
  hours: number | null,
  months: string[],
  companyHours: Map<string, number>,
): number | null => {
  if (hours == null) return null;
  let company = 0;
  let known = false;
  months.forEach((month) => {
    const value = companyHours.get(month);
    if (value == null) return;
    company += value;
    known = true;
  });
  if (!known || company === 0) return null;
  return round1((hours / company) * 100);
};

const aggregate = (
  rows: BizLineProfitRow[],
  months: string[],
  companyHours: Map<string, number>,
): BlMetrics => {
  const metrics = emptyMetrics();
  rows.forEach((row) => {
    accumulate(metrics, row);
  });
  metrics.grossProfitRate = rate(metrics.grossProfit, metrics.revenue);
  metrics.netProfitRate = rate(metrics.netProfit, metrics.revenue);
  metrics.hoursRatio = periodHoursRatio(metrics.totalHours, months, companyHours);
  return metrics;
};

export const availableMonths = (lines: BizLineProfitLineGroup[]): string[] => {
  const months = new Set<string>();
  lines.forEach((line) => {
    line.months.forEach((row) => {
      if (row.yearMonth && /^\d{4}-\d{2}$/.test(row.yearMonth)) months.add(row.yearMonth);
    });
  });
  return [...months].sort();
};

const periodMonths = (
  months: string[],
  viewMode: BlViewMode,
  selectedMonths: number[],
): string[] => {
  if (viewMode === 'month') {
    if (selectedMonths.length === 0) return months;
    return months.filter((month) => selectedMonths.includes(Number(month.slice(5))));
  }
  const from = viewMode === 'H2' ? 7 : 1;
  const to = viewMode === 'H1' ? 6 : 12;
  return months.filter((month) => {
    const value = Number(month.slice(5));
    return value >= from && value <= to;
  });
};

const spanCounts = (keys: string[]) => {
  const spans = new Array<number>(keys.length).fill(0);
  let start = 0;
  for (let i = 1; i <= keys.length; i += 1) {
    if (i === keys.length || keys[i] !== keys[start]) {
      spans[start] = i - start;
      start = i;
    }
  }
  return spans;
};

interface Block {
  key: string;
  label: string;
  months: string[];
  /** 月度且块内仅一个月：行内比率沿用镜像，不重算 */
  preserveSource: boolean;
}

const blocksOf = (
  months: string[],
  viewMode: BlViewMode,
  selectedMonths: number[],
): Block[] => {
  const picked = periodMonths(months, viewMode, selectedMonths);
  if (viewMode === 'month') {
    return picked.map((month) => ({
      key: month,
      label: `${Number(month.slice(5))}月`,
      months: [month],
      preserveSource: true,
    }));
  }
  if (picked.length === 0) return [];
  return [
    {
      key: viewMode,
      label: viewMode === 'YEAR' ? '全年' : viewMode,
      months: picked,
      preserveSource: false,
    },
  ];
};

export const buildBlRows = (input: {
  lines: BizLineProfitLineGroup[];
  viewMode: BlViewMode;
  selectedMonths: number[];
  filterLineNames: string[];
}): BlFlatRow[] => {
  const months = availableMonths(input.lines);
  const companyHours = companyHoursByMonth(input.lines);
  const lines =
    input.filterLineNames.length === 0
      ? input.lines
      : input.lines.filter((line) => input.filterLineNames.includes(line.businessLineName));
  const draft: Omit<BlFlatRow, 'spans'>[] = [];

  blocksOf(months, input.viewMode, input.selectedMonths).forEach((block) => {
    const monthSet = new Set(block.months);
    const visible: { name: string; source: BizLineProfitRow[] }[] = [];
    lines.forEach((line) => {
      const source = line.months.filter(
        (row) => row.yearMonth != null && monthSet.has(row.yearMonth),
      );
      if (source.length === 0) return;
      visible.push({ name: line.businessLineName, source });
    });
    if (visible.length === 0) return;

    visible.forEach(({ name, source }) => {
      draft.push({
        key: `${block.key}-${name}`,
        periodKey: block.key,
        periodLabel: block.label,
        lineName: name,
        summary: false,
        metrics:
          block.preserveSource && source.length === 1
            ? metricsFromRow(source[0])
            : aggregate(source, block.months, companyHours),
      });
    });
    draft.push({
      key: `${block.key}-SUMMARY`,
      periodKey: block.key,
      periodLabel: block.label,
      lineName: '全部业务线',
      summary: true,
      metrics: aggregate(
        visible.flatMap((line) => line.source),
        block.months,
        companyHours,
      ),
    });
  });

  const spans = spanCounts(draft.map((row) => row.periodKey));
  return draft.map((row, index) => ({ ...row, spans: { period: spans[index] } }));
};
