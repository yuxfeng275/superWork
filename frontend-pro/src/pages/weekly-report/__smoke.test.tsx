import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WeeklyReportVO } from '@/services/superwork/api';

const mocks = vi.hoisted(() => ({
  getWeeklyHistory: vi.fn(),
  getWeeklyReport: vi.fn(),
  publishWeeklySheet: vi.fn(),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getWeeklyHistory: mocks.getWeeklyHistory,
    getWeeklyReport: mocks.getWeeklyReport,
    publishWeeklySheet: mocks.publishWeeklySheet,
  },
}));

import WeeklyReportPage from './index';

const report = (
  overrides: Partial<WeeklyReportVO> & Pick<WeeklyReportVO, 'id' | 'status'>,
): WeeklyReportVO => ({
  weekStartDate: '2026-09-08',
  periodEndDate: '2026-09-12',
  editable: overrides.status !== 'PUBLISHED',
  factsPreview: [],
  ...overrides,
});

describe('WeeklyReportPage overview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('stops the in-progress spinner at zero and splits confirmed vs published', async () => {
    mocks.getWeeklyHistory.mockResolvedValue([
      report({
        id: 1,
        status: 'PUBLISHED',
        yuqueDocUrl: 'https://yuque.example/1',
      }),
      report({
        id: 2,
        status: 'CONFIRMED',
        weekStartDate: '2026-09-01',
        periodEndDate: '2026-09-05',
      }),
      report({
        id: 3,
        status: 'GENERATION_FAILED',
        weekStartDate: '2026-08-25',
        periodEndDate: '2026-08-29',
      }),
    ]);

    render(<WeeklyReportPage />);

    expect(await screen.findByText('全部周报')).toBeInTheDocument();
    const titles = Array.from(
      document.querySelectorAll('.sw-report-summary .ant-statistic-title'),
    ).map((node) => node.textContent);
    expect(titles).toEqual(['全部周报', '进行中', '已确认', '已发布']);
    expect(screen.queryByText('已确认 / 发布')).not.toBeInTheDocument();
    expect(document.querySelector('.anticon-loading')).toBeNull();

    const values = Array.from(
      document.querySelectorAll(
        '.sw-report-summary .ant-statistic-content-value-int',
      ),
    ).map((node) => node.textContent);
    expect(values).toEqual(['3', '0', '1', '1']);
  });

  it('spins only while reports are still in progress', async () => {
    mocks.getWeeklyHistory.mockResolvedValue([
      report({ id: 4, status: 'GENERATING' }),
      report({
        id: 5,
        status: 'DRAFT',
        weekStartDate: '2026-09-01',
        periodEndDate: '2026-09-05',
      }),
    ]);

    render(<WeeklyReportPage />);

    expect(await screen.findByText('进行中')).toBeInTheDocument();
    expect(document.querySelector('.anticon-loading')).not.toBeNull();
  });

  it('opens publish drawer from 同步 without opening the report editor', async () => {
    const published = report({
      id: 6,
      status: 'PUBLISHED',
      yuqueDocUrl: 'https://yuque.example/1',
      minutesMarkdown: '# 电商业务BU周会会议纪要',
    });
    mocks.getWeeklyHistory.mockResolvedValue([published]);
    mocks.getWeeklyReport.mockResolvedValue(published);

    render(<WeeklyReportPage />);
    fireEvent.click(await screen.findByRole('button', { name: '同步' }));

    expect(await screen.findByText('发布与同步')).toBeTruthy();
    expect(screen.queryByText('正在加载周报...')).toBeNull();
    expect(mocks.getWeeklyReport).toHaveBeenCalledWith('2026-09-08');
  });

  it('applies generated report into editor draft while polling', () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'),
      'utf8',
    );
    expect(source).toMatch(/const applyReport = useCallback\(\(report: WeeklyReportVO\)/);
    expect(source).toMatch(/applyReport\(latest\)/);
    expect(source).toMatch(/applyReport\(report\)/);
  });

  it('guides manual sheet fill instead of auto-writing Yuque', () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'),
      'utf8',
    );
    expect(source).toMatch(/复制纪要链接/);
    expect(source).toMatch(/标记已回填/);
    expect(source).toMatch(/当前语雀 Token 写不进公司汇总表/);
  });
});
