import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type AiConnectorAuthType,
  type AiConnectorSavePayload,
  type AiConnectorView,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ConnectorForm = AiConnectorSavePayload & {
  code: string;
  name: string;
  authType: AiConnectorAuthType;
  baseUrl: string;
  enabled: boolean;
  sortOrder: number;
};
const authLabels: Record<AiConnectorAuthType, string> = {
  BASIC: '用户名 / 密码',
  TOKEN: 'Token',
  MCP: 'MCP',
};

export default function AiConnectorsPage() {
  const [form] = Form.useForm<ConnectorForm>();
  const [rows, setRows] = useState<AiConnectorView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<AiConnectorView>();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<number>();
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await superworkApi.getAiConnectors());
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : '连接器加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const openCreate = () => {
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      authType: 'BASIC',
      baseUrl: '',
      mcpUrl: '',
      testPath: '',
      queryPath: '',
      readPath: '',
      username: '',
      password: '',
      token: '',
      enabled: true,
      sortOrder: rows.length,
    });
    setOpen(true);
  };
  const openEdit = (row: AiConnectorView) => {
    setEditing(row);
    form.setFieldsValue({
      code: row.code,
      name: row.name,
      authType: row.authType,
      baseUrl: row.baseUrl,
      mcpUrl: row.mcpUrl,
      testPath: row.testPath,
      queryPath: row.queryPath,
      readPath: row.readPath,
      username: '',
      password: '',
      token: '',
      enabled: row.enabled,
      sortOrder: row.sortOrder,
    });
    setOpen(true);
  };
  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload: AiConnectorSavePayload = { ...values };
      if (!payload.username) delete payload.username;
      if (!payload.password) delete payload.password;
      if (!payload.token) delete payload.token;
      if (editing) await superworkApi.updateAiConnector(editing.id, payload);
      else await superworkApi.createAiConnector(payload);
      message.success(editing ? '连接器已更新' : '连接器已创建');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '连接器保存失败');
    } finally {
      setSaving(false);
    }
  };
  const test = async (row: AiConnectorView) => {
    setTesting(row.id);
    try {
      await superworkApi.testAiConnector(row.id);
      message.success('连接测试已完成');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '连接测试失败');
    } finally {
      setTesting(undefined);
    }
  };
  const remove = async (row: AiConnectorView) => {
    try {
      await superworkApi.deleteAiConnector(row.id);
      message.success('连接器已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '连接器删除失败');
    }
  };
  const columns: TableProps<AiConnectorView>['columns'] = [
    {
      title: '连接器',
      dataIndex: 'name',
      render: (value, row) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-connector-meta">
            {row.code} · {authLabels[row.authType]}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '服务地址',
      dataIndex: 'baseUrl',
      render: (value) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '凭据',
      key: 'credentials',
      width: 220,
      render: (_: unknown, row) => (
        <Space wrap>
          <Tag color={row.usernameConfigured ? 'success' : 'default'}>
            用户名 {row.usernameConfigured ? '已配置' : '未配置'}
          </Tag>
          <Tag
            color={
              row.passwordConfigured || row.tokenConfigured
                ? 'success'
                : 'default'
            }
          >
            {row.authType === 'TOKEN' ? 'Token' : '密码'}{' '}
            {row.passwordConfigured || row.tokenConfigured
              ? '已配置'
              : '未配置'}
          </Tag>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 110,
      render: (value, row) => (
        <Space orientation="vertical" size={0}>
          <Tag color={value ? 'success' : 'default'}>
            {value ? '启用' : '停用'}
          </Tag>
          <Typography.Text type="secondary">
            {row.lastTestStatus === 'SUCCESS'
              ? '测试通过'
              : row.lastTestStatus === 'FAILED'
                ? '测试失败'
                : '未测试'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 260,
      render: (_: unknown, row) => (
        <Space size={0}>
          <Button
            type="link"
            icon={<ThunderboltOutlined />}
            loading={testing === row.id}
            onClick={() => void test(row)}
          >
            测试
          </Button>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => openEdit(row)}
          >
            编辑
          </Button>
          {!row.builtIn && (
            <Popconfirm
              title={`删除「${row.name}」吗？`}
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void remove(row)}
            >
              <Button type="link" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];
  return (
    <div className="sw-page sw-ai-connectors">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            AI / CONNECTORS
          </Typography.Text>
          <Typography.Title level={2}>AI 连接器</Typography.Title>
          <Typography.Paragraph type="secondary">
            维护外部 AI 服务与 MCP 连接器，凭据字段留空表示保持原配置。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增连接器
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取 AI 连接器"
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
          scroll={{ x: 900 }}
          locale={{ emptyText: '暂无连接器' }}
        />
      </Card>
      <Modal
        title={editing ? '编辑连接器' : '新增连接器'}
        open={open}
        forceRender
        width={760}
        onCancel={() => setOpen(false)}
        onOk={() => void save()}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
      >
        <Form form={form} layout="vertical">
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="code"
                label="连接器编码"
                rules={[{ required: true }]}
              >
                <Input disabled={Boolean(editing)} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="name"
                label="连接器名称"
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="authType"
                label="认证方式"
                rules={[{ required: true }]}
              >
                <Select
                  options={Object.entries(authLabels).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sortOrder" label="排序">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="baseUrl"
            label="服务地址"
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Row gutter={14}>
            <Col span={8}>
              <Form.Item name="mcpUrl" label="MCP 地址">
                <Input />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="testPath" label="测试路径">
                <Input />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="queryPath" label="查询路径">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="readPath" label="读取路径">
            <Input />
          </Form.Item>
          <Divider />
          <Row gutter={14}>
            <Col span={8}>
              <Form.Item name="username" label="用户名">
                <Input
                  autoComplete="off"
                  placeholder={
                    editing?.usernameConfigured ? '已配置；留空保持不变' : ''
                  }
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="password" label="密码">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    editing?.passwordConfigured ? '已配置；留空保持不变' : ''
                  }
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="token" label="Token">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    editing?.tokenConfigured ? '已配置；留空保持不变' : ''
                  }
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="enabled" label="启用状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
