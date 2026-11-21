import { CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Modal,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type ConfirmationItem,
  type ConfirmationQueue,
  superworkApi,
} from '@/services/superwork/api';

const RISK: Record<string, { color: string; label: string }> = {
  LOW: { color: 'green', label: '低风险' },
  MEDIUM: { color: 'orange', label: '需逐条看' },
  HIGH: { color: 'red', label: '阻塞夜间批' },
};

/**
 * 待我确认：把「夜间批等人放行」和「制品评审」合成一个队列，尽量少点。
 *
 * <p>低风险（方案 / 修复说明 / 清单 / 决策记录）可以一次全过；
 * 补丁类必须逐条看；打回必须写原因，因为原因会进决策制品而不是只留在状态里。
 */
export default function ConfirmationsPage() {
  const [data, setData] = useState<ConfirmationQueue | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await superworkApi.getConfirmations());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const gates = data?.queue.gates ?? [];
  const artifacts = data?.queue.artifacts ?? [];
  const lowRiskIds = useMemo(
    () => artifacts.filter((item) => item.risk === 'LOW').map((item) => item.id),
    [artifacts],
  );

  const run = useCallback(
    async (body: Parameters<typeof superworkApi.batchConfirm>[0]) => {
      setBusy(true);
      try {
        const result = await superworkApi.batchConfirm(body);
        if (result.failures.length) {
          Modal.warning({
            title: `成功 ${result.approved} 条，失败 ${result.failures.length} 条`,
            content: (
              <ul style={{ paddingLeft: 18 }}>
                {result.failures.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            ),
          });
        } else {
          message.success(`已处理 ${result.approved} 条`);
        }
        setSelected([]);
        await load();
      } catch (e) {
        message.error(e instanceof Error ? e.message : '操作失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const rejectSelected = useCallback(() => {
    let reason = '';
    Modal.confirm({
      title: `打回选中的 ${selected.length} 条`,
      content: (
        <div>
          <Typography.Paragraph type="secondary">
            原因会写进决策制品，夜间批据此重做。必填。
          </Typography.Paragraph>
          <textarea
            rows={3}
            style={{ width: '100%' }}
            placeholder="例如：口径不对，需按 11-30 上新范围重写"
            onChange={(event) => {
              reason = event.target.value;
            }}
          />
        </div>
      ),
      onOk: async () => {
        if (!reason.trim()) {
          message.warning('打回必须写原因');
          return Promise.reject();
        }
        await run({ artifactIds: selected, approve: false, reason: reason.trim() });
      },
    });
  }, [run, selected]);

  const artifactColumns: TableProps<ConfirmationItem>['columns'] = [
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '风险',
      dataIndex: 'risk',
      width: 110,
      render: (value: string) => {
        const meta = RISK[value] || { color: 'default', label: value };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    { title: '项目', dataIndex: 'projectId', width: 80, render: (v?: number) => v ?? '—' },
    {
      title: '摘要',
      dataIndex: 'detail',
      ellipsis: true,
      render: (v?: string | null) => v || '—',
    },
  ];

  const gateColumns: TableProps<ConfirmationItem>['columns'] = [
    { title: '等待确认', dataIndex: 'title' },
    { title: '说明', dataIndex: 'detail', ellipsis: true },
    {
      title: '期望值',
      width: 140,
      render: (_: unknown, row) => `attempt=${row.expectedAttempt} rework=${row.expectedRework}`,
    },
    { title: '创建', dataIndex: 'createdAt', width: 170 },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <CheckCircleOutlined />
            待我确认
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        }
      >
        <Typography.Paragraph type="secondary">
          一个队列处理两件事：夜间批停在节点上等你放行（阻塞），以及夜间产出的制品等你评审（不阻塞但会被忘）。
          低风险项可以一次全过，补丁类建议逐条看。
        </Typography.Paragraph>
        {error ? <Alert type="error" showIcon message={error} /> : null}
        <Row gutter={16}>
          <Col xs={12} md={6}>
            <Statistic title="待确认总数" value={data?.queue ? gates.length + artifacts.length : 0} />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="阻塞夜间批（闸门）" value={gates.length} />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="待评审制品" value={artifacts.length} />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="低风险可一次通过" value={data?.lowRiskCount ?? 0} />
          </Col>
        </Row>
      </Card>

      <Card
        size="small"
        title="阻塞夜间批的确认"
        extra={
          gates.length ? (
            <Button
              type="primary"
              loading={busy}
              onClick={() =>
                void run({
                  gates: gates.map((item) => ({
                    id: item.id,
                    expectedAttempt: item.expectedAttempt ?? 0,
                    expectedRework: item.expectedRework ?? 0,
                  })),
                  approve: true,
                })
              }
            >
              全部放行
            </Button>
          ) : null
        }
      >
        <Table
          rowKey="id"
          size="small"
          columns={gateColumns}
          dataSource={gates}
          pagination={false}
          locale={{ emptyText: '没有卡住的夜间批' }}
        />
      </Card>

      <Card
        size="small"
        title="待评审制品"
        extra={
          <Space>
            <Button
              disabled={!selected.length}
              loading={busy}
              onClick={() => void run({ artifactIds: selected, approve: true })}
            >
              通过选中（{selected.length}）
            </Button>
            <Button danger disabled={!selected.length} loading={busy} onClick={rejectSelected}>
              打回选中
            </Button>
            <Button
              type="primary"
              disabled={!lowRiskIds.length}
              loading={busy}
              onClick={() => void run({ artifactIds: lowRiskIds, approve: true })}
            >
              一次通过低风险（{lowRiskIds.length}）
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          size="small"
          columns={artifactColumns}
          dataSource={artifacts}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          rowSelection={{
            selectedRowKeys: selected,
            onChange: (keys) => setSelected(keys as number[]),
          }}
          locale={{ emptyText: '没有待评审制品' }}
        />
      </Card>
    </Space>
  );
}
