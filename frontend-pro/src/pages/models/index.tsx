import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
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
  Tooltip,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type AiModelSavePayload,
  type AiModelView,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ModelForm = {
  providerCode: string;
  apiProtocol: string;
  model: string;
  displayName?: string;
  baseUrl?: string;
  apiKey?: string;
  clearApiKey?: boolean;
  assistantEnabled: boolean;
  digestEnabled: boolean;
  decisionEnabled: boolean;
  isDefault: boolean;
  enabled: boolean;
  sortOrder?: number;
};

const CONNECTORS_PATH = '/system/connectors';
const PROTOCOL_OPTIONS = [
  { value: 'openai-compat', label: 'OpenAI 兼容（对话 / 摘要 / 中转站）' },
  { value: 'typesafe', label: 'TypeSafe Jev（决策，不进助手下拉）' },
];

export default function ModelsPage() {
  const [form] = Form.useForm<ModelForm>();
  const [rows, setRows] = useState<AiModelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<AiModelView>();
  const [saving, setSaving] = useState(false);
  const [updatingId, setUpdatingId] = useState<number>();
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await superworkApi.getAiModels());
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : '模型列表加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const unreadyProviders = useMemo(
    () =>
      Array.from(
        new Set(
          rows
            .filter((row) => !row.providerReady)
            .map((row) => row.providerCode),
        ),
      ),
    [rows],
  );
  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({
      providerCode: 'openai',
      apiProtocol: 'openai-compat',
      model: '',
      displayName: '',
      baseUrl: '',
      apiKey: '',
      clearApiKey: false,
      assistantEnabled: true,
      digestEnabled: false,
      decisionEnabled: false,
      isDefault: false,
      enabled: true,
      sortOrder: 100,
    });
    setOpen(true);
  };
  const openEdit = (row: AiModelView) => {
    setEditing(row);
    form.resetFields();
    form.setFieldsValue({
      providerCode: row.providerCode,
      apiProtocol: row.apiProtocol || 'openai-compat',
      model: row.model,
      displayName: row.displayName,
      baseUrl: row.baseUrl || '',
      apiKey: '',
      clearApiKey: false,
      assistantEnabled: row.assistantEnabled,
      digestEnabled: row.digestEnabled,
      decisionEnabled: row.decisionEnabled,
      isDefault: row.isDefault,
      enabled: row.enabled,
      sortOrder: row.sortOrder,
    });
    setOpen(true);
  };
  const save = async () => {
    try {
      await form.validateFields();
    } catch {
      return;
    }
    const values = form.getFieldsValue(true) as ModelForm;
    const model = values.model?.trim() || '';
    const payload: AiModelSavePayload = {
      providerCode: values.providerCode?.trim(),
      apiProtocol: values.apiProtocol,
      model,
      displayName: values.displayName?.trim() || model,
      baseUrl: values.baseUrl?.trim() || '',
      assistantEnabled: Boolean(values.assistantEnabled),
      digestEnabled: Boolean(values.digestEnabled),
      decisionEnabled: Boolean(values.decisionEnabled),
      isDefault: Boolean(values.isDefault),
      enabled: Boolean(values.enabled),
      sortOrder: values.sortOrder ?? 0,
    };
    if (values.apiKey?.trim()) payload.apiKey = values.apiKey.trim();
    if (values.clearApiKey) payload.clearApiKey = true;
    setSaving(true);
    try {
      if (editing) await superworkApi.updateAiModel(editing.id, payload);
      else await superworkApi.createAiModel(payload);
      message.success(editing ? '模型已更新' : '模型已创建');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型保存失败');
    } finally {
      setSaving(false);
    }
  };
  /** 行内开关只提交单个字段，避免覆盖同行其他配置。 */
  const patch = async (
    row: AiModelView,
    changes: AiModelSavePayload,
    successText: string,
  ) => {
    setUpdatingId(row.id);
    try {
      await superworkApi.updateAiModel(row.id, changes);
      message.success(successText);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型更新失败');
    } finally {
      setUpdatingId(undefined);
    }
  };
  const remove = async (row: AiModelView) => {
    try {
      await superworkApi.deleteAiModel(row.id);
      message.success('模型已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型删除失败');
    }
  };
  const renderSwitch = (
    row: AiModelView,
    key: 'assistantEnabled' | 'digestEnabled' | 'decisionEnabled' | 'isDefault' | 'enabled',
    onText: string,
    offText: string,
  ) => (
    <Switch
      size="small"
      checked={row[key]}
      loading={updatingId === row.id}
      onChange={(checked) =>
        void patch(row, { [key]: checked }, checked ? onText : offText)
      }
    />
  );
  const columns: TableProps<AiModelView>['columns'] = [
    {
      title: '提供方',
      dataIndex: 'providerName',
      width: 200,
      render: (_: unknown, row) => (
        <div>
          <Typography.Text strong>
            {row.providerName || row.providerCode}
          </Typography.Text>
          <Typography.Text type="secondary" className="sw-model-meta">
            {row.providerCode} · {row.apiProtocol || 'openai-compat'}
          </Typography.Text>
          {row.baseUrl ? (
            <Typography.Text type="secondary" className="sw-model-meta">
              {row.baseUrl}
            </Typography.Text>
          ) : null}
          {!row.providerReady && (
            <span className="sw-model-meta">
              <Tag color="orange">接入未就绪</Tag>
            </span>
          )}
        </div>
      ),
    },
    {
      title: '接入',
      width: 220,
      render: (_: unknown, row) => (
        <div>
          <Typography.Text type="secondary" className="sw-model-meta">
            {row.apiKeyConfigured ? 'Key 已配置' : 'Key 未配置'}
            {row.baseUrl ? '' : ' · 回落连接器'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '模型名',
      dataIndex: 'model',
      render: (value) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '展示名',
      dataIndex: 'displayName',
      ellipsis: true,
      render: (value) => value || '—',
    },
    {
      title: '助手可用',
      dataIndex: 'assistantEnabled',
      width: 100,
      render: (_: unknown, row) =>
        renderSwitch(
          row,
          'assistantEnabled',
          '已加入 AI 助手下拉',
          '已从 AI 助手下拉移除',
        ),
    },
    {
      title: '摘要使用',
      dataIndex: 'digestEnabled',
      width: 100,
      render: (_: unknown, row) =>
        renderSwitch(
          row,
          'digestEnabled',
          '已用于邮件摘要 / 周报纪要',
          '已停用摘要用途',
        ),
    },
    {
      title: (
        <Tooltip title="TypeSafe Jev 等 System One 模型：意图路由与写操作门禁，不会出现在 AI 助手下拉">
          <span>决策</span>
        </Tooltip>
      ),
      dataIndex: 'decisionEnabled',
      width: 90,
      render: (_: unknown, row) =>
        renderSwitch(
          row,
          'decisionEnabled',
          '已用于意图路由 / 写操作门禁',
          '已停用决策用途',
        ),
    },
    {
      title: (
        <Tooltip title="AI 助手默认模型全局唯一，开启后其他行会自动取消默认">
          <span>默认</span>
        </Tooltip>
      ),
      dataIndex: 'isDefault',
      width: 90,
      render: (_: unknown, row) =>
        renderSwitch(row, 'isDefault', '已设为默认模型', '已取消默认模型'),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (_: unknown, row) =>
        renderSwitch(row, 'enabled', '模型已启用', '模型已停用'),
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 80,
      render: (value) => value ?? 100,
    },
    {
      title: '操作',
      key: 'action',
      width: 170,
      render: (_: unknown, row) => (
        <Space size={0}>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => openEdit(row)}
          >
            编辑
          </Button>
          <Popconfirm
            title={`删除模型「${row.model}」吗？`}
            description="删除后 AI 助手与摘要将不再使用该模型。"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => void remove(row)}
          >
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];
  return (
    <div className="sw-page sw-models">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / MODELS
          </Typography.Text>
          <Typography.Title level={2}>模型管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            模型自己填协议、接口地址和 API Key（官方 / 中转站 / 自建 OpenAI 兼容）。
            留空时才回落同名连接器。连接器只管外部系统（云效、工时、OA、语雀、邮件、企微），不再当模型提供方。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button onClick={() => history.push(CONNECTORS_PATH)}>
            连接器管理
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建模型
          </Button>
        </Space>
      </div>
      {unreadyProviders.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="sw-model-hint"
          message={`接入未就绪：${unreadyProviders.join('、')}`}
          description="请在本页填写接口地址与 API Key，或给同名连接器补全凭据。未就绪的模型不会出现在 AI 助手与摘要里。"
          action={
            <Button size="small" onClick={() => history.push(CONNECTORS_PATH)}>
              去连接器管理
            </Button>
          }
        />
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取模型列表"
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
          scroll={{ x: 1120 }}
          locale={{ emptyText: '暂无模型，请先新建模型' }}
        />
      </Card>
      <Modal
        title={editing ? `编辑模型 · ${editing.model}` : '新建模型'}
        open={open}
        forceRender
        width={720}
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
                name="providerCode"
                label="提供方编码"
                rules={[{ required: true, message: '请填写提供方编码' }]}
                extra="如 openai / deepseek / glm；可手填，不必先建连接器"
              >
                <Input placeholder="openai" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sortOrder" label="排序">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="model"
                label="模型名"
                rules={[{ required: true, message: '请输入模型名' }]}
              >
                <Input placeholder="如 deepseek-v4-flash" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="displayName"
                label="展示名"
                extra="留空即与模型名一致"
              >
                <Input placeholder="如 DeepSeek V4 Flash" maxLength={128} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="apiProtocol"
                label="接入协议"
                extra="对话模型选 OpenAI 兼容；Jev 选 TypeSafe"
              >
                <Select options={PROTOCOL_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="baseUrl"
                label="接口地址"
                extra="OpenAI 兼容根地址，如 https://api.openai.com/v1；留空回落同名连接器"
              >
                <Input placeholder="https://api.openai.com/v1" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="apiKey"
                label="API Key"
                extra={editing?.apiKeyConfigured ? '已配置；留空保持不变' : '中转站 / 官方密钥'}
              >
                <Input.Password placeholder={editing?.apiKeyConfigured ? '已配置' : 'sk-…'} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="clearApiKey"
                label="清除已存 Key"
                valuePropName="checked"
                extra="勾选后改回使用同名连接器凭据"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="assistantEnabled"
                label="助手可用"
                valuePropName="checked"
                extra="出现在 AI 助手的模型下拉里"
              >
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="digestEnabled"
                label="摘要使用"
                valuePropName="checked"
                extra="用于邮件摘要与周报纪要"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="decisionEnabled"
                label="决策门禁"
                valuePropName="checked"
                extra="Jev / System One：意图路由与写操作确认，不进助手下拉"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="isDefault"
                label="默认模型"
                valuePropName="checked"
                extra="全局唯一，保存后其他行自动取消默认"
              >
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
