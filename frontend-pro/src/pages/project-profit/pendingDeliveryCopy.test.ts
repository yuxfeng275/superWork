import { describe, expect, it } from 'vitest';
import type { PendingDeliveryEntry } from '@/services/superwork/api';
import {
  pendingDeliveryCopyText,
  pendingDeliveryEntryText,
} from './pendingDeliveryCopy';

const entry = (
  overrides: Partial<PendingDeliveryEntry> = {},
): PendingDeliveryEntry => ({
  id: 1,
  contractNo: 'CS2026-001',
  contractName: '某品牌年度代运营合同',
  customer: '某客户',
  itemDesc: '2026 年 10 月服务费',
  receivableAmount: 106000,
  salesOwner: '张三',
  serviceEndDate: '2026-10-31',
  expectedMonth: '2026-10',
  businessLineName: '全域-全渠道-全域云鹿Saas',
  projectName: '黄天鹅',
  overdue: false,
  confirmed: false,
  confirmedByName: null,
  confirmedAt: null,
  ...overrides,
});

describe('pendingDeliveryEntryText', () => {
  it('按固定顺序输出六个字段', () => {
    expect(pendingDeliveryEntryText(entry())).toBe(
      [
        '合同号：CS2026-001',
        '合同名称：某品牌年度代运营合同',
        '客户：某客户',
        '款项内容：2026 年 10 月服务费',
        '服务结束时间：2026-10-31',
        '提交人：张三',
      ].join('\n'),
    );
  });

  it('空值用占位符：缺失字段显示 —，服务结束时间显示缺失', () => {
    const text = pendingDeliveryEntryText(
      entry({
        contractNo: null,
        contractName: '   ',
        customer: null,
        itemDesc: '',
        salesOwner: null,
        serviceEndDate: null,
      }),
    );
    expect(text).toBe(
      [
        '合同号：—',
        '合同名称：—',
        '客户：—',
        '款项内容：—',
        '服务结束时间：缺失',
        '提交人：—',
      ].join('\n'),
    );
  });
});

describe('pendingDeliveryCopyText', () => {
  it('多条之间以空行分隔', () => {
    const text = pendingDeliveryCopyText([
      entry({ id: 1, contractNo: 'CS-A' }),
      entry({ id: 2, contractNo: 'CS-B', salesOwner: '李四' }),
    ]);
    expect(text.split('\n\n')).toHaveLength(2);
    expect(text).toContain('合同号：CS-A');
    expect(text).toContain('合同号：CS-B');
    expect(text).toContain('提交人：李四');
  });

  it('空数组返回空串', () => {
    expect(pendingDeliveryCopyText([])).toBe('');
  });
});
