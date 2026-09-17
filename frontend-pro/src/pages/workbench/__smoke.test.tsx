import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getRequirements: vi.fn(),
  getTaskOverview: vi.fn(),
  getDefectOverview: vi.fn(),
  getKeyMatters: vi.fn(),
  getSalesOpportunities: vi.fn(),
  getQuotations: vi.fn(),
}));

vi.mock('@umijs/max', () => ({
  Link: ({
    to,
    children,
    ...props
  }: {
    to: string;
    children: React.ReactNode;
  }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
  useModel: () => ({
    initialState: { currentUser: { realName: '姜涛', role: 'admin' } },
  }),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getRequirements: mocks.getRequirements,
    getTaskOverview: mocks.getTaskOverview,
    getDefectOverview: mocks.getDefectOverview,
    getKeyMatters: mocks.getKeyMatters,
    getSalesOpportunities: mocks.getSalesOpportunities,
    getQuotations: mocks.getQuotations,
  },
}));

import WorkbenchPage from './index';

describe('WorkbenchPage smoke', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getRequirements.mockResolvedValue({
      records: [
        {
          id: 8,
          title: '会员中心改版',
          status: '开发中',
          priority: '高',
          projectName: '全渠道云',
          owner: '姜涛',
          reqNo: 'REQ-8',
        },
        {
          id: 9,
          title: '结算对账',
          status: '待上线',
          priority: '中',
          projectName: '财务',
          owner: '李雷',
          reqNo: 'REQ-9',
        },
      ],
    });
    mocks.getTaskOverview.mockResolvedValue({
      tasks: [
        {
          recordKey: 't-1',
          title: '接口联调',
          overdueIncomplete: true,
          assigneeName: '王工',
          projectName: '全渠道云',
        },
      ],
      summary: { inProgressCount: 12, pendingCount: 4 },
    });
    mocks.getDefectOverview.mockResolvedValue({
      summary: { inProgressCount: 3 },
      records: [],
    });
    mocks.getKeyMatters.mockResolvedValue([
      {
        id: 21,
        title: '飞鹤周会事项',
        status: '有风险',
        ownerName: '姜涛',
        progress: 40,
      },
    ]);
    mocks.getSalesOpportunities.mockResolvedValue([
      {
        id: 12,
        name: '飞鹤-SCRM系统采购',
        customer: '飞鹤乳业',
        status: '方案报价',
        nextFollowUp: '今天 10:00',
      },
    ]);
    mocks.getQuotations.mockResolvedValue([
      { id: 88, quotationNo: 'QT-001', status: 'DRAFT' },
    ]);
  });

  it('renders cross-module kpis, queues and shortcuts from live functions', async () => {
    render(<WorkbenchPage />);

    expect(await screen.findByText('会员中心改版')).toBeInTheDocument();
    expect(
      Array.from(
        document.querySelectorAll('.sw-workbench-kpis .ant-statistic-title'),
      ).map((node) => node.textContent),
    ).toEqual([
      '进行中需求',
      '待上线',
      '进行中任务',
      '逾期任务',
      '风险大事儿',
      '跟进中商机',
    ]);

    expect(await screen.findByText('会员中心改版')).toBeInTheDocument();
    expect(screen.getByText('飞鹤周会事项')).toBeInTheDocument();
    expect(screen.getByText('飞鹤-SCRM系统采购')).toBeInTheDocument();
    expect(screen.getByText('待开始 4')).toBeInTheDocument();

    const shortcuts = screen.getByText('功能入口').closest('.ant-card');
    expect(shortcuts).toBeTruthy();
    const shortcutLinks = within(shortcuts as HTMLElement);
    expect(
      shortcutLinks.getByRole('link', { name: /需求管理/ }),
    ).toHaveAttribute('href', '/requirements');
    expect(shortcutLinks.getByRole('link', { name: /大事儿/ })).toHaveAttribute(
      'href',
      '/key-matters',
    );
    expect(
      shortcutLinks.getByRole('link', { name: /线索商机/ }),
    ).toHaveAttribute('href', '/opportunities');
    expect(
      shortcutLinks.getByRole('link', { name: /BU 驾驶舱/ }),
    ).toHaveAttribute('href', '/statistics');
  });
});
