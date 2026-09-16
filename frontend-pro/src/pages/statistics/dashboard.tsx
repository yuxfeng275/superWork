import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type BusinessLine,
  type ProjectRecord,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type Direction = {
  id: number;
  code?: string;
  name: string;
  objective?: string;
  ownerId?: number;
  ownerName?: string;
  startDate?: string;
  endDate?: string;
  status?: string;
  progress?: number;
  health?: string;
  requirementCount?: number;
  taskCount?: number;
  projectIds?: number[];
  projectNames?: string[];
  milestones?: Array<{
    id?: number;
    name?: string;
    dueDate?: string;
    status?: string;
    sortOrder?: number;
    overdue?: boolean;
  }>;
};
type Capacity = {
  userId: number;
  realName?: string;
  role?: string;
  activeTaskCount?: number;
  overdueTaskCount?: number;
  actualHours?: number;
  expectedHours?: number;
  actualEffortRate?: number;
  plannedHours?: number;
  plannedLoadRate?: number;
  loadStatus?: string;
  dataCompleteness?: string;
  yunxiaoMapped?: boolean;
  activeWork?: string[];
};
type Worklog = {
  userId: number;
  realName?: string;
  role?: string;
  workDate?: string;
  expectedHours?: number;
  actualHours?: number;
  status?: string;
  finalResult?: boolean;
};
type WorklogGroup =
  | 'all'
  | 'completed'
  | 'missing'
  | 'insufficient'
  | 'unresolved';
type Dashboard = {
  periodStart?: string;
  periodEnd?: string;
  planWindowWorkdays?: number;
  summary?: Record<string, number>;
  directions?: Direction[];
  capacity?: Capacity[];
  worklogs?: Worklog[];
  integration?: Record<string, unknown>;
};
type YunxiaoAnalysis = {
  total?: number;
  requirements?: number;
  tasks?: number;
  delayed?: number;
  byOwner?: Array<{
    name?: string;
    count?: number;
    delayed?: number;
    actualHours?: number;
  }>;
};

const healthColor: Record<string, string> = {
  HEALTHY: 'success',
  AT_RISK: 'warning',
  DELAYED: 'error',
  NORMAL: 'success',
  RISK: 'warning',
};
const display = (value: unknown) =>
  value === undefined || value === null || value === '' ? '—' : String(value);

