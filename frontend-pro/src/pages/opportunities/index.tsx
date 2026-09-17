import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  FieldTimeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
  UserAddOutlined,
} from '@ant-design/icons';
import { history, useModel } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  message,
  Popconfirm,
  Progress,
  Radio,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type QuotationListVO,
  type SalesOpportunity,
  type SalesOpportunityFollowUp,
  type SalesOpportunityStatus,
  type SalesOpportunitySupportWorklog,
  superworkApi,
} from '@/services/superwork/api';
import QuotationGenerateWizard from '../quotations/GenerateWizard';
import '../workbench/style.less';
import './style.less';

const statuses: SalesOpportunityStatus[] = [
  '初步接触',
  '需求确认',
  '方案报价',
  '商务谈判',
  '已成交',
  '已流失',
];
const statusColor: Record<SalesOpportunityStatus, string> = {
  初步接触: 'default',
  需求确认: 'processing',
  方案报价: 'warning',
  商务谈判: 'warning',
  已成交: 'success',
  已流失: 'error',
};
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
type OpportunityForm = {
  name: string;
  customer?: string;
  type: '线索' | '商机';
  status: SalesOpportunityStatus;
  amount?: number;
  owner?: string;
  businessLine?: string;
  nextFollowUp?: string;
  probability: number;
  expectedClose?: string;
  source?: string;
  note?: string;
};

const dateTime = (value?: string) =>
  value ? value.replace('T', ' ').slice(0, 16) : '未记录时间';
const quoteStatusLabel: Record<string, string> = {
  DRAFT: '草稿',
  INTERNAL_REVIEW: '内部审核',
  APPROVED: '已批准',
  SENT: '已发送',
  ACCEPTED: '已接受',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
};
const quoteStatusColor: Record<string, string> = {
  DRAFT: 'default',
  INTERNAL_REVIEW: 'warning',
  APPROVED: 'processing',
  SENT: 'blue',
  ACCEPTED: 'success',
  REJECTED: 'error',
  EXPIRED: 'default',
};
const money = (value?: number) =>
  value == null
    ? '—'
    : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const initialForm = (): OpportunityForm => ({
  name: '',
  customer: '',
  type: '商机',
  status: '需求确认',
  amount: 0,
  owner: '',
  businessLine: '',
  nextFollowUp: '',
  probability: 30,
  expectedClose: '',
  source: '',
  note: '',
});

