import {
  DownloadOutlined,
  InfoCircleOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Descriptions,
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
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import SyncCutoff from '@/components/SyncCutoff';
import PendingDeliveryPanel from './PendingDeliveryPanel';
import {
  type ProjectProfitAllocation,
  type ProjectProfitBlock,
  type ProjectProfitDetail,
  type ProjectProfitLine,
  type ProjectProfitMonthSyncResult,
  type ProjectProfitReason,
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

/** 可分配的 9 个数值列（差额所有项）：行字段 ↔ 后端 cost_type；金额单位元、工时单位人月 */
const ALLOC_FIELDS = [
  { key: 'revenue', costType: 'revenue', label: '营业收入', precision: 2 },
  { key: 'smsCost', costType: 'sms', label: '短信成本', precision: 2 },
  { key: 'directCost', costType: 'direct', label: '直接成本', precision: 2 },
  { key: 'platformFee', costType: 'platform_fee', label: '平台佣金&手续费', precision: 2 },
  { key: 'compensation', costType: 'compensation', label: '赔付', precision: 2 },
  { key: 'outsourcing', costType: 'outsourcing', label: '协力&外包', precision: 2 },
  { key: 'softwareGift', costType: 'software_gift', label: '软件赠送', precision: 2 },
  { key: 'hours', costType: 'hours', label: '工时(人月)', precision: 4 },
  { key: 'cost', costType: 'cost', label: '成本(人工)', precision: 2 },
] as const;

type AllocField = (typeof ALLOC_FIELDS)[number];
type CostKey = AllocField['key'];

/** 差额明细下钻 Tab：营收明细 / 工时成本明细 / 分配记录 */
type DetailTab = 'revenue' | 'labor' | 'alloc';

/** 分配目标：项目行/项目集/精准单行（project）、销售行（sales）、业务线行（line_other） */
interface AllocTarget {
  businessLineId: number;
  businessLineName: string;
  targetType: 'project' | 'sales' | 'line_other';
  /** 主项目ID；非项目目标为 0 */
  projectId: number;
  name: string;
  category: string | null;
}

const targetKey = (target: AllocTarget) =>
  `${target.businessLineId}:${target.targetType}:${target.projectId}`;

/** 单线的可分配目标 = 当前显示的项目行 + 销售行（销售/业务线） */
const lineTargets = (line: ProjectProfitLine): AllocTarget[] =>
  line.rows
    .filter(
      (row) =>
        row.rowType === 'PROJECT' ||
        row.rowType === 'SALES' ||
        row.rowType === 'LINE_OTHER',
    )
    .map((row) => ({
      businessLineId: line.businessLineId,
      businessLineName: line.businessLineName,
      targetType:
        row.rowType === 'PROJECT'
          ? 'project'
          : row.rowType === 'SALES'
            ? 'sales'
            : 'line_other',
      projectId: row.projectId ?? 0,
      name: row.projectName ?? '—',
      category: row.category,
    }));

/** 分配目标域：full 线（云鹿Saas/定制）合并同块内所有 full 线的目标，支持跨线分配；其余线仅本线。
 *  注意：业务线筛选可能使姊妹 full 线不在块内，此时域退化为现有线（块内可见线）。 */
const domainLines = (block: ProjectProfitBlock, line: ProjectProfitLine) =>
  line.revenueMode === 'full'
    ? block.lines.filter((l) => l.revenueMode === 'full')
    : [line];

const targetsOfDomain = (block: ProjectProfitBlock, line: ProjectProfitLine): AllocTarget[] =>
  domainLines(block, line).flatMap(lineTargets);

/** 块内第一条 full 线（共享差额行的分配/明细入口以它为宿主，抽屉内部仍展示整个 full 域） */
const firstFullLineOf = (block: ProjectProfitBlock) =>
  block.lines.find((l) => l.revenueMode === 'full') ?? block.lines[0];

const SYNC_TYPE_LABELS: Record<string, string> = {
  worklog: '工时明细',
  cost: '成本分析（含销售工时）',
  bl_profit: '业务线利润镜像',
  contract: '合同明细',
};

/** 分配记录里的目标类型 → 展示名 */
const TARGET_TYPE_LABELS: Record<string, string> = {
  project: '项目',
  sales: '销售',
  line_other: '业务线',
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
  /** true = full 线（云鹿Saas/定制）共享差额行（伪业务线，跨线分配对消） */
  shared?: boolean;
  spans: RowSpans;
}

/** 差额行的数值列（求和口径；毛利率求和后重算） */
const RESIDUAL_NUM_FIELDS = [
  'revenue',
  'smsCost',
  'directCost',
  'platformFee',
  'compensation',
  'outsourcing',
  'softwareGift',
  'hours',
  'cost',
  'grossProfit',
] as const;

/** 共享行的业务线列名：取两线名的公共前缀 +（差异部分），如 全渠道云鹿定制/全渠道云鹿SAAS → 全渠道云鹿（定制+SAAS） */
const compactSharedName = (lines: ProjectProfitLine[]) => {
  const names = lines.map((l) => l.businessLineName);
  if (names.length !== 2) return names.join('+');
  const [a, b] = names;
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i += 1;
  return i === 0 ? `${a}+${b}` : `${a.slice(0, i)}（${a.slice(i)}+${b.slice(i)}）`;
};

/** full 线共享行 = 各 full 线指定行（差额/合计）逐列求和，毛利率重算。
 *  差额合并为一条（跨线分配对消），同时保留各线镜像合计行 + 末尾「全渠道合计」；
 *  域内恒有 Σ明细行 + 共享差额 = 全渠道合计 = 各线合计之和，分配后立即可对平 */
const sumLineRows = (
  lines: ProjectProfitLine[],
  pick: 'total' | 'residual',
  projectName: string,
): ProjectProfitRow => {
  const base: ProjectProfitRow = {
    rowType: pick === 'total' ? 'TOTAL' : 'RESIDUAL',
    category: pick === 'total' ? null : '差额',
    projectId: null,
    projectName,
    editable: false,
    revenue: 0,
    smsCost: 0,
    directCost: 0,
    platformFee: 0,
    compensation: 0,
    outsourcing: 0,
    softwareGift: 0,
    hours: 0,
    cost: 0,
    grossProfit: 0,
    grossProfitRate: null,
  };
  lines.forEach((line) =>
    RESIDUAL_NUM_FIELDS.forEach((field) => {
      base[field] = Number(base[field] ?? 0) + Number(line[pick][field] ?? 0);
    }),
  );
  base.grossProfitRate =
    base.revenue != null && Number(base.revenue) !== 0
      ? Math.round((Number(base.grossProfit) / Number(base.revenue)) * 10000) / 100
      : null;
  return base;
};

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
/** 元（千分位、两位小数）：明细抽屉与分配记录同单位展示 */
const formatYuan = (value?: number | null) =>
  value == null
    ? '—'
    : Number(value).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
const isNegative = (value?: number | null) => value != null && Number(value) < 0;
/** 抽屉/同步结果等需要精确数字的地方：0 也显示 0.00 */
const num =
  (render: (value?: number | null) => string) => (value: number | null) => (
    <Typography.Text type={isNegative(value) ? 'danger' : undefined}>
      {render(value)}
    </Typography.Text>
  );
/** 表格单元格：0 与空统一弱化为灰色短横，避免整屏 0.00 干扰读数；仅真实数字着色 */
const cellNum =
  (render: (value?: number | null) => string) => (value: number | null) =>
    value == null || Number(value) === 0 ? (
      <Typography.Text className="sw-project-profit-zero">-</Typography.Text>
    ) : (
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

/** 差额原因诊断列表（后端生成，逐条 Alert 展示） */
const ReasonAlerts = ({ reasons }: { reasons?: ProjectProfitReason[] | null }) =>
  reasons && reasons.length > 0 ? (
    <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 12 }}>
      {reasons.map((reason, index) => (
        <Alert
          key={index}
          type={
            reason.level === 'success'
              ? 'success'
              : reason.level === 'warning'
                ? 'warning'
                : 'info'
          }
          showIcon
          message={reason.text}
        />
      ))}
    </Space>
  ) : null;

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

/** 长表头显式换行：避免浏览器在字间断成「平台佣金&手续 / 费」这类难看断点 */
const headerBreaks = (first: string, second: string) => (
  <>
    {first}
    <br />
    {second}
  </>
);

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
  // 待交付确认抽屉
  const [pendingOpen, setPendingOpen] = useState(false);
  const [drawer, setDrawer] = useState<{
    yearMonth: string;
    block: ProjectProfitBlock;
    line: ProjectProfitLine;
  }>();
  // 分配矩阵：targetKey → costType → 输入值；existing 为已保存值（计算「未分配」联动用）
  const [allocValues, setAllocValues] = useState<Record<string, Record<string, number | null>>>({});
  const [allocExisting, setAllocExisting] = useState<Record<string, Record<string, number>>>({});
  const [allocLoading, setAllocLoading] = useState(false);
  const [allocNote, setAllocNote] = useState('');
  const [savingAlloc, setSavingAlloc] = useState(false);
  // 差额明细下钻抽屉
  const [detail, setDetail] = useState<{
    yearMonth: string;
    line: ProjectProfitLine;
    /** 可切换的业务线（共享差额行传入 full 线列表） */
    lines: ProjectProfitLine[];
    tab: DetailTab;
  }>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [revenueDetail, setRevenueDetail] = useState<ProjectProfitDetail>();
  const [laborDetail, setLaborDetail] = useState<ProjectProfitDetail>();
  const [detailAllocations, setDetailAllocations] = useState<ProjectProfitAllocation[]>([]);
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
        // 项目选项随业务线联动：业务线变化后剔除已失效的项目选择。
        // 注意：必须保持原引用（无变化时返回 prev），否则 filterProjectIds 每次都是新数组，
        // 会让 loadReport 换身份、useEffect 再次触发拉取，形成无限请求循环。
        const validProjectIds = new Set(
          result.lineOptions.flatMap((line) =>
            line.projects.map((project) => project.projectId),
          ),
        );
        setFilterProjectIds((prev) => {
          const next = prev.filter((id) => validProjectIds.has(id));
          return next.length === prev.length ? prev : next;
        });
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
    const card = tableCardRef.current;
    const scroller = (card?.closest('.ant-layout-content') as HTMLElement | null) ?? null;
    const compute = () => {
      if (!tableCardRef.current) return;
      const el = tableCardRef.current;
      const top = scroller
        ? el.getBoundingClientRect().top - scroller.getBoundingClientRect().top
        : el.getBoundingClientRect().top;
      const available = (scroller?.clientHeight ?? window.innerHeight) - top;
      // 预留：表头（可折行 ~46px）+ 卡片内边距（24）+ 底部留白（8）
      setTableBodyHeight(Math.max(220, Math.round(available - 78)));
    };
    compute();
    // 布局时序（数据到达、字体/滚动条出现、窗口缩放）都会改变可用高度，用 RO 持续校正
    const observer = new ResizeObserver(compute);
    observer.observe(scroller ?? document.body);
    if (card) observer.observe(card);
    window.addEventListener('resize', compute);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', compute);
    };
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

  // 分配 Drawer（月×业务线粒度；full 线合并 saas/定制两线目标域支持跨线分配）：
  // 拉取当月全部线的分配记录（跨线记录归目标线），初始化矩阵输入
  const openDrawer = async (block: ProjectProfitBlock, line: ProjectProfitLine) => {
    setDrawer({ yearMonth: block.key, block, line });
    setAllocNote('');
    setAllocLoading(true);
    try {
      const list = await superworkApi.getProjectProfitAllocations(block.key);
      const existing: Record<string, Record<string, number>> = {};
      list.forEach((allocation) => {
        const key = `${allocation.businessLineId}:${allocation.targetType ?? 'project'}:${allocation.projectId ?? 0}`;
        (existing[key] ??= {})[allocation.costType] = Number(allocation.amount);
      });
      setAllocExisting(existing);
      const values: Record<string, Record<string, number | null>> = {};
      targetsOfDomain(block, line).forEach((target) => {
        const key = targetKey(target);
        values[key] = {};
        ALLOC_FIELDS.forEach(({ costType }) => {
          values[key][costType] = existing[key]?.[costType] ?? null;
        });
      });
      setAllocValues(values);
    } catch {
      message.error('分配记录加载失败');
      setDrawer(undefined);
    } finally {
      setAllocLoading(false);
    }
  };

  /** 未分配（随输入实时联动）= 该线当前差额 − Σ该线目标(输入 − 已保存)；差额按显示行重算口径 */
  const unallocated = (field: AllocField, line: ProjectProfitLine) => {
    if (!drawer) return 0;
    const residual = Number(line.residual[field.key] ?? 0);
    const delta = lineTargets(line).reduce((sum, target) => {
      const key = targetKey(target);
      return (
        sum +
        (allocValues[key]?.[field.costType] ?? 0) -
        (allocExisting[key]?.[field.costType] ?? 0)
      );
    }, 0);
    return residual - delta;
  };

  /** full 域共享未分配 = 各线差额之和 − 全域目标(输入 − 已保存) */
  const unallocatedShared = (field: AllocField, lines: ProjectProfitLine[]) => {
    if (!drawer) return 0;
    const residual = lines.reduce((sum, l) => sum + Number(l.residual[field.key] ?? 0), 0);
    const delta = targetsOfDomain(drawer.block, drawer.line).reduce((sum, target) => {
      const key = targetKey(target);
      return (
        sum +
        (allocValues[key]?.[field.costType] ?? 0) -
        (allocExisting[key]?.[field.costType] ?? 0)
      );
    }, 0);
    return residual - delta;
  };

  const saveAllocations = async () => {
    if (!drawer) return;
    setSavingAlloc(true);
    try {
      const targets = targetsOfDomain(drawer.block, drawer.line)
        .map((target) => {
          const key = targetKey(target);
          // 仅提交有值或已有记录的列（后者支持清零）；空目标整组跳过
          const items = ALLOC_FIELDS.filter(({ costType }) => {
            const value = allocValues[key]?.[costType];
            return (value != null && value !== 0) || allocExisting[key]?.[costType] != null;
          }).map(({ costType }) => ({
            costType,
            amount: allocValues[key]?.[costType] ?? 0,
            note: allocNote || null,
          }));
          return {
            businessLineId: target.businessLineId,
            targetType: target.targetType,
            projectId: target.projectId,
            items,
          };
        })
        .filter((target) => target.items.length > 0);
      if (targets.length === 0) {
        message.info('没有需要保存的分配');
        return;
      }
      await superworkApi.saveProjectProfitAllocations({
        yearMonth: drawer.yearMonth,
        businessLineId: drawer.line.businessLineId,
        targets,
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

  // 差额明细下钻（差额行非零单元格点击）：营收=镜像 vs OA 合同逐条；工时/成本=镜像 vs 成本分析逐条；分配记录。
  // 共享差额行传入 full 线列表，抽屉顶部可切换业务线
  const fetchDetail = async (yearMonth: string, line: ProjectProfitLine) => {
    setDetailLoading(true);
    try {
      const [revenue, labor, allocations] = await Promise.all([
        superworkApi.getProjectProfitRevenueDetail(yearMonth, line.businessLineId),
        superworkApi.getProjectProfitLaborDetail(yearMonth, line.businessLineId),
        superworkApi.getProjectProfitAllocations(yearMonth, line.businessLineId),
      ]);
      setRevenueDetail(revenue);
      setLaborDetail(labor);
      setDetailAllocations(allocations);
    } catch {
      message.error('明细加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const openDetail = (
    block: ProjectProfitBlock,
    line: ProjectProfitLine,
    tab: DetailTab,
    lines?: ProjectProfitLine[],
  ) => {
    setDetail({ yearMonth: block.key, line, lines: lines ?? [line], tab });
    void fetchDetail(block.key, line);
  };

  const switchDetailLine = (line: ProjectProfitLine) => {
    if (!detail) return;
    setDetail({ ...detail, line });
    void fetchDetail(detail.yearMonth, line);
  };

  // 平铺行：项目行… → 销售行 → 差额行（仅非零） → 合计行（simple 线除外） → 块末「全部业务线」汇总行。
  // full 线（云鹿Saas/定制）≥2 条时合并差额域：各自的差额行与合计行隐藏，最后一条 full 线之后
  // 渲染「共享差额」（仅非零）与「共享合计」（两线镜像之和）——域内恒有 Σ明细行 + 共享差额 = 共享合计；
  // 月份/业务线/分类三列按相邻同值合并
  const dataSource = useMemo<FlatRow[]>(() => {
    if (!report) return [];
    const rows: Omit<FlatRow, 'spans'>[] = [];
    report.blocks.forEach((block) => {
      const fullLines = block.lines.filter((l) => l.revenueMode === 'full');
      const mergeFull = fullLines.length >= 2;
      const sharedName = compactSharedName(fullLines);
      const sharedResidual = mergeFull ? sumLineRows(fullLines, 'residual', '共享差额') : null;
      const sharedTotal = mergeFull ? sumLineRows(fullLines, 'total', '全渠道合计') : null;
      const lastFullLine = fullLines[fullLines.length - 1];
      const pseudoLine = (id: number, row: ProjectProfitRow): ProjectProfitLine => ({
        businessLineId: id,
        businessLineName: sharedName,
        revenueMode: 'full',
        rows: [],
        total: row,
        residual: row,
      });
      block.lines.forEach((line) => {
        const mergedFull = mergeFull && line.revenueMode === 'full';
        line.rows.forEach((row) =>
          rows.push({ key: `${block.key}-${line.businessLineId}-${row.rowType}-${row.projectId ?? row.projectName}`, block, line, row }),
        );
        if (!mergedFull && residualVisible(line.residual)) {
          rows.push({
            key: `${block.key}-${line.businessLineId}-RESIDUAL`,
            block,
            line,
            row: line.residual,
          });
        }
        // simple 线（精准）只有一行明细，合计行冗余不渲染（total 仍参与跨业务线汇总行的计算）；
        // full 线保留各自的镜像合计行（合并域内差额并入「共享差额」，末尾另有「全渠道合计」）
        if (line.revenueMode !== 'simple') {
          rows.push({
            key: `${block.key}-${line.businessLineId}-TOTAL`,
            block,
            line,
            row: line.total,
          });
        }
        // 共享差额行 / 全渠道合计行跟在最后一条 full 线的合计行之后（伪业务线 id=-2/-3，不与任何业务线合并）
        if (mergedFull && line === lastFullLine) {
          if (sharedResidual && residualVisible(sharedResidual)) {
            rows.push({
              key: `${block.key}-SHARED-RESIDUAL`,
              block,
              line: pseudoLine(-2, sharedResidual),
              row: sharedResidual,
              shared: true,
            });
          }
          if (sharedTotal) {
            rows.push({
              key: `${block.key}-SHARED-TOTAL`,
              block,
              line: pseudoLine(-3, sharedTotal),
              row: sharedTotal,
              shared: true,
            });
          }
        }
      });
      if (block.summary) {
        rows.push({
          key: `${block.key}-SUMMARY`,
          block,
          line: {
            businessLineId: -1,
            businessLineName: '全部业务线',
            revenueMode: '',
            rows: [],
            total: block.summary,
            residual: block.summary,
          },
          row: block.summary,
        });
      }
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

  /** 差额行非零单元格（月度块）包装为可点击链接，下钻差额明细；共享差额行带上线切换 */
  const maybeDetailLink = (
    record: FlatRow,
    tab: DetailTab | undefined,
    value: number | null,
    rendered: ReactNode,
  ) =>
    tab &&
    !record.block.provisional &&
    record.row.rowType === 'RESIDUAL' &&
    !['H1', 'H2', 'YEAR'].includes(record.block.key) &&
    value != null &&
    Number(value) !== 0 ? (
      <Typography.Link
        onClick={() =>
          void openDetail(
            record.block,
            record.shared ? firstFullLineOf(record.block) : record.line,
            tab,
            record.shared
              ? record.block.lines.filter((l) => l.revenueMode === 'full')
              : undefined,
          )
        }
      >
        {rendered}
      </Typography.Link>
    ) : (
      rendered
    );

  const moneyColumn = (
    title: ReactNode,
    field: CostKey | 'revenue' | 'cost' | 'grossProfit',
    width: number,
    detailTab?: DetailTab,
  ) => ({
    title,
    width,
    align: 'right' as const,
    render: (_: unknown, record: FlatRow) =>
      maybeDetailLink(record, detailTab, record.row[field], cellNum(formatWan)(record.row[field])),
  });
  // 紧凑列宽：文本列固定窄宽 + 省略号，数值列按当前数据最大文本留 1~2 字符余量，合计宽度收敛到一屏内
  const columns: TableProps<FlatRow>['columns'] = [
    {
      title: '月份',
      width: 48,
      onCell: (record) => ({ rowSpan: record.spans.month }),
      render: (_, record) =>
        record.block.provisional ? (
          <Tooltip title={record.block.provisionalNote}>
            <Space size={2}>
              {record.block.label}
              <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                预估
              </Tag>
            </Space>
          </Tooltip>
        ) : (
          record.block.label
        ),
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
        record.row.rowType === 'TOTAL'
          ? record.row.projectName ?? '合计'
          : record.row.projectName ?? '—',
    },
    {
      title: '营业收入',
      width: 84,
      align: 'right' as const,
      render: (_: unknown, record: FlatRow) => {
        const main = maybeDetailLink(
          record,
          'revenue',
          record.row.revenue,
          cellNum(formatWan)(record.row.revenue),
        );
        // 方案A（预估块）：主数字 = 已确认口径营收；未确认待交付以灰色小字标注，不计入成本与利润
        const unconfirmed = record.row.unconfirmedRevenue;
        if (
          !record.block.provisional ||
          unconfirmed == null ||
          Number(unconfirmed) === 0
        ) {
          return main;
        }
        return (
          <div>
            <div>{main}</div>
            <Tooltip title="待确认合同金额（未税口径）：仅展示，未计入成本与利润；可点右上角「待交付确认」处理">
              <div className="sw-project-profit-unconfirmed">
                <QuestionCircleOutlined /> +{formatWan(unconfirmed)} 待确认
              </div>
            </Tooltip>
          </div>
        );
      },
    },
    moneyColumn('短信成本', 'smsCost', 60, 'alloc'),
    moneyColumn('直接成本', 'directCost', 60, 'alloc'),
    moneyColumn(headerBreaks('平台佣金&', '手续费'), 'platformFee', 64, 'alloc'),
    moneyColumn('赔付', 'compensation', 44, 'alloc'),
    moneyColumn(headerBreaks('协力&', '外包'), 'outsourcing', 58, 'alloc'),
    moneyColumn('软件赠送', 'softwareGift', 58, 'alloc'),
    {
      title: '工时',
      width: 60,
      align: 'right',
      render: (_, record) =>
        maybeDetailLink(record, 'labor', record.row.hours, cellNum(formatHours)(record.row.hours)),
    },
    moneyColumn('成本', 'cost', 74, 'labor'),
    moneyColumn('考核毛利', 'grossProfit', 74),
    {
      title: headerBreaks('考核', '毛利率(%)'),
      width: 76,
      align: 'right',
      render: (_, record) => cellNum(formatRate)(record.row.grossProfitRate),
    },
    {
      title: '操作',
      width: 48,
      fixed: 'right',
      // 分配为月度粒度：合计行（常驻入口，差额为 0 时也可调整）与差额行均可打开；
      // simple 线（精准）无合计行，入口放在唯一的项目行上；共享差额行以块内第一条 full 线为宿主打开；
      // 在途预估块不提供分配（数据是估计值，月结后再分配）
      render: (_, record) =>
        !record.block.provisional &&
        (record.row.rowType === 'TOTAL' ||
          record.row.rowType === 'RESIDUAL' ||
          (record.row.rowType === 'PROJECT' && record.line.revenueMode === 'simple')) &&
        !['H1', 'H2', 'YEAR'].includes(record.block.key) ? (
          <Typography.Link
            className="sw-project-profit-alloc-link"
            onClick={() =>
              void openDrawer(
                record.block,
                record.shared ? firstFullLineOf(record.block) : record.line,
              )
            }
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
                  云鹿Saas/定制 共享一个差额行（跨线分配），两线合计之和 = 全渠道合计，恒有 Σ明细行 + 共享差额 = 全渠道合计；
                  其余业务线差额行 = 合计 − 已显示明细行，差额所有项可经合计行「分配」分摊到项目/销售子项，
                  归零后差额行自动隐藏；每块末行「全部业务线」汇总 = 块内各业务线合计求和。
                  收益单位：金额「万」，工时「人月」。
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
          <Button size="small" onClick={() => setPendingOpen(true)}>
            待交付确认
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
        <div className="sw-project-profit-filters">
          {/* 筛选项按维度分行：业务线（主维度）→ 月份（平铺，快速点选）→ 分类（平铺）+ 项目（下拉可搜索） */}
          <div className="sw-project-profit-filter-row">
            <span className="sw-project-profit-filter-label">业务线</span>
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
          </div>
          {viewMode === 'month' && (
            <div className="sw-project-profit-filter-row">
              <span className="sw-project-profit-filter-label">月份</span>
              <div className="sw-project-profit-pills">
                <button
                  type="button"
                  className={selectedMonths.length === 0 ? 'is-active' : ''}
                  onClick={() => setSelectedMonths([])}
                >
                  全部月份
                </button>
                {monthFilterOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={selectedMonths.includes(option.value) ? 'is-active' : ''}
                    onClick={() =>
                      setSelectedMonths((prev) =>
                        prev.includes(option.value)
                          ? prev.filter((m) => m !== option.value)
                          : [...prev, option.value],
                      )
                    }
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
          )}
          <div className="sw-project-profit-filter-row">
            <span className="sw-project-profit-filter-label">分类</span>
            <div className="sw-project-profit-pills">
              <button
                type="button"
                className={filterCategories.length === 0 ? 'is-active' : ''}
                onClick={() => setFilterCategories([])}
              >
                全部
              </button>
              {['项目', '销售'].map((category) => (
                <button
                  key={category}
                  type="button"
                  className={filterCategories.includes(category) ? 'is-active' : ''}
                  onClick={() =>
                    setFilterCategories((prev) =>
                      prev.includes(category)
                        ? prev.filter((c) => c !== category)
                        : [...prev, category],
                    )
                  }
                >
                  {category}
                </button>
              ))}
            </div>
            <span className="sw-project-profit-filter-label" style={{ marginLeft: 16 }}>
              项目
            </span>
            <Select
              mode="multiple"
              allowClear
              size="small"
              placeholder="仅过滤项目行（可搜索）"
              style={{ minWidth: 240 }}
              value={filterProjectIds}
              options={projectOptions}
              onChange={(value) => setFilterProjectIds(value)}
              showSearch
              optionFilterProp="label"
            />
          </div>
        </div>
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
                record.row.rowType === 'SUMMARY'
                  ? 'sw-project-profit-summary-row'
                  : record.row.rowType === 'TOTAL'
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
        {syncResult?.mirrorZero && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="工时系统该月财报未出报（镜像全零）：报表已改按预估口径展示（项目行取 OA 合同已交付），下方对齐差额仅供参考；请在工时系统确认出报后重新同步"
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
              <div key={line.businessLineId ?? 'shared'}>
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
        title={`待交付确认（${year} 年）`}
        width={1200}
        open={pendingOpen}
        onClose={() => setPendingOpen(false)}
        extra={
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            确认后计入预估营收与利润；未确认仅展示不计利润
          </Typography.Text>
        }
      >
        {pendingOpen && (
          <PendingDeliveryPanel year={year} onChanged={() => void loadReport(year)} />
        )}
      </Drawer>

      <Drawer
        open={!!drawer}
        width={1000}
        title={
          drawer ? (
            <Space size={8}>
              <span>差额分配</span>
              <Tag color="blue">{drawer.yearMonth}</Tag>
              {domainLines(drawer.block, drawer.line).map((line) => (
                <Tag key={line.businessLineId}>{line.businessLineName}</Tag>
              ))}
            </Space>
          ) : (
            ''
          )
        }
        onClose={() => setDrawer(undefined)}
        extra={
          <Space>
            <Button onClick={() => setDrawer(undefined)}>取消</Button>
            <Button type="primary" loading={savingAlloc} onClick={() => void saveAllocations()}>
              保存
            </Button>
          </Space>
        }
      >
        {drawer && (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={
                domainLines(drawer.block, drawer.line).length > 1
                  ? '差额所有项均可分配到项目/销售子项；云鹿Saas 与 云鹿定制 之间支持跨线分配（写入目标所在线）。金额单位「元」（财报未税口径），工时单位「人月」。表底「未分配」按线分行、随输入实时联动，全部归零后差额行自动隐藏。'
                  : '差额所有项均可分配到项目/销售子项：金额单位「元」（财报未税口径），工时单位「人月」。表底「未分配」= 差额 − 本次调整，随输入实时联动；全部归零后差额行自动隐藏。'
              }
            />
            <Table<AllocTarget>
              size="small"
              bordered
              loading={allocLoading}
              dataSource={targetsOfDomain(drawer.block, drawer.line)}
              pagination={false}
              rowKey={(target) => targetKey(target)}
              scroll={{ x: 1160 }}
              columns={[
                {
                  title: '分配目标',
                  width: domainLines(drawer.block, drawer.line).length > 1 ? 172 : 132,
                  fixed: 'left',
                  render: (_, target) => (
                    <Space size={4}>
                      {domainLines(drawer.block, drawer.line).length > 1 && (
                        <Tag color="geekblue" style={{ marginInlineEnd: 0 }}>
                          {target.businessLineName}
                        </Tag>
                      )}
                      <Tag
                        color={target.targetType === 'project' ? 'blue' : 'default'}
                        style={{ marginInlineEnd: 0 }}
                      >
                        {target.category ?? '—'}
                      </Tag>
                      <span>{target.name}</span>
                    </Space>
                  ),
                },
                ...ALLOC_FIELDS.map((field) => ({
                  title: field.label,
                  width: 104,
                  align: 'right' as const,
                  render: (_: unknown, target: AllocTarget) => {
                    const key = targetKey(target);
                    return (
                      <InputNumber
                        size="small"
                        style={{ width: 94 }}
                        value={allocValues[key]?.[field.costType] ?? null}
                        placeholder="0"
                        precision={field.precision}
                        onChange={(value) =>
                          setAllocValues((prev) => ({
                            ...prev,
                            [key]: { ...prev[key], [field.costType]: value },
                          }))
                        }
                      />
                    );
                  },
                })),
              ]}
              summary={() => {
                const lines = domainLines(drawer.block, drawer.line);
                const cells = (valueOf: (field: AllocField) => number) =>
                  ALLOC_FIELDS.map((field, index) => {
                    const value = valueOf(field);
                    const threshold = field.costType === 'hours' ? 0.0001 : 0.01;
                    return (
                      <Table.Summary.Cell index={index + 1} key={field.costType} align="right">
                        <Typography.Text
                          type={Math.abs(value) >= threshold ? 'warning' : 'success'}
                        >
                          {value.toFixed(field.precision)}
                        </Typography.Text>
                      </Table.Summary.Cell>
                    );
                  });
                // full 域（saas/定制）共享一个差额：单行未分配 = 两线差额之和 − 全域输入增量
                if (lines.length > 1) {
                  return (
                    <Table.Summary.Row>
                      <Table.Summary.Cell index={0}>
                        <Typography.Text strong>
                          未分配 · {lines.map((l) => l.businessLineName).join('+')}
                        </Typography.Text>
                      </Table.Summary.Cell>
                      {cells((field) => unallocatedShared(field, lines))}
                    </Table.Summary.Row>
                  );
                }
                return (
                  <>
                    {lines.map((line) => (
                      <Table.Summary.Row key={line.businessLineId}>
                        <Table.Summary.Cell index={0}>
                          <Typography.Text strong>未分配</Typography.Text>
                        </Table.Summary.Cell>
                        {cells((field) => unallocated(field, line))}
                      </Table.Summary.Row>
                    ))}
                  </>
                );
              }}
            />
            <div style={{ marginTop: 16 }}>
              <Typography.Text strong>备注</Typography.Text>
              <Input.TextArea
                rows={2}
                maxLength={500}
                value={allocNote}
                onChange={(e) => setAllocNote(e.target.value)}
                placeholder="本次分配说明（可选，写入本次保存的所有分配项）"
                style={{ marginTop: 4 }}
              />
            </div>
          </>
        )}
      </Drawer>

      <Drawer
        open={!!detail}
        width={880}
        title={
          detail ? (
            <Space size={8}>
              <span>差额明细</span>
              <Tag color="blue">{detail.yearMonth}</Tag>
              <Tag>{detail.line.businessLineName}</Tag>
            </Space>
          ) : (
            ''
          )
        }
        onClose={() => setDetail(undefined)}
      >
        {detail && detail.lines.length > 1 && (
          <Segmented
            size="small"
            style={{ marginBottom: 12 }}
            value={detail.line.businessLineId}
            options={detail.lines.map((l) => ({
              label: l.businessLineName,
              value: l.businessLineId,
            }))}
            onChange={(value) => {
              const next = detail.lines.find((l) => l.businessLineId === value);
              if (next) switchDetailLine(next);
            }}
          />
        )}
        {detail && (
          <Tabs
            activeKey={detail.tab}
            onChange={(key) =>
              setDetail((prev) => (prev ? { ...prev, tab: key as DetailTab } : prev))
            }
            items={[
              {
                key: 'revenue',
                label: '营收明细',
                children: (
                  <>
                    <ReasonAlerts reasons={revenueDetail?.reasons} />
                    <Descriptions size="small" bordered column={2} style={{ marginBottom: 12 }}>
                      <Descriptions.Item label="镜像营收（未税）">
                        {formatYuan(revenueDetail?.mirrorRevenue)}
                      </Descriptions.Item>
                      <Descriptions.Item label="OA 已交付（未税）">
                        {formatYuan(revenueDetail?.oaDeliveredExTax)}
                      </Descriptions.Item>
                      <Descriptions.Item label="手动分配合计">
                        {formatYuan(revenueDetail?.revenueAllocated)}
                      </Descriptions.Item>
                      <Descriptions.Item label="差额（待分配）">
                        <Typography.Text
                          type={
                            revenueDetail?.revenueGap != null &&
                            Math.abs(Number(revenueDetail.revenueGap)) >= 0.01
                              ? 'warning'
                              : 'success'
                          }
                        >
                          {formatYuan(revenueDetail?.revenueGap)}
                        </Typography.Text>
                      </Descriptions.Item>
                    </Descriptions>
                    <Table
                      size="small"
                      bordered
                      loading={detailLoading}
                      dataSource={revenueDetail?.revenueRows ?? []}
                      pagination={false}
                      rowKey={(row, index) => `${row.contractNo ?? 'row'}-${index}`}
                      scroll={{ y: 420 }}
                      columns={[
                        {
                          title: '合同号',
                          dataIndex: 'contractNo',
                          width: 110,
                          ellipsis: true,
                          render: (value) => value ?? '—',
                        },
                        {
                          title: '合同/客户',
                          width: 150,
                          ellipsis: true,
                          render: (_, row) => row.contractName ?? row.customer ?? '—',
                        },
                        { title: '交付日期', dataIndex: 'deliveryDate', width: 92 },
                        {
                          title: '含税（元）',
                          dataIndex: 'receivableAmount',
                          width: 100,
                          align: 'right',
                          render: (value) => formatYuan(value),
                        },
                        {
                          title: '未税（元）',
                          dataIndex: 'exTaxAmount',
                          width: 100,
                          align: 'right',
                          render: (value) => formatYuan(value),
                        },
                        {
                          title: '归属项目',
                          width: 140,
                          ellipsis: true,
                          render: (_, row) =>
                            row.rootProjectName
                              ? `${row.rootProjectName}${row.rootLineName ? `（${row.rootLineName}）` : ''}`
                              : '—',
                        },
                        {
                          title: '计入',
                          width: 82,
                          render: (_, row) =>
                            row.counted ? (
                              <Tag color="green">计入本线</Tag>
                            ) : (
                              <Tag color="orange">未计入</Tag>
                            ),
                        },
                      ]}
                    />
                    <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
                      「未计入」= OA 合同业务线为本线、但项目归属别线或无法归桶的合同，是营收差额的排查线索；
                      跨线（云鹿Saas/定制）归属可在「分配」抽屉中调整。
                    </Typography.Paragraph>
                  </>
                ),
              },
              {
                key: 'labor',
                label: '工时/成本明细',
                children: (
                  <>
                    <ReasonAlerts reasons={laborDetail?.reasons} />
                    <Descriptions size="small" bordered column={2} style={{ marginBottom: 12 }}>
                      <Descriptions.Item label="镜像工时（人月）">
                        {formatHours(laborDetail?.mirrorHours)}
                      </Descriptions.Item>
                      <Descriptions.Item label="镜像人工成本（元）">
                        {formatYuan(laborDetail?.mirrorLaborCost)}
                      </Descriptions.Item>
                      <Descriptions.Item label="手动分配·工时">
                        {formatHours(laborDetail?.hoursAllocated)}
                      </Descriptions.Item>
                      <Descriptions.Item label="手动分配·成本">
                        {formatYuan(laborDetail?.costAllocated)}
                      </Descriptions.Item>
                    </Descriptions>
                    <Table
                      size="small"
                      bordered
                      loading={detailLoading}
                      dataSource={laborDetail?.laborRows ?? []}
                      pagination={false}
                      rowKey={(row, index) => `${row.projectNameRaw ?? 'row'}-${index}`}
                      scroll={{ y: 420 }}
                      columns={[
                        {
                          title: '类型',
                          width: 76,
                          render: (_, row) =>
                            row.workType === 'sales' ? (
                              <Tag>销售</Tag>
                            ) : (
                              <Tag color="blue">项目</Tag>
                            ),
                        },
                        {
                          title: '原始项目',
                          dataIndex: 'projectNameRaw',
                          ellipsis: true,
                          render: (value) => value ?? '—',
                        },
                        {
                          title: '归并',
                          dataIndex: 'rootProjectName',
                          width: 130,
                          ellipsis: true,
                          render: (value) => value ?? '—',
                        },
                        {
                          title: '人数',
                          dataIndex: 'employeeCount',
                          width: 56,
                          align: 'right',
                          render: (value) => value ?? '—',
                        },
                        {
                          title: '工时（人月）',
                          dataIndex: 'hours',
                          width: 96,
                          align: 'right',
                          render: (value) => formatHours(value),
                        },
                        {
                          title: '成本（元）',
                          dataIndex: 'costAmount',
                          width: 110,
                          align: 'right',
                          render: (value) => formatYuan(value),
                        },
                      ]}
                    />
                  </>
                ),
              },
              {
                key: 'alloc',
                label: '分配记录',
                children: (
                  <>
                    {(() => {
                      // 6 个成本列的未分配提示（前端即时有差额行数据，无需后端诊断）
                      const hints = ALLOC_FIELDS.filter(
                        (field) => !['revenue', 'hours', 'cost'].includes(field.costType),
                      )
                        .map((field) => ({
                          label: field.label,
                          value: Number(detail.line.residual[field.key] ?? 0),
                        }))
                        .filter((hint) => Math.abs(hint.value) >= 0.01);
                      return (
                        <Alert
                          type={hints.length > 0 ? 'info' : 'success'}
                          showIcon
                          style={{ marginBottom: 12 }}
                          message={
                            hints.length > 0
                              ? `未分配：${hints
                                  .map(
                                    (hint) =>
                                      `${hint.label} ${hint.value.toLocaleString('zh-CN', {
                                        minimumFractionDigits: 2,
                                        maximumFractionDigits: 2,
                                      })} 元`,
                                  )
                                  .join('；')}——在「分配」抽屉拆分到项目/销售行即可消除`
                              : '6 个成本列均已分配完毕，无待分配差额'
                          }
                        />
                      );
                    })()}
                    <Table
                    size="small"
                    bordered
                    loading={detailLoading}
                    dataSource={detailAllocations}
                    pagination={false}
                    rowKey="id"
                    scroll={{ y: 420 }}
                    locale={{ emptyText: '本月本线暂无分配记录' }}
                    columns={[
                      {
                        title: '目标',
                        width: 140,
                        render: (_, allocation) =>
                          `${TARGET_TYPE_LABELS[allocation.targetType] ?? allocation.targetType}${
                            allocation.projectId ? ` #${allocation.projectId}` : ''
                          }`,
                      },
                      {
                        title: '列',
                        width: 120,
                        render: (_, allocation) =>
                          ALLOC_FIELDS.find((field) => field.costType === allocation.costType)
                            ?.label ?? allocation.costType,
                      },
                      {
                        title: '数量',
                        dataIndex: 'amount',
                        width: 120,
                        align: 'right',
                        render: (value, allocation) =>
                          allocation.costType === 'hours' ? formatHours(value) : formatYuan(value),
                      },
                      {
                        title: '备注',
                        dataIndex: 'note',
                        ellipsis: true,
                        render: (value) => value ?? '—',
                      },
                      {
                        title: '更新时间',
                        dataIndex: 'updatedAt',
                        width: 160,
                        render: (value) => value ?? '—',
                      },
                    ]}
                  />
                  </>
                ),
              },
            ]}
          />
        )}
      </Drawer>
    </div>
  );
}
