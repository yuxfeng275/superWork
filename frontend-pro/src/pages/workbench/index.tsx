import {
  ArrowRightOutlined,
  CheckSquareOutlined,
  CloseCircleOutlined,
  DashboardOutlined,
  FileTextOutlined,
  FlagOutlined,
  ReloadOutlined,
  RiseOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Link, useModel } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Empty,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type QuotationListVO,
  type Requirement,
  type SalesOpportunity,
  superworkApi,
  type WorkItemRecord,
} from '@/services/superwork/api';
import './style.less';

type Matter = Record<string, unknown>;
type Snapshot = {
  requirements: Requirement[];
  tasks: WorkItemRecord[];
  taskSummary: Record<string, unknown>;
  defectSummary: Record<string, unknown>;
  matters: Matter[];
  opportunities: SalesOpportunity[];
  quotations: QuotationListVO[];
};
const emptySnapshot = (): Snapshot => ({
  requirements: [],
  tasks: [],
  taskSummary: {},
  defectSummary: {},
  matters: [],
  opportunities: [],
  quotations: [],
});
const inProgressReq = ['评估中', '设计中', '开发中', '测试中'];
const deliveredReq = ['已上线', '已交付', '已验收'];
const riskMatter = ['有风险', '已阻塞'];
const progressingOpp = ['初步接触', '需求确认', '方案报价', '商务谈判'];
const asList = <T,>(value: T[] | { records?: T[] } | undefined): T[] =>
  Array.isArray(value) ? value : value?.records || [];
const count = (value: unknown) => Number(value || 0);
const reqTone = (status?: string) => {
  if (deliveredReq.includes(status || '')) return 'success';
  if (status === '待上线' || status === '测试中') return 'warning';
  if (status === '已拒绝') return 'error';
  return 'processing';
};
const matterTone = (status?: string) => {
  if (status === '已完成') return 'success';
  if (riskMatter.includes(status || '')) return 'error';
  if (status === '推进中') return 'processing';
  return 'default';
};
const oppTone = (status?: string) => {
  if (status === '已成交') return 'success';
  if (status === '已流失') return 'error';
  if (status === '方案报价' || status === '商务谈判') return 'warning';
  return 'processing';
};

