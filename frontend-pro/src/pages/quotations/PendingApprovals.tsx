import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Card, Empty, Input, message, Modal, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type QuotationDiscountRequest,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';

const pct = (value?: number) =>
  value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`;

/** 折扣审批工作台：低于策略底价的报价必须先在这里放行。 */
export default function PendingApprovals() {
  const [rows, setRows] = useState<QuotationDiscountRequest[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await superworkApi.getQuotationDiscountRequests('PENDING'));
    } catch (e) {
      setRows([]);
      message.error(e instanceof Error ? e.message : '审批列表加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const decide = (row: QuotationDiscountRequest, status: 'APPROVED' | 'REJECTED') => {
    let comment = '';
    Modal.confirm({
      title: status === 'APPROVED' ? '通过该折扣申请？' : '驳回该折扣申请？',
      content: (
        <Input.TextArea
          rows={3}
          placeholder="审批意见"
          onChange={(e) => {
            comment = e.target.value;
          }}
        />
      ),
      okText: '确认',
      okButtonProps: { danger: status === 'REJECTED' },
      onOk: async () => {
        await superworkApi.decideQuotationDiscount(row.id, { status, comment });
        message.success('已处理');
        await load();
      },
    });
  };

  const columns: TableProps<QuotationDiscountRequest>['columns'] = [
    {
      title: '报价单',
      dataIndex: 'quotationId',
      render: (value) => (
        <Typography.Link onClick={() => history.push(`/quotations/${value}`)}>
          #{value}
        </Typography.Link>
      ),
    },
    { title: '申请人', dataIndex: 'requestedBy', width: 120 },
    {
      title: '申请折扣',
      dataIndex: 'requestedDiscount',
      width: 110,
      render: (value) => <Tag color="warning">{pct(value)}</Tag>,
    },
    {
      title: '策略底价',
      dataIndex: 'floorDiscount',
      width: 110,
      render: (value) => pct(value),
    },
    {
      title: '首年毛利率',
      dataIndex: 'grossMarginRate',
      width: 110,
      render: (value) => pct(value),
    },
    { title: '原因', dataIndex: 'reason' },
    {
      title: '操作',
      width: 180,
      render: (_, row) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            icon={<CheckOutlined />}
            onClick={() => decide(row, 'APPROVED')}
          >
            通过
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<CloseOutlined />}
            onClick={() => decide(row, 'REJECTED')}
          >
            驳回
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="sw-page sw-quotations">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">SALES / DISCOUNT APPROVAL</Typography.Text>
          <Typography.Title level={2}>折扣审批</Typography.Title>
          <Typography.Paragraph type="secondary">
            低于策略底价的报价需审批通过后才能进入「已批准」状态。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
      </div>
      <Card variant="borderless">
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无待审批的折扣申请" /> }}
        />
      </Card>
    </div>
  );
}
