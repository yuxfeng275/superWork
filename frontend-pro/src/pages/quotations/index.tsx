import {
  ArrowLeftOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { history, useLocation, useModel } from '@umijs/max';
import type { MenuProps, TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type Quotation,
  type QuotationLineItem,
  type QuotationListVO,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import QuotationGenerateWizard from './GenerateWizard';
import './style.less';

const statuses = [
  'DRAFT',
  'INTERNAL_REVIEW',
  'APPROVED',
  'SENT',
  'ACCEPTED',
  'REJECTED',
  'EXPIRED',
];
const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  INTERNAL_REVIEW: '内部审核',
  APPROVED: '已批准',
  SENT: '已发送',
  ACCEPTED: '已接受',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
};
const statusColor: Record<string, string> = {
  DRAFT: 'default',
  INTERNAL_REVIEW: 'warning',
  APPROVED: 'processing',
  SENT: 'blue',
  ACCEPTED: 'success',
  REJECTED: 'error',
  EXPIRED: 'default',
};
const nextStatuses: Record<string, string[]> = {
  DRAFT: ['INTERNAL_REVIEW'],
  INTERNAL_REVIEW: ['APPROVED'],
  APPROVED: ['SENT'],
  SENT: ['ACCEPTED', 'REJECTED'],
};
const statusActionLabel: Record<string, string> = {
  INTERNAL_REVIEW: '提交审核',
  APPROVED: '批准',
  SENT: '标记已发送',
  ACCEPTED: '标记已接受',
  REJECTED: '标记已拒绝',
};
const taxLabel = (value?: string) =>
  value === 'TAX_INCLUDED' ? '含税' : '未税';
const money = (value?: number) =>
  value == null
    ? '—'
    : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export default function QuotationsPage() {
  const { initialState } = useModel('@@initialState');
  const location = useLocation();
  const canManage = [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(initialState?.currentUser?.role || '');
  const [filterForm] = Form.useForm<{
    keyword?: string;
    status?: string;
    customerName?: string;
  }>();
  const [rows, setRows] = useState<QuotationListVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<Quotation>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [wizardOpen, setWizardOpen] = useState(false);

  const load = useCallback(
    async (values = filterForm.getFieldsValue()) => {
      setLoading(true);
      setError('');
      try {
        const result = await superworkApi.getQuotations(values);
        setRows(Array.isArray(result) ? result : result?.records || []);
      } catch (e) {
        setRows([]);
        setError(e instanceof Error ? e.message : '报价单加载失败');
      } finally {
        setLoading(false);
      }
    },
    [filterForm],
  );
  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (rowOrId: QuotationListVO | number) => {
    const id = typeof rowOrId === 'number' ? rowOrId : rowOrId.id;
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      setDetail(await superworkApi.getQuotationDetail(id));
    } catch (e) {
      setDetail(undefined);
      message.error(e instanceof Error ? e.message : '报价单详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);
  useEffect(() => {
    const match = location.pathname.match(/^\/quotations\/(\d+)$/);
    if (match && !detailOpen) void openDetail(Number(match[1]));
  }, [detailOpen, location.pathname, openDetail]);

  const resetDetailRoute = () => {
    setDetailOpen(false);
    setDetail(undefined);
    if (location.pathname !== '/quotations') history.push('/quotations');
  };
  const exportQuotation = async (
    row: Pick<Quotation, 'id' | 'quotationNo'>,
  ) => {
    try {
      const blob = await superworkApi.exportQuotation(row.id);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `报价单_${row.quotationNo}.xlsx`;
      anchor.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };
  const updateStatus = (row: QuotationListVO, status: string) => {
    Modal.confirm({
      title: '确认变更报价单状态？',
      content: `将「${row.quotationNo}」变更为「${statusLabel[status]}」。`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await superworkApi.updateQuotationStatus(row.id, status);
        message.success('状态已更新');
        await load();
      },
    });
  };
  const remove = (row: QuotationListVO) => {
    Modal.confirm({
      title: '删除报价单',
      content: `确定删除「${row.quotationNo}」吗？此操作不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await superworkApi.deleteQuotation(row.id);
        message.success('报价单已删除');
        await load();
      },
    });
  };
  const updateDetailStatus = (status: string) => {
    if (!detail) return;
    Modal.confirm({
      title: '确认变更报价单状态？',
      content: `将「${detail.quotationNo}」变更为「${statusLabel[status]}」。`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await superworkApi.updateQuotationStatus(detail.id, status);
        message.success('状态已更新');
        await openDetail(detail.id);
        await load();
      },
    });
  };
  const removeDetail = () => {
    if (!detail) return;
    Modal.confirm({
      title: '删除报价单',
      content: `确定删除「${detail.quotationNo}」吗？此操作不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await superworkApi.deleteQuotation(detail.id);
        message.success('报价单已删除');
        resetDetailRoute();
        await load();
      },
    });
  };

  const quotationActionItems = (row: QuotationListVO): MenuProps['items'] => [
    ...(canManage
      ? (nextStatuses[row.status] || []).map((status) => ({
          key: `status:${status}`,
          label: statusActionLabel[status] || statusLabel[status],
        }))
      : []),
    ...(canManage && row.status === 'DRAFT'
      ? [
          { type: 'divider' as const },
          { key: 'remove', label: '删除', danger: true },
        ]
      : []),
  ];
  const handleQuotationAction = (row: QuotationListVO, key: string) => {
    if (key === 'remove') {
      remove(row);
      return;
    }
    if (key.startsWith('status:'))
      updateStatus(row, key.slice('status:'.length));
  };


  const columns: TableProps<QuotationListVO>['columns'] = [
    {
      title: '报价单号',
      dataIndex: 'quotationNo',
      width: 180,
      render: (value, row) => (
        <Typography.Link
          strong
          onClick={() => {
            history.push(`/quotations/${row.id}`);
            void openDetail(row);
          }}
        >
          {value}
        </Typography.Link>
      ),
    },
    {
      title: '客户名称',
      dataIndex: 'customerName',
      width: 180,
      render: (value) => value || '—',
    },
    {
      title: '首年含税总价',
      dataIndex: 'firstYearTotalInclTax',
      width: 150,
      render: (value) => (
        <Typography.Text strong>{money(value)}</Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value) => (
        <Tag color={statusColor[value]}>{statusLabel[value] || value}</Tag>
      ),
    },
    {
      title: '关联商机',
      dataIndex: 'opportunityName',
      width: 160,
      render: (value) => value || '—',
    },
    {
      title: '报价日期',
      dataIndex: 'quoteDate',
      width: 120,
      render: (value) => value?.slice(0, 10) || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 250,
      render: (_: unknown, row) => {
        const items = quotationActionItems(row) ?? [];
        return (
          <Space size={0}>
            <Button
              type="link"
              icon={<EyeOutlined />}
              onClick={() => {
                history.push(`/quotations/${row.id}`);
                void openDetail(row);
              }}
            >
              详情
            </Button>
            <Button
              type="link"
              icon={<DownloadOutlined />}
              onClick={() => void exportQuotation(row)}
            >
              导出
            </Button>
            {items.length > 0 && (
              <Dropdown
                trigger={['click']}
                menu={{
                  items,
                  onClick: ({ key }) => handleQuotationAction(row, key),
                }}
              >
                <Button type="link">操作</Button>
              </Dropdown>
            )}
          </Space>
        );
      },
    },
  ];
  const detailItems: TableProps<QuotationLineItem>['columns'] = [
    { title: '分组', dataIndex: 'section', width: 110 },
    {
      title: '明细项',
      dataIndex: 'itemName',
      render: (value, row) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          {row.description && (
            <Typography.Text type="secondary" className="sw-quotation-subtitle">
              {row.description}
            </Typography.Text>
          )}
        </div>
      ),
    },
    { title: '数量', dataIndex: 'quantity', width: 70 },
    {
      title: '单价',
      dataIndex: 'unitPriceExTax',
      width: 110,
      render: (value) => money(value),
    },
    {
      title: '折扣',
      dataIndex: 'discountRate',
      width: 80,
      render: (value) => `${Number(value || 0) * 100}%`,
    },
    {
      title: '小计',
      dataIndex: 'subtotalInclTax',
      width: 120,
      render: (value, row) =>
        money(detail?.taxMode === 'TAX_INCLUDED' ? value : row.subtotalExTax),
    },
  ];
  return (
    <div className="sw-page sw-quotations">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SALES / QUOTATIONS
          </Typography.Text>
          <Typography.Title level={2}>报价单管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            从已发布报价策略生成报价单，跟踪审核、发送与成交状态。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setWizardOpen(true)}
            >
              新建报价单
            </Button>
          )}
        </Space>
      </div>
      <Card variant="borderless" className="sw-filter-card">
        <Form form={filterForm} layout="inline" onFinish={() => void load()}>
          <Form.Item name="keyword" label="关键词">
            <Input allowClear placeholder="报价单号 / 客户名称" />
          </Form.Item>
          <Form.Item name="customerName" label="客户">
            <Input allowClear placeholder="客户名称" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 140 }}
              options={statuses.map((status) => ({
                label: statusLabel[status],
                value: status,
              }))}
            />
          </Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button
              onClick={() => {
                filterForm.resetFields();
                void load({});
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取报价单"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Card variant="borderless" className="sw-table-card">
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ x: 1180 }}
          locale={{ emptyText: <Empty description="暂无报价单" /> }}
        />
      </Card>
      <QuotationGenerateWizard
        open={wizardOpen}
        onClose={() => setWizardOpen(false)}
        onGenerated={() => void load()}
      />
      <Drawer
        title={detail?.quotationNo || '报价单详情'}
        size={760}
        open={detailOpen}
        onClose={resetDetailRoute}
        loading={detailLoading}
        extra={
          detail && (
            <Space wrap>
              <Button icon={<ArrowLeftOutlined />} onClick={resetDetailRoute}>
                返回列表
              </Button>
              <Button
                icon={<DownloadOutlined />}
                onClick={() => void exportQuotation(detail)}
              >
                导出 Excel
              </Button>
              {canManage &&
                (nextStatuses[detail.status] || []).map((status) => (
                  <Button
                    key={status}
                    type="primary"
                    onClick={() => updateDetailStatus(status)}
                  >
                    {statusLabel[status]}
                  </Button>
                ))}
              {canManage && detail.status === 'DRAFT' && (
                <Button danger icon={<DeleteOutlined />} onClick={removeDetail}>
                  删除
                </Button>
              )}
            </Space>
          )
        }
      >
        {detail && (
          <>
            <Space wrap>
              <Tag color={statusColor[detail.status]}>
                {statusLabel[detail.status] || detail.status}
              </Tag>
              <Typography.Text type="secondary">
                报价日期：{detail.quoteDate?.slice(0, 10) || '—'} ·{' '}
                {taxLabel(detail.taxMode)}
              </Typography.Text>
            </Space>
            <Divider />
            <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
              <Descriptions.Item label="委托方 / 品牌公司">
                {detail.customerName || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="项目负责人">
                {detail.contactPerson || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="电话">
                {detail.contactPhone || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="邮件">
                {detail.contactEmail || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="交货期">
                {detail.deliveryPeriod || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="关联商机">
                {detail.opportunityName || '—'}
              </Descriptions.Item>
            </Descriptions>
            <Row gutter={16} className="sw-quotation-total-row">
              <Col span={12}>
                <Card size="small">
                  <Typography.Text type="secondary">
                    首年合计（{taxLabel(detail.taxMode)}）
                  </Typography.Text>
                  <Typography.Title level={3}>
                    {money(
                      detail.taxMode === 'TAX_INCLUDED'
                        ? detail.firstYearTotalInclTax
                        : detail.firstYearTotalExTax,
                    )}
                  </Typography.Title>
                </Card>
              </Col>
              <Col span={12}>
                <Card size="small">
                  <Typography.Text type="secondary">
                    次年及以后合计
                  </Typography.Text>
                  <Typography.Title level={3}>
                    {money(
                      detail.taxMode === 'TAX_INCLUDED'
                        ? detail.subsequentYearTotalInclTax
                        : detail.subsequentYearTotalExTax,
                    )}
                  </Typography.Title>
                </Card>
              </Col>
            </Row>
            <Divider />
            <Typography.Title level={5}>报价明细</Typography.Title>
            <Table
              rowKey="id"
              size="small"
              columns={detailItems}
              dataSource={(detail.lineItems || []).filter(
                (item) => item.isSelected !== 0,
              )}
              pagination={false}
              scroll={{ x: 680 }}
              locale={{ emptyText: <Empty description="暂无报价明细" /> }}
            />
            <Divider />
            <Typography.Title level={5}>品牌范围</Typography.Title>
            {detail.brandScopes?.length ? (
              detail.brandScopes.map((scope) => (
                <div key={scope.id} className="sw-brand-scope">
                  <Typography.Text strong>
                    {scope.brand || scope.store || '未命名范围'}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {scope.description || scope.target || '—'}
                  </Typography.Text>
                </div>
              ))
            ) : (
              <Typography.Text type="secondary">暂无品牌范围</Typography.Text>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
