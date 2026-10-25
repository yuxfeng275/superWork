import { CheckOutlined, CopyOutlined, StarOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Drawer, Empty, message, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { type QuotationListVO, superworkApi } from '@/services/superwork/api';

const money = (value?: number) =>
  value == null
    ? '—'
    : `¥ ${Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      })}`;

/** 同一商机下多方案横向比价：并排总额、毛利、状态，一键设首选或派生新版本。 */
export default function CompareDrawer({
  open,
  opportunityId,
  onClose,
  onChanged,
}: {
  open: boolean;
  opportunityId?: number;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const [rows, setRows] = useState<QuotationListVO[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!opportunityId) {
      setRows([]);
      return;
    }
    setLoading(true);
    try {
      const list = await superworkApi.getQuotations({ opportunityId, size: 50 });
      const records = Array.isArray(list) ? list : list?.records || [];
      setRows(records);
    } catch (e) {
      setRows([]);
      message.error(e instanceof Error ? e.message : '方案加载失败');
    } finally {
      setLoading(false);
    }
  }, [opportunityId]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const columns: TableProps<QuotationListVO>['columns'] = [
    {
      title: '报价单',
      dataIndex: 'quotationNo',
      render: (value, row) => (
        <div>
          <Typography.Link strong onClick={() => history.push(`/quotations/${row.id}`)}>
            {value}
          </Typography.Link>
          {row.isPreferred === 1 && (
            <Tag color="gold" style={{ marginLeft: 6 }}>
              首选
            </Tag>
          )}
          <Typography.Text type="secondary" style={{ display: 'block' }}>
            {row.variantName || '默认方案'} · V{row.versionNo || 1}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '首年含税',
      dataIndex: 'firstYearTotalInclTax',
      render: (value) => <Typography.Text strong>{money(value)}</Typography.Text>,
    },
    {
      title: '首年毛利',
      dataIndex: 'grossMarginRate',
      render: (value) =>
        value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (value, row) => (
        <Space size={4}>
          <Tag>{value}</Tag>
          {row.discountOverride === 1 && <Tag color="warning">超折扣</Tag>}
        </Space>
      ),
    },
    {
      title: '操作',
      width: 200,
      render: (_, row) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            icon={<StarOutlined />}
            disabled={row.isPreferred === 1}
            onClick={async () => {
              await superworkApi.setQuotationPreferred(row.id);
              message.success('已设为首选');
              await load();
              onChanged?.();
            }}
          >
            设首选
          </Button>
          <Button
            type="link"
            size="small"
            icon={<CopyOutlined />}
            onClick={async () => {
              const created = await superworkApi.createQuotationVersion(row.id);
              message.success(`已生成 ${created.quotationNo}`);
              await load();
              onChanged?.();
            }}
          >
            新版本
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Drawer
      title="方案比价"
      size={900}
      open={open}
      onClose={onClose}
      extra={
        <Button icon={<CheckOutlined />} onClick={onClose}>
          完成
        </Button>
      }
    >
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: <Empty description="该商机暂无报价方案" /> }}
      />
    </Drawer>
  );
}
