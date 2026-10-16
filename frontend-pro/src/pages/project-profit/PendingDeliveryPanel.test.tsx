import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PendingDeliveryReport } from '@/services/superwork/api';

const mocks = vi.hoisted(() => ({
  getPendingDelivery: vi.fn(),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getPendingDelivery: mocks.getPendingDelivery,
  },
}));

import PendingDeliveryPanel from './PendingDeliveryPanel';

const report: PendingDeliveryReport = {
  year: 2026,
  currentMonth: '2026-10',
  groups: [
    {
      month: '2026-10',
      label: '10月',
      warning: null,
      entryCount: 2,
      confirmedCount: 0,
      totalAmount: 212000,
      confirmedAmount: 0,
      entries: [
        {
          id: 1,
          contractNo: 'CS-A',
          contractName: '甲公司服务合同',
          customer: '甲公司',
          itemDesc: '10 月服务费',
          receivableAmount: 106000,
          salesOwner: '张三',
          serviceEndDate: '2026-10-31',
          expectedMonth: '2026-10',
          businessLineName: '云鹿定制',
          projectName: '皇家',
          overdue: false,
          confirmed: false,
          confirmedByName: null,
          confirmedAt: null,
        },
        {
          id: 2,
          contractNo: 'CS-B',
          contractName: '乙公司服务合同',
          customer: '乙公司',
          itemDesc: '10 月服务费',
          receivableAmount: 106000,
          salesOwner: '李四',
          serviceEndDate: null,
          expectedMonth: '2026-10',
          businessLineName: '云鹿定制',
          projectName: '飞鹤',
          overdue: false,
          confirmed: false,
          confirmedByName: null,
          confirmedAt: null,
        },
      ],
    },
  ],
};

const ENTRY_A_TEXT = [
  '合同号：CS-A',
  '合同名称：甲公司服务合同',
  '客户：甲公司',
  '款项内容：10 月服务费',
  '服务结束时间：2026-10-31',
  '提交人：张三',
].join('\n');

describe('PendingDeliveryPanel 复制', () => {
  beforeEach(() => {
    mocks.getPendingDelivery.mockReset();
    mocks.getPendingDelivery.mockResolvedValue(report);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
  });

  it('行内「复制」复制单条合同的六个字段', async () => {
    render(<PendingDeliveryPanel year={2026} onChanged={() => {}} />);
    await screen.findByText('CS-A');

    const rowCopyLinks = screen
      .getAllByText('复制')
      .filter((el) => el.tagName === 'A');
    expect(rowCopyLinks).toHaveLength(2);
    fireEvent.click(rowCopyLinks[0]);

    await waitFor(() =>
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(ENTRY_A_TEXT),
    );
  });

  it('分组「复制未确认」一次性复制本组未确认合同（空行分隔）', async () => {
    render(<PendingDeliveryPanel year={2026} onChanged={() => {}} />);
    await screen.findByText('CS-A');

    fireEvent.click(screen.getByRole('button', { name: '复制未确认（2 笔）' }));

    await waitFor(() => {
      const copied = (navigator.clipboard.writeText as ReturnType<typeof vi.fn>)
        .mock.calls[0][0] as string;
      expect(copied.split('\n\n')).toHaveLength(2);
      expect(copied).toContain('合同号：CS-A');
      expect(copied).toContain('合同号：CS-B');
      expect(copied).toContain('服务结束时间：缺失');
      expect(copied).toContain('提交人：李四');
    });
  });
});
