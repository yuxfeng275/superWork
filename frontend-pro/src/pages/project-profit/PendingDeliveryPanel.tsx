import {
  Alert,
  Button,
  Empty,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { QuestionCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type PendingDeliveryEntry,
  type PendingDeliveryGroup,
  type PendingDeliveryReport,
  superworkApi,
} from '@/services/superwork/api';

/** 元 → 万（与项目利润表同口径展示） */
const formatWan = (value?: number | null) => {
  if (value == null) return '—';
  const wan = Math.round((Number(value) / 10000) * 100) / 100;
  return wan.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

interface Props {
  year: number;
  /** 确认/取消后回调（刷新项目利润报表） */
  onChanged: () => void;
}

/**
 * 待交付合同确认面板：当年 delivery_date 为空的合同明细，按预计交付月份（服务结束时间所在月）分组。
 * 支持按明细勾选批量确认、按提交人一键确认；已确认可取消。
 * 确认后计入项目利润预估块的营收与利润；未确认仅以「+xx 待确认」展示，不计入利润。
 * 「未定月份」（服务结束时间缺失）仅展示标记，不进任何月份预估。
 */
export default function PendingDeliveryPanel({ year, onChanged }: Props) {
  const [data, setData] = useState<PendingDeliveryReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const report = await superworkApi.getPendingDelivery(year);
      setData(report);
      setSelected(new Set());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载待交付合同失败');
    } finally {
      setLoading(false);
    }
  }, [year]);

  useEffect(() => {
    void load();
  }, [load]);

  const selectableIds = useMemo(
    () =>
      new Set(
        (data?.groups ?? [])
          .flatMap((g) => g.entries)
          .filter((e) => !e.confirmed)
          .map((e) => e.id),
      ),
    [data],
  );

  const runAction = async (action: 'confirm' | 'revoke', entryIds: number[]) => {
    if (entryIds.length === 0) return;
    setActionLoading(true);
    try {
      const count =
        action === 'confirm'
          ? await superworkApi.confirmPendingDelivery(entryIds)
          : await superworkApi.revokePendingDelivery(entryIds);
      message.success(action === 'confirm' ? `已确认 ${count} 条` : `已取消确认 ${count} 条`);
      await load();
      onChanged();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  const renderGroup = (group: PendingDeliveryGroup) => {
    // 按提交人聚合（仅未确认部分），用于一键确认某人名下全部待交付
    const byOwner = new Map<string, PendingDeliveryEntry[]>();
    for (const entry of group.entries) {
      if (entry.confirmed) continue;
      const owner = entry.salesOwner || '未填写';
      byOwner.set(owner, [...(byOwner.get(owner) ?? []), entry]);
    }
    const groupSelectable = group.entries.filter((e) => !e.confirmed).map((e) => e.id);

    return (
      <div key={group.month ?? 'unknown'} style={{ marginBottom: 24 }}>
        <Space align="center" size={8} style={{ marginBottom: 8 }} wrap>
          <Typography.Title level={5} style={{ margin: 0 }}>
            {group.label}
          </Typography.Title>
          {group.warning === 'overdue' && (
            <Tag icon={<WarningOutlined />} color="red">
              已逾期
            </Tag>
          )}
          {group.warning === 'unknown' && (
            <Tag icon={<WarningOutlined />} color="orange">
              服务结束时间缺失
            </Tag>
          )}
          <Typography.Text type="secondary">
            共 {group.entryCount} 笔 · 含税合计 {formatWan(group.totalAmount)} 万 · 已确认{' '}
            {group.confirmedCount} 笔 / {formatWan(group.confirmedAmount)} 万
          </Typography.Text>
        </Space>
        {group.warning === 'unknown' && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8 }}
            message="以下合同缺失服务结束时间，不计入任何月份的预估；请与合同提交人确认并到工时系统补录，补录后每日同步自动归位到对应月份。"
          />
        )}
        {byOwner.size > 0 && (
          <Space size={8} wrap style={{ marginBottom: 8 }}>
            <Typography.Text type="secondary">按提交人确认：</Typography.Text>
            {[...byOwner.entries()].map(([owner, entries]) => {
              const amount = entries.reduce(
                (sum, e) => sum + Number(e.receivableAmount ?? 0),
                0,
              );
              return (
                <Button
                  key={owner}
                  size="small"
                  disabled={actionLoading}
                  onClick={() => void runAction('confirm', entries.map((e) => e.id))}
                >
                  {owner}（{entries.length} 笔 / {formatWan(amount)} 万）确认全部
                </Button>
              );
            })}
          </Space>
        )}
        <Table<PendingDeliveryEntry>
          rowKey="id"
          size="small"
          dataSource={group.entries}
          pagination={false}
          rowSelection={{
            selectedRowKeys: groupSelectable.filter((id) => selected.has(id)),
            getCheckboxProps: (entry) => ({ disabled: entry.confirmed }),
            onChange: (keys) => {
              const next = new Set(selected);
              for (const id of groupSelectable) next.delete(id);
              for (const key of keys) next.add(Number(key));
              setSelected(next);
            },
          }}
          columns={[
            {
              title: '合同号',
              dataIndex: 'contractNo',
              width: 110,
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '合同名称',
              dataIndex: 'contractName',
              width: 200,
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '客户',
              dataIndex: 'customer',
              width: 140,
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '款项内容',
              dataIndex: 'itemDesc',
              width: 160,
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '应收金额(万,含税)',
              dataIndex: 'receivableAmount',
              width: 120,
              align: 'right',
              render: (v: number | null) => formatWan(v),
            },
            {
              title: '提交人',
              dataIndex: 'salesOwner',
              width: 80,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '服务结束时间',
              dataIndex: 'serviceEndDate',
              width: 110,
              render: (v: string | null) =>
                v ?? <Typography.Text type="warning">缺失</Typography.Text>,
            },
            {
              title: '业务线 / 项目',
              width: 160,
              ellipsis: true,
              render: (_, entry) =>
                [entry.businessLineName, entry.projectName].filter(Boolean).join(' / ') || '—',
            },
            {
              title: '状态',
              width: 150,
              render: (_, entry) =>
                entry.confirmed ? (
                  <Tooltip
                    title={`${entry.confirmedByName ?? ''} 于 ${entry.confirmedAt ?? ''} 确认`}
                  >
                    <Tag color="green">已确认</Tag>
                  </Tooltip>
                ) : (
                  <Tag>未确认</Tag>
                ),
            },
            {
              title: '操作',
              width: 90,
              render: (_, entry) =>
                entry.confirmed ? (
                  <Typography.Link
                    disabled={actionLoading}
                    onClick={() => void runAction('revoke', [entry.id])}
                  >
                    取消确认
                  </Typography.Link>
                ) : (
                  <Typography.Link
                    disabled={actionLoading}
                    onClick={() => void runAction('confirm', [entry.id])}
                  >
                    确认
                  </Typography.Link>
                ),
            },
          ]}
        />
      </div>
    );
  };

  const selectedCount = [...selected].filter((id) => selectableIds.has(id)).length;

  return (
    <Spin spinning={loading}>
      <Alert
        type="info"
        showIcon
        icon={<QuestionCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="待交付 = 工时系统销售合同中交付时间为空的明细；预计交付月份 = 服务结束时间所在月（早于当月的已逾期合同并入当月预估）。确认后计入项目利润预估营收与利润；未确认仅以「+xx 待确认」展示，不计入利润。合同实际交付后自动从本列表消失。"
      />
      {data && data.groups.length === 0 && (
        <Empty description={`${year} 年暂无待交付合同`} />
      )}
      {(data?.groups ?? []).map(renderGroup)}
      {selectedCount > 0 && (
        <Space
          style={{
            position: 'sticky',
            bottom: 0,
            background: '#fff',
            padding: '8px 0',
            borderTop: '1px solid #f0f0f0',
          }}
        >
          <Typography.Text>已选 {selectedCount} 条（未确认）</Typography.Text>
          <Button
            type="primary"
            size="small"
            loading={actionLoading}
            onClick={() => void runAction('confirm', [...selected].filter((id) => selectableIds.has(id)))}
          >
            批量确认
          </Button>
          <Button size="small" onClick={() => setSelected(new Set())}>
            清空选择
          </Button>
        </Space>
      )}
    </Spin>
  );
}
