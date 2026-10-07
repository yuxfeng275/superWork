import {
  CheckCircleOutlined,
  CopyOutlined,
  EditOutlined,
  LinkOutlined,
  LoadingOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Drawer,
  Input,
  Modal,
  message,
  Row,
  Skeleton,
  Space,
  Steps,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { SimpleMarkdown } from '@/components/SimpleMarkdown';
import {
  superworkApi,
  type WeeklyReportFacts,
  type WeeklyReportGenerationModel,
  type WeeklyReportVO,
} from '@/services/superwork/api';
import { fmtWan, rangeText, statusOf } from './shared';

/** 正文段落定义：key 对应 WeeklyReportVO / 保存接口字段 */
const SECTION_DEFS = [
  { key: 'coreWork', title: '本周核心工作完成情况', rows: 8, markdown: false },
  { key: 'kpiSection', title: 'KPI相关情况', rows: 6, markdown: false },
  { key: 'risks', title: '问题/风险与解决办法', rows: 5, markdown: false },
  { key: 'nextWeekPlan', title: '下周工作计划', rows: 5, markdown: false },
  { key: 'minutesMarkdown', title: '周会纪要', rows: 12, markdown: true },
] as const;
type SectionKey = (typeof SECTION_DEFS)[number]['key'];

type Draft = Record<SectionKey, string>;

const EMPTY_DRAFT: Draft = {
  coreWork: '',
  kpiSection: '',
  risks: '',
  nextWeekPlan: '',
  minutesMarkdown: '',
};

/** 状态 → 步骤条进度：0 生成草稿 / 1 编辑确认 / 2 发布 */
const stepOf = (report: WeeklyReportVO) => {
  switch (report.status) {
    case 'PENDING':
    case 'GENERATING':
      return { current: 0, status: 'process' as const };
    case 'GENERATION_FAILED':
      return { current: 0, status: 'error' as const };
    case 'DRAFT':
      return { current: 1, status: 'process' as const };
    case 'CONFIRMED':
      return { current: 2, status: 'process' as const };
    case 'PUBLISHED':
      return { current: 2, status: 'finish' as const };
    default:
      return { current: 0, status: 'process' as const };
  }
};

interface Props {
  /** 周一日期 YYYY-MM-DD；null 时抽屉不渲染内容 */
  weekStart: string | null;
  open: boolean;
  /** publish = 打开后右侧栏滚动到发布通道卡 */
  anchor?: 'content' | 'publish';
  /** true = 加载完成后自动触发一次 AI 生成（新建周报的「创建并立即生成」） */
  autoGenerate?: boolean;
  onClose: () => void;
}

/**
 * 周报详情双栏工作台（方案甲）：
 * 左栏 = 文档式正文（四段 + 纪要，查看/编辑一体，hover 段落出现编辑，保存即生效）；
 * 右栏 = 固定侧栏（事实采集 / 人工输入 / 操作 / 发布通道），不随左栏滚动。
 * 顶部 = 周期 + 状态步骤条（生成草稿 → 编辑确认 → 发布）。
 */
export default function ReportDrawer({
  weekStart,
  open,
  anchor,
  autoGenerate,
  onClose,
}: Props) {
  const [current, setCurrent] = useState<WeeklyReportVO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [facts, setFacts] = useState<WeeklyReportFacts | null>(null);
  const [factsLoading, setFactsLoading] = useState(false);
  const [factsExpanded, setFactsExpanded] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [inputDraft, setInputDraft] = useState({
    wecomSummary: '',
    manualNotes: '',
    generationPrompt: '',
  });
  const [editingSection, setEditingSection] = useState<SectionKey | null>(null);
  const [sectionDraft, setSectionDraft] = useState('');
  const [savingSection, setSavingSection] = useState(false);
  const [savingInputs, setSavingInputs] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [markingSheet, setMarkingSheet] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [generationModel, setGenerationModel] =
    useState<WeeklyReportGenerationModel | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const publishRef = useRef<HTMLDivElement | null>(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = undefined;
    }
  };

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
      generationPrompt: report.generationPrompt || '',
    });
  }, []);

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
          }
        } catch {
          stopPolling();
        }
      }, 3000);
    },
    [applyReport],
  );

  useEffect(() => {
    if (!open || !weekStart) return undefined;
    setCurrent(null);
    setFacts(null);
    setFactsExpanded(false);
    setEditingSection(null);
    setDetailLoading(true);
    setFactsLoading(true);
    superworkApi
      .getWeeklyReport(weekStart)
      .then((report) => {
        applyReport(report);
        if (report.status === 'GENERATING') {
          startPolling(report.weekStartDate);
        } else if (autoGenerate) {
          // 「创建并立即生成」：加载后直接触发一次生成（generate 内部先落库输入再触发）
          void superworkApi
            .generateWeeklyReport(report.id)
            .then((next) => {
              setCurrent(next);
              message.info('AI 生成中，约需 30-60 秒');
              startPolling(next.weekStartDate);
            })
            .catch((e) =>
              message.error(e instanceof Error ? e.message : '触发生成失败'),
            );
        }
      })
      .catch((e) =>
        message.error(e instanceof Error ? e.message : '周报加载失败'),
      )
      .finally(() => setDetailLoading(false));
    superworkApi
      .getWeeklyFacts(weekStart)
      .then(setFacts)
      .catch(() => setFacts(null))
      .finally(() => setFactsLoading(false));
    superworkApi
      .getWeeklyGenerationModel()
      .then(setGenerationModel)
      .catch(() => setGenerationModel(null));
    return stopPolling;
  }, [open, weekStart, autoGenerate, applyReport, startPolling]);

  // 打开发布锚点：右侧栏滚动到发布通道卡
  useEffect(() => {
    if (open && anchor === 'publish' && current && !detailLoading) {
      publishRef.current?.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      });
    }
  }, [open, anchor, current, detailLoading]);

  const editable = current?.editable ?? false;
  const isGenerating = current?.status === 'GENERATING';

  const saveInputs = async () => {
    if (!current) return;
    setSavingInputs(true);
    try {
      setCurrent(await superworkApi.saveWeeklyInputs(current.id, inputDraft));
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
      // 先把企微总结/补充信息/生成提示词落库，保证本次生成使用最新输入
      await superworkApi.saveWeeklyInputs(current.id, inputDraft);
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

  const startEditSection = (key: SectionKey) => {
    if (!editable) return;
    setEditingSection(key);
    setSectionDraft(draft[key]);
  };
  const saveSection = async () => {
    if (!current || !editingSection) return;
    setSavingSection(true);
    try {
      const nextDraft = { ...draft, [editingSection]: sectionDraft };
      setCurrent(await superworkApi.saveWeeklyContent(current.id, nextDraft));
      setDraft(nextDraft);
      setEditingSection(null);
      message.success('已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingSection(false);
    }
  };

  const confirmReport = async () => {
    if (!current) return;
    setConfirming(true);
    try {
      setCurrent(await superworkApi.confirmWeeklyReport(current.id));
      message.success('周报已确认');
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

  const publishYuque = async () => {
    if (!current) return;
    setPublishing(true);
    try {
      setCurrent(await superworkApi.publishWeeklyYuque(current.id));
      message.success('已发布语雀');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '发布失败');
    } finally {
      setPublishing(false);
    }
  };
  const copyMinutesLink = async () => {
    const url = current?.yuqueDocUrl;
    if (!url) {
      message.warning('请先发布语雀纪要');
      return;
    }
    await navigator.clipboard.writeText(url);
    message.success('纪要链接已复制');
  };
  const markSheet = () => {
    if (!current) return;
    const info = current.sheetTargetInfo;
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
          setCurrent(await superworkApi.publishWeeklySheet(current.id));
          message.success('已标记回填完成');
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
    if (!current) return;
    setPushing(true);
    try {
      setCurrent(await superworkApi.pushWeeklyWecom(current.id));
      message.success('已推送企微');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '推送失败');
    } finally {
      setPushing(false);
    }
  };

  const step = current ? stepOf(current) : null;

  const renderSection = (def: (typeof SECTION_DEFS)[number]) => {
    const editing = editingSection === def.key;
    return (
      <section
        key={def.key}
        className={`sw-wr-section${editing ? ' editing' : ''}`}
      >
        <header>
          <h3>{def.title}</h3>
          {!editing && editable && !isGenerating && (
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              className="sw-wr-section-edit"
              onClick={() => startEditSection(def.key)}
            />
          )}
        </header>
        {editing ? (
          <>
            <Input.TextArea
              autoFocus
              value={sectionDraft}
              onChange={(event) => setSectionDraft(event.target.value)}
              rows={def.rows}
            />
            <Space style={{ marginTop: 8 }}>
              <Button
                type="primary"
                size="small"
                loading={savingSection}
                onClick={() => void saveSection()}
              >
                保存
              </Button>
              <Button size="small" onClick={() => setEditingSection(null)}>
                取消
              </Button>
            </Space>
          </>
        ) : (
          <div
            className="sw-wr-section-body"
            onClick={() => startEditSection(def.key)}
          >
            <SimpleMarkdown
              value={draft[def.key]}
              empty={
                <Typography.Text type="secondary">
                  {editable ? '暂无内容，点击编辑' : '暂无内容'}
                </Typography.Text>
              }
            />
          </div>
        )}
      </section>
    );
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={Math.min(1280, window.innerWidth - 24)}
      styles={{
        body: { padding: 0, display: 'flex', flexDirection: 'column' },
      }}
      title={
        current && (
          <Space size={12} wrap>
            <span>{rangeText(current)} 周报</span>
            <Tag color={statusOf(current.status).color}>
              {statusOf(current.status).label}
            </Tag>
            {current.generationModel && (
              <Tooltip
                title={`生成模型：${
                  current.generationProviderName ||
                  current.generationProvider ||
                  ''
                } / ${current.generationModel}`}
              >
                <Tag icon={<ThunderboltOutlined />} color="geekblue">
                  AI ·{' '}
                  {current.generationProviderName ||
                    current.generationProvider ||
                    ''}{' '}
                  {current.generationModel}
                </Tag>
              </Tooltip>
            )}
          </Space>
        )
      }
      extra={
        current &&
        step && (
          <Steps
            size="small"
            className="sw-wr-steps"
            current={step.current}
            status={step.status}
            items={[
              {
                title: '生成草稿',
                icon: isGenerating ? <LoadingOutlined /> : undefined,
              },
              { title: '编辑确认' },
              { title: '发布' },
            ]}
          />
        )
      }
    >
      {detailLoading ? (
        <div className="sw-report-loading">
          <LoadingOutlined /> 正在加载周报...
        </div>
      ) : !current ? null : (
        <div className="sw-wr-drawer">
          {/* 左栏：文档式正文 */}
          <div className="sw-wr-main">
            {current.status === 'GENERATION_FAILED' &&
              current.generationError && (
                <Alert
                  type="error"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message={current.generationError}
                />
              )}
            {isGenerating ? (
              <Card variant="borderless" className="sw-wr-generating">
                <Skeleton active paragraph={{ rows: 8 }} />
                <Typography.Text type="secondary">
                  AI 正在基于本周事实生成草稿，约 30-60 秒，完成后自动刷新…
                </Typography.Text>
              </Card>
            ) : (
              SECTION_DEFS.map(renderSection)
            )}
          </div>

          {/* 右栏：固定侧栏 */}
          <aside className="sw-wr-side">
            <Card
              size="small"
              title={
                <span>
                  本周事实{' '}
                  <Typography.Text type="secondary" style={{ fontWeight: 400 }}>
                    {facts
                      ? `大事儿 ${facts.keyMatters.length} 项 · 商机 ${
                          facts.opportunities?.length ?? 0
                        } 条`
                      : ''}
                  </Typography.Text>
                </span>
              }
              extra={
                facts && (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => setFactsExpanded((v) => !v)}
                  >
                    {factsExpanded ? '收起' : '明细'}
                  </Button>
                )
              }
              loading={factsLoading}
              className="sw-wr-side-card"
            >
              <Row gutter={[8, 8]}>
                {[
                  ['大事儿', facts?.keyMatters.length ?? 0, '项'],
                  ['商机跟进', facts?.opportunities?.length ?? 0, '条'],
                  ['新增合同', fmtWan(facts?.finance.newContractAmount), '万'],
                  ['交付口径', fmtWan(facts?.finance.deliveredAmount), '万'],
                ].map(([label, value, suffix]) => (
                  <Col span={12} key={String(label)}>
                    <div className="sw-fact-metric">
                      <Typography.Text type="secondary">
                        {label}
                      </Typography.Text>
                      <div>
                        <strong>{value}</strong> <small>{suffix}</small>
                      </div>
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
                    style={{ marginTop: 10 }}
                    dataSource={facts.keyMatters}
                    columns={[
                      { title: '事项', dataIndex: 'title', ellipsis: true },
                      { title: '负责人', dataIndex: 'ownerName', width: 80 },
                      {
                        title: '进度',
                        dataIndex: 'progress',
                        width: 60,
                        render: (v?: number) => `${v ?? '—'}%`,
                      },
                    ]}
                  />
                  {!!facts.opportunities?.length && (
                    <Table
                      size="small"
                      rowKey="id"
                      pagination={false}
                      style={{ marginTop: 10 }}
                      dataSource={facts.opportunities}
                      columns={[
                        {
                          title: '商机',
                          dataIndex: 'opportunityName',
                          ellipsis: true,
                        },
                        {
                          title: '跟进人',
                          dataIndex: 'follower',
                          width: 80,
                        },
                        {
                          title: '内容',
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
              size="small"
              title="人工输入与生成指引"
              extra={
                <Button
                  size="small"
                  type="link"
                  loading={savingInputs}
                  disabled={!editable}
                  onClick={() => void saveInputs()}
                >
                  保存输入
                </Button>
              }
              className="sw-wr-side-card"
            >
              {!generationModel?.configured && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 8 }}
                  message="尚未配置可用的摘要模型，AI 生成会失败"
                />
              )}
              <Typography.Text type="secondary" className="sw-wr-input-label">
                企微智能总结
              </Typography.Text>
              <Input.TextArea
                value={inputDraft.wecomSummary}
                onChange={(e) =>
                  setInputDraft((v) => ({ ...v, wecomSummary: e.target.value }))
                }
                rows={3}
                disabled={!editable}
                placeholder="粘贴企微智能总结…"
              />
              <Typography.Text type="secondary" className="sw-wr-input-label">
                人为补充信息
              </Typography.Text>
              <Input.TextArea
                value={inputDraft.manualNotes}
                onChange={(e) =>
                  setInputDraft((v) => ({ ...v, manualNotes: e.target.value }))
                }
                rows={2}
                disabled={!editable}
                placeholder="可选"
              />
              <Tooltip title="告诉 AI 本周周报的侧重点，生成时与事实一起提交">
                <Typography.Text type="secondary" className="sw-wr-input-label">
                  生成指引（提示词）
                </Typography.Text>
              </Tooltip>
              <Input.TextArea
                value={inputDraft.generationPrompt}
                onChange={(e) =>
                  setInputDraft((v) => ({
                    ...v,
                    generationPrompt: e.target.value,
                  }))
                }
                rows={2}
                disabled={!editable}
                placeholder="可选：如「重点写 XX 项目风险，弱化日常迭代」"
              />
            </Card>

            <Card size="small" title="操作" className="sw-wr-side-card">
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <Button
                  block
                  type="primary"
                  icon={<ThunderboltOutlined />}
                  loading={generating || isGenerating}
                  disabled={!editable}
                  onClick={() => void generate()}
                >
                  生成 / 重新生成
                </Button>
                <Button
                  block
                  icon={<CheckCircleOutlined />}
                  loading={confirming}
                  disabled={!editable || current.status === 'CONFIRMED'}
                  onClick={() => void confirmReport()}
                >
                  确认终稿
                </Button>
                <Button
                  block
                  icon={<CopyOutlined />}
                  onClick={() => void copyReport()}
                >
                  复制全文
                </Button>
              </Space>
            </Card>

            <Card
              size="small"
              title="发布通道"
              className="sw-wr-side-card"
              ref={publishRef}
            >
              <div className="sw-wr-channel">
                <span
                  className={`sw-wr-dot ${
                    current.yuqueDocUrl ? 'success' : 'default'
                  }`}
                />
                <span className="sw-wr-channel-name">语雀纪要</span>
                <Space size={4}>
                  {current.yuqueDocUrl && (
                    <Button
                      size="small"
                      type="link"
                      icon={<LinkOutlined />}
                      href={current.yuqueDocUrl}
                      target="_blank"
                    />
                  )}
                  <Button
                    size="small"
                    loading={publishing}
                    disabled={!current.minutesMarkdown}
                    onClick={() => void publishYuque()}
                  >
                    {current.yuqueDocUrl ? '重新发布' : '发布'}
                  </Button>
                </Space>
              </div>
              <div className="sw-wr-channel">
                <span
                  className={`sw-wr-dot ${
                    current.sheetSyncStatus === 'MANUAL_DONE'
                      ? 'success'
                      : 'default'
                  }`}
                />
                <Tooltip
                  title={`目标：${
                    current.sheetTargetInfo?.dateRangeLabel || ''
                  } · ${current.sheetTargetInfo?.teamName || ''} · K 列（需人工粘贴）`}
                >
                  <span className="sw-wr-channel-name">汇总表回填</span>
                </Tooltip>
                <Space size={4}>
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    disabled={!current.yuqueDocUrl}
                    onClick={() => void copyMinutesLink()}
                  />
                  <Button
                    size="small"
                    loading={markingSheet}
                    disabled={!current.yuqueDocUrl}
                    onClick={markSheet}
                  >
                    {current.sheetSyncStatus === 'MANUAL_DONE'
                      ? '已回填'
                      : '标记回填'}
                  </Button>
                </Space>
              </div>
              <div className="sw-wr-channel">
                <span
                  className={`sw-wr-dot ${
                    current.wecomPushStatus === 'SUCCESS'
                      ? 'success'
                      : 'default'
                  }`}
                />
                <span className="sw-wr-channel-name">企微推送</span>
                <Button
                  size="small"
                  danger
                  icon={<SendOutlined />}
                  loading={pushing}
                  disabled={
                    !['CONFIRMED', 'PUBLISHED'].includes(current.status)
                  }
                  onClick={() => void pushWecom()}
                >
                  推送
                </Button>
              </div>
            </Card>
          </aside>
        </div>
      )}
    </Drawer>
  );
}