export default function OpportunitiesPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = roleCanManage(initialState?.currentUser?.role);
  const [filterForm] = Form.useForm<{
    keyword?: string;
    type?: string;
    status?: string;
    owner?: string;
    businessLine?: string;
    amount?: 'high' | 'low';
  }>();
  const filterValues = Form.useWatch([], filterForm) || {};
  const [opportunityForm] = Form.useForm<OpportunityForm>();
  const [followForm] = Form.useForm<{
    followUpAt: dayjs.Dayjs;
    follower: string;
    content: string;
    status: SalesOpportunityStatus;
    probability: number;
    nextFollowUp?: string;
  }>();
  const [worklogForm] = Form.useForm<{
    supportDate: dayjs.Dayjs;
    supporter: string;
    hours: number;
    supportType: string;
    content: string;
  }>();
  const [rows, setRows] = useState<SalesOpportunity[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [viewMode, setViewMode] = useState<'list' | 'board'>('list');
  const [quickFilter, setQuickFilter] = useState('');
  const [editing, setEditing] = useState<SalesOpportunity>();
  const [formOpen, setFormOpen] = useState(false);
  const [opportunityDraft, setOpportunityDraft] = useState<OpportunityForm>();
  const [formSubmitting, setFormSubmitting] = useState(false);
  const [detail, setDetail] = useState<SalesOpportunity>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [followOpen, setFollowOpen] = useState(false);
  const [followDraft, setFollowDraft] = useState<{
    followUpAt: dayjs.Dayjs;
    follower: string;
    content: string;
    status: SalesOpportunityStatus;
    probability: number;
    nextFollowUp?: string;
  }>();
  const [worklogOpen, setWorklogOpen] = useState(false);
  const [worklogDraft, setWorklogDraft] = useState<{
    supportDate: dayjs.Dayjs;
    supporter: string;
    hours: number;
    supportType: string;
    content: string;
  }>();
  const [followSaving, setFollowSaving] = useState(false);
  const [worklogSaving, setWorklogSaving] = useState(false);
  const [followUps, setFollowUps] = useState<SalesOpportunityFollowUp[]>([]);
  const [worklogs, setWorklogs] = useState<SalesOpportunitySupportWorklog[]>(
    [],
  );
  const [quotations, setQuotations] = useState<QuotationListVO[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [quoteOpen, setQuoteOpen] = useState(false);
  const [quoteTarget, setQuoteTarget] = useState<SalesOpportunity>();
  const [businessLines, setBusinessLines] = useState<string[]>([]);
  const [customerOptions, setCustomerOptions] = useState<string[]>([]);
  // 各商机最近一次跟进时间：用于列表「有更新」标识（近 3 天内有跟进记录）
  const [latestFollowUpAt, setLatestFollowUpAt] = useState<
    Record<number, string>
  >({});
  // 各商机累计售前支持工时：列表「累计工时」列与月度统计的数据源
  const [worklogHours, setWorklogHours] = useState<Record<number, number>>({});
  // 工时按月汇总：{ 'YYYY-MM': { hours, entries } }
  const [worklogMonthly, setWorklogMonthly] = useState<
    Record<string, { hours: number; entries: number }>
  >({});
  // 「本月支持工时」卡片点击查看按月明细
  const [monthlyOpen, setMonthlyOpen] = useState(false);
  const hasFreshFollowUp = (id: number) => {
    const latest = latestFollowUpAt[id];
    return Boolean(latest && dayjs().diff(dayjs(latest), 'day', true) <= 3);
  };
  const refreshFollowUpFreshness = useCallback((list: SalesOpportunity[]) => {
    if (!list.length) {
      setLatestFollowUpAt({});
      return;
    }
    // 分批拉取各商机跟进记录，避免一次性打满并发
    const chunks: SalesOpportunity[][] = [];
    for (let i = 0; i < list.length; i += 6) chunks.push(list.slice(i, i + 6));
    void (async () => {
      const next: Record<number, string> = {};
      for (const chunk of chunks) {
        const results = await Promise.allSettled(
          chunk.map((row) => superworkApi.getSalesOpportunityFollowUps(row.id)),
        );
        results.forEach((result, index) => {
          if (result.status !== 'fulfilled' || !result.value?.length) return;
          const latest = result.value
            .map((item) => item.followUpAt)
            .filter(Boolean)
            .sort()
            .pop();
          if (latest) next[chunk[index].id] = latest;
        });
      }
      setLatestFollowUpAt(next);
    })();
  }, []);
  const refreshWorklogSummary = useCallback((list: SalesOpportunity[]) => {
    if (!list.length) {
      setWorklogHours({});
      setWorklogMonthly({});
      return;
    }
    const chunks: SalesOpportunity[][] = [];
    for (let i = 0; i < list.length; i += 6) chunks.push(list.slice(i, i + 6));
    void (async () => {
      const hoursByOpportunity: Record<number, number> = {};
      const months: Record<string, { hours: number; entries: number }> = {};
      for (const chunk of chunks) {
        const results = await Promise.allSettled(
          chunk.map((row) =>
            superworkApi.getSalesOpportunitySupportWorklogs(row.id),
          ),
        );
        results.forEach((result, index) => {
          if (result.status !== 'fulfilled') return;
          const opportunityId = chunk[index].id;
          hoursByOpportunity[opportunityId] = result.value.reduce(
            (sum, item) => sum + Number(item.hours || 0),
            0,
          );
          result.value.forEach((item) => {
            const month = item.supportDate?.slice(0, 7);
            if (!month) return;
            const current = months[month] || { hours: 0, entries: 0 };
            months[month] = {
              hours: current.hours + Number(item.hours || 0),
              entries: current.entries + 1,
            };
          });
        });
      }
      setWorklogHours(hoursByOpportunity);
      setWorklogMonthly(months);
    })();
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [opportunityResult, lineResult, contactResult] =
        await Promise.allSettled([
          superworkApi.getSalesOpportunities(),
          superworkApi.getBusinessLines({ page: 1, size: 500, status: 1 }),
          superworkApi.getCustomerContacts({ page: 1, size: 500, isActive: 1 }),
        ]);
      if (opportunityResult.status === 'rejected')
        throw opportunityResult.reason;
      const opportunities = opportunityResult.value || [];
      setRows(opportunities);
      refreshFollowUpFreshness(opportunities);
      refreshWorklogSummary(opportunities);
      setBusinessLines(
        lineResult.status === 'fulfilled'
          ? (lineResult.value.records || []).map((line) => line.name)
          : [],
      );
      if (contactResult.status === 'fulfilled') {
        setCustomerOptions(
          Array.from(
            new Set(
              (contactResult.value.records || [])
                .map((contact) => contact.company || contact.name)
                .filter(Boolean),
            ),
          ),
        );
      } else {
        setCustomerOptions([]);
      }
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : '商机数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (formOpen && opportunityDraft)
      opportunityForm.setFieldsValue(opportunityDraft);
  }, [formOpen, opportunityDraft, opportunityForm]);
  useEffect(() => {
    if (followOpen && followDraft) followForm.setFieldsValue(followDraft);
  }, [followDraft, followForm, followOpen]);
  useEffect(() => {
    if (worklogOpen && worklogDraft) worklogForm.setFieldsValue(worklogDraft);
  }, [worklogDraft, worklogForm, worklogOpen]);

  const filteredRows = useMemo(() => {
    const values = filterValues;
    return rows.filter((row) => {
      const text = `${row.name}${row.customer || ''}${row.owner || ''}`;
      const quick =
        !quickFilter ||
        (quickFilter === 'mine' &&
          row.owner === initialState?.currentUser?.realName) ||
        (quickFilter === 'today' && row.nextFollowUp?.includes('今天')) ||
        (quickFilter === 'high' && Number(row.amount || 0) >= 500) ||
        (quickFilter === 'overdue' && row.nextFollowUp?.includes('逾期'));
      return (
        quick &&
        (!values.keyword || text.includes(values.keyword)) &&
        (!values.type || row.type === values.type) &&
        (!values.status || row.status === values.status) &&
        (!values.owner || row.owner === values.owner) &&
        (!values.businessLine || row.businessLine === values.businessLine) &&
        (!values.amount ||
          (values.amount === 'high' && Number(row.amount || 0) >= 500) ||
          (values.amount === 'low' && Number(row.amount || 0) < 500))
      );
    });
  }, [filterValues, initialState?.currentUser?.realName, quickFilter, rows]);

  const summary = useMemo(
    () => ({
      total: rows.length,
      progressing: rows.filter(
        (row) => !['已成交', '已流失'].includes(row.status),
      ).length,
      won: rows.filter((row) => row.status === '已成交').length,
      stages: rows.filter((row) => row.type === '商机').length,
      lost: rows.filter((row) => row.status === '已流失').length,
      amount: rows.reduce((sum, row) => sum + Number(row.amount || 0), 0),
      wonAmount: rows
        .filter((row) => row.status === '已成交')
        .reduce((sum, row) => sum + Number(row.amount || 0), 0),
    }),
    [rows],
  );
  // 整体月度工时统计：当月 + 全部历史，按月明细降序
  const worklogSummary = useMemo(() => {
    const currentMonth = dayjs().format('YYYY-MM');
    const months = Object.entries(worklogMonthly).sort((a, b) =>
      b[0].localeCompare(a[0]),
    );
    return {
      currentMonthHours: worklogMonthly[currentMonth]?.hours ?? 0,
      totalHours: Object.values(worklogMonthly).reduce(
        (sum, item) => sum + item.hours,
        0,
      ),
      months,
    };
  }, [worklogMonthly]);
  const owners = useMemo(
    () =>
      Array.from(
        new Set(
          rows
            .map((row) => row.owner)
            .filter((owner): owner is string => Boolean(owner)),
        ),
      ).sort((a, b) => a.localeCompare(b, 'zh-CN')),
    [rows],
  );

  const openCreate = () => {
    setEditing(undefined);
    setOpportunityDraft(initialForm());
    setFormOpen(true);
  };
  const openEdit = (row: SalesOpportunity) => {
    setEditing(row);
    setOpportunityDraft({
      ...initialForm(),
      ...row,
      amount: Number(row.amount || 0),
      probability: Number(row.probability ?? 30),
    });
    setFormOpen(true);
  };
  const saveOpportunity = async () => {
    const values = await opportunityForm.validateFields();
    setFormSubmitting(true);
    try {
      if (editing)
        await superworkApi.updateSalesOpportunity(editing.id, values);
      else await superworkApi.createSalesOpportunity(values);
      message.success(editing ? '商机已更新' : '商机已创建');
      setFormOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '商机保存失败');
    } finally {
      setFormSubmitting(false);
    }
  };
  const remove = async (row: SalesOpportunity) => {
    try {
      await superworkApi.deleteSalesOpportunity(row.id);
      message.success('商机已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '商机删除失败');
    }
  };

  const loadHistory = async (id: number) => {
    setHistoryLoading(true);
    try {
      const [follow, logs, quotes] = await Promise.allSettled([
        superworkApi.getSalesOpportunityFollowUps(id),
        superworkApi.getSalesOpportunitySupportWorklogs(id),
        superworkApi.getOpportunityQuotations(id),
      ]);
      setFollowUps(follow.status === 'fulfilled' ? follow.value || [] : []);
      setWorklogs(logs.status === 'fulfilled' ? logs.value || [] : []);
      setQuotations(quotes.status === 'fulfilled' ? quotes.value || [] : []);
    } catch {
      setFollowUps([]);
      setWorklogs([]);
      setQuotations([]);
    } finally {
      setHistoryLoading(false);
    }
  };
  const openDetail = async (row: SalesOpportunity) => {
    setDetail(row);
    setDetailOpen(true);
    await loadHistory(row.id);
  };
  const openFollow = async (row: SalesOpportunity) => {
    setDetail(row);
    setFollowDraft({
      followUpAt: dayjs(),
      follower: initialState?.currentUser?.realName || row.owner || '',
      content: '',
      status: row.status,
      probability: Number(row.probability ?? 30),
      nextFollowUp: row.nextFollowUp,
    });
    setFollowOpen(true);
    await loadHistory(row.id);
  };
  const saveFollow = async () => {
    if (!detail) return;
    const values = await followForm.validateFields();
    setFollowSaving(true);
    try {
      await superworkApi.createSalesOpportunityFollowUp(detail.id, {
        followUpAt: values.followUpAt.format('YYYY-MM-DDTHH:mm:ss'),
        follower: values.follower.trim(),
        content: values.content.trim(),
        status: values.status,
        probability: values.probability,
        nextFollowUp: values.nextFollowUp,
      });
      message.success('跟进记录已添加');
      setFollowOpen(false);
      setLatestFollowUpAt((current) => ({
        ...current,
        [detail.id]: values.followUpAt.format('YYYY-MM-DDTHH:mm:ss'),
      }));
      await load();
      await loadHistory(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '跟进记录保存失败');
    } finally {
      setFollowSaving(false);
    }
  };
  const openWorklog = async (row: SalesOpportunity) => {
    setDetail(row);
    setWorklogDraft({
      supportDate: dayjs(),
      supporter: initialState?.currentUser?.realName || '',
      hours: 1,
      supportType: '方案支持',
      content: '',
    });
    setWorklogOpen(true);
    await loadHistory(row.id);
  };
  const openQuote = (row: SalesOpportunity) => {
    setQuoteTarget(row);
    setQuoteOpen(true);
  };
  const saveWorklog = async () => {
    if (!detail) return;
    const values = await worklogForm.validateFields();
    setWorklogSaving(true);
    try {
      await superworkApi.createSalesOpportunitySupportWorklog(detail.id, {
        supportDate: values.supportDate.format('YYYY-MM-DD'),
        supporter: values.supporter.trim(),
        hours: values.hours,
        supportType: values.supportType,
        content: values.content.trim(),
      });
      message.success('售前支持工时已登记');
      worklogForm.setFieldsValue({
        supportDate: dayjs(),
        supporter: initialState?.currentUser?.realName || '',
        hours: 1,
        supportType: '方案支持',
        content: '',
      });
      setWorklogHours((current) => ({
        ...current,
        [detail.id]: (current[detail.id] || 0) + Number(values.hours || 0),
      }));
      setWorklogMonthly((current) => {
        const month = values.supportDate.format('YYYY-MM');
        const currentMonth = current[month] || { hours: 0, entries: 0 };
        return {
          ...current,
          [month]: {
            hours: currentMonth.hours + Number(values.hours || 0),
            entries: currentMonth.entries + 1,
          },
        };
      });
      await loadHistory(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '工时登记失败');
    } finally {
      setWorklogSaving(false);
    }
  };
  const moveStatus = async (
    row: SalesOpportunity,
    status: SalesOpportunityStatus,
  ) => {
    if (row.status === status) return;
    const previous = row.status;
    setRows((items) =>
      items.map((item) => (item.id === row.id ? { ...item, status } : item)),
    );
    try {
      await superworkApi.updateSalesOpportunity(row.id, { ...row, status });
      message.success(`已移动到「${status}」`);
    } catch {
      setRows((items) =>
        items.map((item) =>
          item.id === row.id ? { ...item, status: previous } : item,
        ),
      );
      message.error('状态更新失败');
    }
  };

  const columns: TableProps<SalesOpportunity>['columns'] = [
    {
      title: '线索 / 商机',
      dataIndex: 'name',
      width: 250,
      render: (value, row) => (
        <div>
          <Space size={6}>
            <Typography.Link strong onClick={() => void openDetail(row)}>
              {value}
            </Typography.Link>
            {hasFreshFollowUp(row.id) && (
              <Tag color="green" className="sw-followup-fresh-tag">
                有更新
              </Tag>
            )}
          </Space>
          <Typography.Text type="secondary" className="sw-opportunity-subtitle">
            {row.customer || '未填写客户'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 85,
      render: (value) => <Tag>{value}</Tag>,
    },
    {
      title: '阶段',
      dataIndex: 'status',
      width: 110,
      render: (value: SalesOpportunityStatus) => (
        <Tag color={statusColor[value]}>{value}</Tag>
      ),
    },
    {
      title: '预计金额',
      dataIndex: 'amount',
      width: 130,
      render: (value) => (
        <Typography.Text strong>
          ¥ {Number(value || 0).toFixed(2)}万
        </Typography.Text>
      ),
    },
    {
      title: '概率',
      dataIndex: 'probability',
      width: 120,
      render: (value) => <Progress percent={Number(value || 0)} size="small" />,
    },
    {
      title: '负责人',
      dataIndex: 'owner',
      width: 100,
      render: (value) => value || '未分配',
    },
    {
      title: '下次跟进',
      dataIndex: 'nextFollowUp',
      width: 130,
      render: (value) => value || '待安排',
    },
    {
      title: '累计工时',
      key: 'worklogHours',
      width: 100,
      render: (_: unknown, row: SalesOpportunity) => {
        const hours = worklogHours[row.id];
        return hours ? (
          <Typography.Text strong>{hours.toFixed(1)}h</Typography.Text>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        );
      },
    },
    ...(canManage
      ? [
          {
            title: '操作',
            key: 'action',
            width: 360,
            render: (_: unknown, row: SalesOpportunity) => (
              <Space size={0} wrap>
                <Button
                  type="link"
                  icon={<EyeOutlined />}
                  onClick={() => void openDetail(row)}
                >
                  详情
                </Button>
                <Button
                  type="link"
                  icon={<UserAddOutlined />}
                  onClick={() => void openFollow(row)}
                >
                  跟进
                </Button>
                <Button
                  type="link"
                  icon={<FieldTimeOutlined />}
                  onClick={() => void openWorklog(row)}
                >
                  工时
                </Button>
                <Button
                  type="link"
                  icon={<FileTextOutlined />}
                  onClick={() => openQuote(row)}
                >
                  报价
                </Button>
                <Button
                  type="link"
                  icon={<EditOutlined />}
                  onClick={() => openEdit(row)}
                >
                  编辑
                </Button>
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
              </Space>
            ),
          },
        ]
      : []),
  ];

  const followHistoryTimeline = (
    <Timeline
      pending={historyLoading ? '加载中…' : undefined}
      items={
        followUps.length
          ? followUps.map((item) => ({
              color: statusColor[item.status],
              content: (
                <div>
                  <Space>
                    <Typography.Text strong>{item.follower}</Typography.Text>
                    <Typography.Text type="secondary">
                      {dateTime(item.followUpAt)}
                    </Typography.Text>
                  </Space>
                  <div>{item.content}</div>
                  <Typography.Text type="secondary">
                    {item.status} · {item.probability}% · 下次：
                    {item.nextFollowUp || '待安排'}
                  </Typography.Text>
                </div>
              ),
            }))
          : [{ content: historyLoading ? '正在加载' : '暂无跟进记录' }]
      }
    />
  );
  const worklogHistoryTimeline = (
    <Timeline
      pending={historyLoading ? '加载中…' : undefined}
      items={
        worklogs.length
          ? worklogs.map((item) => ({
              content: (
                <div>
                  <Space>
                    <Typography.Text strong>
                      {item.supporter} · {item.hours} 小时
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      {item.supportDate}
                    </Typography.Text>
                  </Space>
                  <div>{item.content}</div>
                  <Typography.Text type="secondary">
                    {item.supportType}
                  </Typography.Text>
                </div>
              ),
            }))
          : [{ content: historyLoading ? '正在加载' : '暂无工时记录' }]
      }
    />
  );

  return (
    <div className="sw-page sw-opportunities">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SALES / OPPORTUNITIES
          </Typography.Text>
          <Typography.Title level={2}>线索商机管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            把客户、阶段、金额和下一次行动放在同一条推进路径上。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建商机
            </Button>
          )}
        </Space>
      </div>
      <Row gutter={[16, 16]} className="sw-stat-row">
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic title="全部线索" value={loading ? '-' : summary.total} />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="跟进中"
              value={loading ? '-' : summary.progressing}
              styles={{ content: { color: '#2563eb' } }}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="商机阶段"
              value={loading ? '-' : summary.stages}
              styles={{ content: { color: '#d97706' } }}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="预计金额（万）"
              value={loading ? '-' : summary.amount}
              precision={2}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="本月成交"
              value={loading ? '-' : summary.won}
              styles={{ content: { color: '#059669' } }}
            />
            {!loading && (
              <Typography.Text type="secondary" className="sw-stat-note">
                成交金额 ¥{summary.wonAmount.toFixed(0)}万
              </Typography.Text>
            )}
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="已流失"
              value={loading ? '-' : summary.lost}
              styles={{ content: { color: '#dc2626' } }}
            />
            {!loading && (
              <Typography.Text type="secondary" className="sw-stat-note">
                流失率{' '}
                {summary.total
                  ? ((summary.lost / summary.total) * 100).toFixed(1)
                  : '0.0'}
                %
              </Typography.Text>
            )}
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card
            className="sw-stat-card sw-stat-card-clickable"
            variant="borderless"
            onClick={() => setMonthlyOpen(true)}
          >
            <Statistic
              title="本月支持工时"
              value={loading ? '-' : worklogSummary.currentMonthHours}
              precision={1}
              suffix="h"
              styles={{ content: { color: '#7c3aed' } }}
            />
            {!loading && (
              <Typography.Text type="secondary" className="sw-stat-note">
                累计 {worklogSummary.totalHours.toFixed(1)}h ·{' '}
                {worklogSummary.months.length} 个月 · 按月明细 ›
              </Typography.Text>
            )}
          </Card>
        </Col>
      </Row>
      <Card variant="borderless" className="sw-filter-card">
        <Form
          form={filterForm}
          layout="inline"
          onFinish={() => {
            setQuickFilter('');
          }}
        >
          <Form.Item name="keyword" label="关键词">
            <Input allowClear placeholder="名称 / 客户 / 负责人" />
          </Form.Item>
          <Form.Item name="type" label="类型">
            <Select
              allowClear
              placeholder="全部"
              style={{ width: 100 }}
              options={['线索', '商机'].map((value) => ({
                label: value,
                value,
              }))}
            />
          </Form.Item>
          <Form.Item name="status" label="阶段">
            <Select
              allowClear
              placeholder="全部阶段"
              style={{ width: 130 }}
              options={statuses.map((value) => ({ label: value, value }))}
            />
          </Form.Item>
          <Form.Item name="businessLine" label="业务线">
            <Select
              allowClear
              placeholder="全部业务线"
              style={{ width: 150 }}
              options={businessLines.map((value) => ({ label: value, value }))}
            />
          </Form.Item>
          <Form.Item name="owner" label="负责人">
            <Select
              allowClear
              showSearch
              placeholder="全部负责人"
              style={{ width: 130 }}
              options={owners.map((value) => ({ label: value, value }))}
            />
          </Form.Item>
          <Form.Item name="amount" label="预计金额">
            <Select
              allowClear
              placeholder="不限金额"
              style={{ width: 130 }}
              options={[
                { label: '500万以上', value: 'high' },
                { label: '500万以下', value: 'low' },
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
                  filterForm.resetFields();
                  setQuickFilter('');
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
        <Divider style={{ margin: '14px 0' }} />
        <Space wrap>
          <Typography.Text type="secondary">快捷视图</Typography.Text>
          {[
            ['mine', '我的线索'],
            ['today', '今日待跟进'],
            ['high', '高价值商机'],
            ['overdue', '即将逾期'],
          ].map(([value, label]) => (
            <Button
              key={value}
              type={quickFilter === value ? 'primary' : 'default'}
              onClick={() => setQuickFilter(quickFilter === value ? '' : value)}
            >
              {label}
            </Button>
          ))}
          <Segmented
            value={viewMode}
            onChange={(value) => setViewMode(value as 'list' | 'board')}
            options={[
              { label: '列表', value: 'list' },
              { label: '看板', value: 'board' },
            ]}
          />
        </Space>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取商机数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      {viewMode === 'list' ? (
        <Card variant="borderless" className="sw-table-card">
          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={filteredRows}
            locale={{ emptyText: <Empty description="暂无符合条件的商机" /> }}
            scroll={{ x: 1100 }}
            pagination={false}
          />
        </Card>
      ) : (
        <div className="sw-opportunity-board">
          {statuses.map((status) => (
            <Card
              key={status}
              title={
                <Space>
                  <Tag color={statusColor[status]}>{status}</Tag>
                  <Typography.Text type="secondary">
                    {filteredRows.filter((row) => row.status === status).length}
                  </Typography.Text>
                </Space>
              }
              className="sw-opportunity-column"
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                const id = Number(event.dataTransfer.getData('text/plain'));
                const row = rows.find((item) => item.id === id);
                if (row) void moveStatus(row, status);
              }}
            >
              {filteredRows
                .filter((row) => row.status === status)
                .map((row) => (
                  <Card
                    key={row.id}
                    size="small"
                    draggable
                    onDragStart={(event) =>
                      event.dataTransfer.setData('text/plain', String(row.id))
                    }
                    className="sw-opportunity-board-card"
                  >
                    <Typography.Link
                      strong
                      onClick={() => void openDetail(row)}
                    >
                      {row.name}
                    </Typography.Link>
                    <Typography.Text type="secondary">
                      {row.customer || '未填写客户'}
                    </Typography.Text>
                    <Space>
                      <Tag>{row.type}</Tag>
                      <Typography.Text strong>
                        ¥ {Number(row.amount || 0).toFixed(0)}万
                      </Typography.Text>
                    </Space>
                    <Space size={0}>
                      <Button
                        type="link"
                        size="small"
                        onClick={() => void openFollow(row)}
                      >
                        跟进
                      </Button>
                      {canManage && (
                        <Button
                          type="link"
                          size="small"
                          onClick={() => openQuote(row)}
                        >
                          报价
                        </Button>
                      )}
                    </Space>
                  </Card>
                ))}
            </Card>
          ))}
        </div>
      )}
      <Modal
        title={editing ? '编辑商机' : '新建商机'}
        open={formOpen}
        forceRender
        onCancel={() => setFormOpen(false)}
        onOk={() => void saveOpportunity()}
        okText="保存"
        cancelText="取消"
        confirmLoading={formSubmitting}
        width={700}
        destroyOnHidden
      >
        <Form form={opportunityForm} layout="vertical">
          <Row gutter={14}>
            <Col span={14}>
              <Form.Item
                name="name"
                label="商机名称"
                rules={[{ required: true, message: '请输入商机名称' }]}
              >
                <Input maxLength={160} />
              </Form.Item>
            </Col>
            <Col span={10}>
              <Form.Item name="type" label="类型">
                <Radio.Group
                  options={['线索', '商机'].map((value) => ({
                    label: value,
                    value,
                  }))}
                  optionType="button"
                  buttonStyle="solid"
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item name="customer" label="客户公司">
                <AutoComplete
                  allowClear
                  options={customerOptions.map((value) => ({
                    label: value,
                    value,
                  }))}
                  placeholder="可选择已有客户，也可手动输入"
                  filterOption={(input, option) =>
                    String(option?.value || '')
                      .toLowerCase()
                      .includes(input.toLowerCase())
                  }
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="owner" label="负责人">
                <Input allowClear />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item name="status" label="阶段">
                <Select
                  options={statuses.map((value) => ({ label: value, value }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="businessLine" label="业务线">
                <Select
                  allowClear
                  showSearch
                  options={businessLines.map((value) => ({
                    label: value,
                    value,
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={8}>
              <Form.Item name="amount" label="预计金额（万）">
                <InputNumber min={0} precision={2} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="probability" label="成交概率">
                <InputNumber
                  min={0}
                  max={100}
                  step={5}
                  suffix="%"
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="nextFollowUp" label="下次跟进">
                <Input placeholder="如：明天 10:00" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item name="expectedClose" label="预计成交日期">
                <Input placeholder="YYYY-MM-DD" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="source" label="来源">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="note" label="备注">
            <Input.TextArea rows={3} maxLength={1000} showCount />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={detail?.name || '商机详情'}
        size={580}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      >
        {detail && (
          <>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="客户公司">
                {detail.customer || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="类型 / 阶段">
                <Space>
                  <Tag>{detail.type}</Tag>
                  <Tag color={statusColor[detail.status]}>{detail.status}</Tag>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="预计金额">
                ¥ {Number(detail.amount || 0).toFixed(2)} 万
              </Descriptions.Item>
              <Descriptions.Item label="成交概率">
                {detail.probability ?? 0}%
              </Descriptions.Item>
              <Descriptions.Item label="负责人 / 业务线">
                {detail.owner || '未分配'} / {detail.businessLine || '未填写'}
              </Descriptions.Item>
              <Descriptions.Item label="下次跟进">
                {detail.nextFollowUp || '待安排'}
              </Descriptions.Item>
            </Descriptions>
            <Divider />
            <Space wrap>
              <Button
                type="primary"
                icon={<UserAddOutlined />}
                onClick={() => void openFollow(detail)}
              >
                新增跟进
              </Button>
              <Button
                icon={<FieldTimeOutlined />}
                onClick={() => void openWorklog(detail)}
              >
                登记工时
              </Button>
              {canManage && (
                <Button
                  icon={<FileTextOutlined />}
                  onClick={() => openQuote(detail)}
                >
                  新建报价
                </Button>
              )}
            </Space>
            <Divider />
            <Typography.Title level={5}>跟进历史</Typography.Title>
            {followHistoryTimeline}
            <Divider />
            <Typography.Title level={5}>售前支持工时</Typography.Title>
            <List
              loading={historyLoading}
              dataSource={worklogs}
              locale={{ emptyText: '暂无工时记录' }}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta
                    title={`${item.supporter} · ${item.hours} 小时`}
                    description={`${item.supportType} · ${item.supportDate} · ${item.content}`}
                  />
                </List.Item>
              )}
            />
            <Divider />
            <Typography.Title level={5}>关联报价单</Typography.Title>
            <List
              loading={historyLoading}
              dataSource={quotations}
              locale={{ emptyText: '暂无报价单' }}
              renderItem={(item) => (
                <List.Item
                  extra={
                    <Button
                      type="link"
                      onClick={() => history.push(`/quotations/${item.id}`)}
                    >
                      查看
                    </Button>
                  }
                >
                  <List.Item.Meta
                    title={item.quotationNo}
                    description={`${money(item.firstYearTotalInclTax)} · ${item.quoteDate?.slice(0, 10) || '—'}`}
                  />
                  <Tag color={quoteStatusColor[item.status] || 'default'}>
                    {quoteStatusLabel[item.status] || item.status}
                  </Tag>
                </List.Item>
              )}
            />
          </>
        )}
      </Drawer>
      <Modal
        title="商机跟进记录"
        open={followOpen}
        width={960}
        onCancel={() => setFollowOpen(false)}
        onOk={() => void saveFollow()}
        okText="添加记录"
        cancelText="取消"
        confirmLoading={followSaving}
      >
        <Row gutter={20}>
          <Col xs={24} md={14}>
            <Form form={followForm} layout="vertical">
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item
                    name="followUpAt"
                    label="跟进时间"
                    rules={[{ required: true }]}
                  >
                    <DatePicker showTime style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="follower"
                    label="跟进人"
                    rules={[{ required: true, message: '请填写跟进人' }]}
                  >
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item name="status" label="当前阶段">
                    <Select
                      options={statuses.map((value) => ({
                        label: value,
                        value,
                      }))}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="probability" label="成交概率">
                    <InputNumber
                      min={0}
                      max={100}
                      step={5}
                      suffix="%"
                      style={{ width: '100%' }}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="nextFollowUp" label="下次跟进">
                <Input placeholder="如：下周三 10:00" />
              </Form.Item>
              <Form.Item
                name="content"
                label="跟进情况"
                rules={[{ required: true, message: '请填写跟进情况' }]}
              >
                <Input.TextArea rows={5} maxLength={1000} showCount />
              </Form.Item>
            </Form>
          </Col>
          <Col xs={24} md={10} className="sw-follow-history-col">
            <Typography.Title level={5}>历史跟进</Typography.Title>
            {followHistoryTimeline}
          </Col>
        </Row>
      </Modal>
      <Modal
        title="售前支持工时登记"
        open={worklogOpen}
        width={960}
        onCancel={() => setWorklogOpen(false)}
        onOk={() => void saveWorklog()}
        okText="登记"
        cancelText="取消"
        confirmLoading={worklogSaving}
      >
        <Row gutter={20}>
          <Col xs={24} md={14}>
            <Form form={worklogForm} layout="vertical">
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item
                    name="supportDate"
                    label="支持日期"
                    rules={[{ required: true }]}
                  >
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="supporter"
                    label="支持人员"
                    rules={[{ required: true }]}
                  >
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={12}>
                <Col span={10}>
                  <Form.Item
                    name="hours"
                    label="工时（小时）"
                    rules={[{ required: true }]}
                  >
                    <InputNumber min={0.1} step={0.5} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={14}>
                  <Form.Item name="supportType" label="支持类型">
                    <Select
                      options={['方案支持', '售前沟通', '报价支持', '其他'].map(
                        (value) => ({ label: value, value }),
                      )}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="content"
                label="支持内容"
                rules={[{ required: true }]}
              >
                <Input.TextArea rows={4} />
              </Form.Item>
            </Form>
          </Col>
          <Col xs={24} md={10} className="sw-follow-history-col">
            <Typography.Title level={5}>工时记录</Typography.Title>
            {worklogHistoryTimeline}
          </Col>
        </Row>
      </Modal>
      <QuotationGenerateWizard
        open={quoteOpen}
        lockOpportunity
        defaults={
          quoteTarget
            ? {
                opportunityId: quoteTarget.id,
                opportunityName: quoteTarget.name,
                customerName: quoteTarget.customer,
              }
            : undefined
        }
        onClose={() => {
          setQuoteOpen(false);
          setQuoteTarget(undefined);
        }}
        onGenerated={() => {
          if (quoteTarget) void loadHistory(quoteTarget.id);
        }}
      />
      <Modal
        title="按月支持工时"
        open={monthlyOpen}
        footer={null}
        onCancel={() => setMonthlyOpen(false)}
      >
        <Table
          size="small"
          rowKey="month"
          pagination={false}
          locale={{ emptyText: '暂无工时记录' }}
          dataSource={worklogSummary.months.map(([month, item]) => ({
            month,
            ...item,
          }))}
          columns={[
            {
              title: '月份',
              dataIndex: 'month',
              render: (value: string) =>
                dayjs(`${value}-01`).format('YYYY年MM月'),
            },
            {
              title: '支持工时',
              dataIndex: 'hours',
              render: (value: number) => `${value.toFixed(1)}h`,
            },
            { title: '记录条数', dataIndex: 'entries', width: 90 },
          ]}
        />
      </Modal>
    </div>
  );
}
