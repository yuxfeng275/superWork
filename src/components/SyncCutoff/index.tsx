import { Typography } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import { type SyncOverviewItem, superworkApi } from '@/services/superwork/api';

const DOMAIN_LABELS: Record<string, string> = {
  contract: '合同',
  worklog: '工时',
  cost: '成本',
  org: '组织',
  member: '人员',
};

/**
 * 数据血缘标注：展示指定数据域最近一次成功同步的范围/来源/时间。
 * 数据来自统一同步中心（/api/sync/overview），接口不可用时静默不渲染。
 */
export default function SyncCutoff({ domains }: { domains: string[] }) {
  const [items, setItems] = useState<SyncOverviewItem[]>([]);

  useEffect(() => {
    superworkApi
      .getSyncOverview()
      .then(setItems)
      .catch(() => {
        // 同步中心不可用时不影响页面主功能
      });
  }, []);

  const parts = domains
    .map((domain) => {
      const log = items.find((item) => item.domain === domain)?.lastSuccess;
      if (!log) return null;
      const time = log.finishedAt
        ? dayjs(log.finishedAt).format('MM-DD HH:mm')
        : '';
      return `${DOMAIN_LABELS[domain] || domain} ${log.scope || '全量'}（${log.sourceSystem} ${time}）`;
    })
    .filter(Boolean);

  if (parts.length === 0) return null;
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      数据截至：{parts.join(' · ')}
    </Typography.Text>
  );
}
