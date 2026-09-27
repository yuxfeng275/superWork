import {
  DownloadOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  InputNumber,
  message,
  Modal,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import SyncCutoff from '@/components/SyncCutoff';
import {
  type ProjectProfitBlock,
  type ProjectProfitLine,
  type ProjectProfitMonthSyncResult,
  type ProjectProfitReport,
  type ProjectProfitRow,
  superworkApi,
  type WorktimeSyncLog,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const currentYear = new Date().getFullYear();
const yearOptions = [currentYear - 1, currentYear, currentYear + 1].map(
  (value) => ({ label: `${value}年`, value }),
);

type ViewMode = 'month' | 'H1' | 'H2' | 'YEAR';

/** 6 个可分配成本列：行字段 ↔ 后端 cost_type */
const COST_TYPES = [
  { key: 'smsCost', costType: 'sms', label: '短信成本' },
  { key: 'directCost', costType: 'direct', label: '直接成本' },
  { key: 'platformFee', costType: 'platform_fee', label: '平台佣金&手续费' },
  { key: 'compensation', costType: 'compensation', label: '赔付' },
  { key: 'outsourcing', costType: 'outsourcing', label: '协力&外包' },
  { key: 'softwareGift', costType: 'software_gift', label: '软件赠送' },
] as const;

type CostKey = (typeof COST_TYPES)[number]['key'];

const SYNC_TYPE_LABELS: Record<string, string> = {
  worklog: '工时明细',
  cost: '成本分析（含销售工时）',
  bl_profit: '业务线利润镜像',
  contract: '合同明细',
};

/** 合并单元格跨度：仅组内首行有值（= 组内行数），其余行为 0 表示被上行吞并 */
interface RowSpans {
  month: number;
  line: number;
  category: number;
}

interface FlatRow {
  key: string;
  block: ProjectProfitBlock;
  line: ProjectProfitLine;
  row: ProjectProfitRow;
  spans: RowSpans;
}

/** 连续相同取值的分组跨度（依赖行序：块 → 业务线 → 行） */
const spanCounts = <T,>(list: T[], keyOf: (item: T) => string) => {
  const spans = new Array<number>(list.length).fill(0);
  let start = 0;
  for (let i = 1; i <= list.length; i += 1) {
    if (i === list.length || keyOf(list[i]) !== keyOf(list[start])) {
      spans[start] = i - start;
      start = i;
    }
  }
  return spans;
};

const formatWan = (value?: number | null) => {
  if (value == null) return '—';
  const wan = Math.round((Number(value) / 10000) * 100) / 100;
  return wan.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};
const formatRate = (value?: number | null) =>
  value == null ? '—' : `${Number(value).toFixed(1)}%`;
const formatHours = (value?: number | null) => {
  if (value == null) return '—';
  return (Math.round(Number(value) * 100) / 100).toFixed(2);
};
/** 元（千分位、两位小数）：分配抽屉内与输入框同单位，避免与表格「万」列混填 */
const formatYuan = (value?: number | null) =>
  value == null
    ? '—'
    : Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
const isNegative = (value?: number | null) => value != null && Number(value) < 0;
const num =
  (render: (value?: number | null) => string) => (value: number | null) => (
    <Typography.Text type={isNegative(value) ? 'danger' : undefined}>
      {render(value)}
    </Typography.Text>
  );

/** 差额行仅当任一数值列非零（金额 ≥0.01 / 工时 ≥0.0001）时渲染 */
const residualVisible = (row: ProjectProfitRow) => {
  const amounts = [
    row.revenue,
    row.smsCost,
    row.directCost,
    row.platformFee,
    row.compensation,
    row.outsourcing,
    row.softwareGift,
    row.cost,
  ];
  return (
    amounts.some((v) => v != null && Math.abs(Number(v)) >= 0.01) ||
    (row.hours != null && Math.abs(Number(row.hours)) >= 0.0001)
  );
};

/** 同步目标月选项：选中年的 YYYY-01..12 且不大于当前自然月，降序 */
const syncMonthOptions = (year: number) => {
  const currentMonth = dayjs().format('YYYY-MM');
  const options: { label: string; value: string }[] = [];
  for (let m = 12; m >= 1; m -= 1) {
    const value = `${year}-${String(m).padStart(2, '0')}`;
    if (value <= currentMonth) options.push({ label: `${m}月`, value });
  }
  return options;
};

export default function ProjectProfitPage() {
  const [year, setYear] = useState(currentYear);
  const [viewMode, setViewMode] = useState<ViewMode>('month');
  const [selectedMonths, setSelectedMonths] = useState<number[]>([]);
  const [filterLineIds, setFilterLineIds] = useState<number[]>([]);
  const [filterCategories, setFilterCategories] = useState<string[]>([]);
  const [filterProjectIds, setFilterProjectIds] = useState<number[]>([]);
  const [report, setReport] = useState<ProjectProfitReport>();
  const [syncLogs, setSyncLogs] = useState<WorktimeSyncLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncingYear, setSyncingYear] = useState(false);
  const [error, setError] = useState('');
  const [syncTargetMonth, setSyncTargetMonth] = useState(() =>
    dayjs().subtract(1, 'month').format('YYYY-MM'),
  );
  const [syncResult, setSyncResult] = useState<ProjectProfitMonthSyncResult>();
  const [drawer, setDrawer] = useState<{
    yearMonth: string;
    line: ProjectProfitLine;
    row: ProjectProfitRow;
  }>();
  const [allocAmounts, setAllocAmounts] = useState<Record<string, number | null>>({});
  const [allocNote, setAllocNote] = useState('');
  const [savingAlloc, setSavingAlloc] = useState(false);
  // 表头吸顶：页面滚动容器是 .ant-layout-content，表格自带横向 overflow 容器会让 position:sticky 失效，
  // 因此用 scroll.y 固定表头，高度按「滚动容器可视高 − 表格在容器内的偏移 − 表头/内边距」动态计算。
  const tableCardRef = useRef<HTMLDivElement>(null);
  const [tableBodyHeight, setTableBodyHeight] = useState(320);

  const loadReport = useCallback(
    async (targetYear: number) => {
      setLoading(true);
      setError('');
      try {
        const result = await superworkApi.getProjectProfitReport({
          year: targetYear,
          months:
            viewMode === 'month' && selectedMonths.length > 0
              ? selectedMonths
              : undefined,
          periods: viewMode === 'month' ? undefined : [viewMode],
          businessLineIds: filterLineIds.length > 0 ? filterLineIds : undefined,
          categories: filterCategories.length > 0 ? filterCategories : undefined,
          projectIds: filterProjectIds.length > 0 ? filterProjectIds : undefined,
        });
        setReport(result);
        // 项目选项随业务线联动：业务线变化后剔除已失效的项目选择
        const validProjectIds = new Set(
          result.lineOptions.flatMap((line) =>
            line.projects.map((project) => project.projectId),
          ),
        );
        setFilterProjectIds((prev) => prev.filter((id) => validProjectIds.has(id)));
      } catch {
        setError('项目利润报表加载失败');
      } finally {
        setLoading(false);
      }
    },
    [viewMode, selectedMonths, filterLineIds, filterCategories, filterProjectIds],
  );

  const loadSyncLogs = useCallback(async () => {
    try {
      setSyncLogs(await superworkApi.getProjectProfitSyncLogs(10));
    } catch {
      setSyncLogs([]);
    }
  }, []);

  useEffect(() => {
    void loadReport(year);
  }, [loadReport, year]);
  useEffect(() => {
    void loadSyncLogs();
  }, [loadSyncLogs]);

  // 同步目标月默认上月；切换年份后若当前值不在可选范围则取第一个
  const monthOptions = useMemo(() => syncMonthOptions(year), [year]);
  useEffect(() => {
    if (!monthOptions.some((option) => option.value === syncTargetMonth)) {
      setSyncTargetMonth(monthOptions[0]?.value ?? '');
    }
  }, [monthOptions, syncTargetMonth]);

  // 表格可视高度：随 视图/筛选/错误提示/数据 变化重算，保证页面不出现外层滚动、表头常驻
  useEffect(() => {
    const compute = () => {
      const el = tableCardRef.current;
      if (!el) return;
      const scroller = el.closest('.ant-layout-content') as HTMLElement | null;
      const top = scroller
        ? el.getBoundingClientRect().top - scroller.getBoundingClientRect().top
        : el.getBoundingClientRect().top;
      const available = (scroller?.clientHeight ?? window.innerHeight) - top;
      // 预留：表头（可折行 ~32px）+ 卡片内边距（24）+ 底部留白（16）
      setTableBodyHeight(Math.max(220, Math.round(available - 74)));
    };
    compute();
    window.addEventListener('resize', compute);
    return () => window.removeEventListener('resize', compute);
  }, [report, viewMode, error, filterLineIds, filterCategories, filterProjectIds, selectedMonths]);

  // 月度一键同步（决策4）：完成后弹结果 Modal，关闭后刷新报表与日志
  const syncMonth = async () => {
    if (!syncTargetMonth) return;
    setSyncing(true);
    try {
      const result = await superworkApi.syncProjectProfitMonth(syncTargetMonth);
      setSyncResult(result);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const closeSyncResult = () => {
    setSyncResult(undefined);
    void Promise.all([loadReport(year), loadSyncLogs()]);
  };

  // 整年回填（次要按钮，行为与业务线利润页一致）
  const syncWholeYear = async () => {
    setSyncingYear(true);
    try {
      const logs = await superworkApi.syncProjectProfit({ year });
      const success = logs.filter((log) => log.status === 'success').length;
      const failed = logs.filter((log) => log.status === 'failed');
      if (failed.length > 0)
        message.warning(
          `同步完成：${success} 个月成功，${failed.length} 个月失败（${failed[0].scope}: ${failed[0].message ?? ''}）`,
        );
      else message.success(`同步完成：${success} 个月份已更新`);
      await Promise.all([loadReport(year), loadSyncLogs()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setSyncingYear(false);
    }
  };

  // 分配 Drawer：初始化 6 成本列当前项目已分配值
  const openDrawer = (block: ProjectProfitBlock, line: ProjectProfitLine, row: ProjectProfitRow) => {
    const amounts: Record<string, number | null> = {};
    COST_TYPES.forEach(({ key, costType }) => {
      amounts[costType] = row[key] == null ? null : Number(row[key]);
    });
    setAllocAmounts(amounts);
    setAllocNote('');
    setDrawer({ yearMonth: block.key, line, row });
  };

  const saveAllocations = async () => {
    if (!drawer || drawer.row.projectId == null) return;
    setSavingAlloc(true);
    try {
      await superworkApi.saveProjectProfitAllocations({
        yearMonth: drawer.yearMonth,
        businessLineId: drawer.line.businessLineId,
        projectId: drawer.row.projectId,
        items: COST_TYPES.map(({ costType }) => ({
          costType,
          amount: allocAmounts[costType] ?? 0,
          note: allocNote || null,
        })),
      });
      message.success('分配已保存');
      setDrawer(undefined);
      await loadReport(year);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingAlloc(false);
    }
  };

  // 平铺行：项目行… → 销售行 → 差额行（仅非零） → 合计行；月份/业务线/分类三列按相邻同值合并
  const dataSource = useMemo<FlatRow[]>(() => {
    if (!report) return [];
    const rows: Omit<FlatRow, 'spans'>[] = [];
    report.blocks.forEach((block) => {
      block.lines.forEach((line) => {
        line.rows.forEach((row) =>
          rows.push({ key: `${block.key}-${line.businessLineId}-${row.rowType}-${row.projectId ?? row.projectName}`, block, line, row }),
        );
        if (residualVisible(line.residual)) {
          rows.push({
            key: `${block.key}-${line.businessLineId}-RESIDUAL`,
            block,
            line,
            row: line.residual,
          });
        }
        rows.push({
          key: `${block.key}-${line.businessLineId}-TOTAL`,
          block,
          line,
          row: line.total,
        });
      });
    });
    const monthSpans = spanCounts(rows, (row) => row.block.key);
    const lineSpans = spanCounts(rows, (row) => `${row.block.key}|${row.line.businessLineId}`);
    const categorySpans = spanCounts(
      rows,
      (row) =>
        `${row.block.key}|${row.line.businessLineId}|${row.row.category ?? row.row.rowType}`,
    );
    return rows.map((row, index) => ({
      ...row,
      spans: {
        month: monthSpans[index],
        line: lineSpans[index],
        category: categorySpans[index],
      },
    }));
  }, [report]);

  const latestSyncLog = syncLogs[0];

  const projectOptions = useMemo(() => {
    if (!report) return [];
    const lines =
      filterLineIds.length > 0
        ? report.lineOptions.filter((line) => filterLineIds.includes(line.businessLineId))
        : report.lineOptions;
    return lines.flatMap((line) =>
      line.projects.map((project) => ({
        label: `${line.businessLineName} / ${project.projectName}`,
        value: project.projectId,
      })),
    );
  }, [report, filterLineIds]);

  const monthFilterOptions = useMemo(
    () =>
      (report?.availableMonths ?? []).map((yearMonth) => ({
        label: `${Number(yearMonth.slice(5))}月`,
        value: Number(yearMonth.slice(5)),
      })),
    [report],
  );

  const moneyColumn = (
    title: string,
    field: CostKey | 'revenue' | 'cost' | 'grossProfit',
    width: number,
  ) => ({
    title,
    width,
    align: 'right' as const,
    render: (_: unknown, record: FlatRow) => num(formatWan)(record.row[field]),
  });
  // 紧凑列宽：文本列固定窄宽 + 省略号，数值列按当前数据最大文本留 1~2 字符余量，合计宽度收敛到一屏内
  const columns: TableProps<FlatRow>['columns'] = [
    {
      title: '月份',
      width: 48,
      onCell: (record) => ({ rowSpan: record.spans.month }),
      render: (_, record) => record.block.label,
    },
    {
      title: '业务线',
      width: 108,
      ellipsis: true,
      onCell: (record) => ({ rowSpan: record.spans.line }),
      render: (_, record) => record.line.businessLineName,
    },
    {
      title: '分类',
      width: 44,
      onCell: (record) => ({ rowSpan: record.spans.category }),
      render: (_, record) => record.row.category ?? '—',
    },
    {
      title: '项目',
      width: 120,
      ellipsis: true,
      render: (_, record) =>
        record.row.rowType === 'TOTAL' ? '合计' : record.row.projectName ?? '—',
    },
    moneyColumn('营业收入', 'revenue', 74),
    moneyColumn('短信成本', 'smsCost', 60),
    moneyColumn('直接成本', 'directCost', 60),
    moneyColumn('平台佣金&手续费', 'platformFee', 64),
    moneyColumn('赔付', 'compensation', 44),
    moneyColumn('协力&外包', 'outsourcing', 58),
    moneyColumn('软件赠送', 'softwareGift', 58),
    {
      title: '工时',
      width: 60,
      align: 'right',
      render: (_, record) => num(formatHours)(record.row.hours),
    },
    moneyColumn('成本', 'cost', 74),
    moneyColumn('考核毛利', 'grossProfit', 74),
    {
      title: '考核毛利率(%)',
      width: 88,
      align: 'right',
      render: (_, record) => num(formatRate)(record.row.grossProfitRate),
    },
    {
      title: '操作',
      width: 48,
      fixed: 'right',
      render: (_, record) =>
        record.row.rowType === 'PROJECT' &&
        record.row.editable &&
        !['H1', 'H2', 'YEAR'].includes(record.block.key) ? (
          <Typography.Link
            className="sw-project-profit-alloc-link"
            onClick={() => openDrawer(record.block, record.line, record.row)}
          >
            分配
          </Typography.Link>
        ) : null,
    },
  ];

  // 表格总宽 = 各列宽之和：断言横向滚动量，避免固定 1600px 造成的无谓横向滚动
  const tableWidth = columns.reduce((sum, column) => sum + Number(column?.width ?? 0), 0);

  return (
    <div className="sw-page sw-project-profit">
      <div className="sw-page-header sw-project-profit-header">
        <div>
          <Space align="baseline" size={8} wrap={false}>
            <span className="sw-eyebrow">FINANCE</span>
            <Typography.Title level={4} style={{ margin: 0 }}>
              项目利润
            </Typography.Title>
            <Tooltip
              title={
                <span>
                  月份 × 业务线 × 分类 × 项目；合计取工时系统财报镜像（未税）；
                  项目行营收为 OA 已交付（含税 ÷(1+税率) 换算未税）；
                  差额行 = 合计 − 已显示明细行。收益单位：金额「万」，工时「人月」。
                </span>
              }
            >
              <InfoCircleOutlined className="sw-project-profit-info" />
            </Tooltip>
          </Space>
        </div>
        <SyncCutoff domains={['contract', 'worklog', 'cost']} />
        <Space wrap size={8}>
          <Select
            aria-label="选择年份"
            size="small"
            style={{ width: 92 }}
            value={year}
            options={yearOptions}
            onChange={(value) => setYear(value)}
          />
          <Segmented<ViewMode>
            size="small"
            value={viewMode}
            onChange={(value) => setViewMode(value)}
            options={[
              { label: '月度', value: 'month' },
              { label: 'H1', value: 'H1' },
              { label: 'H2', value: 'H2' },
              { label: '全年', value: 'YEAR' },
            ]}
          />
          <Button
            size="small"
            icon={<ReloadOutlined />}
            aria-label="刷新"
            onClick={() => void loadReport(year)}
          />
          <Button
            size="small"
            icon={<DownloadOutlined />}
            loading={syncingYear}
            onClick={() => void syncWholeYear()}
          >
            同步工时系统
          </Button>
          <Select
            aria-label="同步月份"
            size="small"
            style={{ width: 92 }}
            value={syncTargetMonth || undefined}
            options={monthOptions}
            placeholder="月份"
            onChange={(value) => setSyncTargetMonth(value)}
          />
          <Button
            size="small"
            type="primary"
            icon={<DownloadOutlined />}
            loading={syncing}
            disabled={!syncTargetMonth}
            onClick={() => void syncMonth()}
          >
            同步月度数据
          </Button>
        </Space>
      </div>

      {latestSyncLog && (
        <Typography.Paragraph
          className="sw-project-profit-sync-status"
          type={latestSyncLog.status === 'failed' ? 'danger' : 'secondary'}
        >
          最近同步：{latestSyncLog.scope || '—'} ·{' '}
          {latestSyncLog.status === 'failed'
            ? `失败：${latestSyncLog.message ?? ''}`
            : `成功 ${latestSyncLog.upsertCount ?? 0} 行`}{' '}
          · {latestSyncLog.finishedAt || latestSyncLog.startedAt || ''}
        </Typography.Paragraph>
      )}

      {error && (
        <Alert
          type="error"
          message={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={() => void loadReport(year)}>
              重试
            </Button>
          }
        />
      )}

      {report && report.availableMonths.length > 0 && (
        <>
          <Space wrap size={8} style={{ marginBottom: 8 }}>
            {viewMode === 'month' && (
              <Select
                mode="multiple"
                allowClear
                size="small"
                placeholder="月份（默认全部）"
                style={{ minWidth: 180 }}
                value={selectedMonths}
                options={monthFilterOptions}
                onChange={(value) => setSelectedMonths(value)}
              />
            )}
            <Select
              mode="multiple"
              allowClear
              size="small"
              placeholder="分类（项目/销售）"
              style={{ minWidth: 148 }}
              value={filterCategories}
              options={[
                { label: '项目', value: '项目' },
                { label: '销售', value: '销售' },
              ]}
              onChange={(value) => setFilterCategories(value)}
            />
            <Select
              mode="multiple"
              allowClear
              size="small"
              placeholder="项目（仅过滤项目行）"
              style={{ minWidth: 220 }}
              value={filterProjectIds}
              options={projectOptions}
              onChange={(value) => setFilterProjectIds(value)}
              showSearch
              optionFilterProp="label"
            />
          </Space>
          <div className="sw-project-profit-pills">
            <button
              type="button"
              className={filterLineIds.length === 0 ? 'is-active' : ''}
              onClick={() => setFilterLineIds([])}
            >
              全部业务线
            </button>
            {report.lineOptions.map((line) => (
              <button
                key={line.businessLineId}
                type="button"
                className={filterLineIds.includes(line.businessLineId) ? 'is-active' : ''}
                onClick={() =>
                  setFilterLineIds((prev) =>
                    prev.includes(line.businessLineId)
                      ? prev.filter((id) => id !== line.businessLineId)
                      : [...prev, line.businessLineId],
                  )
                }
              >
                {line.businessLineName}
              </button>
            ))}
          </div>
        </>
      )}

      {report && report.availableMonths.length > 0 ? (
        <>
          {viewMode !== 'month' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="成本分配为月度粒度：H1/H2/全年 视图不提供「分配」入口，需要分配时切回月度视图。"
              action={
                <Button size="small" onClick={() => setViewMode('month')}>
                  切到月度
                </Button>
              }
            />
          )}
          <Card
            variant="borderless"
            className="sw-table-card"
            ref={tableCardRef}
            styles={{ body: { padding: 12 } }}
          >
            <Table<FlatRow>
              columns={columns}
              dataSource={dataSource}
              loading={loading}
              pagination={false}
              scroll={{ x: tableWidth, y: tableBodyHeight }}
              size="small"
              rowClassName={(record) =>
                record.row.rowType === 'TOTAL'
                  ? 'sw-project-profit-total-row'
                  : record.row.rowType === 'RESIDUAL'
                    ? 'sw-project-profit-residual-row'
                    : ''
              }
            />
          </Card>
        </>
      ) : (
        !loading &&
        !error && (
          <Empty description="暂无数据，请先用右上角「同步月度数据」选择月份拉取工时系统数据" />
        )
      )}

      <Modal
        open={!!syncResult}
        title={`月度同步结果（${syncResult?.yearMonth ?? ''}）`}
        footer={<Button type="primary" onClick={closeSyncResult}>关闭</Button>}
        onCancel={closeSyncResult}
        width={640}
      >
        {syncResult?.monthClosed && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="该月已完结，工时/成本未重拉，仅刷新了利润镜像"
          />
        )}
        {syncResult?.logs.some((log) => log.status === 'failed') && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="部分同步失败，对齐结果基于现有数据计算"
          />
        )}
        <Typography.Title level={5}>同步步骤</Typography.Title>
        <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
          {syncResult?.logs.map((log) => (
            <div key={log.id ?? log.syncType}>
              <Tag color={log.status === 'success' ? 'green' : 'red'}>
                {log.status === 'success' ? '成功' : '失败'}
              </Tag>
              {SYNC_TYPE_LABELS[log.syncType] ?? log.syncType} · 写入 {log.upsertCount ?? 0} 行
              {log.status === 'failed' && log.message && (
                <Typography.Text type="danger">（{log.message}）</Typography.Text>
              )}
            </div>
          ))}
        </Space>
        <Typography.Title level={5}>逐业务线对齐</Typography.Title>
        {syncResult && syncResult.lines.length > 0 ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            {syncResult.lines.map((line) => (
              <div key={line.businessLineId}>
                {line.aligned ? (
                  <>
                    <Tag color="green">✓ 已对齐</Tag>
                    {line.businessLineName}
                  </>
                ) : (
                  <>
                    <Tag color="orange">有差额</Tag>
                    {line.businessLineName}：营收差额 {num(formatWan)(line.revenueResidual)} 万 · 成本差额{' '}
                    {num(formatWan)(line.costResidual)} 万 · 工时差额 {num(formatHours)(line.hoursResidual)} 人月
                  </>
                )}
              </div>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">该月无镜像数据，未产生对齐结果</Typography.Text>
        )}
      </Modal>

      <Drawer
        open={!!drawer}
        width={480}
        title={
          drawer
            ? `成本分配 · ${drawer.yearMonth} · ${drawer.line.businessLineName} / ${drawer.row.projectName}`
            : ''
        }
        onClose={() => setDrawer(undefined)}
        extra={
          <Button type="primary" loading={savingAlloc} onClick={() => void saveAllocations()}>
            保存
          </Button>
        }
      >
        {drawer && (
          <>
            <Typography.Paragraph type="secondary">
              录入单位：<Typography.Text strong>元</Typography.Text>（财报未税口径，两位小数）。表格里的成本列以「万」展示，
              1 万 = 10,000 元。「未分配」= 该月该业务线合计 − 各项目行已分配之和，保存后差额行联动。
            </Typography.Paragraph>
            {COST_TYPES.map(({ key, costType, label }) => {
              const total = Number(drawer.line.total[key] ?? 0);
              const allocated = drawer.line.rows
                .filter((row) => row.rowType === 'PROJECT')
                .reduce((sum, row) => sum + Number(row[key] ?? 0), 0);
              const balance = total - allocated;
              return (
                <div key={costType} className="sw-project-profit-alloc-item">
                  <div className="sw-project-profit-alloc-meta">
                    <span className="sw-project-profit-alloc-label">{label}</span>
                    <span className="sw-project-profit-alloc-nums">
                      合计 {formatYuan(total)} · 已分配 {formatYuan(allocated)} · 未分配{' '}
                      <Typography.Text type={Math.abs(balance) > 0.005 ? 'warning' : undefined}>
                        {formatYuan(balance)}
                      </Typography.Text>
                      （元）
                    </span>
                  </div>
                  <InputNumber
                    style={{ width: '100%' }}
                    value={allocAmounts[costType]}
                    placeholder="0.00"
                    precision={2}
                    step={100}
                    addonAfter="元"
                    onChange={(value) =>
                      setAllocAmounts((prev) => ({ ...prev, [costType]: value }))
                    }
                  />
                </div>
              );
            })}
            <div className="sw-project-profit-alloc-item">
              <div className="sw-project-profit-alloc-meta">
                <span className="sw-project-profit-alloc-label">备注</span>
              </div>
              <Input.TextArea
                rows={2}
                maxLength={500}
                value={allocNote}
                onChange={(e) => setAllocNote(e.target.value)}
              />
            </div>
          </>
        )}
      </Drawer>
    </div>
  );
}
