import {
  CheckCircleOutlined,
  FileTextOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  Modal,
  message,
  Radio,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type PendingDeliveryStatsRow,
  type PendingDeliveryEntry,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const STATUS_META: Record<
  PendingDeliveryStatsRow['status'],
  { label: string; color: string }
> = {
  PENDING: { label: '待确认', color: 'default' },
  CONFIRMABLE: { label: '可交付', color: 'success' },
  UNCONFIRMABLE: { label: '无法按期交付', color: 'error' },
};

const fmtWan = (value: number) => (value / 10000).toFixed(1);

/** 按月待交付统计 + 与销售一一确认（含备注）。月份=应收日期月（空回落收款销售月份）；待交付=交付日期为空或晚于今天；测试合同已剔除。 */
export default function DeliveryConfirm() {
  const currentYear = new Date().getFullYear();
  const [year, setYear] = useState(currentYear);
  const [rows, setRows] = useState<PendingDeliveryStatsRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [confirmTarget, setConfirmTarget] = useState<PendingDeliveryStatsRow>();
  const [confirmStatus, setConfirmStatus] =
    useState<PendingDeliveryStatsRow['status']>('CONFIRMABLE');
  const [confirmRemark, setConfirmRemark] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [detailTarget, setDetailTarget] = useState<PendingDeliveryStatsRow>();
  const [detailRows, setDetailRows] = useState<PendingDeliveryEntry[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await superworkApi.getPendingDeliveryStats(year));
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : '待交付统计加载失败');
    } finally {
      setLoading(false);
    }
  }, [year]);
  useEffect(() => {
    void load();
  }, [load]);

  const summary = useMemo(() => {
    const amount = rows.reduce((sum, row) => sum + row.pendingAmount, 0);
    return {
      groups: rows.length,
      entries: rows.reduce((sum, row) => sum + row.entryCount, 0),
      amount,
      unconfirmed: rows.filter((row) => row.status === 'PENDING').length,
    };
  }, [rows]);

  const openConfirm = (row: PendingDeliveryStatsRow) => {
    setConfirmTarget(row);
    setConfirmStatus(row.status === 'PENDING' ? 'CONFIRMABLE' : row.status);
    setConfirmRemark(row.remark || '');
  };
  const submitConfirm = async () => {
    if (!confirmTarget || confirmTarget.bizLineId == null) return;
    setConfirming(true);
    try {
      await superworkApi.confirmPendingDelivery({
        yearMonth: confirmTarget.yearMonth,
        bizLineId: confirmTarget.bizLineId,
        salesOwner:
          confirmTarget.salesOwner === '未标注销售'
            ? undefined
            : confirmTarget.salesOwner,
        status: confirmStatus,
        remark: confirmRemark.trim() || undefined,
      });
      message.success('确认结果已保存');
      setConfirmTarget(undefined);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '确认保存失败');
    } finally {
      setConfirming(false);
    }
  };
  const openDetail = async (row: PendingDeliveryStatsRow) => {
    setDetailTarget(row);
    setDetailLoading(true);
    try {
      setDetailRows(
        await superworkApi.getPendingDeliveryEntries({
          month: row.yearMonth,
          bizLineId: row.bizLineId,
          salesOwner:
            row.salesOwner === '未标注销售' ? undefined : row.salesOwner,
        }),
      );
    } catch (e) {
      setDetailRows([]);
      message.error(e instanceof Error ? e.message : '明细加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  /** 连续相同 月份 / 月份+业务线 的行做纵向单元格合并（后端已按此序排序）。 */
  const [monthSpans, lineSpans] = useMemo(() => {
    const months: number[] = new Array(rows.length).fill(0);
    const lines: number[] = new Array(rows.length).fill(0);
    let i = 0;
    while (i < rows.length) {
      let j = i + 1;
      while (j < rows.length && rows[j].yearMonth === rows[i].yearMonth) j++;
      months[i] = j - i;
      i = j;
    }
    i = 0;
    while (i < rows.length) {
      let j = i + 1;
      while (
        j < rows.length &&
        rows[j].yearMonth === rows[i].yearMonth &&
        rows[j].bizLineId === rows[i].bizLineId
      )
        j++;
      lines[i] = j - i;
      i = j;
    }
    return [months, lines];
  }, [rows]);

  return (
    <div className="sw-delivery-confirm">
      <Row gutter={[12, 12]} className="sw-stat-row">
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="待交付分组" value={summary.groups} suffix="组" />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="待交付合同"
              value={summary.entries}
              suffix="笔"
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="待交付金额"
              value={fmtWan(summary.amount)}
              suffix="万元"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="待确认"
              value={summary.unconfirmed}
              suffix="组"
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
      </Row>
      <Card
        variant="borderless"
        className="sw-table-card"
        title="按月待交付（应收月份 × 业务线 × 销售）"
        extra={
          <Space>
            <Select
              value={year}
              style={{ width: 110 }}
              options={[currentYear - 1, currentYear, currentYear + 1].map((value) => ({
                value,
                label: `${value} 年`,
              }))}
              onChange={setYear}
            />
            <Button
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={() => void load()}
            >
              刷新
            </Button>
          </Space>
        }
      >
        {error && (
          <Typography.Text type="danger">{error}</Typography.Text>
        )}
        <Table
          rowKey={(row) =>
            `${row.yearMonth}|${row.bizLineId}|${row.salesOwner}`
          }
          loading={loading}
          dataSource={rows}
          pagination={false}
          locale={{
            emptyText: '本年暂无待交付合同（交付日期为空或晚于今天才计入）',
          }}
          columns={[
            {
              title: '月份',
              dataIndex: 'yearMonth',
              width: 100,
              onCell: (_row, index) => ({ rowSpan: monthSpans[index ?? 0] }),
            },
            {
              title: '业务线',
              dataIndex: 'bizLineName',
              width: 180,
              onCell: (_row, index) => ({ rowSpan: lineSpans[index ?? 0] }),
            },
            { title: '销售', dataIndex: 'salesOwner', width: 110 },
            {
              title: '待交付笔数',
              dataIndex: 'entryCount',
              width: 100,
              align: 'right',
            },
            {
              title: '待交付金额',
              dataIndex: 'pendingAmount',
              width: 130,
              align: 'right',
              render: (value: number) => `${fmtWan(value)} 万`,
            },
            {
              title: '确认状态',
              dataIndex: 'status',
              width: 120,
              render: (value: PendingDeliveryStatsRow['status']) => (
                <Tag color={STATUS_META[value].color}>
                  {STATUS_META[value].label}
                </Tag>
              ),
            },
            {
              title: '备注',
              dataIndex: 'remark',
              ellipsis: true,
              render: (value?: string | null) => value || '—',
            },
            {
              title: '确认人 / 时间',
              width: 170,
              render: (_: unknown, row: PendingDeliveryStatsRow) =>
                row.confirmedByName
                  ? `${row.confirmedByName} · ${(row.confirmedAt || '').replace('T', ' ').slice(0, 16)}`
                  : '—',
            },
            {
              title: '操作',
              width: 150,
              render: (_: unknown, row: PendingDeliveryStatsRow) => (
                <Space size={0}>
                  <Button type="link" size="small" onClick={() => void openDetail(row)}>
                    明细
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    disabled={row.bizLineId == null}
                    onClick={() => openConfirm(row)}
                  >
                    {row.status === 'PENDING' ? '确认' : '改确认'}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={
          confirmTarget
            ? `确认 ${confirmTarget.yearMonth} · ${confirmTarget.bizLineName} · ${confirmTarget.salesOwner}`
            : '确认待交付'
        }
        open={!!confirmTarget}
        onCancel={() => setConfirmTarget(undefined)}
        onOk={() => void submitConfirm()}
        confirmLoading={confirming}
        okText="保存确认"
        cancelText="取消"
      >
        {confirmTarget && (
          <Form layout="vertical">
            <Form.Item
              label={`该组共 ${confirmTarget.entryCount} 笔、${fmtWan(confirmTarget.pendingAmount)} 万元待交付，与销售确认后选择结论`}
            >
              <Radio.Group
                value={confirmStatus}
                onChange={(event) => setConfirmStatus(event.target.value)}
                options={[
                  { value: 'CONFIRMABLE', label: '可在当月交付' },
                  { value: 'UNCONFIRMABLE', label: '无法按期交付' },
                  { value: 'PENDING', label: '重置为待确认' },
                ]}
              />
            </Form.Item>
            <Form.Item label="备注（与销售的沟通结论）">
              <Input.TextArea
                value={confirmRemark}
                onChange={(event) => setConfirmRemark(event.target.value)}
                rows={4}
                maxLength={500}
                showCount
                placeholder="如：与张三电话确认，惠氏项目 9 月 28 日可交付；玛氏因客户验收推迟到 10 月"
              />
            </Form.Item>
          </Form>
        )}
      </Modal>

      <Drawer
        title={
          detailTarget
            ? `待交付明细 · ${detailTarget.yearMonth} · ${detailTarget.bizLineName} · ${detailTarget.salesOwner}`
            : '待交付明细'
        }
        size={720}
        open={!!detailTarget}
        onClose={() => setDetailTarget(undefined)}
      >
        <Table
          rowKey="id"
          loading={detailLoading}
          dataSource={detailRows}
          pagination={false}
          size="small"
          columns={[
            { title: '合同号', dataIndex: 'contractNo', width: 150 },
            {
              title: '合同 / 款项内容',
              dataIndex: 'contractName',
              ellipsis: true,
              render: (_: unknown, row: PendingDeliveryEntry) => (
                <div>
                  <div>{row.contractName || '—'}</div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {row.itemDesc || ''}
                  </Typography.Text>
                </div>
              ),
            },
            { title: '客户', dataIndex: 'customer', width: 150, ellipsis: true },
            {
              title: '应收金额',
              dataIndex: 'receivableAmount',
              width: 110,
              align: 'right',
              render: (value?: number | null) =>
                value == null ? '—' : `${fmtWan(value)} 万`,
            },
            {
              title: '交付日期',
              dataIndex: 'deliveryDate',
              width: 110,
              render: (value?: string | null) => value || '未定',
            },
          ]}
        />
      </Drawer>
    </div>
  );
}
