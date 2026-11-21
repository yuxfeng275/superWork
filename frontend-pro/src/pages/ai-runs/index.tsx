import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type AiRunDetail,
  type AiRunNodeRow,
  type AiRunRow,
  superworkApi,
} from '@/services/superwork/api';

const RUN_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待开始' },
  RUNNING: { color: 'processing', label: '进行中' },
  WAITING_HUMAN: { color: 'gold', label: '等人审批' },
  SUCCEEDED: { color: 'green', label: '完成' },
  PARTIAL: { color: 'orange', label: '部分完成' },
  FAILED: { color: 'red', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

const NODE_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待开始' },
  READY: { color: 'blue', label: '就绪' },
  RUNNING: { color: 'processing', label: '执行中' },
  WAITING_GATE: { color: 'gold', label: '待审批' },
  SUCCEEDED: { color: 'green', label: '成功' },
  FAILED: { color: 'red', label: '失败' },
  UNKNOWN: { color: 'volcano', label: '结果未知' },
  CANCELLED: { color: 'default', label: '已取消' },
};

const time = (value?: string | null) =>
  value ? value.replace('T', ' ').slice(0, 19) : '—';

/**
 * AI 运行面：昨天夜里到底跑了什么、卡在哪、要不要人接手。
 *
 * <p>只读展示 + 手动补跑，不做流程编辑：流程由 SOP 决定，页面只负责让人看懂结果。
 * 「结果未知」单独一种颜色，因为它的含义是「可能已经写了」，和失败完全不同。
 */
export default function AiRunsPage() {
  const [runs, setRuns] = useState<AiRunRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | undefined>();
  const [detail, setDetail] = useState<AiRunDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRuns(await superworkApi.listAiRuns({ status }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (row: AiRunRow) => {
    setDetailLoading(true);
    try {
      setDetail(await superworkApi.getAiRun(row.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const columns: TableProps<AiRunRow>['columns'] = [
    {
      title: '批次',
      dataIndex: 'runNo',
      width: 190,
      render: (value: string | null, row) => (
        <Typography.Link onClick={() => void openDetail(row)}>
          {value || `#${row.id}`}
        </Typography.Link>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => {
        const meta = RUN_STATUS[value] || { color: 'default', label: value };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    { title: '类型', dataIndex: 'kind', width: 130 },
    { title: 'SOP', dataIndex: 'sopCode', width: 140 },
    { title: '项目', dataIndex: 'projectId', width: 90, render: (v?) => v ?? '—' },
    { title: '业务日', dataIndex: 'bizDate', width: 110 },
    { title: '触发', dataIndex: 'triggerSource', width: 100 },
    {
      title: '开始',
      dataIndex: 'startedAt',
      width: 170,
      render: (value?: string | null) => time(value),
    },
    {
      title: '结束',
      dataIndex: 'finishedAt',
      width: 170,
      render: (value?: string | null) => time(value),
    },
    {
      title: '结论',
      dataIndex: 'summary',
      ellipsis: true,
      render: (value?: string | null) => value || '—',
    },
  ];

  const nodeColumns: TableProps<AiRunNodeRow>['columns'] = [
    { title: '#', dataIndex: 'seq', width: 50 },
    { title: '节点', dataIndex: 'nodeKey', width: 120 },
    { title: '执行器', dataIndex: 'executorKind', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => {
        const meta = NODE_STATUS[value] || { color: 'default', label: value };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '尝试',
      dataIndex: 'attempt',
      width: 90,
      render: (value: number, row) => `${value}/${row.maxAttempt}`,
    },
    { title: '返工', dataIndex: 'reworkCount', width: 70 },
    {
      title: '错误',
      dataIndex: 'errorMessage',
      ellipsis: true,
      render: (value?: string | null, row) =>
        value ? `${row.errorCode || ''} ${value}` : '—',
    },
    {
      title: '结束',
      dataIndex: 'finishedAt',
      width: 170,
      render: (value?: string | null) => time(value),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <ThunderboltOutlined />
            AI 运行
          </Space>
        }
        extra={
          <Space>
            <Select
              allowClear
              placeholder="按状态过滤"
              style={{ width: 160 }}
              value={status}
              onChange={(value) => setStatus(value)}
              options={Object.entries(RUN_STATUS).map(([value, meta]) => ({
                value,
                label: meta.label,
              }))}
            />
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary">
          夜间批由合拍编排：事实 → 制品 → 晨报。触发器只负责「什么时候跑」，
          流程、闸门与证据都在这里。标记为「结果未知」的节点表示可能已经写入了业务数据，
          必须先核对再决定是否重跑。
        </Typography.Paragraph>
        {error ? <Alert type="error" showIcon message={error} /> : null}
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={runs}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '还没有运行记录' }}
        />
      </Card>

      <Drawer
        width={860}
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={`运行详情 ${detail?.run.runNo || ''}`}
        destroyOnClose
      >
        {detail ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="状态">
                {RUN_STATUS[detail.run.status]?.label || detail.run.status}
              </Descriptions.Item>
              <Descriptions.Item label="SOP">
                {detail.run.sopCode} v{detail.run.sopVersion}
              </Descriptions.Item>
              <Descriptions.Item label="业务日">{detail.run.bizDate}</Descriptions.Item>
              <Descriptions.Item label="触发">{detail.run.triggerSource}</Descriptions.Item>
              <Descriptions.Item label="开始">{time(detail.run.startedAt)}</Descriptions.Item>
              <Descriptions.Item label="结束">{time(detail.run.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label="结论" span={2}>
                {detail.run.summary || '—'}
              </Descriptions.Item>
            </Descriptions>
            <Table
              rowKey="id"
              size="small"
              loading={detailLoading}
              columns={nodeColumns}
              dataSource={detail.nodes}
              pagination={false}
            />
          </Space>
        ) : null}
      </Drawer>
    </Space>
  );
}
