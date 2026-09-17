import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  QrcodeOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
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
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import {
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  type AiConnectorAuthType,
  type AiConnectorSavePayload,
  type AiConnectorView,
  superworkApi,
  type WecomCliCapability,
  type WecomCliCapabilityState,
  type WecomCliQrPollStatus,
  type WecomCliQrSession,
  type WecomCliStatus,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ConnectorForm = {
  code: string;
  name: string;
  authType: AiConnectorAuthType;
  baseUrl?: string;
  mcpUrl?: string;
  testPath?: string;
  queryPath?: string;
  readPath?: string;
  username?: string;
  password?: string;
  token?: string;
  /** 机器人通道 Bot Secret（写入型，留空不修改） */
  botSecret?: string;
  enabled: boolean;
  sortOrder?: number;
  extraConfig?: Record<string, unknown>;
};

type ExtraField = {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'select' | 'switch';
  placeholder?: string;
  options?: { value: string; label: string }[];
};

/** 内置连接器的专属字段（连接参数 + extra_config 键），与后端注册表口径一致。 */
type BuiltinSpec = {
  baseUrl?: { label?: string; placeholder: string };
  mcpUrl?: boolean;
  username?: string;
  password?: string;
  token?: string;
  extras?: ExtraField[];
};

const authLabels: Record<AiConnectorAuthType, string> = {
  BASIC: '用户名 / 密码',
  TOKEN: 'Token',
  MCP: 'MCP',
  SEEYON: '致远 OA',
  WECOM: '企业微信',
  MAIL: '邮箱（按用户绑定）',
};

/** 模型（模型名 / 用途 / 默认）已从连接器抽离到独立页面。 */
const MODELS_PATH = '/system/models';

/** 各系统的延伸动作入口；通用连接器没有跳转。 */
const connectorLinks: Record<string, { label: string; path: string }> = {
  yunxiao: { label: '云效映射', path: '/statistics?tab=integration' },
  worktime: { label: '工时同步', path: '/kpi-report?tab=worktime' },
  oa: { label: 'OA 待办', path: '/oa-affairs' },
  mail: { label: '邮箱账号', path: '/emails' },
  deepseek: { label: '模型管理', path: MODELS_PATH },
  glm: { label: '模型管理', path: MODELS_PATH },
};

const builtinSpecs: Record<string, BuiltinSpec> = {
  yunxiao: {
    baseUrl: { placeholder: 'https://openapi-rdc.aliyuncs.com' },
    token: '个人访问令牌',
    extras: [
      {
        key: 'edition',
        label: '版本',
        type: 'select',
        options: [
          { value: 'center', label: '中心化版本' },
          { value: 'region', label: '专有云版本' },
        ],
      },
      {
        key: 'organizationId',
        label: '组织 ID',
        placeholder: '专有云版本可留空',
      },
    ],
  },
  worktime: {
    baseUrl: { label: '系统地址', placeholder: 'https://worktime.lucidata.cn' },
    username: '登录工号',
    password: '登录密码',
  },
  oa: {
    baseUrl: { placeholder: 'https://oa.example.com' },
    username: '服务账号',
    password: '服务密码',
    token: '访问令牌',
    extras: [
      {
        key: 'contractExportUrl',
        label: '销售合同导出地址',
        placeholder: 'vReport 导出接口地址，可留空',
      },
    ],
  },
  yuque: {
    mcpUrl: true,
    token: '访问 Token',
    extras: [
      { key: 'repo', label: '知识库', placeholder: '如 vuntcs/cf_records' },
      { key: 'parentDir', label: '父目录', placeholder: '如 周会纪要' },
    ],
  },
  mail: {
    baseUrl: {
      label: '系统外链地址',
      placeholder: '用于邮件内回链，可留空',
    },
    extras: [{ key: 'searchDays', label: '邮件搜索天数', type: 'number' }],
  },
  deepseek: {
    baseUrl: { placeholder: 'https://api.deepseek.com' },
    token: 'API Key',
  },
  glm: {
    baseUrl: { placeholder: 'https://open.bigmodel.cn/api/paas/v4' },
    token: 'API Key',
  },
  wecom: {
    baseUrl: { placeholder: 'https://qyapi.weixin.qq.com' },
    token: 'Secret',
    extras: [
      { key: 'corpId', label: 'CorpId' },
      { key: 'agentId', label: 'AgentId' },
    ],
  },
};

/** 通用连接器的凭据字段（按认证方式渲染）。 */
const genericCredentials: Record<
  AiConnectorAuthType,
  { username?: string; password?: string; token?: string }
> = {
  BASIC: { username: '用户名', password: '密码' },
  TOKEN: { token: 'Token' },
  MCP: { token: '访问 Token' },
  SEEYON: { username: '服务账号', password: '密码', token: '访问令牌' },
  WECOM: { token: 'Secret' },
  MAIL: {},
};

/** 扫码弹窗状态：loading=正在获取二维码，其余与后端轮询状态一致。 */
type QrState = 'loading' | WecomCliQrPollStatus;

/** 机器人通道品类状态徽标（企微口径：可用 / 已过期 / 未授权 / 未对企开放 / 异常）。 */
const capabilityStates: Record<
  WecomCliCapabilityState,
  { color: string; text: string }
> = {
  AVAILABLE: { color: 'success', text: '可用' },
  EXPIRED: { color: 'warning', text: '已过期' },
  UNAUTHORIZED: { color: 'default', text: '未授权' },
  UNAVAILABLE: { color: 'warning', text: '未对企开放' },
  ERROR: { color: 'error', text: '异常' },
};

/** 机器人通道状态徽标：CLI 未安装 > 未授权 > 已授权（Bot xxx）。 */
const wecomStatusBadge = (status?: WecomCliStatus) => {
  if (!status) return { color: 'default', text: '状态未知' };
  if (!status.cliInstalled) return { color: 'error', text: 'CLI 未安装' };
  if (status.authorized)
    return { color: 'success', text: `已授权（Bot ${status.botId || '—'}）` };
  return { color: 'warning', text: '未授权' };
};

/** 企微原文可能内嵌 markdown 链接（品类续期引导），逐字展示并把链接渲染为可点击外链。 */
const renderCapabilityMessage = (message?: string): ReactNode => {
  if (!message) return '—';
  const nodes: ReactNode[] = [];
  const pattern = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g;
  let cursor = 0;
  let match = pattern.exec(message);
  while (match) {
    if (match.index > cursor) nodes.push(message.slice(cursor, match.index));
    nodes.push(
      <a
        key={`link-${match.index}`}
        href={match[2]}
        target="_blank"
        rel="noopener noreferrer"
      >
        {match[1]}
      </a>,
    );
    cursor = match.index + match[0].length;
    match = pattern.exec(message);
  }
  if (cursor < message.length) nodes.push(message.slice(cursor));
  return nodes;
};

export default function ConnectorsPage() {
  const [form] = Form.useForm<ConnectorForm>();
  const [rows, setRows] = useState<AiConnectorView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<AiConnectorView>();
  const [initialExtra, setInitialExtra] = useState<Record<string, unknown>>({});
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<number>();
  const [toggling, setToggling] = useState<number>();
  const [wecomStatus, setWecomStatus] = useState<WecomCliStatus>();
  const [wecomStatusLoading, setWecomStatusLoading] = useState(false);
  const [wecomStatusError, setWecomStatusError] = useState('');
  const [capabilities, setCapabilities] = useState<WecomCliCapability[]>([]);
  const [capabilitiesLoading, setCapabilitiesLoading] = useState(false);
  const [capabilitiesError, setCapabilitiesError] = useState('');
  const [botAuthorizing, setBotAuthorizing] = useState(false);
  const [qrOpen, setQrOpen] = useState(false);
  const [qrSession, setQrSession] = useState<WecomCliQrSession>();
  const [qrState, setQrState] = useState<QrState>('loading');
  const [qrHint, setQrHint] = useState('');
  const [qrRemaining, setQrRemaining] = useState(0);
  const qrPollingRef = useRef(false);
  const authType = Form.useWatch('authType', form);
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
  const wecomRow = rows.find((row) => row.code === 'wecom');
  /** 机器人通道的 Bot 凭证是否已配置（Bot ID 明文回显 + Secret 配置标记）。 */
  const wecomBotConfigured =
    Boolean(wecomRow?.extraConfig?.botId) &&
    Boolean(wecomRow?.botSecretConfigured);
  const loadWecomStatus = useCallback(async () => {
    setWecomStatusLoading(true);
    setWecomStatusError('');
    try {
      setWecomStatus(await superworkApi.getWecomCliStatus());
    } catch (e) {
      setWecomStatus(undefined);
      setWecomStatusError(
        e instanceof Error ? e.message : '机器人通道状态读取失败',
      );
    } finally {
      setWecomStatusLoading(false);
    }
  }, []);
  const loadCapabilities = useCallback(async (refresh = false) => {
    setCapabilitiesLoading(true);
    setCapabilitiesError('');
    try {
      setCapabilities(await superworkApi.getWecomCliCapabilities(refresh));
    } catch (e) {
      setCapabilities([]);
      setCapabilitiesError(
        e instanceof Error ? e.message : '品类授权矩阵读取失败',
      );
    } finally {
      setCapabilitiesLoading(false);
    }
  }, []);
  /** 进入页面（企业微信连接器存在）即拉取机器人通道状态与品类矩阵。 */
  useEffect(() => {
    if (!wecomRow) return;
    void loadWecomStatus();
    void loadCapabilities();
  }, [Boolean(wecomRow), loadWecomStatus, loadCapabilities]);
  /** Bot 凭证授权：用连接器已保存的 Bot ID + Secret 完成无人值守授权。 */
  const authorizeWecomBot = async () => {
    setBotAuthorizing(true);
    try {
      const result = await superworkApi.authorizeWecomCli();
      if (result.authorized) {
        message.success(
          result.hint || `机器人通道已授权（Bot ${result.botId || '—'}）`,
        );
      } else {
        message.warning(result.hint || '授权未完成，请检查 Bot 凭证');
      }
      await loadWecomStatus();
      await loadCapabilities(true);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '机器人授权失败');
    } finally {
      setBotAuthorizing(false);
    }
  };
  const fetchQrcode = async () => {
    setQrState('loading');
    setQrHint('');
    setQrSession(undefined);
    setQrRemaining(0);
    try {
      const session = await superworkApi.createWecomCliQrcode();
      setQrSession(session);
      setQrRemaining(
        Math.max(0, Math.ceil((session.expireAt - Date.now()) / 1000)),
      );
      setQrState('pending');
    } catch (e) {
      setQrState('failed');
      setQrHint(e instanceof Error ? e.message : '二维码获取失败，请重试');
    }
  };
  const openQrAuth = () => {
    setQrOpen(true);
    void fetchQrcode();
  };
  /** 轮询扫码结果；authorized 后关闭弹窗并刷新状态与品类矩阵。 */
  const pollQrSession = useCallback(
    async (sessionId: string) => {
      if (qrPollingRef.current) return;
      qrPollingRef.current = true;
      try {
        const result = await superworkApi.pollWecomCliAuth(sessionId);
        if (result.status === 'pending') return;
        if (result.status === 'authorized') {
          setQrState('authorized');
          setQrOpen(false);
          message.success(
            result.hint || `机器人通道已授权（Bot ${result.botId || '—'}）`,
          );
          await loadWecomStatus();
          await loadCapabilities(true);
          return;
        }
        setQrState(result.status);
        setQrHint(
          result.hint ||
            (result.status === 'expired'
              ? '二维码已过期，请重新获取'
              : '授权失败，请重新获取二维码'),
        );
      } catch (e) {
        setQrState('failed');
        setQrHint(
          e instanceof Error ? e.message : '授权状态查询失败，请重新获取二维码',
        );
      } finally {
        qrPollingRef.current = false;
      }
    },
    [loadCapabilities, loadWecomStatus],
  );
  /** 二维码倒计时与轮询：弹窗关闭、超时或终态时随 effect 清理停止，避免定时器泄漏。 */
  useEffect(() => {
    if (!qrOpen || !qrSession || qrState !== 'pending') return;
    const deadline = qrSession.expireAt;
    const tick = () => {
      const remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      setQrRemaining(remaining);
      if (remaining <= 0) setQrState('expired');
    };
    tick();
    const countdownTimer = window.setInterval(tick, 1000);
    const pollTimer = window.setInterval(() => {
      void pollQrSession(qrSession.sessionId);
    }, 3000);
    return () => {
      window.clearInterval(countdownTimer);
      window.clearInterval(pollTimer);
    };
  }, [pollQrSession, qrOpen, qrSession, qrState]);
  const openCreate = () => {
    setEditing(undefined);
    setInitialExtra({});
    form.resetFields();
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
      enabled: false,
      sortOrder: rows.length,
      extraConfig: {},
    });
    setOpen(true);
  };
  const openEdit = (row: AiConnectorView) => {
    setEditing(row);
    setInitialExtra({ ...(row.extraConfig || {}) });
    form.resetFields();
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
      botSecret: '',
      enabled: row.enabled,
      sortOrder: row.sortOrder,
      extraConfig: { ...(row.extraConfig || {}) },
    });
    setOpen(true);
  };
  /** extra_config 只提交变化的字面量，服务端按缺省键合并。 */
  const changedExtraConfig = (values: ConnectorForm) => {
    const changed: Record<string, unknown> = {};
    Object.entries(values.extraConfig || {}).forEach(([key, value]) => {
      const next = value === undefined || value === null ? '' : value;
      const before = initialExtra[key];
      const previous = before === undefined || before === null ? '' : before;
      if (String(next) === String(previous)) return;
      changed[key] = next;
    });
    return changed;
  };
  const save = async () => {
    try {
      await form.validateFields();
    } catch {
      return;
    }
    const values = form.getFieldsValue(true) as ConnectorForm;
    const spec = editing?.builtIn ? builtinSpecs[editing.code] : undefined;
    const payload: AiConnectorSavePayload = { name: values.name?.trim() };
    if (spec) {
      if (spec.baseUrl) payload.baseUrl = values.baseUrl || '';
      if (spec.mcpUrl) payload.mcpUrl = values.mcpUrl || '';
      if (spec.username && values.username) payload.username = values.username;
      if (spec.password && values.password) payload.password = values.password;
      if (spec.token && values.token) payload.token = values.token;
      if (editing?.code === 'wecom' && values.botSecret)
        payload.botSecret = values.botSecret;
      const extraConfig = changedExtraConfig(values);
      if (Object.keys(extraConfig).length) payload.extraConfig = extraConfig;
    } else {
      if (!editing) payload.code = values.code?.trim();
      payload.authType = values.authType;
      payload.baseUrl = values.baseUrl || '';
      payload.mcpUrl = values.mcpUrl || '';
      payload.testPath = values.testPath || '';
      payload.queryPath = values.queryPath || '';
      payload.readPath = values.readPath || '';
      payload.sortOrder = values.sortOrder ?? 0;
      payload.enabled = Boolean(values.enabled);
      const labels = genericCredentials[values.authType] || {};
      if (labels.username && values.username)
        payload.username = values.username;
      if (labels.password && values.password)
        payload.password = values.password;
      if (labels.token && values.token) payload.token = values.token;
    }
    setSaving(true);
    try {
      if (editing) await superworkApi.updateAiConnector(editing.id, payload);
      else await superworkApi.createAiConnector(payload);
      message.success(editing ? '连接器已更新' : '连接器已创建');
      setOpen(false);
      await load();
      if (editing?.code === 'wecom') {
        void loadWecomStatus();
        void loadCapabilities();
      }
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
  const toggleEnabled = async (row: AiConnectorView, enabled: boolean) => {
    setToggling(row.id);
    try {
      await superworkApi.updateAiConnector(row.id, { enabled });
      message.success(enabled ? '连接器已启用' : '连接器已停用');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '启用状态更新失败');
    } finally {
      setToggling(undefined);
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
            {row.code} · {authLabels[row.authType] || row.authType}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '服务地址',
      dataIndex: 'baseUrl',
      render: (value, row) => (
        <Typography.Text code>{row.mcpUrl || value || '—'}</Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'ready',
      width: 110,
      render: (_: unknown, row) => {
        const status = !row.enabled
          ? { color: 'default', text: '已停用' }
          : row.ready
            ? { color: 'success', text: '已就绪' }
            : { color: 'warning', text: '未配置' };
        return (
          <Tooltip title={row.hint}>
            <Tag color={status.color}>{status.text}</Tag>
          </Tooltip>
        );
      },
    },
    {
      title: '最近测试',
      dataIndex: 'lastTestedAt',
      width: 190,
      render: (_: unknown, row) => (
        <Tooltip title={row.lastTestMessage || undefined}>
          <Space orientation="vertical" size={0}>
            <Tag
              color={
                row.lastTestStatus === 'SUCCESS'
                  ? 'success'
                  : row.lastTestStatus === 'FAILED'
                    ? 'error'
                    : 'default'
              }
            >
              {row.lastTestStatus === 'SUCCESS'
                ? '通过'
                : row.lastTestStatus === 'FAILED'
                  ? '失败'
                  : '未测试'}
            </Tag>
            <Typography.Text type="secondary">
              {row.lastTestedAt
                ? dayjs(row.lastTestedAt).format('YYYY-MM-DD HH:mm')
                : '—'}
            </Typography.Text>
          </Space>
        </Tooltip>
      ),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (value, row) => (
        <Switch
          size="small"
          checked={value}
          loading={toggling === row.id}
          onChange={(checked) => void toggleEnabled(row, checked)}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 340,
      render: (_: unknown, row) => {
        const link = connectorLinks[row.code];
        return (
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
            {link && (
              <Button type="link" onClick={() => history.push(link.path)}>
                {link.label}
              </Button>
            )}
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
        );
      },
    },
  ];
  const capabilityColumns: TableProps<WecomCliCapability>['columns'] = [
    {
      title: '品类',
      dataIndex: 'label',
      render: (value: string, row) => (
        <div>
          <Typography.Text strong>{value || row.service}</Typography.Text>
          <Typography.Text type="secondary" className="sw-connector-meta">
            {row.service}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'state',
      width: 120,
      render: (value: WecomCliCapabilityState) => {
        const state = capabilityStates[value] ?? {
          color: 'default',
          text: value || '未知',
        };
        return <Tag color={state.color}>{state.text}</Tag>;
      },
    },
    {
      title: '说明',
      dataIndex: 'message',
      render: (value: string) => (
        <Typography.Text type="secondary" className="sw-wecom-message">
          {renderCapabilityMessage(value)}
        </Typography.Text>
      ),
    },
  ];
  const activeSpec = editing?.builtIn ? builtinSpecs[editing.code] : undefined;
  const credentialLabels = activeSpec
    ? {
        username: activeSpec.username,
        password: activeSpec.password,
        token: activeSpec.token,
      }
    : genericCredentials[authType || 'BASIC'];
  const credentialNames = [
    credentialLabels.username,
    credentialLabels.password,
    credentialLabels.token,
  ].filter(Boolean);
  const credentialSpan = credentialNames.length
    ? Math.floor(24 / credentialNames.length)
    : 24;
  const renderCredentials = () =>
    credentialNames.length > 0 && (
      <Row gutter={14}>
        {credentialLabels.username && (
          <Col span={credentialSpan}>
            <Form.Item name="username" label={credentialLabels.username}>
              <Input
                autoComplete="off"
                placeholder={
                  editing?.usernameConfigured ? '已配置；留空保持不变' : ''
                }
              />
            </Form.Item>
          </Col>
        )}
        {credentialLabels.password && (
          <Col span={credentialSpan}>
            <Form.Item name="password" label={credentialLabels.password}>
              <Input.Password
                autoComplete="new-password"
                placeholder={
                  editing?.passwordConfigured ? '已配置；留空保持不变' : ''
                }
              />
            </Form.Item>
          </Col>
        )}
        {credentialLabels.token && (
          <Col span={credentialSpan}>
            <Form.Item name="token" label={credentialLabels.token}>
              <Input.Password
                autoComplete="new-password"
                placeholder={
                  editing?.tokenConfigured ? '已配置；留空保持不变' : ''
                }
              />
            </Form.Item>
          </Col>
        )}
      </Row>
    );
  const statusBadge = wecomStatusBadge(wecomStatus);
  return (
    <div className="sw-page sw-connectors">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / CONNECTORS
          </Typography.Text>
          <Typography.Title level={2}>连接器管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            统一维护云效、工时、OA、语雀、邮件与 AI
            提供方等外部系统连接；凭据留空表示保持原配置。
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
      <Alert
        type="info"
        showIcon
        className="sw-connector-hint"
        message={
          <span>
            模型名与用途（助手可用 /
            摘要使用）已移至「模型管理」，本页只维护地址与凭据。
            <Typography.Link
              className="sw-connector-hint-link"
              onClick={() => history.push(MODELS_PATH)}
            >
              前往模型管理
            </Typography.Link>
          </span>
        }
      />
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取连接器"
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
          locale={{ emptyText: '暂无连接器' }}
        />
      </Card>
      {wecomRow && (
        <Card
          variant="borderless"
          className="sw-table-card sw-wecom-bot"
          title="企业微信 · 机器人通道"
          extra={
            <Button
              size="small"
              icon={<ReloadOutlined />}
              loading={capabilitiesLoading}
              onClick={() => void loadCapabilities(true)}
            >
              刷新体检
            </Button>
          }
        >
          <Space orientation="vertical" size={12} className="sw-wecom-bot-body">
            <Space size={8} wrap>
              {wecomStatusLoading && !wecomStatus ? (
                <Spin size="small" />
              ) : (
                <Tag color={statusBadge.color}>{statusBadge.text}</Tag>
              )}
              {wecomStatus?.hint && (
                <Typography.Text type="secondary">
                  {wecomStatus.hint}
                </Typography.Text>
              )}
            </Space>
            {wecomStatusError && (
              <Alert
                type="error"
                showIcon
                message={wecomStatusError}
                action={
                  <Button size="small" onClick={() => void loadWecomStatus()}>
                    重试
                  </Button>
                }
              />
            )}
            {!wecomBotConfigured && (
              <Alert
                type="info"
                showIcon
                message="未配置：填写 Bot ID 与 Bot Secret 后可启用"
                description="Bot 凭证在「企业微信」连接器的编辑弹窗内填写，保存后回到本面板完成授权；也可直接扫码授权。"
                action={
                  wecomRow && (
                    <Button size="small" onClick={() => openEdit(wecomRow)}>
                      去填写
                    </Button>
                  )
                }
              />
            )}
            <Space size={8} wrap>
              <Button
                type="primary"
                icon={<SafetyCertificateOutlined />}
                loading={botAuthorizing}
                onClick={() => void authorizeWecomBot()}
              >
                使用 Bot 凭证授权
              </Button>
              <Button icon={<QrcodeOutlined />} onClick={openQrAuth}>
                扫码授权
              </Button>
            </Space>
            <Divider titlePlacement="left" plain>
              品类授权矩阵
            </Divider>
            {capabilitiesError && (
              <Alert
                type="error"
                showIcon
                message={capabilitiesError}
                action={
                  <Button
                    size="small"
                    onClick={() => void loadCapabilities(true)}
                  >
                    重试
                  </Button>
                }
              />
            )}
            <Table<WecomCliCapability>
              rowKey="service"
              size="small"
              loading={capabilitiesLoading}
              columns={capabilityColumns}
              dataSource={capabilities}
              pagination={false}
              locale={{ emptyText: '暂无品类数据' }}
            />
          </Space>
        </Card>
      )}
      <Modal
        title="企业微信扫码授权"
        open={qrOpen}
        width={400}
        onCancel={() => setQrOpen(false)}
        footer={<Button onClick={() => setQrOpen(false)}>关闭</Button>}
      >
        <div className="sw-wecom-qr">
          {qrState === 'loading' ? (
            <Space orientation="vertical" align="center" size={8}>
              <Spin />
              <Typography.Text type="secondary">
                正在获取二维码…
              </Typography.Text>
            </Space>
          ) : qrState === 'pending' && qrSession ? (
            <>
              <div className="sw-wecom-qr-frame">
                <img
                  src={`data:image/png;base64,${qrSession.imageBase64}`}
                  alt="企业微信扫码授权二维码"
                />
              </div>
              <Typography.Text type="secondary">
                请用企业微信扫码完成授权；二维码{' '}
                {Math.floor(qrRemaining / 60)}:
                {String(qrRemaining % 60).padStart(2, '0')} 后过期
              </Typography.Text>
            </>
          ) : qrState === 'authorized' ? (
            <Alert
              type="success"
              showIcon
              message={qrHint || '扫码授权成功'}
            />
          ) : (
            <>
              <Alert
                type="warning"
                showIcon
                message={qrHint || '二维码已失效，请重新获取'}
              />
              <Button type="primary" onClick={() => void fetchQrcode()}>
                重新获取
              </Button>
            </>
          )}
        </div>
      </Modal>
      <Modal
        title={editing ? `编辑连接器 · ${editing.name}` : '新增连接器'}
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
                rules={
                  activeSpec
                    ? []
                    : [
                        { required: true, message: '请输入连接器编码' },
                        {
                          pattern: /^[a-z][a-z0-9_]{1,31}$/,
                          message:
                            '小写字母开头，可含小写字母、数字、下划线（2-32 位）',
                        },
                      ]
                }
              >
                <Input disabled={Boolean(editing)} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="name"
                label="连接器名称"
                rules={[{ required: true, message: '请输入连接器名称' }]}
              >
                <Input />
              </Form.Item>
            </Col>
          </Row>
          {activeSpec ? (
            <>
              {activeSpec.baseUrl && (
                <Form.Item
                  name="baseUrl"
                  label={activeSpec.baseUrl.label || '服务地址'}
                >
                  <Input placeholder={activeSpec.baseUrl.placeholder} />
                </Form.Item>
              )}
              {activeSpec.mcpUrl && (
                <Form.Item name="mcpUrl" label="MCP 服务地址">
                  <Input placeholder="https://mcp.example.com/mcp" />
                </Form.Item>
              )}
              {renderCredentials()}
              {activeSpec.extras?.map((field) => (
                <Form.Item
                  key={field.key}
                  name={['extraConfig', field.key]}
                  label={field.label}
                  valuePropName={
                    field.type === 'switch' ? 'checked' : undefined
                  }
                >
                  {field.type === 'number' ? (
                    <InputNumber min={1} style={{ width: '100%' }} />
                  ) : field.type === 'switch' ? (
                    <Switch />
                  ) : field.type === 'select' ? (
                    <Select options={field.options} />
                  ) : (
                    <Input placeholder={field.placeholder} />
                  )}
                </Form.Item>
              ))}
              {editing?.code === 'wecom' && (
                <>
                  <Divider titlePlacement="left" plain>
                    机器人通道（wecom-cli）
                  </Divider>
                  <Typography.Paragraph
                    type="secondary"
                    className="sw-wecom-form-hint"
                  >
                    待办、会议、文档等品类走企微智能机器人通道；保存后在下方的「机器人通道」面板完成授权与品类体检。
                  </Typography.Paragraph>
                  <Row gutter={14}>
                    <Col span={12}>
                      <Form.Item name={['extraConfig', 'botId']} label="Bot ID">
                        <Input
                          autoComplete="off"
                          placeholder="企微智能机器人 Bot ID"
                        />
                      </Form.Item>
                    </Col>
                    <Col span={12}>
                      <Form.Item name="botSecret" label="Bot Secret">
                        <Input.Password
                          autoComplete="new-password"
                          placeholder={
                            editing.botSecretConfigured
                              ? '已配置，留空则不修改'
                              : '企微智能机器人 Bot Secret'
                          }
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                </>
              )}
            </>
          ) : (
            <>
              <Row gutter={14}>
                <Col span={12}>
                  <Form.Item
                    name="authType"
                    label="认证方式"
                    rules={[{ required: true }]}
                  >
                    <Select
                      options={Object.entries(authLabels).map(
                        ([value, label]) => ({
                          value,
                          label,
                        }),
                      )}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="sortOrder" label="排序">
                    <InputNumber min={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="baseUrl" label="服务地址">
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
              {renderCredentials()}
              <Form.Item
                name="enabled"
                label="启用状态"
                valuePropName="checked"
              >
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </div>
  );
}
