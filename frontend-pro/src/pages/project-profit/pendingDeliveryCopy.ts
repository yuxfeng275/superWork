import type { PendingDeliveryEntry } from '@/services/superwork/api';

const text = (value?: string | null) => {
  const trimmed = value?.trim();
  return trimmed ? trimmed : '—';
};

/**
 * 单条待交付合同的复制文本：合同号 / 合同名称 / 客户 / 款项内容 / 服务结束时间 / 提交人。
 * 供复制后发给对应销售确认是否可交付使用。
 */
export const pendingDeliveryEntryText = (entry: PendingDeliveryEntry): string =>
  [
    `合同号：${text(entry.contractNo)}`,
    `合同名称：${text(entry.contractName)}`,
    `客户：${text(entry.customer)}`,
    `款项内容：${text(entry.itemDesc)}`,
    `服务结束时间：${entry.serviceEndDate?.trim() ? entry.serviceEndDate : '缺失'}`,
    `提交人：${text(entry.salesOwner)}`,
  ].join('\n');

/** 多条待交付合同：逐条复制、空行分隔，便于一次性发给同一销售/同一月份。 */
export const pendingDeliveryCopyText = (
  entries: PendingDeliveryEntry[],
): string => entries.map(pendingDeliveryEntryText).join('\n\n');
