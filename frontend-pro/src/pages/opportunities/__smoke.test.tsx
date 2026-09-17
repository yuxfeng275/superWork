import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  getSalesOpportunities: vi.fn(),
  getBusinessLines: vi.fn(),
  getCustomerContacts: vi.fn(),
  getSalesOpportunityFollowUps: vi.fn(),
  getSalesOpportunitySupportWorklogs: vi.fn(),
  createSalesOpportunitySupportWorklog: vi.fn(),
  getOpportunityQuotations: vi.fn(),
  getQuotationPolicies: vi.fn(),
  getQuotationPolicyItems: vi.fn(),
  generateQuotation: vi.fn(),
}));

vi.mock('@umijs/max', () => ({
  history: { push: mocks.push },
  useModel: () => ({
    initialState: {
      currentUser: { realName: '测试用户', role: 'admin' },
    },
  }),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getSalesOpportunities: mocks.getSalesOpportunities,
    getBusinessLines: mocks.getBusinessLines,
    getCustomerContacts: mocks.getCustomerContacts,
    getSalesOpportunityFollowUps: mocks.getSalesOpportunityFollowUps,
    getSalesOpportunitySupportWorklogs:
      mocks.getSalesOpportunitySupportWorklogs,
    createSalesOpportunitySupportWorklog:
      mocks.createSalesOpportunitySupportWorklog,
    getOpportunityQuotations: mocks.getOpportunityQuotations,
    getQuotationPolicies: mocks.getQuotationPolicies,
    getQuotationPolicyItems: mocks.getQuotationPolicyItems,
    generateQuotation: mocks.generateQuotation,
  },
}));

vi.mock('../quotations/GenerateWizard', () => ({
  default: ({
    open,
    defaults,
  }: {
    open: boolean;
    defaults?: { customerName?: string; opportunityName?: string };
  }) =>
    open ? (
      <div role="dialog" aria-label="新建报价单">
        <div>选择报价策略</div>
        <div>{defaults?.customerName}</div>
        <div>{defaults?.opportunityName}</div>
      </div>
    ) : null,
}));

import OpportunitiesPage from './index';

const opportunity = {
  id: 12,
  name: '飞鹤-SCRM系统采购',
  customer: '飞鹤乳业',
  type: '商机' as const,
  status: '方案报价' as const,
  amount: 320,
  owner: '姜涛',
  businessLine: '全渠道云',
  nextFollowUp: '明天 10:00',
  probability: 50,
};

describe('OpportunitiesPage smoke', () => {
  afterEach(() => {
    cleanup();
  });
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getSalesOpportunities.mockResolvedValue([opportunity]);
    mocks.getBusinessLines.mockResolvedValue({ records: [] });
    mocks.getCustomerContacts.mockResolvedValue({ records: [] });
    mocks.getSalesOpportunityFollowUps.mockResolvedValue([]);
    mocks.getSalesOpportunitySupportWorklogs.mockResolvedValue([
      {
        id: 3,
        opportunityId: 12,
        supportDate: '2026-09-10',
        supporter: '王工',
        hours: 2.5,
        supportType: '方案支持',
        content: '方案评审',
      },
    ]);
    mocks.getOpportunityQuotations.mockResolvedValue([
      {
        id: 88,
        quotationNo: 'QT-20260917-001',
        customerName: '飞鹤乳业',
        firstYearTotalInclTax: 128000,
        status: 'DRAFT',
        quoteDate: '2026-09-17',
      },
    ]);
    mocks.getQuotationPolicies.mockResolvedValue([]);
    mocks.getQuotationPolicyItems.mockResolvedValue([]);
    mocks.generateQuotation.mockResolvedValue({ id: 99, quotationNo: 'QT-NEW' });
    mocks.createSalesOpportunitySupportWorklog.mockResolvedValue({ id: 4 });
  });

  it('renders opportunity actions including hours and quotation', async () => {
    render(<OpportunitiesPage />);
    expect(await screen.findByText('飞鹤-SCRM系统采购')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /工时/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /报价/ })).toBeInTheDocument();
  });

  it('opens quotation wizard from opportunity actions with customer prefilled', async () => {
    render(<OpportunitiesPage />);
    expect(await screen.findByText('飞鹤-SCRM系统采购')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /报价/ }));

    const dialog = await screen.findByRole('dialog', { name: '新建报价单' });
    expect(within(dialog).getByText('选择报价策略')).toBeInTheDocument();
    expect(within(dialog).getByText('飞鹤乳业')).toBeInTheDocument();
  });

  it('lists existing quotations in opportunity detail', async () => {
    render(<OpportunitiesPage />);
    fireEvent.click(await screen.findByText('飞鹤-SCRM系统采购'));

    expect(await screen.findByText('关联报价单')).toBeInTheDocument();
    expect(await screen.findByText('QT-20260917-001')).toBeInTheDocument();
    await waitFor(() =>
      expect(mocks.getOpportunityQuotations).toHaveBeenCalledWith(12),
    );
  });
});
