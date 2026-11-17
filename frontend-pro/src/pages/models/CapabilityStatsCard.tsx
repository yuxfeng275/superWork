import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type AiCapabilityStat,
  type AiCapabilityStats,
  superworkApi,
} from '@/services/superwork/api';

const percent = (value: number) => `${(value * 100).toFixed(1)}%`;

/** 失败率按颜色分档：超过 20% 才算「需要看」，不是所有非零都报警。 */
const rateColor = (value: number) => (value >= 0.2 ? 'red' : value > 0 ? 'orange' : 'green');

/**
 * 工具调用观测：哪个能力在跑、成功率/超时率/门禁拒绝率多少。
 *
 * <p>数字来自 ai_capability_call 审计表，允许丢行，所以是下限；只给管理岗位看。
 */
export default function CapabilityStatsCard() {
  const [stats, setStats] = useState<AiCapabilityStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setStats(await superworkApi.getAiCapabilityStats(7));
    } catch (e) {
      setError(e instanceof Error ? e.message : '工具调用统计加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const columns: TableProps<AiCapabilityStat>['columns'] = [
    {
      title: '能力',
      dataIndex: 'capabilityId',
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    { title: '调用', dataIndex: 'calls', width: 80 },
    {
      title: '成功率',
      dataIndex: 'successRate',
      width: 110,
      render: (value: number) => <Tag color={rateColor(1 - value)}>{percent(value)}</Tag>,
    },
    {
      title: '超时率',
      dataIndex: 'timeoutRate',
      width: 100,
      render: (value: number) => <Tag color={rateColor(value)}>{percent(value)}</Tag>,
    },
    {
      title: '门禁拒绝',
      dataIndex: 'gateDeniedRate',
      width: 110,
      render: (value: number) => <Tag color={rateColor(value)}>{percent(value)}</Tag>,
    },
    {
      title: '平均耗时',
      dataIndex: 'avgLatencyMs',
      width: 110,
      render: (value: number) => `${value} ms`,
    },
    {
      title: '最近调用',
      dataIndex: 'lastCalledAt',
      width: 170,
      render: (value?: string | null) =>
        value ? value.replace('T', ' ').slice(0, 16) : '-',
    },
  ];

  return (
    <Card
      size="small"
      title="工具调用（近 7 天）"
      style={{ marginTop: 16 }}
      extra={
        <Space>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        </Space>
      }
    >
      {error ? (
        <Alert type="warning" showIcon message={error} />
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            统计来自能力网关审计表（允许丢行，数字是下限）。成功率 = 成功 / 调用，
            超时与门禁拒绝同分母；连续超时的能力优先看侧车与下游接口。
          </Typography.Paragraph>
          <Table
            rowKey="capabilityId"
            size="small"
            loading={loading}
            columns={columns}
            dataSource={stats?.capabilities || []}
            pagination={false}
            locale={{ emptyText: '近 7 天没有能力调用记录' }}
          />
        </>
      )}
    </Card>
  );
}
