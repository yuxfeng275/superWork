import { describe, expect, it } from 'vitest';
import type { BizLineProfitLineGroup, BizLineProfitRow } from '@/services/superwork/api';
import { availableMonths, buildBlRows } from './tableModel';

const row = (partial: Partial<BizLineProfitRow> & { yearMonth: string }): BizLineProfitRow => ({
  worktimeBusinessLineName: 'A',
  revenue: null,
  totalHours: null,
  grossProfit: null,
  grossProfitRate: null,
  netProfit: null,
  netProfitRate: null,
  ...partial,
});

const line = (
  name: string,
  months: BizLineProfitRow[],
  id = 1,
): BizLineProfitLineGroup => ({
  businessLineId: id,
  businessLineName: name,
  groupName: null,
  months,
  ytd: row({ yearMonth: 'YTD', worktimeBusinessLineName: name }),
});

const lines = (): BizLineProfitLineGroup[] => [
  line(
    '会员通',
    [
      row({
        yearMonth: '2026-01',
        revenue: 100,
        grossProfit: 40,
        grossProfitRate: 40,
        netProfit: 10,
        netProfitRate: 10,
        totalHours: 10,
        hoursRatio: 10,
        smsCost: 1,
      }),
      row({
        yearMonth: '2026-06',
        revenue: 100,
        grossProfit: 10,
        grossProfitRate: 10,
        netProfit: -10,
        netProfitRate: -10,
        totalHours: 10,
        hoursRatio: 5,
      }),
      row({
        yearMonth: '2026-07',
        revenue: 50,
        grossProfit: 25,
        grossProfitRate: 50,
        netProfit: 5,
        netProfitRate: 10,
        totalHours: 5,
        hoursRatio: 5,
      }),
    ],
    1,
  ),
  line(
    '云鹿',
    [
      row({
        yearMonth: '2026-01',
        revenue: 200,
        grossProfit: -50,
        grossProfitRate: -25,
        netProfit: -20,
        netProfitRate: -10,
        totalHours: 20,
        hoursRatio: 20,
        directCost: 3,
      }),
      row({
        yearMonth: '2026-07',
        revenue: 0,
        grossProfit: 0,
        grossProfitRate: null,
        netProfit: null,
        netProfitRate: null,
        totalHours: 0,
        hoursRatio: 0,
      }),
    ],
    2,
  ),
];

describe('buildBlRows', () => {
  it('lists synced months in order', () => {
    expect(availableMonths(lines())).toEqual(['2026-01', '2026-06', '2026-07']);
  });

  it('merges the month cell across business lines and the block total', () => {
    const rows = buildBlRows({
      lines: lines(),
      viewMode: 'month',
      selectedMonths: [1],
      filterLineNames: [],
    });
    expect(rows.map((item) => item.lineName)).toEqual(['会员通', '云鹿', '全部业务线']);
    expect(rows.map((item) => item.spans.period)).toEqual([3, 0, 0]);
    expect(rows[0].periodLabel).toBe('1月');
    expect(rows[0].metrics.grossProfitRate).toBe(40);
    expect(rows[0].metrics.hoursRatio).toBe(10);
    expect(rows[2].metrics.revenue).toBe(300);
    expect(rows[2].metrics.grossProfit).toBe(-10);
    expect(rows[2].metrics.grossProfitRate).toBe(-3.33);
    expect(rows[2].metrics.smsCost).toBe(1);
    expect(rows[2].metrics.directCost).toBe(3);
    // 1 月公司工时 = 20 / 20% = 100；合计工时 30 → 30%
    expect(rows[2].metrics.hoursRatio).toBe(30);
  });

  it('keeps each month as its own merged block and honors the month filter', () => {
    const rows = buildBlRows({
      lines: lines(),
      viewMode: 'month',
      selectedMonths: [],
      filterLineNames: [],
    });
    expect(rows.map((item) => `${item.periodLabel}:${item.spans.period}`)).toEqual([
      '1月:3',
      '1月:0',
      '1月:0',
      '6月:2',
      '6月:0',
      '7月:3',
      '7月:0',
      '7月:0',
    ]);
  });

  it('aggregates H1, H2 and the full year, recomputing rates', () => {
    const h1 = buildBlRows({
      lines: lines(),
      viewMode: 'H1',
      selectedMonths: [],
      filterLineNames: [],
    });
    expect(h1.map((item) => item.lineName)).toEqual(['会员通', '云鹿', '全部业务线']);
    expect(h1[0].periodLabel).toBe('H1');
    expect(h1[0].spans.period).toBe(3);
    expect(h1[0].metrics.revenue).toBe(200);
    expect(h1[0].metrics.grossProfit).toBe(50);
    expect(h1[0].metrics.grossProfitRate).toBe(25);
    expect(h1[0].metrics.netProfit).toBe(0);
    expect(h1[0].metrics.netProfitRate).toBe(0);
    // 1 月公司工时 100 + 6 月公司工时 200；会员通工时 20 → 6.7%
    expect(h1[0].metrics.hoursRatio).toBe(6.7);
    expect(h1[1].metrics.revenue).toBe(200);
    expect(h1[2].metrics.revenue).toBe(400);

    const h2 = buildBlRows({
      lines: lines(),
      viewMode: 'H2',
      selectedMonths: [],
      filterLineNames: [],
    });
    expect(h2.map((item) => item.lineName)).toEqual(['会员通', '云鹿', '全部业务线']);
    expect(h2[0].metrics.revenue).toBe(50);
    expect(h2[1].metrics.revenue).toBe(0);
    expect(h2[2].metrics.revenue).toBe(50);
    expect(h2[0].periodLabel).toBe('H2');

    const year = buildBlRows({
      lines: lines(),
      viewMode: 'YEAR',
      selectedMonths: [],
      filterLineNames: [],
    });
    expect(year[0].periodLabel).toBe('全年');
    expect(year[0].metrics.revenue).toBe(250);
    expect(year[2].metrics.revenue).toBe(450);
    expect(year.every((item) => item.periodKey === 'YEAR')).toBe(true);
  });

  it('filters business lines without changing a month row taken from the mirror', () => {
    const rows = buildBlRows({
      lines: lines(),
      viewMode: 'month',
      selectedMonths: [1],
      filterLineNames: ['云鹿'],
    });
    expect(rows.map((item) => item.lineName)).toEqual(['云鹿', '全部业务线']);
    expect(rows[0].spans.period).toBe(2);
    expect(rows[0].metrics.grossProfitRate).toBe(-25);
    expect(rows[1].metrics.revenue).toBe(200);
    expect(rows[1].metrics.hoursRatio).toBe(20);
  });

  it('returns no rows when the period has no synced months', () => {
    const onlyH1 = [
      line('会员通', [row({ yearMonth: '2026-03', revenue: 1, grossProfit: 1 })]),
    ];
    expect(
      buildBlRows({
        lines: onlyH1,
        viewMode: 'H2',
        selectedMonths: [],
        filterLineNames: [],
      }),
    ).toEqual([]);
  });

  it('leaves a sum field null when every source month is null', () => {
    const rows = buildBlRows({
      lines: [
        line('会员通', [
          row({ yearMonth: '2026-01', revenue: 10, smsCost: null, grossProfit: 1 }),
          row({ yearMonth: '2026-02', revenue: 10, smsCost: null, grossProfit: 1 }),
        ]),
      ],
      viewMode: 'H1',
      selectedMonths: [],
      filterLineNames: [],
    });
    expect(rows[0].metrics.smsCost).toBeNull();
    expect(rows[0].metrics.revenue).toBe(20);
  });
});
