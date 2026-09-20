import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  FileTextOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  ThunderboltOutlined,
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
  Modal,
  message,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SimpleMarkdown } from '@/components/SimpleMarkdown';
import {
  superworkApi,
  type WeeklyReportFacts,
  type WeeklyReportStatus,
  type WeeklyReportVO,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const statusMeta: Record<WeeklyReportStatus, { label: string; color: string }> =
  {
    PENDING: { label: '待生成', color: 'default' },
    GENERATING: { label: '生成中', color: 'processing' },
    DRAFT: { label: '草稿', color: 'warning' },
    CONFIRMED: { label: '已确认', color: 'success' },
    PUBLISHED: { label: '已发布', color: 'blue' },
    GENERATION_FAILED: { label: '生成失败', color: 'error' },
  };

const toMonday = (value: Dayjs | Date) => {
  const date = dayjs(value);
  const day = date.day() === 0 ? 7 : date.day();
  return date.subtract(day - 1, 'day').format('YYYY-MM-DD');
};

const rangeText = (row: WeeklyReportVO) =>
  `${row.weekStartDate.slice(5).replace('-', '.')} – ${row.periodEndDate
    .slice(5)
    .replace('-', '.')}`;
const fmtWan = (value?: number | null) =>
  value == null ? '—' : (value / 10000).toFixed(1);
const statusOf = (status: WeeklyReportStatus) =>
  statusMeta[status] || statusMeta.PENDING;

export default function WeeklyReportPage() {
  const [list, setList] = useState<WeeklyReportVO[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [error, setError] = useState('');
  const [editorOpen, setEditorOpen] = useState(false);
  const [panelMode, setPanelMode] = useState<'view' | 'edit'>('view');
  const [current, setCurrent] = useState<WeeklyReportVO | null>(null);
  const [facts, setFacts] = useState<WeeklyReportFacts | null>(null);
  const [factsLoading, setFactsLoading] = useState(false);
  const [factsExpanded, setFactsExpanded] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createWeek, setCreateWeek] = useState<Dayjs>(dayjs());
  const [creating, setCreating] = useState(false);
  const [savingInputs, setSavingInputs] = useState(false);
  const [savingContent, setSavingContent] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishTarget, setPublishTarget] = useState<WeeklyReportVO | null>(
    null,
  );
  const [publishing, setPublishing] = useState(false);
  const [markingSheet, setMarkingSheet] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [draft, setDraft] = useState({
    coreWork: '',
    kpiSection: '',
    risks: '',
    nextWeekPlan: '',
    minutesMarkdown: '',
  });
  const [inputDraft, setInputDraft] = useState({
    wecomSummary: '',
    manualNotes: '',
  });
  const pollRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);

  const applyReport = useCallback((report: WeeklyReportVO) => {
    setCurrent(report);
    setDraft({
      coreWork: report.coreWork || '',
      kpiSection: report.kpiSection || '',
      risks: report.risks || '',
      nextWeekPlan: report.nextWeekPlan || '',
      minutesMarkdown: report.minutesMarkdown || '',
    });
    setInputDraft({
      wecomSummary: report.wecomSummary || '',
      manualNotes: report.manualNotes || '',
    });
  }, []);

  const loadList = useCallback(async () => {
    setListLoading(true);
    setError('');
    try {
      setList(await superworkApi.getWeeklyHistory());
    } catch (e) {
      setError(e instanceof Error ? e.message : '周报列表加载失败');
    } finally {
      setListLoading(false);
    }
  }, []);
  useEffect(() => {
    void loadList();
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [loadList]);

  const summary = useMemo(() => {
    const total = list.length;
    const inFlight = list.filter((r) =>
      ['PENDING', 'GENERATING', 'DRAFT'].includes(r.status),
    ).length;
    const confirmed = list.filter((r) => r.status === 'CONFIRMED').length;
    const published = list.filter((r) => r.status === 'PUBLISHED').length;
    return {
      total,
      inFlight,
      confirmed,
      published,
    };
  }, [list]);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = undefined;
    }
  };
  const startPolling = useCallback(
    (weekStartDate: string) => {
      stopPolling();
      pollRef.current = setInterval(async () => {
        try {
          const latest = await superworkApi.getWeeklyReport(weekStartDate);
          applyReport(latest);
          if (latest.status !== 'GENERATING') {
            stopPolling();
            if (latest.status === 'DRAFT') message.success('周报草稿已生成');
            if (latest.status === 'GENERATION_FAILED')
              message.error(
                `生成失败：${latest.generationError || '未知错误'}`,
              );
            await loadList();
          }
        } catch {
          stopPolling();
        }
      }, 3000);
    },
    [applyReport, loadList],
  );

  const loadFacts = async (weekStartDate: string) => {
    setFactsLoading(true);
    try {
      setFacts(await superworkApi.getWeeklyFacts(weekStartDate));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '事实采集失败');
    } finally {
      setFactsLoading(false);
    }
  };

  const openReport = async (
    weekStartDate: string,
    mode: 'view' | 'edit' = 'view',
  ) => {
    setPanelMode(mode);
    setEditorOpen(true);
    setDetailLoading(true);
    setFacts(null);
    setFactsExpanded(false);
    stopPolling();
    try {
      const report = await superworkApi.getWeeklyReport(weekStartDate);
      applyReport(report);
      if (report.status === 'GENERATING') startPolling(report.weekStartDate);
      void loadFacts(weekStartDate);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '周报加载失败');
    } finally {
      setDetailLoading(false);
    }
  };
  const openEditor = async (weekStartDate: string) => {
    await openReport(weekStartDate, 'edit');
  };
  const openDetail = async (weekStartDate: string) => {
    await openReport(weekStartDate, 'view');
  };

  const closeEditor = () => {
    stopPolling();
    setEditorOpen(false);
    setCurrent(null);
    void loadList();
  };
  const createReport = async () => {
    setCreating(true);
    try {
      const report = await superworkApi.getWeeklyReport(toMonday(createWeek));
      setCreateOpen(false);
      await loadList();
      await openEditor(report.weekStartDate);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '新建失败');
    } finally {
      setCreating(false);
    }
  };
  const saveInputs = async () => {
    if (!current) return;
    setSavingInputs(true);
    try {
      const next = await superworkApi.saveWeeklyInputs(current.id, inputDraft);
      setCurrent(next);
      message.success('输入已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingInputs(false);
    }
  };
  const generate = async () => {
    if (!current) return;
    setGenerating(true);
    try {
      const next = await superworkApi.generateWeeklyReport(current.id);
      setCurrent(next);
      message.info('AI 生成中，约需 30-60 秒');
      startPolling(next.weekStartDate);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '触发生成失败');
    } finally {
      setGenerating(false);
    }
  };
  const saveContent = async () => {
    if (!current) return;
    setSavingContent(true);
    try {
      const next = await superworkApi.saveWeeklyContent(current.id, draft);
      setCurrent(next);
      message.success('内容已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingContent(false);
    }
  };
  const confirmReport = async () => {
    if (!current) return;
    setConfirming(true);
    try {
      const next = await superworkApi.confirmWeeklyReport(current.id);
      setCurrent(next);
      message.success('周报已确认');
      await loadList();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '确认失败');
    } finally {
      setConfirming(false);
    }
  };
  const copyReport = async () => {
    const text = [
      ['本周核心工作完成情况', current?.coreWork],
      ['KPI相关情况', current?.kpiSection],
      ['问题/风险与解决办法', current?.risks],
      ['下周工作计划', current?.nextWeekPlan],
    ]
      .filter(([, body]) => body?.trim())
      .map(([title, body]) => `■ ${title}\n${body?.trim()}`)
      .join('\n\n');
    if (!text) {
      message.warning('周报内容为空');
      return;
    }
    await navigator.clipboard.writeText(text);
    message.success('周报全文已复制');
  };
  const openPublish = async (row: WeeklyReportVO) => {
    setPublishTarget(row);
    setPublishOpen(true);
    try {
      setPublishTarget(await superworkApi.getWeeklyReport(row.weekStartDate));
    } catch {
      /* 保留列表行数据 */
    }
  };
  const publishYuque = async () => {
    if (!publishTarget) return;
    setPublishing(true);
    try {
      setPublishTarget(await superworkApi.publishWeeklyYuque(publishTarget.id));
      message.success('已发布语雀');
      await loadList();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '发布失败');
    } finally {
      setPublishing(false);
    }
  };
  const copyMinutesLink = async () => {
    const url = publishTarget?.yuqueDocUrl;
    if (!url) {
      message.warning('请先发布语雀纪要');
      return;
    }
    await navigator.clipboard.writeText(url);
    message.success('纪要链接已复制');
  };
  const markSheet = () => {
    if (!publishTarget) return;
    const info = publishTarget.sheetTargetInfo;
    Modal.confirm({
      title: '确认已粘贴到汇总表？',
      content: `当前语雀 Token 写不进公司汇总表。请把纪要链接粘贴到「${
        info?.sheetName || '汇总表'
      }」${info?.dateRangeLabel || ''} 行、${info?.teamName || ''}、K 列后确认。`,
      okText: '已粘贴，标记完成',
      cancelText: '取消',
      onOk: async () => {
        setMarkingSheet(true);
        try {
          setPublishTarget(
            await superworkApi.publishWeeklySheet(publishTarget.id),
          );
          message.success('已标记回填完成');
          await loadList();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '标记失败');
          throw e;
        } finally {
          setMarkingSheet(false);
        }
      },
    });
  };
  const pushWecom = async () => {
    if (!publishTarget) return;
    setPushing(true);
    try {
      setPublishTarget(await superworkApi.pushWeeklyWecom(publishTarget.id));
      message.success('已推送企微');
      await loadList();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '推送失败');
    } finally {
      setPushing(false);
    }
  };
  const editable = current?.editable ?? true;

  const columns = [
    {
      title: '周',
      dataIndex: 'weekStartDate',
      render: (_: string, row: WeeklyReportVO) => (
        <div>
          <Typography.Text strong>{rangeText(row)}</Typography.Text>
          <Typography.Text type="secondary" className="sw-report-subline">
            {row.weekStartDate.slice(0, 4)} 年 ·{' '}
            {row.generationMode
              ? `${row.generationMode} · ${row.generationModel || ''}`
              : '未生成'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: WeeklyReportStatus) => (
        <Tag color={statusOf(value).color}>{statusOf(value).label}</Tag>
      ),
    },
    {
      title: '同步状态',
      width: 240,
      render: (_: unknown, row: WeeklyReportVO) => (
        <Space wrap size={[4, 4]}>
          <Tag color={row.yuqueDocUrl ? 'success' : 'default'}>
            语雀{row.yuqueDocUrl ? '已发布' : '未发布'}
          </Tag>
          <Tag
            color={
              row.sheetSyncStatus === 'MANUAL_DONE' ? 'success' : 'default'
            }
          >
            汇总表{row.sheetSyncStatus === 'MANUAL_DONE' ? '已回填' : '待回填'}
          </Tag>
          <Tag
            color={row.wecomPushStatus === 'SUCCESS' ? 'success' : 'default'}
          >
            企微{row.wecomPushStatus === 'SUCCESS' ? '已推送' : '未推送'}
          </Tag>
        </Space>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 150,
      render: (value?: string) => value?.replace('T', ' ').slice(0, 16) || '—',
    },
    {
      title: '操作',
      width: 180,
      render: (_: unknown, row: WeeklyReportVO) => (
        <Space
          onClick={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
        >
          <Button
            type="link"
            size="small"
            onClick={() => void openDetail(row.weekStartDate)}
          >
            查看
          </Button>
          {row.editable && (
            <Button
              type="link"
              size="small"
              onClick={() => void openEditor(row.weekStartDate)}
            >
              编辑
            </Button>
          )}
          <Button
            type="link"
            size="small"
            onClick={() => void openPublish(row)}
          >
            同步
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="sw-page sw-weekly-page">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            OPERATIONS / WEEKLY REPORT
          </Typography.Text>
          <Typography.Title level={2}>周报中心</Typography.Title>
          <Typography.Paragraph type="secondary">
            BG 周报与周会纪要 · 每周五 17:00 自动生成草稿。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button
            icon={<ReloadOutlined />}
            loading={listLoading}
            onClick={() => void loadList()}
          >
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setCreateWeek(dayjs());
              setCreateOpen(true);
            }}
          >
            新建周报
          </Button>
        </Space>
      </div>
      <Row gutter={[12, 12]} className="sw-report-summary">
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="全部周报"
              value={summary.total}
              prefix={<FileTextOutlined />}
              suffix="周"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="进行中"
              value={summary.inFlight}
              prefix={
                summary.inFlight > 0 ? (
                  <LoadingOutlined />
                ) : (
                  <ClockCircleOutlined />
                )
              }
              suffix="待办"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="已确认"
              value={summary.confirmed}
              prefix={<CheckCircleOutlined />}
              suffix="周"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="已发布"
              value={summary.published}
              prefix={<SendOutlined />}
              suffix="周"
            />
          </Card>
        </Col>
      </Row>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取周报数据"
          description={error}
          action={
            <Button size="small" onClick={() => void loadList()}>
              重试
            </Button>
          }
        />
      )}
      <Card variant="borderless" className="sw-report-table">
        <Table
          rowKey="id"
          loading={listLoading}
          columns={columns}
          dataSource={list}
          onRow={(row) => ({
            onClick: (event) => {
              const target = event.target as HTMLElement | null;
              if (target?.closest('button, a, .ant-btn, .ant-space')) return;
              void openDetail(row.weekStartDate);
            },
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: <Empty description="暂无周报记录，点击右上角新建周报" />,
          }}
          scroll={{ x: 980 }}
          pagination={{
            pageSize: 10,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 周`,
          }}
        />
      </Card>

      <Modal
        title="新建周报"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void createReport()}
        confirmLoading={creating}
        okText="确定"
        cancelText="取消"
      >
        <div className="sw-create-row">
          <Typography.Text>选择周</Typography.Text>
          <DatePicker
            picker="week"
            value={createWeek}
            onChange={(value) => value && setCreateWeek(value)}
            allowClear={false}
            format="YYYY 第 ww 周"
          />
        </div>
        <Typography.Text type="secondary">
          已存在的周将直接打开对应周报。
        </Typography.Text>
      </Modal>

      <Drawer
        title={
          current
            ? `${rangeText(current)} ${panelMode === 'edit' ? '编辑周报' : '周报详情'}`
            : '周报'
        }
        size={Math.min(panelMode === 'view' ? 1040 : 900, (typeof window === 'undefined' ? 1040 : window.innerWidth) - 24)}
        open={editorOpen}
        onClose={closeEditor}
        extra={
          current && (
            <Space>
              {panelMode === 'view' ? (
                editable && (
                  <Button
                    type="primary"
                    onClick={() => setPanelMode('edit')}
                  >
                    编辑
                  </Button>
                )
              ) : (
                <>
                  <Button onClick={() => setPanelMode('view')}>预览</Button>
                  <Button
                    type="primary"
                    icon={<ThunderboltOutlined />}
                    loading={generating || current.status === 'GENERATING'}
                    disabled={!editable}
                    onClick={() => void generate()}
                  >
                    生成 / 重新生成
                  </Button>
                  <Button
                    loading={savingContent}
                    disabled={!editable}
                    onClick={() => void saveContent()}
                  >
                    保存
                  </Button>
                  <Button
                    type="primary"
                    ghost
                    icon={<CheckCircleOutlined />}
                    loading={confirming}
                    disabled={!editable || current.status === 'CONFIRMED'}
                    onClick={() => void confirmReport()}
                  >
                    确认
                  </Button>
                </>
              )}
              <Button icon={<CopyOutlined />} onClick={() => void copyReport()}>
                复制全文
              </Button>
            </Space>
          )
        }
      >
        {detailLoading ? (
          <div className="sw-report-loading">
            <LoadingOutlined /> 正在加载周报...
          </div>
        ) : (
          current && (
            <div className={panelMode === 'view' ? 'sw-report-detail' : 'sw-report-editor'}>
              <div className="sw-editor-meta">
                <Tag color={statusOf(current.status).color}>
                  {statusOf(current.status).label}
                </Tag>
                <Typography.Text type="secondary">
                  {current.weekStartDate} ~ {current.periodEndDate}
                  {current.generationModel
                    ? ` · ${current.generationModel}`
                    : ''}
                </Typography.Text>
              </div>
              {current.status === 'GENERATION_FAILED' &&
                current.generationError && (
                  <Alert
                    type="error"
                    showIcon
                    message={current.generationError}
                  />
                )}
              {panelMode === 'view' ? (
                <>
                  <div className="sw-detail-sections">
                    {[
                      ['本周核心工作', current.coreWork],
                      ['KPI', current.kpiSection],
                      ['问题 / 风险', current.risks],
                      ['下周计划', current.nextWeekPlan],
                    ].map(([title, body]) => (
                      <section className="sw-detail-block" key={String(title)}>
                        <h3>{title}</h3>
                        <SimpleMarkdown
                          value={String(body || '')}
                          empty={
                            <Typography.Text type="secondary">暂无内容</Typography.Text>
                          }
                        />
                      </section>
                    ))}
                  </div>
                  <section className="sw-detail-minutes">
                    <h3>周会纪要</h3>
                    <SimpleMarkdown
                      value={current.minutesMarkdown || ''}
                      empty={
                        <Typography.Text type="secondary">暂无纪要</Typography.Text>
                      }
                    />
                  </section>
                  <Card
                    title="本周事实"
                    loading={factsLoading}
                    className="sw-editor-card"
                  >
                    <Row gutter={[10, 10]}>
                      {[
                        ['大事儿', facts?.keyMatters.length ?? 0, '项'],
                        ['商机跟进', facts?.opportunities?.length ?? 0, '条'],
                        [
                          '新增合同',
                          fmtWan(facts?.finance.newContractAmount),
                          '万元',
                        ],
                        [
                          '交付口径',
                          fmtWan(facts?.finance.deliveredAmount),
                          '万元',
                        ],
                      ].map(([label, value, suffix]) => (
                        <Col xs={12} md={6} key={String(label)}>
                          <div className="sw-fact-metric">
                            <Typography.Text type="secondary">
                              {label}
                            </Typography.Text>
                            <strong>{value}</strong>
                            <small>{suffix}</small>
                          </div>
                        </Col>
                      ))}
                    </Row>
                    {!!facts?.opportunities?.length && (
                      <div className="sw-detail-followups">
                        <Typography.Title level={5}>商机跟进</Typography.Title>
                        {facts.opportunities.map((item) => (
                          <article key={item.id} className="sw-detail-follow">
                            <strong>
                              {item.opportunityName || '未命名商机'}
                            </strong>
                            <span>
                              {item.customer || '—'} · {item.follower || item.owner || '—'}
                              {item.status ? ` · ${item.status}` : ''}
                            </span>
                            <p>{item.content || '—'}</p>
                          </article>
                        ))}
                      </div>
                    )}
                    {!!facts?.keyMatters.length && (
                      <Table
                        size="small"
                        rowKey="id"
                        pagination={false}
                        className="sw-detail-matters"
                        dataSource={facts.keyMatters}
                        columns={[
                          { title: '大事儿', dataIndex: 'title', ellipsis: true },
                          { title: '负责人', dataIndex: 'ownerName', width: 90 },
                          { title: '状态', dataIndex: 'status', width: 90 },
                          {
                            title: '进度',
                            dataIndex: 'progress',
                            width: 70,
                            render: (value?: number) => `${value ?? '—'}%`,
                          },
                        ]}
                      />
                    )}
                  </Card>
                </>
              ) : (
                <>
              <Card
                title={
                  <span>
                    自动采集事实{' '}
                    <Typography.Text type="secondary">
                      {facts
                        ? `大事儿 ${facts.keyMatters.length} 项 · 商机 ${facts.opportunities?.length ?? 0} 条 · ${facts.finance.month}`
                        : ''}
                    </Typography.Text>
                  </span>
                }
                loading={factsLoading}
                extra={
                  facts && (
                    <Button
                      type="link"
                      onClick={() => setFactsExpanded((value) => !value)}
                    >
                      {factsExpanded ? '收起明细' : '展开明细'}
                    </Button>
                  )
                }
                className="sw-editor-card"
              >
                <Row gutter={[10, 10]}>
                  {[
                    ['大事儿跟踪', facts?.keyMatters.length ?? 0, '项'],
                    ['商机跟进', facts?.opportunities?.length ?? 0, '条'],
                    [
                      '新增合同',
                      fmtWan(facts?.finance.newContractAmount),
                      '万元',
                    ],
                    [
                      '交付口径',
                      fmtWan(facts?.finance.deliveredAmount),
                      '万元',
                    ],
                  ].map(([label, value, suffix]) => (
                    <Col xs={12} md={6} key={String(label)}>
                      <div className="sw-fact-metric">
                        <Typography.Text type="secondary">
                          {label}
                        </Typography.Text>
                        <strong>{value}</strong>
                        <small>{suffix}</small>
                      </div>
                    </Col>
                  ))}
                </Row>
                {factsExpanded && facts && (
                  <>
                    <Table
                      size="small"
                      rowKey="id"
                      pagination={false}
                      dataSource={facts.keyMatters}
                      columns={[
                        { title: '事项', dataIndex: 'title', ellipsis: true },
                        { title: '负责人', dataIndex: 'ownerName', width: 90 },
                        { title: '状态', dataIndex: 'status', width: 90 },
                        {
                          title: '进度',
                          dataIndex: 'progress',
                          width: 70,
                          render: (value?: number) => `${value ?? '—'}%`,
                        },
                      ]}
                    />
                    {!!facts.opportunities?.length && (
                      <Table
                        size="small"
                        rowKey="id"
                        pagination={false}
                        className="sw-detail-matters"
                        dataSource={facts.opportunities}
                        columns={[
                          {
                            title: '商机',
                            dataIndex: 'opportunityName',
                            ellipsis: true,
                          },
                          { title: '跟进人', dataIndex: 'follower', width: 90 },
                          { title: '阶段', dataIndex: 'status', width: 90 },
                          {
                            title: '跟进内容',
                            dataIndex: 'content',
                            ellipsis: true,
                          },
                        ]}
                      />
                    )}
                    <Typography.Paragraph
                      type="secondary"
                      className="sw-last-week"
                    >
                      上周计划：
                      {facts.lastWeekReport.exists
                        ? facts.lastWeekReport.nextWeekPlan || '无'
                        : '暂无'}
                    </Typography.Paragraph>
                  </>
                )}
              </Card>
              <Card
                title="人工输入"
                extra={
                  <Button
                    size="small"
                    loading={savingInputs}
                    disabled={!editable}
                    onClick={() => void saveInputs()}
                  >
                    保存输入
                  </Button>
                }
                className="sw-editor-card"
              >
                <Form layout="vertical">
                  <Form.Item label="企微智能总结">
                    <Input.TextArea
                      value={inputDraft.wecomSummary}
                      onChange={(event) =>
                        setInputDraft((draftValue) => ({
                          ...draftValue,
                          wecomSummary: event.target.value,
                        }))
                      }
                      rows={4}
                      disabled={!editable}
                      placeholder="粘贴企微智能总结…"
                    />
                  </Form.Item>
                  <Form.Item label="人为补充信息">
                    <Input.TextArea
                      value={inputDraft.manualNotes}
                      onChange={(event) =>
                        setInputDraft((draftValue) => ({
                          ...draftValue,
                          manualNotes: event.target.value,
                        }))
                      }
                      rows={3}
                      disabled={!editable}
                      placeholder="可选"
                    />
                  </Form.Item>
                </Form>
              </Card>
              <Card
                title={
                  <span>
                    周报（四段）{' '}
                    <Typography.Text type="secondary">
                      纯文本，可直接复制提交企微汇报
                    </Typography.Text>
                  </span>
                }
                className="sw-editor-card"
              >
                {[
                  ['本周核心工作完成情况', 'coreWork', 8],
                  ['KPI相关情况', 'kpiSection', 6],
                  ['问题/风险与解决办法', 'risks', 5],
                  ['下周工作计划', 'nextWeekPlan', 5],
                ].map(([label, key, rows]) => (
                  <Form.Item label={label} key={String(key)}>
                    <Input.TextArea
                      value={draft[key as keyof typeof draft]}
                      onChange={(event) =>
                        setDraft((draftValue) => ({
                          ...draftValue,
                          [key as string]: event.target.value,
                        }))
                      }
                      rows={Number(rows)}
                      disabled={!editable}
                    />
                  </Form.Item>
                ))}
              </Card>
              <Card
                title={
                  <span>
                    周会纪要{' '}
                    <Typography.Text type="secondary">
                      Markdown，发布至语雀部门会议目录
                    </Typography.Text>
                  </span>
                }
                className="sw-editor-card"
              >
                <Input.TextArea
                  value={draft.minutesMarkdown}
                  onChange={(event) =>
                    setDraft((draftValue) => ({
                      ...draftValue,
                      minutesMarkdown: event.target.value,
                    }))
                  }
                  rows={14}
                  disabled={!editable}
                />
              </Card>
                </>
              )}
            </div>
          )
        )}
      </Drawer>

      <Drawer
        title="发布与同步"
        size={460}
        open={publishOpen}
        onClose={() => setPublishOpen(false)}
      >
        {publishTarget && (
          <div className="sw-publish-drawer">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="周期间">
                {rangeText(publishTarget)}
              </Descriptions.Item>
              <Descriptions.Item label="语雀状态">
                <Tag color={publishTarget.yuqueDocUrl ? 'success' : 'default'}>
                  {publishTarget.yuqueDocUrl ? '已发布' : '未发布'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="汇总表状态">
                <Tag
                  color={
                    publishTarget.sheetSyncStatus === 'MANUAL_DONE'
                      ? 'success'
                      : 'default'
                  }
                >
                  {publishTarget.sheetSyncStatus === 'MANUAL_DONE'
                    ? '已回填'
                    : '待回填'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="企微状态">
                <Tag
                  color={
                    publishTarget.wecomPushStatus === 'SUCCESS'
                      ? 'success'
                      : 'default'
                  }
                >
                  {publishTarget.wecomPushStatus === 'SUCCESS'
                    ? '已推送'
                    : '未推送'}
                </Tag>
              </Descriptions.Item>
            </Descriptions>
            <Card title="语雀发布" size="small" className="sw-publish-card">
              <Typography.Paragraph type="secondary">
                周会纪要发布至部门会议目录。
              </Typography.Paragraph>
              {publishTarget.yuqueDocUrl && (
                <Typography.Paragraph>
                  <a
                    href={publishTarget.yuqueDocUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    打开已发布文档
                  </a>
                </Typography.Paragraph>
              )}
              <Button
                type="primary"
                size="small"
                loading={publishing}
                disabled={!publishTarget.minutesMarkdown}
                onClick={() => void publishYuque()}
              >
                发布语雀
              </Button>
            </Card>
            <Card title="汇总表回填" size="small" className="sw-publish-card">
              <Typography.Paragraph type="secondary">
                公司汇总表不在当前语雀 Token 权限内，需人工粘贴。目标：
                {publishTarget.sheetTargetInfo?.dateRangeLabel || ''} ·{' '}
                {publishTarget.sheetTargetInfo?.teamName || ''} · K 列
              </Typography.Paragraph>
              <Space wrap>
                <Button
                  size="small"
                  icon={<CopyOutlined />}
                  disabled={!publishTarget.yuqueDocUrl}
                  onClick={() => void copyMinutesLink()}
                >
                  复制纪要链接
                </Button>
                {publishTarget.sheetTargetInfo?.sheetUrl && (
                  <Button
                    size="small"
                    href={String(publishTarget.sheetTargetInfo.sheetUrl)}
                    target="_blank"
                  >
                    打开汇总表
                  </Button>
                )}
                <Button
                  size="small"
                  type="primary"
                  loading={markingSheet}
                  disabled={!publishTarget.yuqueDocUrl}
                  onClick={markSheet}
                >
                  标记已回填
                </Button>
              </Space>
            </Card>
            <Card title="企微推送" size="small" className="sw-publish-card">
              <Typography.Paragraph type="secondary">
                推送终稿内容提醒。
              </Typography.Paragraph>
              <Button
                size="small"
                type="primary"
                danger
                icon={<SendOutlined />}
                loading={pushing}
                disabled={
                  !['CONFIRMED', 'PUBLISHED'].includes(publishTarget.status)
                }
                onClick={() => void pushWecom()}
              >
                推送企微
              </Button>
            </Card>
          </div>
        )}
      </Drawer>
    </div>
  );
}
