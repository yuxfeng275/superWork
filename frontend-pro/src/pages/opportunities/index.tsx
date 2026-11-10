import {
  DeleteOutlined,
  DiffOutlined,
  EditOutlined,
  EyeOutlined,
  FieldTimeOutlined,
  FileTextOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
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
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  message,
  Progress,
  Radio,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type OpportunityCoach,
  type OpportunityFollowupPrompt,
  type QuotationListVO,
  type SalesOpportunity,
  type SalesOpportunityFollowUp,
  type SalesOpportunityStatus,
  type SalesOpportunitySupportWorklog,
  superworkApi,
} from '@/services/superwork/api';
import CompareDrawer from '../quotations/CompareDrawer';
import QuotationGenerateWizard from '../quotations/GenerateWizard';
import '../workbench/style.less';
import './style.less';

const statuses: SalesOpportunityStatus[] = [
  '初步接触',
  '需求确认',
  '方案报价',
  '商务谈判',
  '暂停推进',
  '已成交',
  '已流失',
];
const statusColor: Record<SalesOpportunityStatus, string> = {
  初步接触: 'default',
  需求确认: 'processing',
  方案报价: 'warning',
  商务谈判: 'warning',
  暂停推进: 'purple',
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
/** 解析问句台账里的 parsedJson（后端存的是字符串），解析失败时不展示解析结果 */
const parseFollowupParsed = (
  raw?: string | null,
): { probability?: number; nextAt?: string } => {
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};
    const record = parsed as Record<string, unknown>;
    return {
      probability:
        typeof record.probability === 'number' ? record.probability : undefined,
      nextAt: typeof record.nextAt === 'string' ? record.nextAt : undefined,
    };
  } catch {
    return {};
  }
};
/** 已结束的商机不再参与今日/逾期跟进视图 */
const closedStatuses: SalesOpportunityStatus[] = ['已成交', '已流失'];
/** 下次跟进时间：优先结构化字段 nextFollowUpAt，解析不出时回落旧文本 */
const followUpAt = (row: SalesOpportunity) => {
  if (row.nextFollowUpAt) {
    const parsed = dayjs(row.nextFollowUpAt);
    if (parsed.isValid()) return parsed;
  }
  return null;
};
/** 今日待跟进：按 nextFollowUpAt 的日历日比较，无结构化时间时回落旧文本「今天」 */
const isFollowUpToday = (row: SalesOpportunity) => {
  const at = followUpAt(row);
  if (at) return at.isSame(dayjs(), 'day');
  return Boolean(row.nextFollowUp?.includes('今天'));
};
/** 逾期天数：nextFollowUpAt 早于今日的天数，无结构化时间时解析旧文本「逾期 N 天」 */
const followUpOverdueDays = (row: SalesOpportunity) => {
  if (closedStatuses.includes(row.status)) return 0;
  const at = followUpAt(row);
  if (at) {
    const days = dayjs().startOf('day').diff(at.startOf('day'), 'day');
    return days > 0 ? days : 0;
  }
  if (row.nextFollowUp?.includes('逾期')) {
    const matched = row.nextFollowUp.match(/逾期\s*(\d+)\s*天/);
    return matched ? Number(matched[1]) : 1;
  }
  return 0;
};
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
  const [compareOpen, setCompareOpen] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  // 企微跟进问句台账：PARSED/REPLIED 的回复需人工确认后才写入商机
  const [prompts, setPrompts] = useState<OpportunityFollowupPrompt[]>([]);
  const [promptsLoading, setPromptsLoading] = useState(false);
  // AI 教练建议：接口按时间倒序返回，coaches[0] 即最新一次生成结果
  const [coaches, setCoaches] = useState<OpportunityCoach[]>([]);
  const [coachLoading, setCoachLoading] = useState(false);
  const [coachPolling, setCoachPolling] = useState(false);
  // 轮询句柄 + 序号：序号一变旧循环立即作废，保证切商机/关抽屉/卸载后不泄漏
  const coachTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const coachPollSeq = useRef(0);
  const [pasteText, setPasteText] = useState('');
  const [pasting, setPasting] = useState(false);
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
  // 待确认回复：PARSED/REPLIED，以及 SENT 且尚未回复，取最近 5 条
  const pendingPrompts = useMemo(() => {
    const visible = prompts.filter((item) => {
      if (item.state === 'PARSED' || item.state === 'REPLIED') return true;
      return item.state === 'SENT' && !item.replyText;
    });
    return visible
      .slice()
      .sort((a, b) =>
        (b.sentAt || b.createdAt || '').localeCompare(
          a.sentAt || a.createdAt || '',
        ),
      )
      .slice(0, 5);
  }, [prompts]);
  const clearCoachTimer = () => {
    if (coachTimer.current) {
      clearTimeout(coachTimer.current);
      coachTimer.current = null;
    }
  };
  const stopCoachPolling = useCallback(() => {
    // 序号自增：在途的轮询循环下一次醒来会发现序号已换，直接退出
    coachPollSeq.current += 1;
    clearCoachTimer();
    setCoachPolling(false);
  }, []);
  const loadCoach = useCallback(
    async (id: number, silent = false): Promise<OpportunityCoach[]> => {
      if (!silent) setCoachLoading(true);
      try {
        const list = await superworkApi.getOpportunityCoach(id);
        const next = list || [];
        setCoaches(next);
        return next;
      } catch {
        // 老环境没有该接口时不阻塞详情，只是不展示 AI 教练
        if (!silent) setCoaches([]);
        return [];
      } finally {
        if (!silent) setCoachLoading(false);
      }
    },
    [],
  );
  /**
   * 轮询：仅当最新一条仍为 PENDING/GENERATING 时启动，每 3 秒一次、最多 20 次。
   * 循环自己持有 seq 快照，stopCoachPolling / 切商机 / 卸载都会把它作废，不会幽灵回调。
   */
  const startCoachPolling = useCallback(
    (id: number) => {
      stopCoachPolling();
      const seq = coachPollSeq.current;
      setCoachPolling(true);
      let attempts = 0;
      const tick = async () => {
        if (coachPollSeq.current !== seq) return;
        attempts += 1;
        const list = await loadCoach(id, true);
        if (coachPollSeq.current !== seq) return;
        const latest = list[0];
        const settled =
          !latest ||
          (latest.status !== 'PENDING' && latest.status !== 'GENERATING');
        if (settled || attempts >= 20) {
          setCoachPolling(false);
          return;
        }
        coachTimer.current = setTimeout(() => void tick(), 3000);
      };
      coachTimer.current = setTimeout(() => void tick(), 3000);
    },
    [loadCoach, stopCoachPolling],
  );
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
  // 卸载时兜底清定时器（轮询循环自身也会检查序号作废）
  useEffect(() => () => stopCoachPolling(), [stopCoachPolling]);
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
        (quickFilter === 'today' && isFollowUpToday(row)) ||
        (quickFilter === 'high' && Number(row.amount || 0) >= 500) ||
        (quickFilter === 'overdue' && followUpOverdueDays(row) > 0);
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
        (row) => !['已成交', '已流失', '暂停推进'].includes(row.status),
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
  const loadPrompts = async (id: number) => {
    setPromptsLoading(true);
    try {
        const list = await superworkApi.getOpportunityFollowupPrompts(id);
        setPrompts(list || []);
    } catch {
      // 老环境没有该接口时不阻塞详情，只是不展示待确认回复
      setPrompts([]);
    } finally {
      setPromptsLoading(false);
    }
  };
  const generateCoach = async () => {
    if (!detail) return;
    stopCoachPolling();
    setCoachLoading(true);
    try {
      await superworkApi.generateOpportunityCoach(detail.id);
      message.success('已触发 AI 分析，稍候自动刷新');
      const list = await loadCoach(detail.id);
      const latest = list[0];
      if (
        latest &&
        (latest.status === 'PENDING' || latest.status === 'GENERATING')
      ) {
        startCoachPolling(detail.id);
      }
    } catch (e) {
      // 后端无可用模型时 400，message 里带「模型管理」提示，原样展示
      message.error(e instanceof Error ? e.message : 'AI 教练生成失败');
    } finally {
      setCoachLoading(false);
    }
  };
  const adoptCoachAction = async (actionIndex: number) => {
    const latest = coaches[0];
    if (!detail || !latest) return;
    try {
      await superworkApi.adoptOpportunityCoachAction(
        detail.id,
        latest.id,
        actionIndex,
      );
      message.success('已写入跟进');
      await load();
      await loadHistory(detail.id);
      await loadCoach(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '采纳失败');
    }
  };
  const renderCoachContent = () => {
    const latest = coaches[0];
    if (!latest) {
      return (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="还没有建议，点右上角生成"
        />
      );
    }
    if (latest.status === 'FAILED') {
      return (
        <Alert type="error" showIcon message={latest.error || '生成失败'} />
      );
    }
    if (latest.status === 'PENDING' || latest.status === 'GENERATING') {
      return (
        <Space direction="vertical" size={6}>
          <Space>
            <Spin size="small" />
            <Typography.Text>AI 正在分析这条反馈…</Typography.Text>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {coachPolling
              ? '每 3 秒自动刷新一次（最多 20 次）'
              : '已达轮询上限，可点右上角重新生成'}
          </Typography.Text>
        </Space>
      );
    }
    const content = latest.content;
    if (!content) {
      return (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="本次未返回内容，可重新生成"
        />
      );
    }
    return (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {content.readiness && (
          <div>
            <Typography.Text strong>要素完备度</Typography.Text>
            <Progress
              size="small"
              percent={Math.min(
                100,
                Math.max(0, Number(content.readiness.score ?? 0)),
              )}
            />
            {Boolean(content.readiness.missing?.length) && (
              <Space wrap size={4}>
                <Typography.Text type="danger">
                  缺：{content.readiness.missing?.join('、')}
                </Typography.Text>
                {content.readiness.missing?.map((item) => (
                  <Tag key={item} color="red">
                    {item}
                  </Tag>
                ))}
              </Space>
            )}
          </div>
        )}
        {content.diagnosis && (
          <div>
            <Typography.Text strong>诊断</Typography.Text>
            <Typography.Paragraph style={{ marginBottom: 0 }}>
              {content.diagnosis}
            </Typography.Paragraph>
          </div>
        )}
        {Boolean(content.risks?.length) && (
          <div>
            <Typography.Text strong>风险</Typography.Text>
            <List
              size="small"
              dataSource={content.risks || []}
              renderItem={(item) => (
                <List.Item style={{ padding: '2px 0' }}>{item}</List.Item>
              )}
            />
          </div>
        )}
        {Boolean(content.questions?.length) && (
          <div>
            <Typography.Text strong>追问</Typography.Text>
            <List
              size="small"
              dataSource={(content.questions || []).slice(0, 4)}
              renderItem={(item) => (
                <List.Item style={{ padding: '4px 0' }}>
                  <Space
                    direction="vertical"
                    size={0}
                    style={{ width: '100%' }}
                  >
                    <Typography.Text>问：{item.question}</Typography.Text>
                    {item.why && (
                      <Typography.Text type="secondary">
                        为什么问：{item.why}
                      </Typography.Text>
                    )}
                    {item.expect && (
                      <Typography.Text type="secondary">
                        想听到：{item.expect}
                      </Typography.Text>
                    )}
                  </Space>
                </List.Item>
              )}
            />
          </div>
        )}
        {Boolean(content.solutions?.length) && (
          <div>
            <Typography.Text strong>方案建议</Typography.Text>
            <List
              size="small"
              dataSource={content.solutions || []}
              renderItem={(item) => (
                <List.Item style={{ padding: '4px 0' }}>
                  <Space
                    direction="vertical"
                    size={0}
                    style={{ width: '100%' }}
                  >
                    <Typography.Text strong>{item.title}</Typography.Text>
                    {item.pitch && (
                      <Typography.Text>{item.pitch}</Typography.Text>
                    )}
                    {item.evidence && (
                      <Typography.Text type="secondary">
                        {item.evidence}
                      </Typography.Text>
                    )}
                  </Space>
                </List.Item>
              )}
            />
          </div>
        )}
        {Boolean(content.nextActions?.length) && (
          <div>
            <Typography.Text strong>下一步动作</Typography.Text>
            <List
              size="small"
              dataSource={content.nextActions || []}
              renderItem={(item, index) => (
                <List.Item
                  style={{ padding: '4px 0' }}
                  actions={[
                    <Button
                      key="adopt"
                      type="link"
                      size="small"
                      onClick={() => void adoptCoachAction(index)}
                    >
                      采纳
                    </Button>,
                  ]}
                >
                  <Space
                    direction="vertical"
                    size={2}
                    style={{ width: '100%' }}
                  >
                    <Typography.Text>{item.action}</Typography.Text>
                    <Space size={4} wrap>
                      {item.when && <Tag>{item.when}</Tag>}
                      {item.owner && (
                        <Typography.Text type="secondary">
                          {item.owner}
                        </Typography.Text>
                      )}
                    </Space>
                  </Space>
                </List.Item>
              )}
            />
          </div>
        )}
        {content.probabilitySuggestion && (
          <div>
            <Typography.Text strong>成交概率</Typography.Text>
            <Typography.Paragraph style={{ marginBottom: 0 }}>
              建议 {content.probabilitySuggestion.value ?? '—'}%（原{' '}
              {detail?.probability ?? '—'}%）
              {content.probabilitySuggestion.reason
                ? `——${content.probabilitySuggestion.reason}`
                : ''}
            </Typography.Paragraph>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              仅建议，不会自动改商机
            </Typography.Text>
          </div>
        )}
      </Space>
    );
  };
  const openDetail = async (row: SalesOpportunity) => {
    setDetail(row);
    setDetailOpen(true);
    // 切商机先停掉上一条的轮询并清空旧建议，避免旧 timer 写进新详情
    stopCoachPolling();
    setCoaches([]);
    await Promise.all([
      loadHistory(row.id),
      loadPrompts(row.id),
      loadCoach(row.id).then((list) => {
        const latest = list[0];
        if (
          latest &&
          (latest.status === 'PENDING' || latest.status === 'GENERATING')
        ) {
          startCoachPolling(row.id);
        }
      }),
    ]);
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
  // 确认入账：只有确认后后端才会改概率/下次跟进时间，所以列表+历史+问句三处都刷
  const confirmPrompt = async (prompt: OpportunityFollowupPrompt) => {
    if (!detail) return;
    try {
      await superworkApi.confirmOpportunityFollowupPrompt(
        detail.id,
        prompt.id,
        initialState?.currentUser?.id,
      );
      message.success('已写入商机');
      await load();
      await loadHistory(detail.id);
      await loadPrompts(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '确认入账失败');
    }
  };
  const ignorePrompt = async (prompt: OpportunityFollowupPrompt) => {
    if (!detail) return;
    try {
      await superworkApi.ignoreOpportunityFollowupPrompt(
        detail.id,
        prompt.id,
      );
      message.success('已忽略该问句');
      await loadPrompts(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '忽略失败');
    }
  };
  // 粘贴企微回复原文：后端解析出概率/下次跟进时间，仍需人工确认才入账
  const pasteReply = async () => {
    if (!detail) return;
    const text = pasteText.trim();
    if (!text) {
      message.warning('请先粘贴回复原文');
      return;
    }
    setPasting(true);
    try {
      await superworkApi.pasteOpportunityFollowupReply(detail.id, text);
      setPasteText('');
      message.success('已解析，请确认入账');
      await load();
      await loadHistory(detail.id);
      await loadPrompts(detail.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '解析失败，请稍后重试');
    } finally {
      setPasting(false);
    }
  };
  const canLogHours = (row?: SalesOpportunity | null) => row?.status !== '已成交';
  const openWorklog = async (row: SalesOpportunity) => {
    if (!canLogHours(row)) {
      message.warning('已成交商机不可再维护售前工时');
      return;
    }
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
      title: '最新报价',
      dataIndex: 'quotedAmount',
      width: 130,
      render: (_: unknown, row: SalesOpportunity) =>
        row.quotedAmount == null ? (
          <Typography.Text type="secondary">未报价</Typography.Text>
        ) : (
          <div>
            <Typography.Text strong>
              ¥ {Number(row.quotedAmount).toLocaleString('zh-CN')}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ display: 'block' }}>
              {row.quotationCount || 0} 张报价
              {row.grossMarginRate != null
                ? ` · 毛利 ${(Number(row.grossMarginRate) * 100).toFixed(0)}%`
                : ''}
            </Typography.Text>
          </div>
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
      width: 160,
      render: (value: string | undefined, row: SalesOpportunity) => {
          const at = followUpAt(row);
        const overdueDays = followUpOverdueDays(row);
        return (
          <Space size={4} wrap>
            <Typography.Text>
              {at ? at.format('YYYY-MM-DD HH:mm') : value || '待安排'}
            </Typography.Text>
            {overdueDays > 0 && (
              <Tag color="error">逾期 {overdueDays} 天</Tag>
            )}
          </Space>
        );
      },
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
            width: 196,
            render: (_: unknown, row: SalesOpportunity) => (
              <Space size={0}>
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
                <Dropdown
                  trigger={['click']}
                  placement="bottomRight"
                  menu={{
                    items: [
                      {
                        key: 'worklog',
                        icon: <FieldTimeOutlined />,
                        label: '工时',
                        disabled: !canLogHours(row),
                      },
                      {
                        key: 'quote',
                        icon: <FileTextOutlined />,
                        label: '报价',
                      },
                      {
                        key: 'edit',
                        icon: <EditOutlined />,
                        label: '编辑',
                      },
                      {
                        key: 'delete',
                        icon: <DeleteOutlined />,
                        label: '删除',
                        danger: true,
                      },
                    ],
                    onClick: ({ key }) => {
                      if (key === 'worklog') void openWorklog(row);
                      if (key === 'quote') openQuote(row);
                      if (key === 'edit') openEdit(row);
                      if (key === 'delete') {
                        Modal.confirm({
                          title: `删除「${row.name}」吗？`,
                          okText: '删除',
                          cancelText: '取消',
                          okButtonProps: { danger: true },
                          onOk: () => remove(row),
                        });
                      }
                    },
                  }}
                >
                  <Button type="link" icon={<MoreOutlined />}>
                    更多
                  </Button>
                </Dropdown>
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
        onClose={() => {
          setDetailOpen(false);
          // 关抽屉即停轮询，不留后台 timer
          stopCoachPolling();
        }}
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
                disabled={!canLogHours(detail)}
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
            <Card size="small" title="待确认回复" style={{ marginBottom: 16 }}>
              <Typography.Paragraph
                type="secondary"
                style={{ marginBottom: 12 }}
              >
                企微问句的回复在这里确认后才写进商机（概率/下次跟进时间才会变）。
              </Typography.Paragraph>
              <List
                size="small"
                loading={promptsLoading}
                dataSource={pendingPrompts}
                locale={{ emptyText: '暂无待确认回复' }}
                renderItem={(item) => {
                  const parsed = parseFollowupParsed(item.parsedJson);
                  return (
                    <List.Item
                      actions={[
                        <Button
                          key="confirm"
                          type="link"
                          size="small"
                          disabled={item.state === 'SENT'}
                          onClick={() => void confirmPrompt(item)}
                        >
                          确认入账
                        </Button>,
                        <Button
                          key="ignore"
                          type="link"
                          size="small"
                          danger
                          onClick={() => void ignorePrompt(item)}
                        >
                          忽略
                        </Button>,
                      ]}
                    >
                      <Space
                        direction="vertical"
                        size={2}
                        style={{ width: '100%' }}
                      >
                        <Space wrap>
                          <Tag color="processing">
                            {item.remindStage || '跟进问句'}
                          </Tag>
                          <Typography.Text type="secondary">
                            {dateTime(
                              item.sentAt || item.createdAt || undefined,
                            )}
                          </Typography.Text>
                        </Space>
                        <Typography.Text>
                          {item.replyText || '等待回复'}
                        </Typography.Text>
                        {(parsed.probability !== undefined ||
                          parsed.nextAt) && (
                          <Typography.Text type="secondary">
                            {parsed.probability !== undefined
                              ? `概率 ${parsed.probability}%`
                              : ''}
                            {parsed.probability !== undefined && parsed.nextAt
                              ? ' · '
                              : ''}
                            {parsed.nextAt
                              ? `下次跟进 ${dateTime(parsed.nextAt)}`
                              : ''}
                          </Typography.Text>
                        )}
                      </Space>
                    </List.Item>
                  );
                }}
              />
              <Input.TextArea
                rows={2}
                style={{ marginTop: 12 }}
                value={pasteText}
                onChange={(event) => setPasteText(event.target.value)}
                placeholder="把企微里的回复原文粘贴到这里，例如『下周三 15:00 再沟通，概率 8%』"
              />
              <Button
                type="primary"
                loading={pasting}
                style={{ marginTop: 8 }}
                onClick={() => void pasteReply()}
              >
                解析并保存
              </Button>
            </Card>
            <Card
              size="small"
              style={{ marginBottom: 16 }}
              title={
                <Space>
                  <RobotOutlined />
                  AI 教练
                </Space>
              }
              extra={
                <Button
                  size="small"
                  loading={coachLoading}
                  onClick={() => void generateCoach()}
                >
                  生成 / 重新生成
                </Button>
              }
            >
              {renderCoachContent()}
            </Card>
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
            <Space style={{ width: '100%', justifyContent: 'space-between' }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                关联报价单
              </Typography.Title>
              {quotations.length > 1 && (
                <Button size="small" icon={<DiffOutlined />} onClick={() => setCompareOpen(true)}>
                  方案比价
                </Button>
              )}
            </Space>
            <List
              loading={historyLoading}
              dataSource={quotations}
              locale={{ emptyText: '暂无报价单' }}
              renderItem={(item) => (
                <List.Item
                  extra={
                    <Space size={4}>
                      {(item.status === 'DRAFT' || item.status === 'APPROVED') &&
                        item.isPreferred !== 1 && (
                          <Button
                            type="link"
                            onClick={async () => {
                              await superworkApi.setQuotationPreferred(item.id);
                              message.success('已设为首选方案');
                              if (detail) await loadHistory(detail.id);
                              await load();
                            }}
                          >
                            设首选
                          </Button>
                        )}
                      <Button
                        type="link"
                        onClick={() => history.push(`/quotations/${item.id}`)}
                      >
                        打开
                      </Button>
                    </Space>
                  }
                >
                  <List.Item.Meta
                    title={
                      <Space size={4}>
                        <span>{item.quotationNo}</span>
                        {item.isPreferred === 1 && <Tag color="gold">首选</Tag>}
                        <Tag>V{item.versionNo || 1}</Tag>
                        {item.discountOverride === 1 && <Tag color="warning">超折扣</Tag>}
                        {item.dueSoon && <Tag color="red">即将过期</Tag>}
                      </Space>
                    }
                    description={`${money(item.firstYearTotalInclTax)} · ${
                      item.quoteDate?.slice(0, 10) || '—'
                    }${item.variantName ? ` · ${item.variantName}` : ''}`}
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
      <CompareDrawer
        open={compareOpen}
        opportunityId={detail?.id}
        onClose={() => setCompareOpen(false)}
        onChanged={() => {
          if (detail) void loadHistory(detail.id);
          void load();
        }}
      />
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
