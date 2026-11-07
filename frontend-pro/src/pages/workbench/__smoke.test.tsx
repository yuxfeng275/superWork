import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getRequirements: vi.fn(),
  getTaskOverview: vi.fn(),
  getDefectOverview: vi.fn(),
  getKeyMatters: vi.fn(),
  getSalesOpportunities: vi.fn(),
  getQuotations: vi.fn(),
  getMyBriefs: vi.fn(),
  getMyReviews: vi.fn(),
  decideArtifact: vi.fn(),
  applyArtifact: vi.fn(),
}));

const navigateMock = vi.fn();
vi.mock('@umijs/max', () => ({
  useNavigate: () => navigateMock,
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
    getMyBriefs: mocks.getMyBriefs,
      getMyReviews: mocks.getMyReviews,
    decideArtifact: mocks.decideArtifact,
    applyArtifact: mocks.applyArtifact,
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
    mocks.getMyBriefs.mockResolvedValue([]);
    mocks.getMyReviews.mockResolvedValue([]);
    mocks.decideArtifact.mockResolvedValue({ id: 1, status: 'APPROVED' });
    mocks.applyArtifact.mockResolvedValue({ id: 1, status: 'APPLIED' });
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

  it('renders today brief card with at most three items and external links', async () => {
    mocks.getMyBriefs.mockResolvedValue([
      {
        id: 1,
        userId: 7,
        briefDate: '2026-09-01',
        content: JSON.stringify({
          items: [
            {
              title: '先回飞鹤报价邮件',
              why: '客户今天等方案',
              links: ['https://example.com/quotations/88'],
            },
            { title: '确认结算对账上线窗口', why: '需求已待上线' },
            { title: '跟接口联调进度', why: '任务已逾期' },
            { title: '第四条不该出现', why: '超出三条' },
          ],
        }),
      },
    ]);

    render(<WorkbenchPage />);

    expect(await screen.findByText('今日安排（AI 晨报）')).toBeInTheDocument();
    expect(await screen.findByText('先回飞鹤报价邮件')).toBeInTheDocument();
    expect(screen.getByText('客户今天等方案')).toBeInTheDocument();
    expect(screen.queryByText('第四条不该出现')).toBeNull();
    expect(
      screen.getByRole('link', { name: 'https://example.com/quotations/88' }),
    ).toHaveAttribute('href', 'https://example.com/quotations/88');
  });

  it('renders pending artifact reviews with kind labels and decide buttons', async () => {
    // 同一个接口被按状态拉两次：IN_REVIEW（待评审）与 APPROVED（已通过待采纳）。
    mocks.getMyReviews.mockImplementation((status?: string) =>
      Promise.resolve(
        status === 'APPROVED'
          ? [
              {
                id: 41,
                kind: 'PLAN',
                title: '回款口径对齐方案（已通过）',
                status: 'APPROVED',
              },
            ]
          : [
              {
                id: 31,
                kind: 'PLAN',
                title: '会员中心改版方案',
                status: 'IN_REVIEW',
              },
              { id: 32, kind: 'PATCH', title: '修复对账空值', status: 'IN_REVIEW' },
            ],
      ),
    );

    render(<WorkbenchPage />);

    expect(await screen.findByText('待我评审')).toBeInTheDocument();
    expect(await screen.findByText('会员中心改版方案')).toBeInTheDocument();
    expect(screen.getByText('方案')).toBeInTheDocument();
    expect(screen.getByText('补丁')).toBeInTheDocument();
    // antd Button 会在两个汉字之间插空格，故用正则匹配。
    expect(
      screen.getAllByRole('button', { name: /通\s*过/ }).length,
    ).toBeGreaterThan(0);
    expect(
      screen.getAllByRole('button', { name: /驳\s*回/ }).length,
    ).toBeGreaterThan(0);
  });

  it('renders approved artifacts with an apply button, hides the group when empty', async () => {
    mocks.getMyReviews.mockImplementation((status?: string) =>
      Promise.resolve(
        status === 'APPROVED'
          ? [
              {
                id: 41,
                kind: 'PLAN',
                title: '回款口径对齐方案',
                status: 'APPROVED',
              },
            ]
          : [],
      ),
    );

    const { unmount } = render(<WorkbenchPage />);

    expect(
      await screen.findByText('回款口径对齐方案'),
    ).toBeInTheDocument();
    const applyButtons = screen.getAllByRole('button', { name: /采\s*纳/ });
    expect(applyButtons.length).toBeGreaterThan(0);

    unmount();

    // 没有已通过条目时整组不渲染
    mocks.getMyReviews.mockResolvedValue([]);
    render(<WorkbenchPage />);
    expect(await screen.findByText('待我评审')).toBeInTheDocument();
    expect(screen.queryByText(/已通过待采纳/)).toBeNull();
  });
});
