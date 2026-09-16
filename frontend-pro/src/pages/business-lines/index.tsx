import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { type BusinessLine, superworkApi } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type BusinessLineForm = { name: string; description?: string; status: number };

const roleCanManage = (role?: string) =>
  [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(role || '');

const formatDate = (value?: string) => {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false });
};

export default function BusinessLinesPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = roleCanManage(initialState?.currentUser?.role);
  const [form] = Form.useForm<{ name?: string; status?: number }>();
  const [modalForm] = Form.useForm<BusinessLineForm>();
  const [records, setRecords] = useState<BusinessLine[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<BusinessLine>();
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [viewMode, setViewMode] = useState<'cards' | 'table'>('cards');

  const load = useCallback(
    async (values = form.getFieldsValue()) => {
      setLoading(true);
      setError('');
      try {
        const result = await superworkApi.getBusinessLines({
          page: 1,
          size: 200,
          ...values,
        });
        setRecords(result.records || []);
      } catch (e) {
        setRecords([]);
        setError(e instanceof Error ? e.message : '业务线数据加载失败');
      } finally {
        setLoading(false);
      }
    },
    [form],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const activeCount = useMemo(
    () => records.filter((item) => item.status === 1).length,
    [records],
  );
  const inactiveCount = records.length - activeCount;

  const openCreate = () => {
    setEditing(undefined);
    modalForm.setFieldsValue({ name: '', description: '', status: 1 });
    setModalOpen(true);
  };

  const openEdit = (record: BusinessLine) => {
    setEditing(record);
    modalForm.setFieldsValue({
      name: record.name,
      description: record.description || '',
      status: record.status ?? 1,
    });
    setModalOpen(true);
  };

  const submit = async () => {
    const values = await modalForm.validateFields();
    setSubmitting(true);
    try {
      if (editing) await superworkApi.updateBusinessLine(editing.id, values);
      else await superworkApi.createBusinessLine(values);
      message.success(editing ? '业务线已更新' : '业务线已创建');
      setModalOpen(false);
      await load();
    } catch (e) {
      message.error(
        e instanceof Error
          ? e.message
          : editing
            ? '更新业务线失败'
            : '创建业务线失败',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (record: BusinessLine) => {
    try {
      await superworkApi.deleteBusinessLine(record.id);
      message.success('业务线已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除业务线失败');
    }
  };

  const columns: TableProps<BusinessLine>['columns'] = [
    {
      title: '业务线',
      dataIndex: 'name',
      render: (value, record) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-business-line-id">
            BL-{String(record.id).padStart(2, '0')}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (value) => value || '尚未补充描述',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value) => (
        <Tag color={value === 1 ? 'success' : 'default'}>
          {value === 1 ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '最近更新',
      dataIndex: 'updatedAt',
      width: 178,
      render: (_, record) => formatDate(record.updatedAt || record.createdAt),
    },
    ...(canManage
      ? [
          {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_: unknown, record: BusinessLine) => (
              <Space>
                <Button
                  type="link"
                  icon={<EditOutlined />}
                  onClick={() => openEdit(record)}
                >
                  编辑
                </Button>
                <Popconfirm
                  title={`确定删除「${record.name}」吗？`}
                  description="若被项目引用，后端可能拒绝删除。"
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => void remove(record)}
                >
                  <Button type="link" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]
      : []),
  ];

  return (
    <div className="sw-page sw-business-lines">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            FOUNDATION / BUSINESS LINES
          </Typography.Text>
          <Typography.Title level={2}>业务线管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            统一维护业务域，项目与客户信息会引用这里的分类。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增业务线
            </Button>
          )}
        </Space>
      </div>
      <Row gutter={[16, 16]} className="sw-stat-row">
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="业务线总数"
              value={loading ? '-' : records.length}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="启用中"
              value={loading ? '-' : activeCount}
              styles={{ content: { color: '#059669' } }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="已禁用"
              value={loading ? '-' : inactiveCount}
              styles={{ content: { color: '#667085' } }}
            />
          </Card>
        </Col>
      </Row>
      <Card variant="borderless" className="sw-filter-card">
        <Form
          form={form}
          layout="inline"
          onFinish={() => void load(form.getFieldsValue())}
        >
          <Form.Item name="name" label="名称">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索业务线名称"
            />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 132 }}
              options={[
                { label: '启用', value: 1 },
                { label: '禁用', value: 0 },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button
                onClick={() => {
                  form.resetFields();
                  void load({});
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
          <Form.Item>
            <Segmented
              value={viewMode}
              onChange={(value) => setViewMode(value as 'cards' | 'table')}
              options={[
                { label: '卡片', value: 'cards' },
                { label: '表格', value: 'table' },
              ]}
            />
          </Form.Item>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取业务线数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      {viewMode === 'table' ? (
        <Card variant="borderless" className="sw-table-card">
          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={records}
            locale={{ emptyText: <Empty description="暂无业务线数据" /> }}
            pagination={false}
          />
        </Card>
      ) : (
        <div className="sw-business-line-grid">
          {records.map((record) => (
            <Card key={record.id} className="sw-business-line-card" hoverable>
              <Space orientation="vertical" size={12} style={{ width: '100%' }}>
                <Space
                  style={{ width: '100%', justifyContent: 'space-between' }}
                >
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {record.name}
                  </Typography.Title>
                  <Tag color={record.status === 1 ? 'success' : 'default'}>
                    {record.status === 1 ? '启用' : '禁用'}
                  </Tag>
                </Space>
                <Typography.Text type="secondary">
                  BL-{String(record.id).padStart(2, '0')}
                </Typography.Text>
                <Typography.Paragraph
                  type="secondary"
                  ellipsis={{ rows: 3 }}
                  style={{ minHeight: 66 }}
                >
                  {record.description || '尚未补充描述'}
                </Typography.Paragraph>
                <Typography.Text type="secondary">
                  最近更新：{formatDate(record.updatedAt || record.createdAt)}
                </Typography.Text>
                {canManage && (
                  <Space>
                    <Button
                      type="link"
                      icon={<EditOutlined />}
                      onClick={() => openEdit(record)}
                    >
                      编辑
                    </Button>
                    <Popconfirm
                      title={`确定删除「${record.name}」吗？`}
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => void remove(record)}
                    >
                      <Button type="link" danger icon={<DeleteOutlined />}>
                        删除
                      </Button>
                    </Popconfirm>
                  </Space>
                )}
              </Space>
            </Card>
          ))}
        </div>
      )}
      <Modal
        title={editing ? '编辑业务线' : '新增业务线'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => void submit()}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={modalForm} layout="vertical">
          <Form.Item
            name="name"
            label="业务线名称"
            rules={[{ required: true, message: '请输入业务线名称' }]}
          >
            <Input placeholder="如：电商业务线" maxLength={80} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea
              rows={4}
              placeholder="补充业务线定位或边界"
              maxLength={500}
              showCount
            />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select
              options={[
                { label: '启用', value: 1 },
                { label: '禁用', value: 0 },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
