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
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type BusinessLine,
  type CustomerContact,
  type ProjectRecord,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ContactForm = {
  businessLineId?: number;
  projectId?: number;
  name: string;
  company?: string;
  position?: string;
  phone?: string;
  email?: string;
  isActive: number;
};
const ROLE_CODES = [
  'admin',
  '系统管理员',
  'DIRECTOR',
  'DEPUTY_DIRECTOR',
  'BUSINESS_OWNER',
  'EFFECTIVENESS_OWNER',
  'BU_ADMIN',
];
const canManageRole = (role?: string) => ROLE_CODES.includes(role || '');
const formatDate = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';

export default function CustomersPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = canManageRole(initialState?.currentUser?.role);
  const [form] = Form.useForm<{
    businessLineId?: number;
    projectId?: number;
    name?: string;
    isActive?: number;
  }>();
  const [modalForm] = Form.useForm<ContactForm>();
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [records, setRecords] = useState<CustomerContact[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<CustomerContact>();
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [selectedBusinessLine, setSelectedBusinessLine] = useState<number>();
  const modalBusinessLineId = Form.useWatch('businessLineId', modalForm);

  const projectMap = useMemo(
    () => new Map(projects.map((project) => [project.id, project])),
    [projects],
  );
  const businessLineMap = useMemo(
    () => new Map(businessLines.map((line) => [line.id, line])),
    [businessLines],
  );
  const filteredProjects = useMemo(
    () =>
      selectedBusinessLine
        ? projects.filter(
            (project) => project.businessLineId === selectedBusinessLine,
          )
        : projects,
    [projects, selectedBusinessLine],
  );
  const modalProjects = useMemo(
    () =>
      modalBusinessLineId
        ? projects.filter(
            (project) => project.businessLineId === modalBusinessLineId,
          )
        : projects,
    [modalBusinessLineId, projects],
  );
  const visibleRecords = useMemo(
    () =>
      selectedBusinessLine
        ? records.filter(
            (record) =>
              projectMap.get(record.projectId)?.businessLineId ===
              selectedBusinessLine,
          )
        : records,
    [projectMap, records, selectedBusinessLine],
  );
  const activeCount = useMemo(
    () => visibleRecords.filter((record) => record.isActive === 1).length,
    [visibleRecords],
  );

  const loadBase = useCallback(async () => {
    const [linePage, projectPage] = await Promise.all([
      superworkApi.getBusinessLines({ page: 1, size: 500 }),
      superworkApi.getProjects({ page: 1, size: 999 }),
    ]);
    setBusinessLines(linePage.records || []);
    setProjects(projectPage.records || []);
  }, []);
  const load = useCallback(
    async (values = form.getFieldsValue()) => {
      setLoading(true);
      setError('');
      try {
        const result = await superworkApi.getCustomerContacts({
          page: 1,
          size: 999,
          projectId: values.projectId,
          name: values.name,
          isActive: values.isActive,
        });
        setRecords(result.records || []);
      } catch (e) {
        setRecords([]);
        setError(e instanceof Error ? e.message : '客户信息加载失败');
      } finally {
        setLoading(false);
      }
    },
    [form],
  );
  const refresh = useCallback(async () => {
    try {
      await loadBase();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '基础数据加载失败');
    }
  }, [load, loadBase]);
  useEffect(() => {
    void refresh();
  }, [refresh]);

  const openCreate = () => {
    setEditing(undefined);
    modalForm.setFieldsValue({
      businessLineId: undefined,
      projectId: undefined,
      name: '',
      company: '',
      position: '',
      phone: '',
      email: '',
      isActive: 1,
    });
    setModalOpen(true);
  };
  const openEdit = (record: CustomerContact) => {
    const project = projectMap.get(record.projectId);
    setEditing(record);
    modalForm.setFieldsValue({
      businessLineId: project?.businessLineId,
      projectId: record.projectId,
      name: record.name,
      company: record.company || '',
      position: record.position || '',
      phone: record.phone || '',
      email: record.email || '',
      isActive: record.isActive ?? 1,
    });
    setModalOpen(true);
  };
  const submit = async () => {
    const values = await modalForm.validateFields();
    if (values.projectId == null) {
      message.error('请选择项目');
      return;
    }
    setSubmitting(true);
    try {
      if (editing)
        await superworkApi.updateCustomerContact(editing.id, {
          ...values,
          projectId: values.projectId,
        });
      else
        await superworkApi.createCustomerContact({
          ...values,
          projectId: values.projectId,
        });
      message.success(editing ? '客户信息已更新' : '客户信息已创建');
      setModalOpen(false);
      await load();
    } catch (e) {
      message.error(
        e instanceof Error
          ? e.message
          : editing
            ? '更新客户信息失败'
            : '创建客户信息失败',
      );
    } finally {
      setSubmitting(false);
    }
  };
  const remove = async (record: CustomerContact) => {
    try {
      await superworkApi.deleteCustomerContact(record.id);
      message.success('客户信息已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除客户信息失败');
    }
  };

  const columns: TableProps<CustomerContact>['columns'] = [
    {
      title: '客户姓名',
      dataIndex: 'name',
      render: (value) => <Typography.Text strong>{value}</Typography.Text>,
    },
    { title: '公司', dataIndex: 'company', render: (value) => value || '—' },
    { title: '职位', dataIndex: 'position', render: (value) => value || '—' },
    {
      title: '项目',
      dataIndex: 'projectId',
      render: (value) => {
        const project = projectMap.get(value);
        return project?.fullPath || project?.name || '—';
      },
    },
    {
      title: '业务线',
      key: 'businessLine',
      render: (_, record) => {
        const project = projectMap.get(record.projectId);
        return businessLineMap.get(project?.businessLineId || -1)?.name || '—';
      },
    },
    {
      title: '联系方式',
      key: 'contact',
      render: (_, record) => (
        <div className="sw-contact-lines">
          <span>{record.phone || '未填写电话'}</span>
          <span>{record.email || '未填写邮箱'}</span>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      width: 90,
      render: (value) => (
        <Tag color={value === 1 ? 'success' : 'default'}>
          {value === 1 ? '有效' : '停用'}
        </Tag>
      ),
    },
    {
      title: '更新于',
      dataIndex: 'updatedAt',
      width: 170,
      render: (_, record) => formatDate(record.updatedAt || record.createdAt),
    },
    ...(canManage
      ? [
          {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_: unknown, record: CustomerContact) => (
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
            ),
          },
        ]
      : []),
  ];

  return (
    <div className="sw-page sw-customers">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            FOUNDATION / CUSTOMER CONTACTS
          </Typography.Text>
          <Typography.Title level={2}>客户信息管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            按业务线和项目定位客户联系人，保留原有有效状态与联系方式字段。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>
            刷新
          </Button>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增客户
            </Button>
          )}
        </Space>
      </div>
      <Row gutter={[16, 16]} className="sw-stat-row">
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="客户联系人"
              value={loading ? '-' : visibleRecords.length}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="有效联系人"
              value={loading ? '-' : activeCount}
              styles={{ content: { color: '#059669' } }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="覆盖项目"
              value={
                loading
                  ? '-'
                  : new Set(visibleRecords.map((record) => record.projectId))
                      .size
              }
              styles={{ content: { color: '#2563eb' } }}
            />
          </Card>
        </Col>
      </Row>
      <Card variant="borderless" className="sw-filter-card">
        <Form
          form={form}
          layout="inline"
          onFinish={(values) => {
            setSelectedBusinessLine(values.businessLineId);
            void load(values);
          }}
        >
          <Form.Item name="businessLineId" label="业务线">
            <Select
              allowClear
              placeholder="全部业务线"
              style={{ width: 170 }}
              options={businessLines.map((line) => ({
                label: line.name,
                value: line.id,
              }))}
              onChange={(value) => {
                setSelectedBusinessLine(value);
                form.setFieldValue('projectId', undefined);
              }}
            />
          </Form.Item>
          <Form.Item name="projectId" label="项目">
            <Select
              allowClear
              placeholder="全部项目"
              style={{ width: 210 }}
              options={filteredProjects.map((project) => ({
                label: project.name,
                value: project.id,
              }))}
            />
          </Form.Item>
          <Form.Item name="name" label="姓名">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索客户姓名"
            />
          </Form.Item>
          <Form.Item name="isActive" label="状态">
            <Select
              allowClear
              placeholder="全部"
              style={{ width: 110 }}
              options={[
                { label: '有效', value: 1 },
                { label: '停用', value: 0 },
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
                  setSelectedBusinessLine(undefined);
                  void load({});
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取客户信息"
          description={error}
          action={
            <Button size="small" onClick={() => void refresh()}>
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
          dataSource={visibleRecords}
          locale={{ emptyText: <Empty description="暂无客户联系人" /> }}
          scroll={{ x: 1150 }}
          pagination={{
            defaultPageSize: 20,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 位联系人`,
          }}
        />
      </Card>
      <Modal
        title={editing ? '编辑客户信息' : '新增客户信息'}
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
          <Form.Item name="businessLineId" label="业务线">
            <Select
              allowClear
              placeholder="请选择业务线"
              options={businessLines.map((line) => ({
                label: line.name,
                value: line.id,
              }))}
              onChange={() => modalForm.setFieldValue('projectId', undefined)}
            />
          </Form.Item>
          <Form.Item
            name="projectId"
            label="项目"
            rules={[{ required: true, message: '请选择项目' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="请选择项目"
              options={modalProjects.map((project) => ({
                label: project.name,
                value: project.id,
              }))}
            />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="客户姓名"
                rules={[{ required: true, message: '请输入客户姓名' }]}
              >
                <Input maxLength={80} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="company" label="公司">
                <Input maxLength={120} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="position" label="职位">
                <Input maxLength={80} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="phone" label="电话">
                <Input maxLength={40} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="email"
            label="邮箱"
            rules={[{ type: 'email', message: '请输入正确的邮箱格式' }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item name="isActive" label="状态">
            <Select
              options={[
                { label: '有效', value: 1 },
                { label: '停用', value: 0 },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