export default function DashboardPage() {
  const [directionForm] = Form.useForm<Record<string, unknown>>();
  const [projectMappingForm] = Form.useForm<Record<string, unknown>>();
  const [userMappingForm] = Form.useForm<Record<string, unknown>>();
  const [data, setData] = useState<Dashboard>({});
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [users, setUsers] = useState<
    Array<{ id: number; realName?: string; username?: string }>
  >([]);
  const [projectMappings, setProjectMappings] = useState<
    Record<string, unknown>[]
  >([]);
  const [userMappings, setUserMappings] = useState<Record<string, unknown>[]>(
    [],
  );
  const [yunxiaoProjects, setYunxiaoProjects] = useState<
    Record<string, unknown>[]
  >([]);
  const [yunxiaoMembers, setYunxiaoMembers] = useState<
    Record<string, unknown>[]
  >([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [directionOpen, setDirectionOpen] = useState(false);
  const [editingDirection, setEditingDirection] = useState<Direction>();
  const [mappingOpen, setMappingOpen] = useState<'project' | 'user'>();
  const [editingMapping, setEditingMapping] =
    useState<Record<string, unknown>>();
  const [configOpen, setConfigOpen] = useState(false);
  const [configForm] = Form.useForm<Record<string, unknown>>();
  const [saving, setSaving] = useState(false);
  const [period, setPeriod] = useState<[Dayjs, Dayjs]>([
    dayjs().subtract(14, 'day'),
    dayjs(),
  ]);
  const [planWindow, setPlanWindow] = useState(10);
  const [worklogGroup, setWorklogGroup] = useState<WorklogGroup>('all');
  const [yunxiaoAnalysis, setYunxiaoAnalysis] = useState<YunxiaoAnalysis>({});
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const worklogGroupForStatus = (
    status?: string,
  ): Exclude<WorklogGroup, 'all'> => {
    if (status === '已填写' || status === '已豁免') return 'completed';
    if (status === '未填写' || status === '预警未填') return 'missing';
    if (status === '填写不足' || status === '预警不足') return 'insufficient';
    return 'unresolved';
  };
  const historicalWorklogs = useMemo(
    () =>
      (data.worklogs || []).filter(
        (item) => item.workDate && item.workDate < dayjs().format('YYYY-MM-DD'),
      ),
    [data.worklogs],
  );
  const worklogCounts = useMemo(() => {
    const counts: Record<WorklogGroup, number> = {
      all: historicalWorklogs.length,
      completed: 0,
      missing: 0,
      insufficient: 0,
      unresolved: 0,
    };
    historicalWorklogs.forEach((item) => {
      counts[worklogGroupForStatus(item.status)] += 1;
    });
    return counts;
  }, [historicalWorklogs]);
  const filteredWorklogs = useMemo(() => {
    if (worklogGroup === 'all') return historicalWorklogs;
    return historicalWorklogs.filter(
      (item) => worklogGroupForStatus(item.status) === worklogGroup,
    );
  }, [historicalWorklogs, worklogGroup]);
  const worklogReport = useMemo(() => {
    const rows = historicalWorklogs;
    const totalExpected = rows.reduce(
      (sum, item) => sum + Number(item.expectedHours || 0),
      0,
    );
    const totalActual = rows.reduce(
      (sum, item) => sum + Number(item.actualHours || 0),
      0,
    );
    const filledRows = rows.filter(
      (item) => worklogGroupForStatus(item.status) === 'completed',
    ).length;
    const people = new Map<
      number,
      {
        userId: number;
        realName: string;
        expectedHours: number;
        actualHours: number;
        filled: number;
        total: number;
      }
    >();
    rows.forEach((item) => {
      const current = people.get(item.userId) || {
        userId: item.userId,
        realName: item.realName || `用户${item.userId}`,
        expectedHours: 0,
        actualHours: 0,
        filled: 0,
        total: 0,
      };
      current.expectedHours += Number(item.expectedHours || 0);
      current.actualHours += Number(item.actualHours || 0);
      current.filled +=
        worklogGroupForStatus(item.status) === 'completed' ? 1 : 0;
      current.total += 1;
      people.set(item.userId, current);
    });
    const memberRows = Array.from(people.values())
      .map((item) => ({
        ...item,
        rate: item.total ? Math.round((item.filled / item.total) * 100) : 0,
      }))
      .sort(
        (left, right) =>
          left.rate - right.rate || right.actualHours - left.actualHours,
      );
    const statusRows = (
      [
        ['all', '全部'],
        ['completed', '已填写'],
        ['missing', '未填写'],
        ['insufficient', '填写不足'],
        ['unresolved', '待确认'],
      ] as Array<[WorklogGroup, string]>
    ).map(([value, label]) => ({
      value,
      label,
      count: worklogCounts[value],
      rate: rows.length
        ? Math.round((worklogCounts[value] / rows.length) * 100)
        : 0,
    }));
    return {
      totalExpected,
      totalActual,
      filledRows,
      completionRate: rows.length
        ? Math.round((filledRows / rows.length) * 100)
        : 0,
      memberCount: people.size,
      memberRows,
      statusRows,
    };
  }, [historicalWorklogs, worklogCounts]);
  const loadYunxiaoAnalysis = useCallback(async () => {
    setAnalysisLoading(true);
    try {
      setYunxiaoAnalysis(
        await superworkApi.getYunxiaoAnalysis<YunxiaoAnalysis>(),
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '云效分析加载失败');
    } finally {
      setAnalysisLoading(false);
    }
  }, []);
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [dashboard, projectPage, userPage, businessLinePage] =
        await Promise.all([
          superworkApi.getBuDashboard<Dashboard>({
            startDate: period[0].format('YYYY-MM-DD'),
            endDate: period[1].format('YYYY-MM-DD'),
            planWindowWorkdays: planWindow,
          }),
          superworkApi.getProjects({ page: 1, size: 300 }),
          superworkApi.getUsers({ page: 1, size: 300 }),
          superworkApi
            .getBusinessLines({ page: 1, size: 100, status: 1 })
            .catch(() => ({ records: [], total: 0, current: 1, size: 100 })),
        ]);
      setData(dashboard);
      setProjects(projectPage.records || []);
      setUsers(userPage.records || []);
      setBusinessLines(businessLinePage.records || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'BU 驾驶舱加载失败');
    } finally {
      setLoading(false);
    }
  }, [period, planWindow]);
  const projectBusinessLineName = (projectId: unknown) => {
    const project = projects.find((item) => item.id === Number(projectId));
    return businessLines.find((line) => line.id === project?.businessLineId)
      ?.name;
  };
  const loadMappings = useCallback(async () => {
    const [pm, um, yp, ym, rawStatus] = await Promise.all([
      superworkApi.getYunxiaoProjectMappings().catch(() => []),
      superworkApi.getYunxiaoUserMappings().catch(() => []),
      superworkApi.getYunxiaoProjects().catch(() => []),
      superworkApi.getYunxiaoMembers().catch(() => []),
      superworkApi.getYunxiaoStatus().catch(() => ({})),
    ]);
    const status = rawStatus as Record<string, unknown>;
    setProjectMappings(pm);
    setUserMappings(um);
    setYunxiaoProjects(yp);
    setYunxiaoMembers(ym);
    configForm.setFieldsValue({
      enabled: Boolean(status.enabled ?? data.integration?.enabled),
      edition: status.edition || data.integration?.edition || 'center',
      baseUrl:
        status.baseUrl ||
        data.integration?.baseUrl ||
        'https://openapi-rdc.aliyuncs.com',
      organizationId:
        status.organizationId || data.integration?.organizationId || '',
      token: '',
    });
  }, [configForm, data.integration]);
  useEffect(() => {
    void load();
  }, [load]);
  const openDirection = (record?: Direction) => {
    setEditingDirection(record);
    directionForm.setFieldsValue(
      record
        ? { ...record, projectIds: record.projectIds || [] }
        : {
            status: '进行中',
            sortOrder: (data.directions?.length || 0) + 1,
            projectIds: [],
            milestones: [],
          },
    );
    setDirectionOpen(true);
  };
  const saveDirection = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      if (editingDirection)
        await superworkApi.updateBuDirection(editingDirection.id, values);
      else await superworkApi.createBuDirection(values);
      message.success(editingDirection ? 'BU 方向已更新' : 'BU 方向已创建');
      setDirectionOpen(false);
      directionForm.resetFields();
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };
  const removeDirection = async (id: number) => {
    try {
      await superworkApi.deleteBuDirection(id);
      message.success('BU 方向已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };
  const sync = async () => {
    setSaving(true);
    try {
      const logs = await superworkApi.syncYunxiao();
      message.success(
        `云效同步完成${logs.length ? `：${logs.join('；')}` : ''}`,
      );
      await Promise.all([load(), loadMappings()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setSaving(false);
    }
  };
  const saveMapping = async (
    kind: 'project' | 'user',
    values: Record<string, unknown>,
  ) => {
    setSaving(true);
    try {
      if (kind === 'project')
        await superworkApi.saveYunxiaoProjectMapping({
          ...values,
          category: values.category || 'Req',
          syncEnabled: values.syncEnabled === false ? 0 : 1,
        });
      else
        await superworkApi.saveYunxiaoUserMapping({
          ...values,
          syncEnabled: values.syncEnabled === false ? 0 : 1,
        });
      message.success('映射已保存');
      setMappingOpen(undefined);
      await loadMappings();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '映射保存失败');
    } finally {
      setSaving(false);
    }
  };
  const saveConfig = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      await superworkApi.updateYunxiaoConfig({
        ...values,
        enabled: values.enabled ? 1 : 0,
      });
      message.success('云效配置已保存');
      setConfigOpen(false);
      await Promise.all([load(), loadMappings()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '配置保存失败');
    } finally {
      setSaving(false);
    }
  };
  const removeMapping = async (
    kind: 'project' | 'user',
    id: number | string,
  ) => {
    try {
      if (kind === 'project')
        await superworkApi.deleteYunxiaoProjectMapping(id);
      else await superworkApi.deleteYunxiaoUserMapping(id);
      message.success('映射已删除');
      await loadMappings();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };
  const summary = data.summary || {};
  return (
    <div className="sw-page sw-statistics">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA / BU DASHBOARD
          </Typography.Text>
          <Typography.Title level={2}>BU 驾驶舱</Typography.Title>
          <Typography.Paragraph type="secondary">
            方向、人员负荷、工时核对和云效映射分别呈现，计划负荷与实际投入不混算。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
        <DatePicker.RangePicker
          value={period}
          allowClear={false}
          onChange={(value) => {
            if (value?.[0] && value?.[1]) setPeriod([value[0], value[1]]);
          }}
        />
        <Select
          value={planWindow}
          style={{ width: 150 }}
          options={[5, 10, 20].map((value) => ({
            value,
            label: `未来 ${value} 个工作日`,
          }))}
          onChange={setPlanWindow}
        />
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取驾驶舱数据"
          description={error}
          action={<Button onClick={() => void load()}>重试</Button>}
        />
      )}
      <Row gutter={[14, 14]}>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic
              title="BU 方向"
              value={summary.directionCount ?? data.directions?.length ?? 0}
            />
          </Card>
        </Col>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic
              title="风险方向"
              value={summary.atRiskDirectionCount ?? 0}
            />
          </Card>
        </Col>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic
              title="活跃需求"
              value={summary.activeRequirementCount ?? 0}
            />
          </Card>
        </Col>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic title="逾期任务" value={summary.overdueTaskCount ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic
              title="超负荷人员"
              value={summary.overloadedPeopleCount ?? 0}
            />
          </Card>
        </Col>
        <Col xs={12} md={4}>
          <Card variant="borderless">
            <Statistic
              title="工时缺失人员"
              value={summary.missingWorklogPeopleCount ?? 0}
            />
          </Card>
        </Col>
      </Row>
      <Tabs
        onChange={(key) => {
          if (key === 'integration') void loadMappings();
          if (key === 'yunxiao-analysis') void loadYunxiaoAnalysis();
        }}
        items={[
          {
            key: 'directions',
            label: '方向总览',
            children: (
              <Card
                variant="borderless"
                title={`统计周期 ${display(data.periodStart)} 至 ${display(
                  data.periodEnd,
                )}`}
                extra={
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => openDirection()}
                  >
                    新增方向
                  </Button>
                }
              >
                <Table
                  loading={loading}
                  rowKey="id"
                  dataSource={data.directions || []}
                  locale={{ emptyText: <Empty description="暂无 BU 方向" /> }}
                  columns={[
                    {
                      title: '方向',
                      dataIndex: 'name',
                      render: (value, row: Direction) => (
                        <Space orientation="vertical" size={0}>
                          <Typography.Text strong>{value}</Typography.Text>
                          <Typography.Text type="secondary">
                            {row.objective || '未填写目标'}
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    {
                      title: '负责人',
                      dataIndex: 'ownerName',
                      width: 110,
                      render: display,
                    },
                    {
                      title: '项目',
                      dataIndex: 'projectNames',
                      width: 180,
                      render: (v: string[]) => v?.join('、') || '—',
                    },
                    {
                      title: '里程碑',
                      dataIndex: 'milestones',
                      width: 220,
                      render: (items: Direction['milestones']) =>
                        items?.length ? (
                          <Space wrap size={[4, 4]}>
                            {items.slice(0, 3).map((item) => (
                              <Tag
                                key={`${
                                  item?.id ||
                                  item?.name ||
                                  item?.dueDate ||
                                  'milestone'
                                }-${item?.status || ''}`}
                                color={
                                  item?.status === '已完成'
                                    ? 'success'
                                    : item?.overdue
                                      ? 'error'
                                      : 'default'
                                }
                              >
                                {item?.name || '未命名'}
                              </Tag>
                            ))}
                            {items.length > 3 && <Tag>+{items.length - 3}</Tag>}
                          </Space>
                        ) : (
                          '—'
                        ),
                    },
                    {
                      title: '进度',
                      dataIndex: 'progress',
                      width: 150,
                      render: (v: number) => (
                        <Progress percent={Number(v || 0)} size="small" />
                      ),
                    },
                    {
                      title: '健康度',
                      dataIndex: 'health',
                      width: 100,
                      render: (v: string) => (
                        <Tag color={healthColor[v] || 'default'}>
                          {display(v)}
                        </Tag>
                      ),
                    },
                    {
                      title: '需求 / 任务',
                      width: 120,
                      render: (_: unknown, row: Direction) =>
                        `${row.requirementCount || 0} / ${row.taskCount || 0}`,
                    },
                    {
                      title: '操作',
                      width: 110,
                      render: (_: unknown, row: Direction) => (
                        <Space>
                          <Button
                            type="text"
                            icon={<EditOutlined />}
                            onClick={() => openDirection(row)}
                          />
                          <Popconfirm
                            title="确认删除这个方向？"
                            onConfirm={() => void removeDirection(row.id)}
                          >
                            <Button
                              type="text"
                              danger
                              icon={<DeleteOutlined />}
                            />
                          </Popconfirm>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'capacity',
            label: '人员负荷',
            children: (
              <Card variant="borderless">
                <Alert
                  showIcon
                  type="info"
                  message="计划负荷取未来计划窗口，实际投入取统计周期工时；两列不可互相替代。"
                  style={{ marginBottom: 16 }}
                />
                <Table
                  loading={loading}
                  rowKey="userId"
                  dataSource={data.capacity || []}
                  columns={[
                    {
                      title: '人员',
                      dataIndex: 'realName',
                      render: (v, row: Capacity) => (
                        <Space orientation="vertical" size={0}>
                          <Typography.Text strong>{display(v)}</Typography.Text>
                          <Typography.Text type="secondary">
                            {display(row.role)}
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    {
                      title: '活跃 / 逾期任务',
                      render: (_: unknown, row: Capacity) =>
                        `${row.activeTaskCount || 0} / ${
                          row.overdueTaskCount || 0
                        }`,
                    },
                    {
                      title: '实际投入',
                      render: (_: unknown, row: Capacity) =>
                        `${display(row.actualHours)}h / ${display(
                          row.expectedHours,
                        )}h`,
                    },
                    {
                      title: '实际投入率',
                      dataIndex: 'actualEffortRate',
                      render: (v: number) => (v == null ? '—' : `${v}%`),
                    },
                    {
                      title: '计划负荷',
                      render: (_: unknown, row: Capacity) =>
                        `${display(row.plannedHours)}h / ${
                          data.planWindowWorkdays || '—'
                        }工作日`,
                    },
                    {
                      title: '计划负荷率',
                      dataIndex: 'plannedLoadRate',
                      render: (v: number) => (v == null ? '—' : `${v}%`),
                    },
                    {
                      title: '状态',
                      render: (_: unknown, row: Capacity) => (
                        <Space orientation="vertical" size={2}>
                          <Tag
                            color={
                              row.loadStatus === 'OVERLOADED'
                                ? 'error'
                                : row.loadStatus === 'NORMAL'
                                  ? 'success'
                                  : 'default'
                            }
                          >
                            {display(row.loadStatus)}
                          </Tag>
                          {!row.yunxiaoMapped && (
                            <Tag color="warning">未映射云效</Tag>
                          )}
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'worklogs',
            label: '工时核对',
            children: (
              <Card variant="borderless">
                <Space wrap className="sw-worklog-quick-filter">
                  {worklogReport.statusRows.map((row) => (
                    <Button
                      key={row.value}
                      size="small"
                      type={worklogGroup === row.value ? 'primary' : 'default'}
                      onClick={() => setWorklogGroup(row.value)}
                    >
                      {row.label}
                      <Typography.Text
                        type="secondary"
                        style={{ marginLeft: 4 }}
                      >
                        {row.count}
                      </Typography.Text>
                    </Button>
                  ))}
                </Space>
                <Row gutter={[12, 12]} style={{ marginBlock: 16 }}>
                  {[
                    [
                      '填写完成率',
                      `${worklogReport.completionRate}%`,
                      `${worklogReport.filledRows}/${worklogCounts.all} 条记录已完成`,
                    ],
                    [
                      '团队实际工时',
                      `${worklogReport.totalActual.toFixed(1)}h`,
                      `应填 ${worklogReport.totalExpected.toFixed(1)}h`,
                    ],
                    [
                      '涉及成员',
                      worklogReport.memberCount,
                      '按成员查看填写分布',
                    ],
                    [
                      '待跟进记录',
                      worklogCounts.missing +
                        worklogCounts.insufficient +
                        worklogCounts.unresolved,
                      '未填、不足或待确认',
                    ],
                  ].map(([title, value, note]) => (
                    <Col xs={12} md={6} key={String(title)}>
                      <Card size="small" variant="borderless">
                        <Statistic title={title as string} value={value} />
                        <Typography.Text type="secondary">
                          {note as string}
                        </Typography.Text>
                      </Card>
                    </Col>
                  ))}
                </Row>
                <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
                  <Col xs={24} md={12}>
                    <Card
                      size="small"
                      variant="borderless"
                      title="填写状态分布"
                    >
                      <Space
                        orientation="vertical"
                        size={8}
                        style={{ width: '100%' }}
                      >
                        {worklogReport.statusRows
                          .filter((row) => row.value !== 'all')
                          .map((row) => (
                            <div
                              key={row.value}
                              className="sw-worklog-dist-row"
                            >
                              <div className="sw-worklog-dist-label">
                                <span>{row.label}</span>
                                <strong>{row.count}</strong>
                              </div>
                              <div className="sw-worklog-dist-track">
                                <i style={{ width: `${row.rate}%` }} />
                              </div>
                              <small>{row.rate}%</small>
                            </div>
                          ))}
                      </Space>
                    </Card>
                  </Col>
                  <Col xs={24} md={12}>
                    <Card
                      size="small"
                      variant="borderless"
                      title="成员填写分布"
                    >
                      <Space
                        orientation="vertical"
                        size={6}
                        style={{ width: '100%' }}
                      >
                        {worklogReport.memberRows.slice(0, 6).map((member) => (
                          <div
                            key={member.userId}
                            className="sw-worklog-member-row"
                          >
                            <Typography.Text strong>
                              {member.realName}
                            </Typography.Text>
                            <Typography.Text type="secondary">
                              {member.actualHours.toFixed(1)}h /{' '}
                              {member.expectedHours.toFixed(1)}h
                            </Typography.Text>
                            <Progress
                              percent={member.rate}
                              showInfo={false}
                              size="small"
                            />
                            <strong>{member.rate}%</strong>
                          </div>
                        ))}
                        {!worklogReport.memberRows.length && (
                          <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="暂无成员工时记录"
                          />
                        )}
                      </Space>
                    </Card>
                  </Col>
                </Row>
                <Table
                  loading={loading}
                  rowKey={(row: Worklog) => `${row.userId}-${row.workDate}`}
                  dataSource={filteredWorklogs}
                  columns={[
                    { title: '日期', dataIndex: 'workDate' },
                    { title: '人员', dataIndex: 'realName' },
                    { title: '岗位', dataIndex: 'role', render: display },
                    {
                      title: '应填工时',
                      dataIndex: 'expectedHours',
                      render: (v) => (v == null ? '—' : `${v}h`),
                    },
                    {
                      title: '实际工时',
                      dataIndex: 'actualHours',
                      render: (v) => (v == null ? '—' : `${v}h`),
                    },
                    {
                      title: '核对结果',
                      render: (_: unknown, row: Worklog) => (
                        <Tag
                          color={
                            row.finalResult
                              ? 'success'
                              : row.status === 'MISSING'
                                ? 'error'
                                : 'warning'
                          }
                        >
                          {display(row.status)}
                        </Tag>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'yunxiao-analysis',
            label: '云效分析',
            children: (
              <Card variant="borderless" loading={analysisLoading}>
                <Typography.Paragraph type="secondary">
                  基于已同步的云效工作项缓存，识别需求/任务规模、延期情况与人员执行负荷。
                </Typography.Paragraph>
                <Row gutter={[12, 12]}>
                  {[
                    ['云效工作项', yunxiaoAnalysis.total],
                    ['云效需求', yunxiaoAnalysis.requirements],
                    ['云效任务', yunxiaoAnalysis.tasks],
                    ['可能延期', yunxiaoAnalysis.delayed],
                    ['涉及成员', yunxiaoAnalysis.byOwner?.length],
                  ].map(([title, value]) => (
                    <Col xs={12} sm={8} lg={4} key={String(title)}>
                      <Card size="small" variant="borderless">
                        <Statistic
                          title={title as string}
                          value={value ?? '—'}
                        />
                      </Card>
                    </Col>
                  ))}
                </Row>
                <Table
                  style={{ marginTop: 16 }}
                  size="small"
                  rowKey={(row) =>
                    String(row.name || row.count || row.actualHours || 'owner')
                  }
                  dataSource={yunxiaoAnalysis.byOwner || []}
                  locale={{ emptyText: <Empty description="暂无同步数据" /> }}
                  columns={[
                    { title: '负责人', dataIndex: 'name', render: display },
                    { title: '工作项数', dataIndex: 'count', render: display },
                    {
                      title: '延期数',
                      dataIndex: 'delayed',
                      render: (value: unknown) => (
                        <Typography.Text
                          type={Number(value || 0) > 0 ? 'danger' : undefined}
                        >
                          {display(value)}
                        </Typography.Text>
                      ),
                    },
                    {
                      title: '实际工时',
                      dataIndex: 'actualHours',
                      render: (value: unknown) => `${display(value)}h`,
                    },
                    {
                      title: '工作项分布',
                      dataIndex: 'count',
                      render: (value: unknown) => (
                        <Progress
                          percent={
                            yunxiaoAnalysis.total
                              ? Math.round(
                                  (Number(value || 0) / yunxiaoAnalysis.total) *
                                    100,
                                )
                              : 0
                          }
                          size="small"
                        />
                      ),
                    },
                  ]}
                  pagination={{ pageSize: 12 }}
                />
              </Card>
            ),
          },
          {
            key: 'integration',
            label: '云效配置',
            children: (
              <Space orientation="vertical" style={{ width: '100%' }} size={16}>
                <Card
                  variant="borderless"
                  title="连接状态"
                  extra={
                    <Space>
                      <Button
                        icon={<SettingOutlined />}
                        onClick={() => {
                          void loadMappings();
                          setConfigOpen(true);
                        }}
                      >
                        编辑配置
                      </Button>
                      <Button
                        onClick={() =>
                          void superworkApi
                            .testYunxiaoConnection()
                            .then((r) =>
                              message.success(display(r.message || '连接正常')),
                            )
                            .catch((e) =>
                              message.error(
                                e instanceof Error ? e.message : '测试失败',
                              ),
                            )
                        }
                      >
                        测试连接
                      </Button>
                      <Button
                        type="primary"
                        icon={<SyncOutlined spin={saving} />}
                        onClick={() => void sync()}
                      >
                        立即同步
                      </Button>
                    </Space>
                  }
                >
                  <Descriptions column={3}>
                    <Descriptions.Item label="启用">
                      {data.integration?.enabled ? '是' : '否'}
                    </Descriptions.Item>
                    <Descriptions.Item label="配置完成">
                      {data.integration?.configured ? '是' : '否'}
                    </Descriptions.Item>
                    <Descriptions.Item label="版本">
                      {display(data.integration?.edition)}
                    </Descriptions.Item>
                    <Descriptions.Item label="组织 ID">
                      {display(data.integration?.organizationId)}
                    </Descriptions.Item>
                    <Descriptions.Item label="项目映射">
                      {display(data.integration?.mappedProjects)}
                    </Descriptions.Item>
                    <Descriptions.Item label="用户映射">
                      {display(data.integration?.mappedUsers)}
                    </Descriptions.Item>
                    <Descriptions.Item label="最近成功同步">
                      {display(data.integration?.lastSuccessfulSync)}
                    </Descriptions.Item>
                    <Descriptions.Item label="最近错误" span={2}>
                      {display(data.integration?.lastError)}
                    </Descriptions.Item>
                  </Descriptions>
                </Card>
                <Card
                  variant="borderless"
                  title="项目映射"
                  extra={
                    <Button
                      icon={<PlusOutlined />}
                      onClick={() => {
                        setEditingMapping(undefined);
                        projectMappingForm.resetFields();
                        setMappingOpen('project');
                      }}
                    >
                      新增映射
                    </Button>
                  }
                >
                  <Table
                    size="small"
                    rowKey={(r) => String(r.id)}
                    dataSource={projectMappings}
                    columns={[
                      {
                        title: '本地项目',
                        dataIndex: 'projectId',
                        render: (v) => {
                          const project = projects.find((p) => p.id === v);
                          return (
                            <Space orientation="vertical" size={0}>
                              <Typography.Text>
                                {project?.fullPath ||
                                  project?.name ||
                                  display(v)}
                              </Typography.Text>
                              {projectBusinessLineName(v) && (
                                <Typography.Text type="secondary">
                                  {projectBusinessLineName(v)}
                                </Typography.Text>
                              )}
                            </Space>
                          );
                        },
                      },
                      {
                        title: '云效项目',
                        dataIndex: 'yunxiaoProjectId',
                        render: (value) => {
                          const matched = yunxiaoProjects.find(
                            (item) => String(item.id) === String(value),
                          );
                          return (
                            <Space orientation="vertical" size={0}>
                              <Typography.Text>
                                {display(matched?.name || value)}
                              </Typography.Text>
                              {matched?.name != null && (
                                <Typography.Text type="secondary">
                                  {display(value)}
                                </Typography.Text>
                              )}
                            </Space>
                          );
                        },
                      },
                      {
                        title: '工作项类型',
                        dataIndex: 'workitemTypeId',
                        render: display,
                      },
                      {
                        title: '同步',
                        dataIndex: 'syncEnabled',
                        render: (v) =>
                          Number(v) === 1 ? (
                            <Tag color="success">启用</Tag>
                          ) : (
                            <Tag>停用</Tag>
                          ),
                      },
                      {
                        title: '操作',
                        render: (_: unknown, row: Record<string, unknown>) => (
                          <Space size={0}>
                            <Button
                              type="link"
                              icon={<EditOutlined />}
                              onClick={() => {
                                setEditingMapping(row);
                                projectMappingForm.setFieldsValue({
                                  ...row,
                                  syncEnabled: Number(row.syncEnabled) === 1,
                                });
                                setMappingOpen('project');
                              }}
                            >
                              编辑
                            </Button>
                            <Button
                              type="link"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() =>
                                void removeMapping('project', String(row.id))
                              }
                            >
                              删除
                            </Button>
                          </Space>
                        ),
                      },
                    ]}
                  />
                </Card>
                <Card
                  variant="borderless"
                  title="用户映射"
                  extra={
                    <Button
                      icon={<PlusOutlined />}
                      onClick={() => {
                        setEditingMapping(undefined);
                        userMappingForm.resetFields();
                        setMappingOpen('user');
                      }}
                    >
                      新增映射
                    </Button>
                  }
                >
                  <Table
                    size="small"
                    rowKey={(r) => String(r.id)}
                    dataSource={userMappings}
                    columns={[
                      {
                        title: '本地用户',
                        dataIndex: 'userId',
                        render: (v) =>
                          users.find((u) => u.id === v)?.realName || display(v),
                      },
                      {
                        title: '云效用户',
                        dataIndex: 'yunxiaoUserId',
                        render: (value) => {
                          const matched = yunxiaoMembers.find(
                            (item) => String(item.userId) === String(value),
                          );
                          return (
                            <Space orientation="vertical" size={0}>
                              <Typography.Text>
                                {display(matched?.name || value)}
                              </Typography.Text>
                              {matched?.name != null && (
                                <Typography.Text type="secondary">
                                  {display(value)}
                                </Typography.Text>
                              )}
                            </Space>
                          );
                        },
                      },
                      {
                        title: '同步',
                        dataIndex: 'syncEnabled',
                        render: (v) =>
                          Number(v) === 1 ? (
                            <Tag color="success">启用</Tag>
                          ) : (
                            <Tag>停用</Tag>
                          ),
                      },
                      {
                        title: '操作',
                        render: (_: unknown, row: Record<string, unknown>) => (
                          <Space size={0}>
                            <Button
                              type="link"
                              icon={<EditOutlined />}
                              onClick={() => {
                                setEditingMapping(row);
                                userMappingForm.setFieldsValue({
                                  ...row,
                                  syncEnabled: Number(row.syncEnabled) === 1,
                                });
                                setMappingOpen('user');
                              }}
                            >
                              编辑
                            </Button>
                            <Button
                              type="link"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() =>
                                void removeMapping('user', String(row.id))
                              }
                            >
                              删除
                            </Button>
                          </Space>
                        ),
                      },
                    ]}
                  />
                </Card>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={editingDirection ? '编辑 BU 方向' : '新增 BU 方向'}
        open={directionOpen}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => setDirectionOpen(false)}
        onOk={() => directionForm.submit()}
      >
        <Form
          form={directionForm}
          layout="vertical"
          onFinish={(values) => void saveDirection(values)}
        >
          <Space style={{ display: 'flex' }}>
            <Form.Item name="code" label="方向编码">
              <Input />
            </Form.Item>
            <Form.Item
              name="name"
              label="方向名称"
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
          </Space>
          <Form.Item name="objective" label="目标说明">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="ownerId" label="负责人">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={users.map((u) => ({
                value: u.id,
                label: u.realName || u.username || String(u.id),
              }))}
            />
          </Form.Item>
          <Form.Item name="projectIds" label="关联项目">
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="label"
              options={projects.map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>
          <Form.List name="milestones">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: '100%' }}>
                <Space
                  style={{ width: '100%', justifyContent: 'space-between' }}
                >
                  <Typography.Text strong>里程碑</Typography.Text>
                  <Button
                    type="dashed"
                    size="small"
                    onClick={() => add({ status: '未开始' })}
                  >
                    添加里程碑
                  </Button>
                </Space>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    align="start"
                    style={{ width: '100%' }}
                  >
                    <Form.Item
                      {...field}
                      name={[field.name, 'name']}
                      rules={[{ required: true, message: '请填写里程碑名称' }]}
                    >
                      <Input placeholder="里程碑名称" />
                    </Form.Item>
                    <Form.Item
                      {...field}
                      name={[field.name, 'dueDate']}
                      rules={[{ required: true, message: '请填写计划日期' }]}
                    >
                      <Input placeholder="YYYY-MM-DD" style={{ width: 130 }} />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'status']}>
                      <Select
                        style={{ width: 110 }}
                        options={['未开始', '进行中', '已完成'].map(
                          (value) => ({
                            value,
                            label: value,
                          }),
                        )}
                      />
                    </Form.Item>
                    <Button
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      aria-label="移除里程碑"
                      onClick={() => remove(field.name)}
                    />
                  </Space>
                ))}
              </Space>
            )}
          </Form.List>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="startDate" label="开始日期">
              <Input placeholder="YYYY-MM-DD" />
            </Form.Item>
            <Form.Item name="endDate" label="结束日期">
              <Input placeholder="YYYY-MM-DD" />
            </Form.Item>
          </Space>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="status" label="状态">
              <Select
                style={{ width: 150 }}
                options={['未开始', '进行中', '已完成', '已暂停'].map(
                  (value) => ({ value, label: value }),
                )}
              />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
      <Modal
        title={editingMapping ? '编辑项目映射' : '新增项目映射'}
        open={mappingOpen === 'project'}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => {
          setMappingOpen(undefined);
          setEditingMapping(undefined);
        }}
        onOk={() => projectMappingForm.submit()}
      >
        <Form
          form={projectMappingForm}
          layout="vertical"
          onFinish={(values) => void saveMapping('project', values)}
        >
          <Form.Item
            name="projectId"
            label="本地项目"
            rules={[{ required: true }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={projects.map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>
          <Form.Item
            name="yunxiaoProjectId"
            label="云效项目"
            rules={[{ required: true }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={yunxiaoProjects.map((p) => ({
                value: String(p.id),
                label: String(p.name || p.id),
              }))}
            />
          </Form.Item>
          <Form.Item name="workitemTypeId" label="工作项类型 ID">
            <Input />
          </Form.Item>
          <Form.Item name="category" label="分类" initialValue="Req">
            <Select
              options={['Req', 'Task', 'Bug'].map((value) => ({
                value,
                label: value,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="syncEnabled"
            label="启用同步"
            valuePropName="checked"
            initialValue
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={editingMapping ? '编辑用户映射' : '新增用户映射'}
        open={mappingOpen === 'user'}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => {
          setMappingOpen(undefined);
          setEditingMapping(undefined);
        }}
        onOk={() => userMappingForm.submit()}
      >
        <Form
          form={userMappingForm}
          layout="vertical"
          onFinish={(values) => void saveMapping('user', values)}
        >
          <Form.Item
            name="userId"
            label="本地用户"
            rules={[{ required: true }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={users.map((u) => ({
                value: u.id,
                label: u.realName || u.username || String(u.id),
              }))}
            />
          </Form.Item>
          <Form.Item
            name="yunxiaoUserId"
            label="云效成员"
            rules={[{ required: true }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={yunxiaoMembers.map((m) => ({
                value: String(m.userId),
                label: String(m.name || m.userId),
              }))}
            />
          </Form.Item>
          <Form.Item
            name="syncEnabled"
            label="启用同步"
            valuePropName="checked"
            initialValue
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="云效连接配置"
        open={configOpen}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => setConfigOpen(false)}
        onOk={() => configForm.submit()}
      >
        <Form
          form={configForm}
          layout="vertical"
          onFinish={(values) => void saveConfig(values)}
        >
          <Form.Item name="enabled" label="启用集成" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="edition" label="版本">
            <Select
              options={[
                { value: 'center', label: '中心化版本' },
                { value: 'region', label: '专有云版本' },
              ]}
            />
          </Form.Item>
          <Form.Item name="baseUrl" label="服务地址">
            <Input placeholder="https://openapi-rdc.aliyuncs.com" />
          </Form.Item>
          <Form.Item name="organizationId" label="组织 ID">
            <Input />
          </Form.Item>
          <Form.Item name="token" label="个人访问令牌">
            <Input.Password placeholder="已配置则留空保持不变" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
