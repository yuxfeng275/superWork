import {
  ApiOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  AutoComplete,
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
  Statistic,
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
  const [testingId, setTestingId] = useState<number>();
  const [testingDraft, setTestingDraft] = useState(false);
  const [remoteModels, setRemoteModels] = useState<string[]>([]);
  const [fetchingRemote, setFetchingRemote] = useState(false);
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
  const summary = useMemo(() => {
    const enabled = rows.filter((row) => row.enabled).length;
    const ready = rows.filter((row) => row.enabled && row.providerReady).length;
    const fallbackCount = rows.filter((row) => !row.baseUrl).length;
    const defaultRow = rows.find((row) => row.isDefault && row.enabled);
    return {
      total: rows.length,
      enabled,
      ready,
      fallbackCount,
      defaultLabel: defaultRow
        ? defaultRow.displayName || defaultRow.model
        : '未设置',
    };
  }, [rows]);
  const openCreate = () => {
    setEditing(undefined);
    setRemoteModels([]);
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
    setRemoteModels([]);
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
  const testRow = async (row: AiModelView) => {
    setTestingId(row.id);
    try {
      const result = await superworkApi.testAiModel(row.id);
      message.success(
        `「${row.displayName || row.model}」${result.message}${
          result.latencyMs != null ? ` · ${result.latencyMs}ms` : ''
        }`,
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型测试失败');
    } finally {
      setTestingId(undefined);
    }
  };
  /** 拉取提供方模型列表：编辑时用已存凭据，新建时用表单地址/Key。 */
  const fetchRemoteList = async () => {
    setFetchingRemote(true);
    try {
      const values = form.getFieldsValue(true) as ModelForm;
      const list = await superworkApi.fetchRemoteAiModels({
        id: editing?.id,
        providerCode: values.providerCode?.trim() || undefined,
        baseUrl: values.baseUrl?.trim() || undefined,
        apiKey: values.apiKey?.trim() || undefined,
      });
      setRemoteModels(list);
      message.success(`获取到 ${list.length} 个可用模型，请在模型名下拉中选择`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型列表获取失败');
    } finally {
      setFetchingRemote(false);
    }
  };
  /** 测试弹窗里未保存的配置。 */
  const testDraft = async () => {
    const values = form.getFieldsValue(true) as ModelForm;
    if (!values.model?.trim()) {
      message.warning('请先填写模型名再测试');
      return;
    }
    if (values.apiProtocol === 'typesafe') {
      message.warning('TypeSafe 决策模型不走 OpenAI 兼容协议，不支持在线测试');
      return;
    }
    setTestingDraft(true);
    try {
      const result = await superworkApi.testAiModelDraft({
        id: editing?.id,
        providerCode: values.providerCode?.trim() || undefined,
        apiProtocol: values.apiProtocol,
        model: values.model.trim(),
        baseUrl: values.baseUrl?.trim() || undefined,
        apiKey: values.apiKey?.trim() || undefined,
      });
      message.success(
        `${result.message}${result.latencyMs != null ? ` · ${result.latencyMs}ms` : ''}`,
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '模型测试失败');
    } finally {
      setTestingDraft(false);
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
      title: '模型',
      dataIndex: 'model',
      render: (_: unknown, row) => (
        <div className="sw-model-identity">
          <Space size={6} wrap>
            <Typography.Text strong>
              {row.displayName || row.model}
            </Typography.Text>
            {row.isDefault && <Tag color="gold">默认</Tag>}
          </Space>
          <Typography.Text code className="sw-model-meta">
            {row.model}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '提供方 / 接入',
      dataIndex: 'providerName',
      width: 260,
      render: (_: unknown, row) => (
        <div>
          <Space size={6} wrap>
            <Typography.Text>
              {row.providerName || row.providerCode}
            </Typography.Text>
            <Tag>{row.apiProtocol || 'openai-compat'}</Tag>
          </Space>
          <Typography.Text type="secondary" className="sw-model-meta">
            {row.baseUrl || '未配置接口地址'}
          </Typography.Text>
          <Typography.Text type="secondary" className="sw-model-meta">
            {row.apiKeyConfigured ? 'Key 已配置' : 'Key 未配置'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_: unknown, row) =>
        !row.enabled ? (
          <Tag>已停用</Tag>
        ) : row.providerReady ? (
          <Tag color="success">就绪</Tag>
        ) : (
          <Tag color="orange">未就绪</Tag>
        ),
    },
    {
      title: '助手可用',
      dataIndex: 'assistantEnabled',
      width: 96,
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
      width: 96,
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
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (_: unknown, row) =>
        renderSwitch(row, 'enabled', '模型已启用', '模型已停用'),
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 70,
      render: (value) => value ?? 100,
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      render: (_: unknown, row) => (
        <Space size={0}>
          {(row.apiProtocol || 'openai-compat') === 'typesafe' ? (
            <Tooltip title="TypeSafe 决策模型不走 OpenAI 兼容协议，不支持在线测试">
              <Button type="link" icon={<ThunderboltOutlined />} disabled>
                测试
              </Button>
            </Tooltip>
          ) : (
            <Button
              type="link"
              icon={<ThunderboltOutlined />}
              loading={testingId === row.id}
              disabled={!row.enabled}
              onClick={() => void testRow(row)}
            >
              测试
            </Button>
          )}
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
            模型在这里自填协议、接口地址和 API Key（官方 / 中转站 / 自建 OpenAI 兼容），
            支持一键拉取提供方的模型列表并在线测试连通性。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建模型
          </Button>
        </Space>
      </div>
      <Row gutter={[12, 12]} className="sw-stat-row">
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="模型总数" value={summary.total} suffix="个" />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="已启用" value={summary.enabled} suffix="个" />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="接入就绪"
              value={summary.ready}
              suffix="个"
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="助手默认模型"
              value={summary.defaultLabel}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
      </Row>
      {unreadyProviders.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="sw-model-hint"
          message={`接入未就绪：${unreadyProviders.join('、')}`}
          description="请在编辑里补全该模型的接口地址与 API Key。未就绪的模型不会出现在 AI 助手与摘要里。"
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
          scroll={{ x: 1080 }}
          locale={{ emptyText: '暂无模型，请先新建模型' }}
        />
      </Card>
      <Modal
        title={editing ? `编辑模型 · ${editing.model}` : '新建模型'}
        open={open}
        forceRender
        width={760}
        onCancel={() => setOpen(false)}
        footer={
          <div className="sw-model-modal-footer">
            <Button
              icon={<ApiOutlined />}
              loading={testingDraft}
              onClick={() => void testDraft()}
            >
              测试连接
            </Button>
            <Space>
              <Button onClick={() => setOpen(false)}>取消</Button>
              <Button
                type="primary"
                loading={saving}
                onClick={() => void save()}
              >
                保存
              </Button>
            </Space>
          </div>
        }
      >
        <Form form={form} layout="vertical" className="sw-model-form">
          <Divider titlePlacement="left" plain className="sw-model-divider">
            基本信息
          </Divider>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="providerCode"
                label="提供方编码"
                rules={[{ required: true, message: '请填写提供方编码' }]}
                extra="如 openai / deepseek / glm；自定义编码即可"
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
                extra={
                  remoteModels.length > 0
                    ? `已从提供方拉取 ${remoteModels.length} 个模型，可直接下拉选择`
                    : '不知道模型 ID？先在下方填好接口地址与 Key，点「获取模型列表」'
                }
              >
                <AutoComplete
                  options={remoteModels.map((item) => ({ value: item }))}
                  placeholder="如 deepseek-v4-flash"
                  filterOption={(input, option) =>
                    (option?.value ?? '')
                      .toLowerCase()
                      .includes(input.toLowerCase())
                  }
                />
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
          <Divider titlePlacement="left" plain className="sw-model-divider">
            接入配置
          </Divider>
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
                extra="OpenAI 兼容根地址，如 https://api.openai.com/v1"
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
                extra={
                  editing?.apiKeyConfigured
                    ? '已配置；留空保持不变'
                    : '中转站 / 官方密钥'
                }
              >
                <Input.Password
                  placeholder={editing?.apiKeyConfigured ? '已配置' : 'sk-…'}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="clearApiKey"
                label="清除已存 Key"
                valuePropName="checked"
                extra="勾选后删除已保存的 Key，下次保存生效"
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <div className="sw-model-actions">
            <Space>
              <Button
                size="small"
                icon={<ReloadOutlined />}
                loading={fetchingRemote}
                onClick={() => void fetchRemoteList()}
              >
                获取模型列表
              </Button>
              <Button
                size="small"
                icon={<ApiOutlined />}
                loading={testingDraft}
                onClick={() => void testDraft()}
              >
                测试连接
              </Button>
            </Space>
            <Typography.Text type="secondary" className="sw-model-actions-hint">
              中转站不知道模型 ID 时，先拉取列表再下拉选择；保存前可先测试连通性
            </Typography.Text>
          </div>
          <Divider titlePlacement="left" plain className="sw-model-divider">
            用途与状态
          </Divider>
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
          </Row>
          <Row gutter={14}>
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