export default function WorkbenchPage() {
  const { initialState } = useModel('@@initialState');
  const [data, setData] = useState<Snapshot>(emptySnapshot);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [
        requirementResult,
        taskResult,
        defectResult,
        matterResult,
        opportunityResult,
        quotationResult,
      ] = await Promise.allSettled([
        superworkApi.getRequirements({ size: 100 }),
        superworkApi.getTaskOverview(),
        superworkApi.getDefectOverview({ size: 20 }),
        superworkApi.getKeyMatters(),
        superworkApi.getSalesOpportunities(),
        superworkApi.getQuotations({ size: 20 }),
      ]);
      const failed = [
        requirementResult,
        taskResult,
        defectResult,
        matterResult,
        opportunityResult,
        quotationResult,
      ].filter((item) => item.status === 'rejected');
      setData({
        requirements:
          requirementResult.status === 'fulfilled'
            ? requirementResult.value.records || []
            : [],
        tasks:
          taskResult.status === 'fulfilled'
            ? taskResult.value.tasks || taskResult.value.records || []
            : [],
        taskSummary:
          taskResult.status === 'fulfilled'
            ? taskResult.value.summary || {}
            : {},
        defectSummary:
          defectResult.status === 'fulfilled'
            ? defectResult.value.summary || {}
            : {},
        matters:
          matterResult.status === 'fulfilled' ? matterResult.value || [] : [],
        opportunities:
          opportunityResult.status === 'fulfilled'
            ? opportunityResult.value || []
            : [],
        quotations:
          quotationResult.status === 'fulfilled'
            ? asList(quotationResult.value)
            : [],
      });
      if (failed.length === 6) {
        const first = failed[0] as PromiseRejectedResult;
        throw first.reason instanceof Error
          ? first.reason
          : new Error('工作台数据加载失败');
      }
      if (failed.length)
        setError(`部分模块暂不可用（${failed.length}），已展示其余数据。`);
    } catch (e) {
      setData(emptySnapshot());
      setError(e instanceof Error ? e.message : '工作台数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const monthStart = dayjs().startOf('month');
  const kpis = useMemo(() => {
    const overdueTasks = data.tasks.filter((item) => item.overdueIncomplete);
    const riskMatters = data.matters.filter((item) =>
      riskMatter.includes(String(item.status || '')),
    );
    const progressingOpps = data.opportunities.filter((item) =>
      progressingOpp.includes(item.status),
    );
    return [
      {
        label: '进行中需求',
        value: data.requirements.filter((item) =>
          inProgressReq.includes(item.status || ''),
        ).length,
        note: '评估 / 设计 / 开发 / 测试',
        color: '#2563eb',
        to: '/requirements',
      },
      {
        label: '待上线',
        value: data.requirements.filter((item) => item.status === '待上线')
          .length,
        note: '需要排入上线窗口',
        color: '#d97706',
        to: '/requirements',
      },
      {
        label: '进行中任务',
        value: count(data.taskSummary.inProgressCount),
        note: `待开始 ${count(data.taskSummary.pendingCount)}`,
        color: '#2563eb',
        to: '/tasks',
      },
      {
        label: '逾期任务',
        value: overdueTasks.length,
        note: '未完成且已过期',
        color: '#dc2626',
        to: '/tasks',
      },
      {
        label: '风险大事儿',
        value: riskMatters.length,
        note: '有风险 / 已阻塞',
        color: '#d97706',
        to: '/key-matters',
      },
      {
        label: '跟进中商机',
        value: progressingOpps.length,
        note: `报价草稿 ${data.quotations.filter((item) => item.status === 'DRAFT').length}`,
        color: '#7c3aed',
        to: '/opportunities',
      },
    ];
  }, [data]);
  const deliveredThisMonth = data.requirements.filter(
    (item) =>
      deliveredReq.includes(item.status || '') &&
      item.updatedAt &&
      dayjs(item.updatedAt).isAfter(monthStart.subtract(1, 'millisecond')),
  ).length;
  const openDefects = count(data.defectSummary.inProgressCount);
  const todayOpps = data.opportunities.filter((item) =>
    String(item.nextFollowUp || '').includes('今天'),
  );
  const attention = [
    {
      title: '逾期任务',
      text: kpis[3].value
        ? `有 ${kpis[3].value} 项任务已过期未完成。`
        : '暂无逾期任务。',
      tone: kpis[3].value ? 'danger' : 'blue',
      to: '/tasks',
      action: '去处理',
    },
    {
      title: '风险大事儿',
      text: kpis[4].value
        ? `有 ${kpis[4].value} 项大事儿处于风险或阻塞。`
        : '大事儿推进正常。',
      tone: kpis[4].value ? 'danger' : 'blue',
      to: '/key-matters',
      action: '看周进展',
    },
    {
      title: '今日待跟进',
      text: todayOpps.length
        ? `有 ${todayOpps.length} 条商机需要今天跟进。`
        : '今天没有标记待跟进的商机。',
      tone: todayOpps.length ? 'danger' : 'blue',
      to: '/opportunities',
      action: '去跟进',
    },
  ] as const;
  const shortcuts = [
    { to: '/requirements', label: '需求管理', icon: <FileTextOutlined /> },
    { to: '/tasks', label: '任务管理', icon: <CheckSquareOutlined /> },
    { to: '/defects', label: '缺陷管理', icon: <CloseCircleOutlined /> },
    { to: '/key-matters', label: '大事儿', icon: <FlagOutlined /> },
    { to: '/opportunities', label: '线索商机', icon: <RiseOutlined /> },
    { to: '/quotations', label: '报价单', icon: <FileTextOutlined /> },
    { to: '/statistics', label: 'BU 驾驶舱', icon: <DashboardOutlined /> },
    { to: '/kpi-report', label: 'KPI 周报', icon: <DashboardOutlined /> },
  ];
  const greeting = initialState?.currentUser?.realName
    ? `${initialState.currentUser.realName}，今天先看这些。`
    : '今天先看这些。';

  return (
    <div className="sw-page sw-workbench">
      {error && (
        <Alert
          type={
            data.requirements.length || data.tasks.length ? 'warning' : 'error'
          }
          showIcon
          message="工作台数据不完整"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <div className="sw-page-header">
        <div>
          <Typography.Title level={2}>工作台</Typography.Title>
          <Typography.Paragraph type="secondary">
            {greeting} 覆盖需求、任务、大事儿和商机，直接跳到要处理的模块。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => void load()}
          >
            刷新
          </Button>
          <Link to="/statistics">
            BU 驾驶舱 <ArrowRightOutlined />
          </Link>
        </Space>
      </div>
      <div className="sw-workbench-kpis">
        {kpis.map((stat) => (
          <Link className="sw-workbench-kpi" to={stat.to} key={stat.label}>
            <Statistic
              title={stat.label}
              value={loading ? '-' : stat.value}
              styles={{ content: { color: stat.color } }}
            />
            <Typography.Text type="secondary">{stat.note}</Typography.Text>
          </Link>
        ))}
      </div>
      <div className="sw-workbench-grid">
        <section className="sw-workbench-main" aria-label="待办队列">
          <Card
            variant="borderless"
            title="需求推进"
            extra={
              <Link to="/requirements">
                全部需求 <ArrowRightOutlined />
              </Link>
            }
          >
            {loading ? (
              <Skeleton active paragraph={{ rows: 5 }} />
            ) : data.requirements.length ? (
              <ul className="sw-recent-list">
                {data.requirements.slice(0, 6).map((item) => (
                  <li className="sw-recent-item" key={String(item.id)}>
                    <div className="sw-recent-meta">
                      <Link
                        className="sw-recent-title"
                        to={`/requirements?id=${item.id}`}
                      >
                        {item.title}
                      </Link>
                      <Typography.Text type="secondary">
                        {item.reqNo || `REQ-${item.id}`} ·{' '}
                        {item.projectName || item.project || '未关联项目'} ·{' '}
                        {item.owner || '未分配'}
                      </Typography.Text>
                    </div>
                    <Tag color={reqTone(item.status)}>
                      {item.status || '未设置'}
                    </Tag>
                  </li>
                ))}
              </ul>
            ) : (
              <Empty description="暂无需求数据" />
            )}
          </Card>
          <Card
            variant="borderless"
            title="大事儿风险"
            extra={
              <Link to="/key-matters">
                大事儿管理 <ArrowRightOutlined />
              </Link>
            }
          >
            {loading ? (
              <Skeleton active paragraph={{ rows: 4 }} />
            ) : data.matters.length ? (
              <ul className="sw-recent-list">
                {data.matters
                  .filter((item) =>
                    riskMatter.includes(String(item.status || '')),
                  )
                  .concat(
                    data.matters.filter(
                      (item) => !riskMatter.includes(String(item.status || '')),
                    ),
                  )
                  .slice(0, 5)
                  .map((item) => (
                    <li className="sw-recent-item" key={String(item.id)}>
                      <div className="sw-recent-meta">
                        <Link className="sw-recent-title" to="/key-matters">
                          {String(item.title || '未命名事项')}
                        </Link>
                        <Typography.Text type="secondary">
                          {String(item.ownerName || '未指定负责人')} · 进度{' '}
                          {Number(item.progress || 0)}%
                        </Typography.Text>
                      </div>
                      <Tag color={matterTone(String(item.status || ''))}>
                        {String(item.status || '未设置')}
                      </Tag>
                    </li>
                  ))}
              </ul>
            ) : (
              <Empty description="暂无大事儿" />
            )}
          </Card>
          <Card
            variant="borderless"
            title="商机跟进"
            extra={
              <Link to="/opportunities">
                线索商机 <ArrowRightOutlined />
              </Link>
            }
          >
            {loading ? (
              <Skeleton active paragraph={{ rows: 4 }} />
            ) : data.opportunities.length ? (
              <ul className="sw-recent-list">
                {data.opportunities.slice(0, 5).map((item) => (
                  <li className="sw-recent-item" key={item.id}>
                    <div className="sw-recent-meta">
                      <Link className="sw-recent-title" to="/opportunities">
                        {item.name}
                      </Link>
                      <Typography.Text type="secondary">
                        {item.customer || '未填写客户'} ·{' '}
                        {item.nextFollowUp || '待安排跟进'}
                      </Typography.Text>
                    </div>
                    <Tag color={oppTone(item.status)}>{item.status}</Tag>
                  </li>
                ))}
              </ul>
            ) : (
              <Empty description="暂无商机" />
            )}
          </Card>
        </section>
        <aside className="sw-workbench-aside" aria-label="快捷入口">
          <Card variant="borderless" className="sw-workbench-welcome">
            <Typography.Title level={2}>今日关注</Typography.Title>
            <Typography.Paragraph type="secondary">
              本月已交付 {loading ? '—' : deliveredThisMonth} 项需求
              {openDefects ? `，进行中缺陷 ${openDefects} 项。` : '。'}
            </Typography.Paragraph>
            {attention.map((item) => (
              <div className="sw-attention-item" key={item.title}>
                <span className={`sw-attention-icon ${item.tone}`}>
                  <WarningOutlined />
                </span>
                <div>
                  <Typography.Text strong>{item.title}</Typography.Text>
                  <Typography.Paragraph type="secondary">
                    {item.text}
                  </Typography.Paragraph>
                  <Link to={item.to}>{item.action}</Link>
                </div>
              </div>
            ))}
          </Card>
          <Card variant="borderless" title="功能入口">
            <div className="sw-workbench-shortcuts">
              {shortcuts.map((item) => (
                <Link
                  className="sw-workbench-shortcut"
                  to={item.to}
                  key={item.to}
                >
                  <span aria-hidden>{item.icon}</span>
                  {item.label}
                </Link>
              ))}
            </div>
          </Card>
        </aside>
      </div>
    </div>
  );
}
