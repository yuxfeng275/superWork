import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WeeklyReportVO } from '@/services/superwork/api';

const mocks = vi.hoisted(() => ({
  getWeeklyHistory: vi.fn(),
  getWeeklyReport: vi.fn(),
  getWeeklyFacts: vi.fn(),
  getWeeklyGenerationModel: vi.fn(),
  publishWeeklySheet: vi.fn(),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getWeeklyHistory: mocks.getWeeklyHistory,
    getWeeklyReport: mocks.getWeeklyReport,
    getWeeklyFacts: mocks.getWeeklyFacts,
    getWeeklyGenerationModel: mocks.getWeeklyGenerationModel,
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

const FACTS = {
  weekStart: '2026-09-08',
  periodEnd: '2026-09-12',
  keyMatters: [],
  opportunities: [
    {
      id: 21,
      opportunityName: '飞鹤-SCRM系统采购',
      customer: '飞鹤乳业',
      follower: '姜涛',
      status: '商务谈判',
      content: '客户确认一期预算',
    },
  ],
  finance: {
    month: '2026-09',
    newContractAmount: 0,
    deliveredAmount: 0,
    cumulativeReceivable: 0,
  },
  lastWeekReport: { exists: false },
};

describe('WeeklyReportPage overview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getWeeklyFacts.mockResolvedValue(FACTS);
    mocks.getWeeklyGenerationModel.mockResolvedValue({ configured: true });
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

  it('opens the workbench drawer at publish anchor from 发布', async () => {
    const published = report({
      id: 6,
      status: 'PUBLISHED',
      yuqueDocUrl: 'https://yuque.example/1',
      minutesMarkdown: '# 电商业务BU周会会议纪要',
    });
    mocks.getWeeklyHistory.mockResolvedValue([published]);
    mocks.getWeeklyReport.mockResolvedValue(published);

    render(<WeeklyReportPage />);
    fireEvent.click(await screen.findByRole('button', { name: '发布' }));

    // 双栏工作台：右侧栏含发布通道卡
    expect(await screen.findByText('发布通道')).toBeTruthy();
    expect(mocks.getWeeklyReport).toHaveBeenCalledWith('2026-09-08');
  });

  it('keeps generated report applied into draft while polling', () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), 'ReportDrawer.tsx'),
      'utf8',
    );
    expect(source).toMatch(
      /const applyReport = useCallback\(\(report: WeeklyReportVO\)/,
    );
    expect(source).toMatch(/applyReport\(latest\)/);
    expect(source).toMatch(/applyReport\(report\)/);
  });

  it('opens the workbench: document sections plus fixed sidebar inputs', async () => {
    const published = report({
      id: 7,
      status: 'PUBLISHED',
      coreWork: '项目：皇家积分切换完成对账',
      kpiSection: '新增合同 120 万',
      risks: '无重大风险',
      nextWeekPlan: '推进二期',
      minutesMarkdown: '# 电商业务BU周会会议纪要',
    });
    mocks.getWeeklyHistory.mockResolvedValue([published]);
    mocks.getWeeklyReport.mockResolvedValue(published);

    render(<WeeklyReportPage />);
    fireEvent.click(await screen.findByRole('button', { name: '详情' }));

    // 左栏文档式正文（查看/编辑一体）
    expect(await screen.findByText('本周核心工作完成情况')).toBeInTheDocument();
    expect(await screen.findByText('项目：')).toBeInTheDocument();
    expect(screen.getByText('皇家积分切换完成对账')).toBeInTheDocument();
    // 右侧栏：事实 + 人工输入 + 操作 + 发布通道（统一布局，不再有独立编辑模式）
    expect(screen.getByText('本周事实')).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText('粘贴企微智能总结…'),
    ).toBeInTheDocument();
    expect(screen.getByText('生成 / 重新生成')).toBeInTheDocument();
    const copyButtons = screen.getAllByRole('button', { name: /^复制$/ });
    expect(copyButtons).toHaveLength(4);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    fireEvent.click(copyButtons[0]);
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith('项目：皇家积分切换完成对账'),
    );
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    const execCommand = vi.fn().mockReturnValue(true);
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^复制$/ })[1]);
    await waitFor(() => expect(execCommand).toHaveBeenCalledWith('copy'));
    // 商机明细默认折叠，展开「明细」后可见
    fireEvent.click(screen.getByRole('button', { name: '明细' }));
    expect(await screen.findByText('飞鹤-SCRM系统采购')).toBeInTheDocument();
    expect(
      screen.queryByText(/电商业务BU周会会议纪要/),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: '周会纪要' }));
    expect(
      await screen.findByText(/电商业务BU周会会议纪要/),
    ).toBeInTheDocument();
    expect(screen.queryByText('本周核心工作完成情况')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^复制$/ })).not.toBeInTheDocument();
  });

  it('guides manual sheet fill instead of auto-writing Yuque', () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), 'ReportDrawer.tsx'),
      'utf8',
    );
    expect(source).toMatch(/copyMinutesLink/);
    expect(source).toMatch(/已回填/);
    expect(source).toMatch(/当前语雀 Token 写不进公司汇总表/);
  });
});
