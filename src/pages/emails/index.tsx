import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CalendarOutlined,
  EditOutlined,
  MailOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Modal,
  message,
  Pagination,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Statistic,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type EmailAccount,
  type EmailActionLink,
  type EmailDailyDigest,
  type EmailDigestItem,
  type EmailGroupingJobStatus,
  type EmailInterpretation,
  type EmailMessageDetail,
  type EmailMessagePage,
  type EmailMessageSummary,
  type EmailProjectGroup,
  type EmailSenderCompanyGroup,
  type EmailSyncStatus,
  type EmailValueMetrics,
  type EmailWeComMapping,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const PAGE_SIZE = 20;
const emptyPage: EmailMessagePage = {
  records: [],
  total: 0,
  size: PAGE_SIZE,
  current: 1,
};
const dateText = (v?: string) =>
  v ? v.replace('T', ' ').slice(0, 16) : '暂无';
const maskEmail = (v?: string) => {
  if (!v) return '邮箱账户';
  const [a, b] = v.split('@');
  return b ? `${a.slice(0, 1)}***${a.slice(-1)}@${b}` : '***';
};

export default function EmailsPage() {
  const [account, setAccount] = useState<EmailAccount>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [bindOpen, setBindOpen] = useState(false);
  const [bindForm] = Form.useForm<{
    emailAddress: string;
    appPassword: string;
  }>();
  const [saving, setSaving] = useState(false);
  const [digestDate, setDigestDate] = useState(dayjs().subtract(1, 'day'));
  const [digest, setDigest] = useState<EmailDailyDigest>();
  const [digestLoading, setDigestLoading] = useState(false);
  const [digestTab, setDigestTab] = useState('overview');
  const [regenerating, setRegenerating] = useState(false);
  const [messages, setMessages] = useState<EmailMessagePage>(emptyPage);
  const [messageLoading, setMessageLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [messageDate, setMessageDate] = useState('');
  const [page, setPage] = useState(1);
  const [projectGroups, setProjectGroups] = useState<EmailProjectGroup[]>([]);
  const [companyGroups, setCompanyGroups] = useState<EmailSenderCompanyGroup[]>(
    [],
  );
  const [groupMode, setGroupMode] = useState<'project' | 'company'>('project');
  const [selectedGroup, setSelectedGroup] = useState('all');
  const [syncStatus, setSyncStatus] = useState<EmailSyncStatus>({
    status: 'IDLE',
  });
  const [_groupingStatus, setGrouping] = useState<EmailGroupingJobStatus>({
    status: 'IDLE',
    total: 0,
    processed: 0,
    grouped: 0,
    ungrouped: 0,
  });
  const [wecomMapping, setWecomMapping] = useState<EmailWeComMapping>();
  const [metrics, setMetrics] = useState<EmailValueMetrics>();
  const [actions, setActions] = useState<EmailActionLink[]>([]);
  const [syncTimer, setSyncTimer] = useState<number>();
  const [groupingTimer, setGroupingTimer] = useState<number>();
  const [selected, setSelected] = useState<EmailMessageDetail>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [interpretLoading, setInterpretLoading] = useState(false);
  const [detailTab, setDetailTab] = useState('original');
  const [replyDraft, setReplyDraft] = useState('');
  const [replying, setReplying] = useState(false);
  const [wecomForm] = Form.useForm<{
    weComUserId?: string;
    enabled?: boolean;
  }>();
  const [wecomOpen, setWecomOpen] = useState(false);
  const [regroupOpen, setRegroupOpen] = useState(false);
  const [regroupProjectId, setRegroupProjectId] = useState<number>();
  const [regroupSaving, setRegroupSaving] = useState(false);
  const [convertOpen, setConvertOpen] = useState(false);
  const [convertItemState, setConvertItemState] = useState<EmailDigestItem>();
  const [convertActionType, setConvertActionType] = useState<'TASK' | 'ISSUE'>(
    'TASK',
  );
  const [convertForm] = Form.useForm<{
    projectId?: number;
    assigneeId?: number;
    severity?: string;
  }>();
  const [projectOptions, setProjectOptions] = useState<
    Array<{ id: number; name: string }>
  >([]);
  const [userOptions, setUserOptions] = useState<
    Array<{ id: number; name: string }>
  >([]);
  const configured = Boolean(account?.configured);
  const loadDigest = useCallback(async () => {
    if (!configured) return;
    setDigestLoading(true);
    try {
      const d = await superworkApi.getEmailDigest(
        digestDate.format('YYYY-MM-DD'),
      );
      setDigest(d);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '摘要加载失败');
    } finally {
      setDigestLoading(false);
    }
  }, [configured, digestDate]);
  const loadMessages = useCallback(async () => {
    if (!configured) return;
    setMessageLoading(true);
    try {
      const params: Parameters<typeof superworkApi.getEmailMessages>[0] = {
        page,
        size: PAGE_SIZE,
        keyword: keyword.trim() || undefined,
        date: messageDate || undefined,
      };
      if (groupMode === 'project' && selectedGroup.startsWith('project:'))
        params.projectId = Number(selectedGroup.slice(8));
      if (groupMode === 'project' && selectedGroup === 'ungrouped')
        params.ungrouped = true;
      if (groupMode === 'company' && selectedGroup !== 'all')
        params.senderDomain = selectedGroup;
      setMessages(await superworkApi.getEmailMessages(params));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '收件箱加载失败');
    } finally {
      setMessageLoading(false);
    }
  }, [configured, page, keyword, messageDate, groupMode, selectedGroup]);
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const a = await superworkApi.getEmailAccount();
      setAccount(a);
      if (a.configured) {
        const [
          projects,
          companies,
          syncState,
          groupingState,
          mapping,
          valueMetrics,
          projectPage,
          userPage,
        ] = await Promise.all([
          superworkApi.getEmailProjectGroups(),
          superworkApi.getEmailSenderCompanyGroups(),
          superworkApi.getEmailSyncStatus().catch(() => ({ status: 'IDLE' })),
          superworkApi.getEmailGroupingStatus().catch(
            () =>
              ({
                status: 'IDLE',
                total: 0,
                processed: 0,
                grouped: 0,
                ungrouped: 0,
              }) as EmailGroupingJobStatus,
          ),
          superworkApi.getEmailWeComMapping().catch(() => undefined),
          superworkApi.getEmailValueMetrics().catch(() => undefined),
          superworkApi
            .getProjects({ page: 1, size: 500 })
            .catch(() => ({ records: [] })),
          superworkApi
            .getUsers({ page: 1, size: 500 })
            .catch(() => ({ records: [] })),
        ]);
        setProjectGroups(projects);
        setCompanyGroups(companies);
        setSyncStatus(syncState);
        setGrouping(groupingState);
        setWecomMapping(mapping);
        setMetrics(valueMetrics);
        setProjectOptions(projectPage.records || []);
        setUserOptions(
          (userPage.records || []).map((user: any) => ({
            id: user.id,
            name: user.realName || user.username || String(user.id),
          })),
        );
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '邮箱账户加载失败');
    } finally {
      setLoading(false);
    }
  }, [wecomForm]);
  useEffect(() => {
    void load();
    return () => {
      if (syncTimer) window.clearInterval(syncTimer);
      if (groupingTimer) window.clearInterval(groupingTimer);
    };
  }, [load, syncTimer, groupingTimer]);
  useEffect(() => {
    if (configured) {
      void loadDigest();
      void loadMessages();
    }
  }, [configured, loadDigest, loadMessages]);
  useEffect(() => {
    if (bindOpen) {
      bindForm.setFieldsValue({
        emailAddress: account?.emailAddress || '',
        appPassword: '',
      });
    }
  }, [account, bindForm, bindOpen]);
  useEffect(() => {
    if (wecomOpen) {
      wecomForm.setFieldsValue({
        weComUserId: wecomMapping?.weComUserId || '',
        enabled: wecomMapping?.enabled ?? true,
      });
    }
  }, [wecomForm, wecomMapping, wecomOpen]);
  useEffect(() => {
    if (convertOpen) convertForm.resetFields();
  }, [convertForm, convertOpen]);
  const saveAccount = async () => {
    const values = await bindForm.validateFields();
    setSaving(true);
    try {
      const a = await superworkApi.saveEmailAccount(values);
      setAccount(a);
      bindForm.resetFields();
      setBindOpen(false);
      message.success('邮箱配置已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '邮箱保存失败');
    } finally {
      setSaving(false);
    }
  };
  const testAccount = async () => {
    setSaving(true);
    try {
      const result = await superworkApi.testEmailAccount();
      if (result.success) message.success(result.message || '连接测试成功');
      else message.error(result.message || '连接测试失败');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '连接测试失败');
    } finally {
      setSaving(false);
    }
  };
  const removeAccount = () => {
    Modal.confirm({
      title: '确认解绑企业邮箱？',
      content: '解绑后将停止后续同步，已经收取的邮件不会被删除。',
      okText: '确认解绑',
      cancelText: '取消',
      okType: 'danger',
      onOk: async () => {
        await superworkApi.removeEmailAccount();
        message.success('邮箱已解绑');
        setAccount(undefined);
        setDigest(undefined);
        setMessages({ records: [], total: 0, current: 1, size: PAGE_SIZE });
        await load();
      },
    });
  };
  const pollSync = () => {
    if (syncTimer) window.clearInterval(syncTimer);
    const timer = window.setInterval(async () => {
      const next = await superworkApi
        .getEmailSyncStatus()
        .catch(() => undefined);
      if (!next) return;
      setSyncStatus(next);
      if (!['RUNNING', 'QUEUED'].includes(next.status)) {
        window.clearInterval(timer);
        setSyncTimer(undefined);
        if (next.status === 'SUCCESS') {
          await Promise.all([loadDigest(), loadMessages(), load()]);
          message.success('邮件同步完成');
        }
      }
    }, 800);
    setSyncTimer(timer);
  };
  const pollGrouping = () => {
    if (groupingTimer) window.clearInterval(groupingTimer);
    const timer = window.setInterval(async () => {
      const next = await superworkApi
        .getEmailGroupingStatus()
        .catch(() => undefined);
      if (!next) return;
      setGrouping(next);
      if (next.status !== 'RUNNING') {
        window.clearInterval(timer);
        setGroupingTimer(undefined);
        if (next.status === 'SUCCESS') {
          await Promise.all([loadMessages(), load()]);
          message.success(`智能分组完成：${next.grouped} 封已归入项目`);
        }
      }
    }, 800);
    setGroupingTimer(timer);
  };
  const sync = async () => {
    try {
      const next = await superworkApi.startEmailSync();
      setSyncStatus(next);
      message.success('同步任务已启动');
      pollSync();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步启动失败');
    }
  };
  const grouping = async (regroupAll: boolean) => {
    try {
      const next = await superworkApi.startEmailGrouping(regroupAll);
      setGrouping(next);
      message.success('智能分组任务已启动');
      pollGrouping();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '智能分组失败');
    }
  };
  const openMessage = async (row: EmailMessageSummary | number) => {
    const id = typeof row === 'number' ? row : row.id;
    setDrawerOpen(true);
    setDetailLoading(true);
    try {
      const d = await superworkApi.getEmailMessage(id);
      setSelected(d);
      setActions(await superworkApi.getEmailActions(id).catch(() => []));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '邮件详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  };
  const openAdjacent = (direction: -1 | 1) => {
    if (!selected) return;
    const index = messages.records.findIndex((item) => item.id === selected.id);
    const target = messages.records[index + direction];
    if (target) void openMessage(target);
  };
  const saveWecom = async (values: {
    weComUserId?: string;
    enabled?: boolean;
  }) => {
    if (!values.weComUserId?.trim()) {
      message.warning('请填写企业微信 UserId');
      return;
    }
    try {
      const mapping = await superworkApi.saveEmailWeComMapping(
        values.weComUserId.trim(),
        values.enabled !== false,
      );
      setWecomMapping(mapping);
      message.success('企业微信推送身份已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '企业微信映射保存失败');
    }
  };
  const saveRegroup = async () => {
    if (!selected || !regroupProjectId) {
      message.warning('请选择归属项目');
      return;
    }
    setRegroupSaving(true);
    try {
      await superworkApi.assignEmailProject(selected.id, regroupProjectId);
      message.success('邮件分组已更正');
      setRegroupOpen(false);
      await openMessage(selected.id);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '分组更正失败');
    } finally {
      setRegroupSaving(false);
    }
  };
  const convertItem = (item: EmailDigestItem, actionType: 'TASK' | 'ISSUE') => {
    setConvertItemState(item);
    setConvertActionType(actionType);
    setConvertOpen(true);
  };
  const submitConvert = async (values: {
    projectId?: number;
    assigneeId?: number;
    severity?: string;
  }) => {
    if (!convertItemState) return;
    try {
      const result = await superworkApi.convertEmailItem({
        messageId: convertItemState.messageId,
        itemKind: convertActionType === 'TASK' ? 'TODO' : 'RISK',
        itemTitle:
          convertItemState.title || convertItemState.subject || '邮件行动项',
        actionType: convertActionType,
        projectId: values.projectId,
        assigneeId: values.assigneeId,
        severity: values.severity,
      });
      message.success(
        result.created
          ? `已创建${convertActionType === 'TASK' ? '任务' : '缺陷'}`
          : '已有对应行动项',
      );
      setConvertOpen(false);
      if (selected?.id === convertItemState.messageId)
        setActions(
          await superworkApi
            .getEmailActions(convertItemState.messageId)
            .catch(() => []),
        );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '行动转换失败');
    }
  };
  const feedback = async (value: 'USEFUL' | 'USELESS') => {
    try {
      const next = await superworkApi.feedbackEmailDigest(
        digestDate.format('YYYY-MM-DD'),
        value,
      );
      setDigest(next);
      message.success(value === 'USEFUL' ? '已标记为有用' : '已记录改进反馈');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '反馈失败');
    }
  };
  const generateInterpretation = async () => {
    if (!selected) return;
    setInterpretLoading(true);
    try {
      const i: EmailInterpretation =
        await superworkApi.generateEmailInterpretation(selected.id);
      setSelected({ ...selected, interpretation: i });
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'AI 解读失败');
    } finally {
      setInterpretLoading(false);
    }
  };
  const sendReply = async () => {
    if (!selected || !replyDraft.trim()) {
      message.warning('请填写回复内容');
      return;
    }
    setReplying(true);
    try {
      const result = await superworkApi.replyEmail(selected.id, replyDraft);
      if (result.status === 'SENT') {
        message.success('回复已发送');
        setReplyDraft('');
      } else message.error(result.errorMessage || '回复失败');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '回复失败');
    } finally {
      setReplying(false);
    }
  };
  const digestItems = (
    key: 'importantItems' | 'todos' | 'risks' | 'replySuggestions',
  ) => digest?.[key] || [];
  const activeGroupLabel = useMemo(
    () =>
      groupMode === 'project'
        ? selectedGroup === 'all'
          ? '全部项目邮件'
          : projectGroups.find(
              (g) =>
                (g.projectId ? `project:${g.projectId}` : 'ungrouped') ===
                selectedGroup,
            )?.projectName || '未分组'
        : selectedGroup === 'all'
          ? '全部公司邮件'
          : companyGroups.find((g) => g.domain === selectedGroup)
              ?.companyName || selectedGroup,
    [groupMode, selectedGroup, projectGroups, companyGroups],
  );
  return (
    <div className="sw-page sw-emails">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            AI / PERSONAL INBOX
          </Typography.Text>
          <Typography.Title level={2}>邮件管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            个人邮箱、每日摘要、项目分组与 AI
            解读。正文只展示纯文本，敏感凭据不回显。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {configured && (
            <Button
              type="primary"
              icon={<SyncOutlined />}
              onClick={() => void sync()}
            >
              立即同步
            </Button>
          )}
          <Button
            icon={<EditOutlined />}
            onClick={() => {
              setBindOpen(true);
            }}
          >
            {configured ? '账户设置' : '绑定邮箱'}
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取邮箱配置"
          description={error}
          action={<Button onClick={() => void load()}>重试</Button>}
        />
      )}
      {configured && (
        <Card
          variant="borderless"
          title="本月邮件价值"
          style={{ marginBottom: 14 }}
        >
          <Space size="large" wrap>
            <Statistic title="已转化" value={metrics?.converted ?? '—'} />
            <Statistic
              title="闭环率"
              value={
                metrics?.closeRate == null
                  ? '—'
                  : `${Math.round(metrics.closeRate * 100)}%`
              }
            />
            <Statistic title="摘要有用" value={metrics?.useful ?? '—'} />
            <Statistic
              title="平均响应"
              value={
                metrics?.avgResponseMinutes == null
                  ? '—'
                  : `${metrics.avgResponseMinutes} 分钟`
              }
            />
          </Space>
        </Card>
      )}
      {configured && digest && (
        <Card size="small" variant="borderless" style={{ marginBottom: 14 }}>
          <Space>
            <Typography.Text type="secondary">这份摘要有用吗？</Typography.Text>
            <Button
              size="small"
              type={digest.feedback === 'USEFUL' ? 'primary' : 'default'}
              onClick={() => void feedback('USEFUL')}
            >
              有用
            </Button>
            <Button
              size="small"
              danger={digest.feedback === 'USELESS'}
              onClick={() => void feedback('USELESS')}
            >
              没用
            </Button>
          </Space>
        </Card>
      )}
      {configured && (
        <Card size="small" variant="borderless" style={{ marginBottom: 14 }}>
          <Space>
            <Typography.Text>
              企业微信摘要推送：
              {wecomMapping?.enabled
                ? `已启用（${wecomMapping.weComUserId || '未填写'}）`
                : '未启用'}
            </Typography.Text>
            <Button
              size="small"
              onClick={() => {
                setWecomOpen(true);
              }}
            >
              配置推送
            </Button>
          </Space>
        </Card>
      )}
      {!loading && !configured && (
        <Card variant="borderless" className="sw-email-bind">
          <Row gutter={40} align="middle">
            <Col flex="1">
              <Typography.Title level={3}>绑定阿里云企业邮箱</Typography.Title>
              <Typography.Paragraph type="secondary">
                使用第三方客户端安全密码，不是网页登录密码。保存后不会回显，系统仅同步收件箱纯文本和附件元数据。
              </Typography.Paragraph>
              <Space wrap>
                <Tag>最近 7 天首次同步</Tag>
                <Tag>每小时增量同步</Tag>
                <Tag>安全阅读模式</Tag>
              </Space>
            </Col>
            <Col flex="360px">
              <Button
                type="primary"
                size="large"
                onClick={() => setBindOpen(true)}
              >
                开始绑定
              </Button>
            </Col>
          </Row>
        </Card>
      )}
      {configured && (
        <>
          <Card variant="borderless" className="sw-email-account">
            <Space orientation="vertical" size={10} style={{ width: '100%' }}>
              <Typography.Text className="sw-eyebrow">
                ALIBABA CLOUD ENTERPRISE MAIL
              </Typography.Text>
              <Space>
                <Typography.Title level={3} style={{ margin: 0 }}>
                  {maskEmail(account?.emailAddress)}
                </Typography.Title>
                <Tag
                  color={syncStatus.status === 'FAILED' ? 'error' : 'success'}
                >
                  {syncStatus.status === 'RUNNING'
                    ? '正在同步'
                    : syncStatus.status === 'FAILED'
                      ? '同步失败'
                      : '连接正常'}
                </Tag>
              </Space>
              <Typography.Text type="secondary">
                上次同步：{dateText(account?.lastSyncAt)} {syncStatus.message}
              </Typography.Text>
              <Space wrap>
                <Button size="small" onClick={() => setBindOpen(true)}>
                  账户设置
                </Button>
                <Button
                  size="small"
                  loading={saving}
                  onClick={() => void testAccount()}
                >
                  测试连接
                </Button>
                <Button size="small" danger onClick={removeAccount}>
                  解绑邮箱
                </Button>
              </Space>
            </Space>
          </Card>
          <Row gutter={14} className="sw-email-metrics">
            <Col span={6}>
              <Card variant="borderless">
                <Statistic
                  title="邮件总数"
                  value={digest?.mailCount ?? '—'}
                  prefix={<MailOutlined />}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card variant="borderless">
                <Statistic
                  title="重要邮件"
                  value={digest?.importantItems?.length ?? '—'}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card variant="borderless">
                <Statistic
                  title="待办事项"
                  value={digest?.todos?.length ?? '—'}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card variant="borderless">
                <Statistic
                  title="风险提醒"
                  value={digest?.risks?.length ?? '—'}
                />
              </Card>
            </Col>
          </Row>
          <Card
            variant="borderless"
            className="sw-email-digest"
            title={
              <Space>
                <CalendarOutlined /> 每日邮件摘要
              </Space>
            }
            extra={
              <Space>
                <Button
                  onClick={() => setDigestDate((d) => d.subtract(1, 'day'))}
                >
                  前一天
                </Button>
                <DatePicker
                  value={digestDate}
                  onChange={(d) => d && setDigestDate(d)}
                  allowClear={false}
                />
                <Button onClick={() => setDigestDate((d) => d.add(1, 'day'))}>
                  后一天
                </Button>
                <Button
                  loading={regenerating}
                  onClick={async () => {
                    setRegenerating(true);
                    try {
                      await superworkApi.regenerateEmailDigest(
                        digestDate.format('YYYY-MM-DD'),
                      );
                      message.success('摘要已进入生成队列');
                      setTimeout(() => void loadDigest(), 800);
                    } catch (e) {
                      message.error(
                        e instanceof Error ? e.message : '重新生成失败',
                      );
                    } finally {
                      setRegenerating(false);
                    }
                  }}
                >
                  重新生成
                </Button>
              </Space>
            }
          >
            <Spin spinning={digestLoading}>
              <Tabs
                activeKey={digestTab}
                onChange={setDigestTab}
                items={[
                  {
                    key: 'overview',
                    label: '摘要总览',
                    children: (
                      <Typography.Paragraph>
                        {digest?.overview ||
                          (digest ? '当天没有可展示的摘要。' : '暂无摘要')}
                      </Typography.Paragraph>
                    ),
                  },
                  {
                    key: 'topics',
                    label: `议题归纳 ${digest?.topics?.length || 0}`,
                    children: (
                      <List
                        dataSource={digest?.topics || []}
                        locale={{ emptyText: '暂无议题' }}
                        renderItem={(i) => (
                          <List.Item>
                            <List.Item.Meta
                              title={i.title}
                              description={i.summary}
                            />
                          </List.Item>
                        )}
                      />
                    ),
                  },
                  ...(
                    [
                      'importantItems',
                      'todos',
                      'risks',
                      'replySuggestions',
                    ] as const
                  ).map((key) => ({
                    key,
                    label: `${
                      (
                        {
                          importantItems: '重要邮件',
                          todos: '待办事项',
                          risks: '风险提醒',
                          replySuggestions: '回复建议',
                        } as Record<string, string>
                      )[key]
                    } ${digestItems(key).length}`,
                    children: (
                      <List
                        dataSource={digestItems(key)}
                        locale={{ emptyText: '暂无内容' }}
                        renderItem={(i: EmailDigestItem) => (
                          <List.Item
                            actions={[
                              <Button
                                key="view"
                                type="link"
                                onClick={() => void openMessage(i.messageId)}
                              >
                                查看邮件
                              </Button>,
                              ...(key === 'todos'
                                ? [
                                    <Button
                                      key="task"
                                      type="link"
                                      onClick={() =>
                                        void convertItem(i, 'TASK')
                                      }
                                    >
                                      转为任务
                                    </Button>,
                                  ]
                                : []),
                              ...(key === 'risks'
                                ? [
                                    <Button
                                      key="issue"
                                      type="link"
                                      onClick={() =>
                                        void convertItem(i, 'ISSUE')
                                      }
                                    >
                                      转为缺陷
                                    </Button>,
                                  ]
                                : []),
                            ]}
                          >
                            <List.Item.Meta
                              title={i.title || i.subject || '关联邮件'}
                              description={
                                i.content || i.summary || i.action || i.sender
                              }
                            />
                          </List.Item>
                        )}
                      />
                    ),
                  })),
                ]}
              />
            </Spin>
          </Card>
          <Card
            variant="borderless"
            className="sw-email-inbox"
            title={
              <Space>
                <MailOutlined /> {activeGroupLabel}
              </Space>
            }
            extra={
              <Space wrap>
                <DatePicker
                  placeholder="收件日期"
                  onChange={(d) => {
                    setMessageDate(d?.format('YYYY-MM-DD') || '');
                    setPage(1);
                  }}
                />
                <Input
                  allowClear
                  prefix={<SearchOutlined />}
                  placeholder="搜索主题或发件人"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  onPressEnter={() => {
                    setPage(1);
                    void loadMessages();
                  }}
                />
                <Button
                  type="primary"
                  onClick={() => {
                    setPage(1);
                    void loadMessages();
                  }}
                >
                  筛选
                </Button>
              </Space>
            }
          >
            <Row gutter={18}>
              <Col flex="260px">
                <Segmented
                  block
                  options={[
                    { label: '按项目', value: 'project' },
                    { label: '按发件人公司', value: 'company' },
                  ]}
                  value={groupMode}
                  onChange={(v) => {
                    setGroupMode(v as 'project' | 'company');
                    setSelectedGroup('all');
                    setPage(1);
                  }}
                />
                <List
                  size="small"
                  dataSource={
                    groupMode === 'project'
                      ? [
                          {
                            projectName: '全部邮件',
                            projectFullPath: '',
                            mailCount: projectGroups.reduce(
                              (n, g) => n + g.mailCount,
                              0,
                            ),
                            projectId: undefined,
                          },
                          ...projectGroups,
                        ]
                      : [
                          {
                            companyName: '全部公司',
                            domain: 'all',
                            mailCount: companyGroups.reduce(
                              (n, g) => n + g.mailCount,
                              0,
                            ),
                          },
                          ...companyGroups,
                        ]
                  }
                  renderItem={(
                    g: EmailProjectGroup | EmailSenderCompanyGroup,
                  ) => {
                    const id =
                      'domain' in g
                        ? g.domain
                        : g.projectId
                          ? `project:${g.projectId}`
                          : 'ungrouped';
                    const label = 'domain' in g ? g.companyName : g.projectName;
                    return (
                      <List.Item
                        className={
                          selectedGroup === id ||
                          (selectedGroup === 'all' && id === 'all')
                            ? 'is-active'
                            : ''
                        }
                        onClick={() => {
                          setSelectedGroup(id === 'all' ? 'all' : id);
                          setPage(1);
                        }}
                      >
                        <Typography.Text ellipsis>{label}</Typography.Text>
                        <Tag>{g.mailCount}</Tag>
                      </List.Item>
                    );
                  }}
                />
                <Space>
                  <Button size="small" onClick={() => void grouping(false)}>
                    智能分组
                  </Button>
                  <Button size="small" onClick={() => void grouping(true)}>
                    全部重分
                  </Button>
                </Space>
              </Col>
              <Col flex="1">
                <List
                  loading={messageLoading}
                  dataSource={messages.records}
                  locale={{ emptyText: <Empty description="暂无邮件" /> }}
                  renderItem={(row) => (
                    <List.Item
                      className="sw-email-row"
                      onClick={() => void openMessage(row)}
                    >
                      <List.Item.Meta
                        title={
                          <Space>
                            {row.subject || '（无主题）'}
                            <Tag color={row.projectId ? 'success' : 'default'}>
                              {row.projectName || '未分组'}
                            </Tag>
                          </Space>
                        }
                        description={`${row.fromName || row.fromAddress} · ${
                          row.preview || '暂无正文预览'
                        }`}
                      />
                      <Typography.Text type="secondary">
                        {dateText(row.receivedAt)}
                      </Typography.Text>
                    </List.Item>
                  )}
                />
                <Pagination
                  current={page}
                  pageSize={PAGE_SIZE}
                  total={messages.total}
                  showSizeChanger={false}
                  onChange={(p) => {
                    setPage(p);
                  }}
                />
              </Col>
            </Row>
          </Card>
        </>
      )}
      <Modal
        title={configured ? '账户设置' : '绑定邮箱'}
        open={bindOpen}
        onCancel={() => setBindOpen(false)}
        onOk={() => void saveAccount()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
      >
        <Form form={bindForm} layout="vertical">
          <Form.Item
            name="emailAddress"
            label="企业邮箱地址"
            rules={[{ required: true, type: 'email' }]}
          >
            <Input autoComplete="email" />
          </Form.Item>
          <Form.Item
            name="appPassword"
            label="第三方客户端安全密码"
            rules={[
              {
                required: !account?.credentialConfigured,
                message: '请输入安全密码',
              },
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder={
                account?.credentialConfigured ? '留空保持现有配置' : '不会回显'
              }
            />
          </Form.Item>
        </Form>
        <Alert
          type="info"
          showIcon
          message="密码只用于服务端连接测试与同步，前端不会展示已保存的值。"
        />
      </Modal>
      <Modal
        title="企业微信摘要推送"
        open={wecomOpen}
        onCancel={() => setWecomOpen(false)}
        onOk={() => void wecomForm.submit()}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={wecomForm}
          layout="vertical"
          onFinish={(values) => void saveWecom(values)}
        >
          <Form.Item
            name="weComUserId"
            label="企业微信 UserId"
            rules={[{ required: true, message: '请填写企业微信 UserId' }]}
          >
            <Input placeholder="例如：zhangsan" />
          </Form.Item>
          <Form.Item name="enabled" label="启用推送" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title="邮件详情"
        size={760}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      >
        {detailLoading ? (
          <Spin />
        ) : selected ? (
          <>
            <Space style={{ marginBottom: 12 }}>
              <Button
                icon={<ArrowLeftOutlined />}
                disabled={
                  !messages.records[
                    messages.records.findIndex(
                      (item) => item.id === selected.id,
                    ) - 1
                  ]
                }
                onClick={() => openAdjacent(-1)}
              >
                上一封
              </Button>
              <Button
                icon={<ArrowRightOutlined />}
                disabled={
                  !messages.records[
                    messages.records.findIndex(
                      (item) => item.id === selected.id,
                    ) + 1
                  ]
                }
                onClick={() => openAdjacent(1)}
              >
                下一封
              </Button>
              <Button
                onClick={() => {
                  setRegroupProjectId(selected.projectId);
                  setRegroupOpen(true);
                }}
              >
                更正分组
              </Button>
            </Space>
            <Typography.Title level={3}>
              {selected.subject || '（无主题）'}
            </Typography.Title>
            <Descriptions
              size="small"
              column={1}
              items={[
                {
                  label: '发件人',
                  children: `${selected.fromName || ''} <${
                    selected.fromAddress
                  }>`,
                },
                {
                  label: '收件人',
                  children: selected.toAddresses?.join('、') || '—',
                },
                {
                  label: '抄送',
                  children: selected.ccAddresses?.join('、') || '—',
                },
                { label: '时间', children: dateText(selected.receivedAt) },
                {
                  label: '项目',
                  children: selected.projectFullPath || '未分组',
                },
                { label: 'Message-ID', children: selected.messageId || '—' },
              ]}
            />
            <Tabs
              activeKey={detailTab}
              onChange={setDetailTab}
              items={[
                {
                  key: 'original',
                  label: '邮件原文',
                  children: (
                    <>
                      <Alert
                        type="info"
                        showIcon
                        message="安全阅读模式：仅展示纯文本，不执行 HTML、脚本或远程图片。"
                      />
                      <pre className="sw-email-body">
                        {selected.textBody || '（邮件正文为空）'}
                      </pre>
                      {selected.attachments?.length ? (
                        <List
                          header="附件元数据"
                          dataSource={selected.attachments}
                          renderItem={(a) => (
                            <List.Item>
                              {a.fileName} · {a.contentType || '未知类型'} ·{' '}
                              {a.size} bytes
                            </List.Item>
                          )}
                        />
                      ) : null}
                    </>
                  ),
                },
                {
                  key: 'ai',
                  label: (
                    <Space>
                      <RobotOutlined />
                      AI 解读
                    </Space>
                  ),
                  children: (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      <Button
                        type="primary"
                        loading={interpretLoading}
                        onClick={() => void generateInterpretation()}
                      >
                        {selected.interpretation?.status === 'SUCCESS'
                          ? '重新生成'
                          : '生成 AI 解读'}
                      </Button>
                      <Card size="small">
                        <Typography.Paragraph>
                          {selected.interpretation?.summary || '尚未生成解读'}
                        </Typography.Paragraph>
                        <List
                          size="small"
                          header="关键点"
                          dataSource={selected.interpretation?.keyPoints || []}
                          renderItem={(x) => <List.Item>{x}</List.Item>}
                        />
                      </Card>
                      <Card size="small" title="已关联行动项">
                        {actions.length
                          ? actions.map((item) => (
                              <Tag
                                key={item.id}
                                color={
                                  item.status === 'CLOSED'
                                    ? 'success'
                                    : 'processing'
                                }
                              >
                                {item.actionType} ·{' '}
                                {item.targetTitle || item.itemTitle}
                              </Tag>
                            ))
                          : '暂无行动项'}
                      </Card>
                    </Space>
                  ),
                },
              ]}
            />
            <Card size="small" title="回复邮件" style={{ marginTop: 16 }}>
              <Input.TextArea
                rows={5}
                value={replyDraft}
                onChange={(event) => setReplyDraft(event.target.value)}
                placeholder="输入回复内容；发送前请确认收件人和正文。"
              />
              <Space style={{ marginTop: 10 }}>
                <Button
                  type="primary"
                  loading={replying}
                  disabled={!replyDraft.trim()}
                  onClick={() => void sendReply()}
                >
                  发送回复
                </Button>
                <Typography.Text type="secondary">
                  邮件将通过已绑定账户发送
                </Typography.Text>
              </Space>
            </Card>
          </>
        ) : (
          <Empty description="无法加载邮件" />
        )}
      </Drawer>
      <Modal
        title="更正邮件分组"
        open={regroupOpen}
        onCancel={() => setRegroupOpen(false)}
        onOk={() => void saveRegroup()}
        confirmLoading={regroupSaving}
        okText="保存"
        cancelText="取消"
      >
        <Select
          showSearch
          optionFilterProp="label"
          style={{ width: '100%' }}
          value={regroupProjectId}
          onChange={setRegroupProjectId}
          placeholder="选择项目"
          options={projectOptions.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
        />
      </Modal>
      <Modal
        title={`转为${convertActionType === 'TASK' ? '任务' : '缺陷'}`}
        open={convertOpen}
        onCancel={() => setConvertOpen(false)}
        onOk={() => void convertForm.submit()}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={convertForm}
          layout="vertical"
          onFinish={(values) => void submitConvert(values)}
        >
          <Form.Item name="projectId" label="归属项目">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={projectOptions.map((project) => ({
                value: project.id,
                label: project.name,
              }))}
            />
          </Form.Item>
          <Form.Item name="assigneeId" label="负责人">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={userOptions.map((user) => ({
                value: user.id,
                label: user.name,
              }))}
            />
          </Form.Item>
          {convertActionType === 'ISSUE' && (
            <Form.Item name="severity" label="缺陷级别">
              <Select
                allowClear
                options={['P0', 'P1', 'P2', 'P3'].map((value) => ({
                  value,
                  label: value,
                }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
