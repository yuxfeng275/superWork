import {
  DeleteOutlined,
  EditOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import type { TableColumnsType } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import SyncCutoff from '@/components/SyncCutoff';
import type {
  RevenueCell,
  RevenueCellDetail,
  RevenueCostEntry,
  RevenueEstimateEntry,
  RevenueLineBlock,
  RevenueMatrix,
  RevenueMonthInfo,
  RevenueRow,
  RevenueWorklogEntry,
} from '@/services/superwork/api';
import { superworkApi } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

/** 矩阵行上下文：明细行 + 所属业务线（下钻与录入定位用） */
type MatrixRowContext = RevenueRow & {
  businessLineId: number;
  businessLineName: string;
};

/** 矩阵表格数据源：明细行 / 业务线小计 / 合计 */
type MatrixDisplayRow = {
  kind: 'data' | 'line_total' | 'grand_total';
  key: string;
  lineSpan: number;
  sectionLabel: string;
  sectionSpan: number;
  lineId: number;
  lineName: string;
  row: MatrixRowContext | null;
  monthTotals: RevenueCell[];
  totals: RevenueCell;
};

const PROJECT_KINDS: RevenueRow['kind'][] = [
  'project',
  'agg_project',
  'line_pool',
  'simple',
];

export default function RevenuePage() {
  const [year, setYear] = useState(() => new Date().getFullYear());
  const location = useLocation();
  const revenueTabByPath: Record<string, string> = {
    '/revenue/worktime': 'matrix',
    '/revenue/delivery': 'delivery',
    '/revenue/import': 'import',
    '/revenue/pending': 'pending',
  };
  const [activeTab, setActiveTab] = useState('matrix');
  const [matrix, setMatrix] = useState<RevenueMatrix | null>(null);
  const [pending, setPending] = useState<{
    worklog: Record<string, any>[];
    cost: Record<string, any>[];
  }>({ worklog: [], cost: [] });
  const [estimates, setEstimates] = useState<Record<string, any>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [importMonth, setImportMonth] = useState(`${year}-09`);
  const [worklogFile, setWorklogFile] = useState<File>();
  const [costFile, setCostFile] = useState<File>();
  const [importing, setImporting] = useState(false);
  const [deliverySummary, setDeliverySummary] = useState<Record<string, any>>(
    {},
  );
  const [deliveryPeriod, setDeliveryPeriod] = useState<'h1' | 'h2' | 'ytd'>(
    'ytd',
  );
  const [deliveryIncludeEstimate, setDeliveryIncludeEstimate] = useState(true);
  const [deliveryExcludeTax, setDeliveryExcludeTax] = useState(true);
  const [deliveryViewMode, setDeliveryViewMode] = useState<'cards' | 'matrix'>(
    'cards',
  );
  const [deliveryDetail, setDeliveryDetail] = useState<{
    open: boolean;
    row?: Record<string, any>;
    label?: string;
  }>({ open: false });
  const [importBatches, setImportBatches] = useState<Record<string, any>[]>([]);
  const [contractBatches, setContractBatches] = useState<Record<string, any>[]>(
    [],
  );
  const [pendingContracts, setPendingContracts] = useState<
    Record<string, any>[]
  >([]);
  const [mappedContracts, setMappedContracts] = useState<Record<string, any>[]>(
    [],
  );
  const [contractFile, setContractFile] = useState<File>();
  const [contractBusy, setContractBusy] = useState(false);
  const [contractDrafts, setContractDrafts] = useState<
    Record<string, { businessLineId?: number; projectId?: number | null }>
  >({});
  const [displayMode, setDisplayMode] = useState<'merge' | 'hours' | 'cost'>(
    'merge',
  );
  // 数据口径：true=含预估，false=只看实际
  const [showEstimates, setShowEstimates] = useState(true);
  const [filterLineIds, setFilterLineIds] = useState<number[]>([]);
  const [filterProjectIds, setFilterProjectIds] = useState<number[]>([]);
  const [closeToggling, setCloseToggling] = useState('');
  const [cellOpen, setCellOpen] = useState(false);
  const [cellLoading, setCellLoading] = useState(false);
  const [cellRow, setCellRow] = useState<MatrixRowContext | null>(null);
  const [cellContext, setCellContext] = useState({
    yearMonth: '',
    businessLineId: 0,
    rowKey: '',
    title: '',
  });
  const [cellDetail, setCellDetail] = useState<RevenueCellDetail | null>(null);
  const [businessLines, setBusinessLines] = useState<Record<string, any>[]>([]);
  const [projects, setProjects] = useState<Record<string, any>[]>([]);
  const [salesProjects, setSalesProjects] = useState<Record<string, any>[]>([]);
  const [opportunityOptions, setOpportunityOptions] = useState<
    Record<string, any>[]
  >([]);
  const [resolveState, setResolveState] = useState<{
    type: 'worklog' | 'cost';
    row: Record<string, any>;
  }>();
  const [resolveForm] = Form.useForm();
  const [estimateForm] = Form.useForm();
  const [entryForm] = Form.useForm();
  const [deliveryState, setDeliveryState] = useState<{
    open: boolean;
    kind: 'plans' | 'costs';
    row?: Record<string, any>;
  }>({ open: false, kind: 'plans' });
  const [deliveryBusy, setDeliveryBusy] = useState(false);
  const [deliverySaving, setDeliverySaving] = useState(false);
  const [deliveryPlans, setDeliveryPlans] = useState<Record<string, any>[]>([]);
  const [deliveryCosts, setDeliveryCosts] = useState<Record<string, any>[]>([]);
  const [deliveryUnitPrice, setDeliveryUnitPrice] = useState<number | null>(
    null,
  );
  const newPlanRow = () => ({
    key: `${Date.now()}-${Math.random()}`,
    yearMonth: `${year}-01`,
    amountWan: 0,
    personMonths: 0,
  });
  const [planRows, setPlanRows] = useState([newPlanRow()]);
  const [editingPlan, setEditingPlan] = useState<Record<string, any>>();
  const [planEdit, setPlanEdit] = useState({ amountWan: 0, personMonths: 0 });
  const [costForm] = Form.useForm();
  const yearMonths = Array.from(
    { length: 12 },
    (_, index) => `${year}-${String(index + 1).padStart(2, '0')}`,
  );
  const [estimateState, setEstimateState] = useState<{
    open: boolean;
    row?: RevenueEstimateEntry;
  }>({ open: false });
  const [entryState, setEntryState] = useState<{
    open: boolean;
    kind: 'worklog' | 'cost';
    row?: RevenueWorklogEntry | RevenueCostEntry;
  }>({ open: false, kind: 'worklog' });
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [
        m,
        p,
        e,
        d,
        b,
        cb,
        pendingContractRows,
        mappedContractRows,
        linePage,
        projectPage,
        salesProjectRows,
        opportunityRows,
      ] = await Promise.all([
        superworkApi.getRevenueMatrix(year),
        superworkApi.getRevenuePending(),
        superworkApi.getRevenueEstimates(),
        superworkApi
          .getDeliverySummary({
            year,
            includeEstimate: deliveryIncludeEstimate,
            excludeTax: deliveryExcludeTax,
          })
          .catch(() => ({})),
        superworkApi.getRevenueImportBatches().catch(() => []),
        superworkApi.getDeliveryContractBatches().catch(() => []),
        superworkApi.getPendingDeliveryContracts().catch(() => []),
        superworkApi.getMappedDeliveryContracts(year).catch(() => []),
        superworkApi
          .getBusinessLines({ page: 1, size: 200 })
          .catch(() => ({ records: [] })),
        superworkApi
          .getProjects({ page: 1, size: 500 })
          .catch(() => ({ records: [] })),
        superworkApi.getRevenueSalesProjects().catch(() => []),
        superworkApi.getRevenueOpportunityOptions().catch(() => []),
      ]);
      setMatrix(m);
      setPending(p);
      setEstimates(e);
      setDeliverySummary(d);
      setImportBatches(b);
      setContractBatches(cb);
      setPendingContracts(pendingContractRows);
      setMappedContracts(mappedContractRows);
      setBusinessLines(linePage.records || []);
      setProjects(projectPage.records || []);
      setSalesProjects(salesProjectRows);
      setOpportunityOptions(opportunityRows);
    } catch (x) {
      setError(x instanceof Error ? x.message : '营收数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [deliveryExcludeTax, deliveryIncludeEstimate, year]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    setActiveTab(revenueTabByPath[location.pathname] || 'matrix');
  }, [location.pathname]);
  const formatWan = (value: unknown) => {
    const amount = Number(value || 0) / 10000;
    return amount
      ? amount.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
      : '—';
  };
  const formatHours = (value: unknown) => {
    if (value == null || value === '') return '—';
    const num = Number(value);
    if (!Number.isFinite(num) || num === 0) return '—';
    return String(Math.round(num * 100) / 100);
  };
  const deliveryLines = Array.isArray(deliverySummary.lines)
    ? deliverySummary.lines
    : [];
  const deliveryPeriodOf = (row: Record<string, any>) =>
    row?.[deliveryPeriod] || {};
  const deliveryOverview = deliverySummary.overview || {};
  const deliveryMatrixRows = useMemo(
    () =>
      deliveryLines.flatMap((line: Record<string, any>) => {
        const lineTotal = line.totals || {};
        const lineRow = {
          key: `line-${line.businessLineId}`,
          label: line.businessLineName || '未命名业务线',
          kind: 'line',
          row: lineTotal,
          line,
        };
        const projectRows = (
          Array.isArray(line.projects) ? line.projects : []
        ).map((project: Record<string, any>) => ({
          key: `project-${line.businessLineId}-${project.projectId || project.name}`,
          label: `　${project.name || '未命名项目'}`,
          kind: 'project',
          row: project,
          project,
          line,
        }));
        return [lineRow, ...projectRows];
      }),
    [deliveryLines],
  );
  const openDeliveryDetail = (row: Record<string, any>, label: string) =>
    setDeliveryDetail({ open: true, row, label });
  // ---------- 营收矩阵（工时 & 成本）----------
  const matrixLines: RevenueLineBlock[] = matrix?.lines ?? [];
  const matrixMonths: RevenueMonthInfo[] = matrix?.months ?? [];
  const mergeCellSource = (
    current: RevenueCell['source'],
    next: RevenueCell['source'],
  ): RevenueCell['source'] => {
    if (next == null) return current ?? null;
    if (current == null) return next;
    return current === next ? current : 'mixed';
  };
  const sumCells = (targetRows: RevenueRow[]) => {
    const months: RevenueCell[] = Array.from({ length: 12 }, () => ({
      hours: 0,
      cost: 0,
      source: null,
    }));
    targetRows.forEach((row) => {
      (row.months ?? []).forEach((cell, i) => {
        const month = months[i];
        if (!month) return;
        month.hours += Number(cell?.hours || 0);
        month.cost += Number(cell?.cost || 0);
        month.source = mergeCellSource(month.source, cell?.source ?? null);
      });
    });
    const totals = months.reduce<RevenueCell>(
      (acc, cell) => ({
        hours: acc.hours + cell.hours,
        cost: acc.cost + cell.cost,
        source: mergeCellSource(acc.source, cell.source),
      }),
      { hours: 0, cost: 0, source: null },
    );
    return { months, totals };
  };
  // 「只看实际」口径：预估格按空值参与汇总
  const rowWithVisibleMonths = (row: RevenueRow): RevenueRow => {
    if (showEstimates) return row;
    const months = (row.months ?? []).map<RevenueCell>((cell) =>
      cell?.source === 'estimate' ? { hours: 0, cost: 0, source: null } : cell,
    );
    const totals = months.reduce<RevenueCell>(
      (acc, cell) => ({
        hours: acc.hours + Number(cell?.hours || 0),
        cost: acc.cost + Number(cell?.cost || 0),
        source: mergeCellSource(acc.source, cell?.source ?? null),
      }),
      { hours: 0, cost: 0, source: null },
    );
    return { ...row, months, totals };
  };
  const filteredMatrixLines = useMemo<RevenueLineBlock[]>(() => {
    const projectFilterActive = filterProjectIds.length > 0;
    return matrixLines
      .filter(
        (line) =>
          !filterLineIds.length || filterLineIds.includes(line.businessLineId),
      )
      .map((line) => {
        const sections = (line.sections ?? [])
          .map((section) => ({
            ...section,
            rows: (section.rows ?? [])
              .filter((row) =>
                projectFilterActive
                  ? row.kind === 'project' &&
                    row.projectId != null &&
                    filterProjectIds.includes(row.projectId)
                  : true,
              )
              .map(rowWithVisibleMonths),
          }))
          .filter((section) => section.rows.length > 0);
        const { months, totals } = sumCells(
          sections.flatMap((section) => section.rows),
        );
        return { ...line, sections, monthTotals: months, totals };
      })
      .filter((line) => line.sections.length > 0);
  }, [matrixLines, filterLineIds, filterProjectIds, showEstimates]);
  const filteredMonthTotals = useMemo<RevenueCell[]>(() => {
    const totals: RevenueCell[] = Array.from({ length: 12 }, () => ({
      hours: 0,
      cost: 0,
      source: null,
    }));
    filteredMatrixLines.forEach((line) => {
      (line.monthTotals ?? []).forEach((cell, i) => {
        const month = totals[i];
        if (!month) return;
        month.hours += Number(cell?.hours || 0);
        month.cost += Number(cell?.cost || 0);
        month.source = mergeCellSource(month.source, cell?.source ?? null);
      });
    });
    return totals;
  }, [filteredMatrixLines]);
  const filteredGrandTotal = useMemo<RevenueCell>(() => {
    const total: RevenueCell = { hours: 0, cost: 0, source: null };
    filteredMatrixLines.forEach((line) => {
      total.hours += Number(line.totals?.hours || 0);
      total.cost += Number(line.totals?.cost || 0);
      total.source = mergeCellSource(total.source, line.totals?.source ?? null);
    });
    return total;
  }, [filteredMatrixLines]);
  const filteredMatrixOverview = useMemo(() => {
    const allRows = filteredMatrixLines.flatMap((line) =>
      line.sections.flatMap((section) => section.rows),
    );
    const sumHours = (rows: RevenueRow[]) =>
      rows.reduce((sum, row) => sum + Number(row.totals?.hours || 0), 0);
    const projectHours = sumHours(
      allRows.filter((row) => PROJECT_KINDS.includes(row.kind)),
    );
    const salesHours = sumHours(
      allRows.filter((row) => !PROJECT_KINDS.includes(row.kind)),
    );
    const totalHours = projectHours + salesHours;
    const totalCost = Number(filteredGrandTotal.cost || 0);
    return {
      totalHours,
      projectHours,
      salesHours,
      totalCost,
      avgUnitPrice: totalHours > 0 ? totalCost / totalHours : null,
      closedMonthCount: Number(
        matrix?.overview.closedMonthCount ??
          matrixMonths.filter((month) => month.closed).length,
      ),
    };
  }, [filteredMatrixLines, filteredGrandTotal, matrix, matrixMonths]);
  const flatMatrixRows = useMemo<MatrixDisplayRow[]>(() => {
    const result: MatrixDisplayRow[] = [];
    filteredMatrixLines.forEach((line) => {
      const lineRows: MatrixDisplayRow[] = [];
      (line.sections ?? []).forEach((section) => {
        (section.rows ?? []).forEach((row, index) => {
          lineRows.push({
            kind: 'data',
            key: `data-${line.businessLineId}-${row.rowKey}`,
            lineSpan: 0,
            sectionLabel: section.type === 'project' ? '项目' : '销售',
            sectionSpan: index === 0 ? section.rows.length : 0,
            lineId: line.businessLineId,
            lineName: line.businessLineName,
            row: {
              ...row,
              businessLineId: line.businessLineId,
              businessLineName: line.businessLineName,
            },
            monthTotals: row.months ?? [],
            totals: row.totals,
          });
        });
      });
      lineRows.forEach((item, index) => {
        item.lineSpan = index === 0 ? lineRows.length : 0;
      });
      result.push(...lineRows);
      // 单行汇总的业务线（海外/全渠道产品/全域精准）不需要小计
      if (line.mode !== 'simple') {
        result.push({
          kind: 'line_total',
          key: `line-total-${line.businessLineId}`,
          lineSpan: 1,
          sectionLabel: '小计',
          sectionSpan: 1,
          lineId: line.businessLineId,
          lineName: line.businessLineName,
          row: null,
          monthTotals: line.monthTotals ?? [],
          totals: line.totals,
        });
      }
    });
    if (result.length) {
      result.push({
        kind: 'grand_total',
        key: 'grand-total',
        lineSpan: 1,
        sectionLabel: '',
        sectionSpan: 1,
        lineId: 0,
        lineName: '合计',
        row: null,
        monthTotals: filteredMonthTotals,
        totals: filteredGrandTotal,
      });
    }
    return result;
  }, [filteredMatrixLines, filteredMonthTotals, filteredGrandTotal]);
  const projectOptionsOf = (lineIds: number[]) =>
    matrixLines
      .filter(
        (line) => !lineIds.length || lineIds.includes(line.businessLineId),
      )
      .flatMap((line) =>
        (line.sections ?? []).flatMap((section) => section.rows ?? []),
      )
      .filter((row) => row.kind === 'project' && row.projectId != null)
      .map((row) => ({
        id: Number(row.projectId),
        name: String(row.name ?? ''),
      }));
  const projectFilterOptions = useMemo(
    () => projectOptionsOf(filterLineIds),
    [matrixLines, filterLineIds],
  );
  const toggleInList = (list: number[], id: number) =>
    list.includes(id) ? list.filter((item) => item !== id) : [...list, id];
  const toggleLineFilter = (id: number) => {
    const next = toggleInList(filterLineIds, id);
    const valid: Record<number, true> = {};
    projectOptionsOf(next).forEach((option) => {
      valid[option.id] = true;
    });
    setFilterLineIds(next);
    setFilterProjectIds((prev) => prev.filter((pid) => valid[pid]));
  };
  const resetMatrixFilters = () => {
    setFilterLineIds([]);
    setFilterProjectIds([]);
  };
  const matrixOverviewCells = [
    {
      label: '年度总工时',
      value: formatHours(filteredMatrixOverview.totalHours),
      unit: '人月',
    },
    {
      label: '项目工时',
      value: formatHours(filteredMatrixOverview.projectHours),
      unit: '人月',
    },
    {
      label: '销售工时',
      value: formatHours(filteredMatrixOverview.salesHours),
      unit: '人月',
    },
    {
      label: '年度总成本',
      value: formatWan(filteredMatrixOverview.totalCost),
      unit: '万元',
    },
    {
      label: '综合单价',
      value:
        filteredMatrixOverview.avgUnitPrice == null
          ? '—'
          : formatWan(filteredMatrixOverview.avgUnitPrice),
      unit: '万/人月',
    },
    {
      label: '已完结月份',
      value: String(filteredMatrixOverview.closedMonthCount),
      unit: '/ 12',
    },
  ];

  const openCell = async (row: MatrixRowContext, yearMonth: string) => {
    const businessLineId = Number(row.businessLineId || 0);
    const rowKey = String(row.rowKey || '');
    if (!businessLineId || !rowKey || !yearMonth) {
      message.info('该矩阵行缺少单元格明细定位信息');
      return;
    }
    setCellContext({
      businessLineId,
      rowKey,
      yearMonth,
      title: `${row.businessLineName || '业务线'} / ${row.name || rowKey} / ${yearMonth}`,
    });
    setCellRow(row);
    setCellOpen(true);
    setCellLoading(true);
    try {
      setCellDetail(
        await superworkApi.getRevenueCellDetail(
          yearMonth,
          businessLineId,
          rowKey,
        ),
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '单元格明细加载失败');
    } finally {
      setCellLoading(false);
    }
  };
  const refreshCell = async () => {
    if (
      !cellContext.yearMonth ||
      !cellContext.businessLineId ||
      !cellContext.rowKey
    ) {
      return;
    }
    setCellLoading(true);
    try {
      setCellDetail(
        await superworkApi.getRevenueCellDetail(
          cellContext.yearMonth,
          cellContext.businessLineId,
          cellContext.rowKey,
        ),
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '单元格明细加载失败');
    } finally {
      setCellLoading(false);
    }
  };
  const buildEntryKind = () => {
    const kindMap: Record<
      string,
      { workType: string; salesKind?: string | null }
    > = {
      project: { workType: 'project' },
      line_pool: { workType: 'project' },
      agg_project: { workType: 'project' },
      sales_specific: { workType: 'sales', salesKind: 'specific' },
      pool: { workType: 'sales', salesKind: 'pool' },
      agg_sales: { workType: 'sales' },
      other: { workType: 'sales', salesKind: 'other' },
      simple: { workType: 'project' },
    };
    return kindMap[cellRow?.kind ?? 'project'] || kindMap.project;
  };
  const openEstimate = (row?: RevenueEstimateEntry) => {
    setEstimateState({ open: true, row });
    estimateForm.setFieldsValue({
      description: row?.description || '',
      personMonths: row?.personMonths ?? 1,
    });
  };
  const saveEstimate = async () => {
    try {
      const values = await estimateForm.validateFields();
      const kind = buildEntryKind();
      const payload = {
        yearMonth: cellContext.yearMonth,
        businessLineId: cellContext.businessLineId,
        projectId: cellRow?.projectId ?? null,
        salesProjectId: cellRow?.salesProjectId ?? null,
        workType: kind.workType,
        salesKind: kind.salesKind ?? null,
        description: String(values.description || '').trim(),
        personMonths: Number(values.personMonths),
      };
      if (estimateState.row?.id) {
        await superworkApi.updateRevenueEstimate(
          Number(estimateState.row.id),
          payload,
        );
        message.success('预估已更新');
      } else {
        await superworkApi.createRevenueEstimate(payload);
        message.success('预估已添加');
      }
      setEstimateState({ open: false });
      await Promise.all([load(), refreshCell()]);
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '预估保存失败');
    }
  };
  const removeEstimate = (row: RevenueEstimateEntry) => {
    Modal.confirm({
      title: '删除营收估算？',
      content: row.description || '删除后不可恢复',
      okType: 'danger',
      onOk: async () => {
        await superworkApi.deleteRevenueEstimate(Number(row.id));
        message.success('估算已删除');
        await Promise.all([load(), refreshCell()]);
      },
    });
  };
  const openEntry = (
    kind: 'worklog' | 'cost',
    row?: RevenueWorklogEntry | RevenueCostEntry,
  ) => {
    setEntryState({ open: true, kind, row });
    const fallbackProject = `${cellRow?.name || '项目'}（手工补录）`;
    if (kind === 'worklog') {
      const entry = row as RevenueWorklogEntry | undefined;
      entryForm.setFieldsValue({
        employeeName: entry?.employeeName || '',
        department: entry?.department || '',
        hours: entry?.hours ?? 0.1,
        workNote: entry?.workNote || '',
        specialNote: entry?.specialNote || '',
        projectNameRaw: entry?.projectNameRaw || fallbackProject,
      });
      return;
    }
    const entry = row as RevenueCostEntry | undefined;
    entryForm.setFieldsValue({
      employeeCount: entry?.employeeCount ?? undefined,
      hours: entry?.hours ?? 0,
      costAmount: entry?.costAmount ?? 0,
      personMonthCost: entry?.personMonthCost ?? undefined,
      projectNameRaw: entry?.projectNameRaw || fallbackProject,
    });
  };
  const saveEntry = async () => {
    try {
      const values = await entryForm.validateFields();
      const kind = buildEntryKind();
      const base = {
        yearMonth: cellContext.yearMonth,
        businessLineId: cellContext.businessLineId,
        projectId: cellRow?.projectId ?? null,
        salesProjectId: cellRow?.salesProjectId ?? null,
        workType: kind.workType,
        salesKind: kind.salesKind ?? null,
      };
      if (entryState.kind === 'worklog') {
        const payload = {
          ...base,
          employeeName: String(values.employeeName || '').trim(),
          department: String(values.department || '').trim(),
          hours: Number(values.hours),
          workNote: String(values.workNote || '').trim(),
          specialNote: String(values.specialNote || '').trim(),
          projectNameRaw: String(values.projectNameRaw || '').trim(),
        };
        if (entryState.row?.id) {
          await superworkApi.updateRevenueWorklogEntry(
            Number(entryState.row.id),
            payload,
          );
        } else {
          await superworkApi.createRevenueWorklogEntry(payload);
        }
      } else {
        const payload = {
          ...base,
          projectNameRaw: String(values.projectNameRaw || '').trim(),
          employeeCount:
            values.employeeCount == null ? null : Number(values.employeeCount),
          hours: Number(values.hours || 0),
          costAmount: Number(values.costAmount || 0),
          personMonthCost:
            values.personMonthCost == null
              ? null
              : Number(values.personMonthCost),
        };
        if (entryState.row?.id) {
          await superworkApi.updateRevenueCostEntry(
            Number(entryState.row.id),
            payload,
          );
        } else {
          await superworkApi.createRevenueCostEntry(payload);
        }
      }
      message.success('明细已保存');
      setEntryState({ open: false, kind: entryState.kind });
      await Promise.all([load(), refreshCell()]);
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
      message.error(e instanceof Error ? e.message : '明细保存失败');
    }
  };
  const removeEntry = (
    kind: 'worklog' | 'cost',
    row: RevenueWorklogEntry | RevenueCostEntry,
  ) => {
    Modal.confirm({
      title: '删除这条明细？',
      content:
        row.projectNameRaw ||
        ('employeeName' in row ? row.employeeName : '') ||
        '删除后不可恢复',
      okType: 'danger',
      onOk: async () => {
        if (kind === 'worklog') {
          await superworkApi.deleteRevenueWorklogEntry(Number(row.id));
        } else {
          await superworkApi.deleteRevenueCostEntry(Number(row.id));
        }
        message.success('明细已删除');
        await Promise.all([load(), refreshCell()]);
      },
    });
  };
  const deliveryContext = (row?: Record<string, any>) => ({
    businessLineId: Number(
      row?.businessLineId || row?.lineId || row?.businessLine?.id || 0,
    ),
    projectId: row?.projectId == null ? null : Number(row.projectId),
  });
  const loadDeliveryRecords = async (
    row: Record<string, any>,
    kind: 'plans' | 'costs',
  ) => {
    const context = deliveryContext(row);
    if (!context.businessLineId) {
      message.info('该行缺少业务线定位信息');
      return;
    }
    setDeliveryBusy(true);
    try {
      if (kind === 'plans')
        setDeliveryPlans(
          await superworkApi.getDeliveryPlans({ year, ...context }),
        );
      else
        setDeliveryCosts(
          await superworkApi.getOtherCosts({ year, ...context }),
        );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '交付维护数据加载失败');
    } finally {
      setDeliveryBusy(false);
    }
  };
  const openDelivery = async (
    row: Record<string, any>,
    kind: 'plans' | 'costs',
  ) => {
    setDeliveryState({ open: true, kind, row });
    setEditingPlan(undefined);
    setDeliveryUnitPrice(null);
    setPlanRows([newPlanRow()]);
    costForm.resetFields();
    await loadDeliveryRecords(row, kind);
    if (kind === 'plans' && row.projectId != null) {
      try {
        const value = await superworkApi.getDeliveryUnitPrice(
          Number(row.projectId),
        );
        setDeliveryUnitPrice(value == null ? null : Number(value));
      } catch {
        setDeliveryUnitPrice(null);
      }
    }
  };
  const savePlanBatch = async () => {
    const row = deliveryState.row;
    if (!row) return;
    const context = deliveryContext(row);
    const rowsToSave = planRows.filter(
      (item) =>
        item.yearMonth &&
        (Number(item.amountWan) > 0 || Number(item.personMonths) > 0),
    );
    if (!rowsToSave.length) {
      message.warning('请至少填写一条月份与金额或人月');
      return;
    }
    setDeliverySaving(true);
    try {
      await superworkApi.createDeliveryPlansBatch({
        ...context,
        year,
        rows: rowsToSave.map((item) => ({
          yearMonth: item.yearMonth,
          amountYuan: Math.round(Number(item.amountWan) * 10000),
          personMonths: Number(item.personMonths),
        })),
      });
      message.success(`已保存 ${rowsToSave.length} 条预估交付`);
      setPlanRows([newPlanRow()]);
      await Promise.all([loadDeliveryRecords(row, 'plans'), load()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预估交付保存失败');
    } finally {
      setDeliverySaving(false);
    }
  };
  const savePlanEdit = async (record: Record<string, any>) => {
    setDeliverySaving(true);
    try {
      await superworkApi.updateDeliveryPlan(Number(record.id), {
        yearMonth: record.yearMonth,
        amountYuan: Math.round(Number(planEdit.amountWan) * 10000),
        personMonths: Number(planEdit.personMonths),
      });
      message.success('预估交付已更新');
      setEditingPlan(undefined);
      if (deliveryState.row)
        await Promise.all([
          loadDeliveryRecords(deliveryState.row, 'plans'),
          load(),
        ]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预估交付更新失败');
    } finally {
      setDeliverySaving(false);
    }
  };
  const removePlan = (record: Record<string, any>) =>
    Modal.confirm({
      title: '删除预估交付？',
      content: `${record.yearMonth || '该月份'} · ${(Number(record.amountYuan || 0) / 10000).toFixed(2)} 万`,
      okType: 'danger',
      onOk: async () => {
        await superworkApi.deleteDeliveryPlan(Number(record.id));
        message.success('预估交付已删除');
        if (deliveryState.row)
          await Promise.all([
            loadDeliveryRecords(deliveryState.row, 'plans'),
            load(),
          ]);
      },
    });
  const saveCost = async () => {
    const row = deliveryState.row;
    if (!row) return;
    const values = await costForm.validateFields();
    const context = deliveryContext(row);
    setDeliverySaving(true);
    try {
      const payload = {
        ...context,
        yearMonth: values.yearMonth,
        costType: values.costType,
        amountYuan: Math.round(Number(values.amountWan) * 10000),
        note: String(values.note || '').trim(),
      };
      if (values.id)
        await superworkApi.updateOtherCost(Number(values.id), payload);
      else await superworkApi.createOtherCost(payload);
      message.success(values.id ? '其他成本已更新' : '其他成本已添加');
      costForm.resetFields();
      await Promise.all([loadDeliveryRecords(row, 'costs'), load()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '其他成本保存失败');
    } finally {
      setDeliverySaving(false);
    }
  };
  const editCost = (record: Record<string, any>) =>
    costForm.setFieldsValue({
      id: record.id,
      yearMonth: record.yearMonth,
      costType: record.costType || 'partner',
      amountWan: Number(record.amountYuan || 0) / 10000,
      note: record.note || '',
    });
  const removeCost = (record: Record<string, any>) =>
    Modal.confirm({
      title: '删除其他成本？',
      content:
        record.note ||
        `${record.yearMonth || '该月份'} · ${(Number(record.amountYuan || 0) / 10000).toFixed(2)} 万`,
      okType: 'danger',
      onOk: async () => {
        await superworkApi.deleteOtherCost(Number(record.id));
        message.success('其他成本已删除');
        if (deliveryState.row)
          await Promise.all([
            loadDeliveryRecords(deliveryState.row, 'costs'),
            load(),
          ]);
      },
    });
  const toggleMonthClose = async (month: RevenueMonthInfo) => {
    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: month.closed ? '取消完结' : '标记完结',
        content: month.closed
          ? `取消完结后，${month.yearMonth} 将改回展示预估数据，且允许重新导入。确定继续吗？`
          : `完结后 ${month.yearMonth} 展示导入的实际数据并锁定（不可导入、不可改预估）。确定完结吗？`,
        okText: '确定',
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!confirmed) return;
    setCloseToggling(month.yearMonth);
    try {
      if (month.closed) {
        await superworkApi.reopenRevenueMonth(month.yearMonth);
        message.success(`${month.yearMonth} 已取消完结`);
      } else {
        await superworkApi.closeRevenueMonth(month.yearMonth);
        message.success(`${month.yearMonth} 已完结`);
      }
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '月结操作失败');
    } finally {
      setCloseToggling('');
    }
  };
  const renderMatrixValues = (cell: RevenueCell | undefined) => (
    <>
      {displayMode === 'hours' ? null : (
        <span className="sw-matrix-cost">{formatWan(cell?.cost)}</span>
      )}
      {displayMode === 'cost' ? null : (
        <span className="sw-matrix-hours">{formatHours(cell?.hours)}</span>
      )}
    </>
  );
  const matrixCols: TableColumnsType<MatrixDisplayRow> = [
    {
      title: '业务线',
      key: 'line',
      fixed: 'left',
      width: 132,
      className: 'sw-matrix-col-line',
      onCell: (record) => ({
        rowSpan: record.kind === 'data' ? record.lineSpan : 1,
      }),
      render: (_value, record) => record.lineName,
    },
    {
      title: '类型',
      key: 'type',
      fixed: 'left',
      width: 62,
      className: 'sw-matrix-col-type',
      onCell: (record) =>
        record.kind === 'data'
          ? { rowSpan: record.sectionSpan }
          : { colSpan: 2 },
      render: (_value, record) =>
        record.kind === 'data'
          ? record.row?.kind === 'simple'
            ? '—'
            : record.sectionLabel
          : record.sectionLabel,
    },
    {
      title: '项目',
      key: 'project',
      fixed: 'left',
      width: 190,
      className: 'sw-matrix-col-project',
      onCell: (record) => (record.kind === 'data' ? {} : { colSpan: 0 }),
      render: (_value, record) =>
        record.row ? (
          <>
            {record.row.name}
            {record.row.opportunityName ? (
              <span className="sw-matrix-opp">
                商机:{record.row.opportunityName}
              </span>
            ) : null}
          </>
        ) : null,
    },
    {
      title: (
        <>
          单价
          <small>万/人月</small>
        </>
      ),
      key: 'unitPrice',
      width: 84,
      className: 'sw-matrix-col-price',
      render: (_value, record) =>
        record.row?.unitPrice == null ? '—' : formatWan(record.row.unitPrice),
    },
    ...matrixMonths.map((month, index) => ({
      title: (
        <button
          type="button"
          className={`sw-matrix-month${month.closed ? ' closed' : ''}`}
          disabled={closeToggling === month.yearMonth}
          title={month.closed ? '已完结，点击取消完结' : '未完结，点击标记完结'}
          onClick={() => void toggleMonthClose(month)}
        >
          {index + 1}月{month.closed ? <em>完</em> : null}
        </button>
      ),
      key: month.yearMonth,
      width: 84,
      className: 'sw-matrix-col-month',
      onCell: (record: MatrixDisplayRow) => {
        const row = record.row;
        if (record.kind !== 'data' || !row) {
          return { className: 'sw-matrix-cell sw-matrix-sum' };
        }
        const cell = row.months?.[index];
        const clickable = row.kind !== 'simple' || !month.closed;
        return {
          className: [
            'sw-matrix-cell',
            cell?.source ?? 'empty',
            clickable ? 'clickable' : '',
          ]
            .filter(Boolean)
            .join(' '),
          onClick: clickable
            ? () => void openCell(row, month.yearMonth)
            : undefined,
        };
      },
      render: (_value: unknown, record: MatrixDisplayRow) => {
        const cell =
          record.kind === 'data'
            ? record.row?.months?.[index]
            : record.monthTotals[index];
        if (record.kind === 'data' && !cell?.source) {
          return <span className="sw-matrix-empty">—</span>;
        }
        return (
          <>
            {renderMatrixValues(cell)}
            {record.kind === 'data' && cell?.source === 'estimate' ? (
              <i className="sw-matrix-estimate">预</i>
            ) : null}
          </>
        );
      },
    })),
    {
      title: '合计',
      key: 'total',
      width: 92,
      className: 'sw-matrix-col-total',
      render: (_value, record) => renderMatrixValues(record.totals),
    },
  ];
  const pendingTable = (
    type: 'worklog' | 'cost',
    data: Record<string, any>[],
  ) =>
    data.length ? (
      <Table
        size="small"
        rowKey={(r) => String(r.id || r.key || r.yearMonth || r.name || 'row')}
        dataSource={data}
        columns={[
          ...Object.keys(data[0])
            .slice(0, 8)
            .map((key) => ({
              title: key,
              dataIndex: key,
              key,
              render: (value: unknown) => String(value ?? '—'),
            })),
          {
            title: '处理',
            key: 'action',
            render: (_: unknown, row: Record<string, any>) => (
              <Button
                type="link"
                onClick={() => {
                  setResolveState({ type, row });
                  resolveForm.setFieldsValue({
                    businessLineId: row.businessLineId,
                    projectId: row.projectId,
                  });
                }}
              >
                归属映射
              </Button>
            ),
          },
        ]}
        pagination={{ pageSize: 10 }}
      />
    ) : (
      <Empty
        description={type === 'worklog' ? '暂无待处理工时' : '暂无待处理成本'}
      />
    );
  const runImport = async (kind: 'worklog' | 'cost') => {
    const file = kind === 'worklog' ? worklogFile : costFile;
    if (!file) {
      message.warning('请选择文件');
      return;
    }
    setImporting(true);
    try {
      await (kind === 'worklog'
        ? superworkApi.importRevenueWorklog(file, importMonth)
        : superworkApi.importRevenueCost(file));
      message.success('导入已完成');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败');
    } finally {
      setImporting(false);
    }
  };
  const importContracts = async () => {
    if (!contractFile) {
      message.warning('请选择合同文件');
      return;
    }
    setContractBusy(true);
    try {
      await superworkApi.importDeliveryContracts(contractFile);
      message.success('合同导入已完成');
      setContractFile(undefined);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '合同导入失败');
    } finally {
      setContractBusy(false);
    }
  };
  const resolveContract = async (row: Record<string, any>) => {
    const draft = contractDrafts[String(row.id)] || {};
    if (!draft.businessLineId) {
      message.warning('请选择业务线');
      return;
    }
    setContractBusy(true);
    try {
      await superworkApi.resolvePendingDeliveryContract(
        Number(row.id),
        draft.projectId,
        draft.businessLineId,
      );
      message.success('合同已完成归属映射');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '合同映射失败');
    } finally {
      setContractBusy(false);
    }
  };
  const saveMappedContract = async (row: Record<string, any>) => {
    const draft = contractDrafts[String(row.id)] || {};
    if (!draft.businessLineId) {
      message.warning('请选择业务线');
      return;
    }
    setContractBusy(true);
    try {
      await superworkApi.updateDeliveryContractMapping(Number(row.id), {
        businessLineId: draft.businessLineId,
        projectId: draft.projectId ?? null,
      });
      message.success('合同归属已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '合同归属保存失败');
    } finally {
      setContractBusy(false);
    }
  };
  const bindOpportunity = async (
    row: Record<string, any>,
    opportunityId: number | null,
  ) => {
    if (!row.id) return;
    setContractBusy(true);
    try {
      await superworkApi.bindRevenueSalesProject(Number(row.id), opportunityId);
      message.success('商机关联已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '商机关联失败');
    } finally {
      setContractBusy(false);
    }
  };
  const drawerWorklogs: RevenueWorklogEntry[] =
    cellDetail?.worklogEntries ?? [];
  const drawerCosts: RevenueCostEntry[] = cellDetail?.costEntries ?? [];
  const drawerEstimates: RevenueEstimateEntry[] = cellDetail?.estimates ?? [];
  const deviationText = (actual: number, estimate: number) => {
    const diff = actual - estimate;
    const pct = estimate !== 0 ? (diff / estimate) * 100 : null;
    return {
      label: `${diff >= 0 ? '+' : ''}${(Math.round(diff * 100) / 100).toLocaleString('zh-CN')}`,
      pct: pct == null ? '—' : `${diff >= 0 ? '+' : ''}${pct.toFixed(1)}%`,
      tone: diff > 0 ? 'over' : diff < 0 ? 'under' : 'flat',
    };
  };
  const cellDeviation = useMemo(() => {
    if (!cellDetail?.closed || !drawerEstimates.length) return null;
    const estHours = drawerEstimates.reduce(
      (sum, item) => sum + Number(item.personMonths || 0),
      0,
    );
    const estCost = drawerEstimates.reduce(
      (sum, item) => sum + Number(item.amount || 0),
      0,
    );
    const actualHours = drawerWorklogs.length
      ? drawerWorklogs.reduce((sum, item) => sum + Number(item.hours || 0), 0)
      : drawerCosts.reduce((sum, item) => sum + Number(item.hours || 0), 0);
    const actualCost = drawerCosts.reduce(
      (sum, item) => sum + Number(item.costAmount || 0),
      0,
    );
    return {
      estHours,
      estCost,
      actualHours,
      actualCost,
      hoursText: deviationText(actualHours, estHours),
      costText: deviationText(actualCost / 10000, estCost / 10000),
    };
  }, [cellDetail, drawerEstimates, drawerWorklogs, drawerCosts]);
  return (
    <div className="sw-page sw-revenue">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA / REVENUE
          </Typography.Text>
          <Typography.Title level={2}>营收管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            工时、成本、营收矩阵与待处理数据统一查看，保留导入批次、月份结账和交付数据状态。
          </Typography.Paragraph>
          <SyncCutoff domains={['contract', 'worklog', 'cost']} />
        </div>
        <Space>
          <Select
            value={year}
            style={{ width: 118 }}
            aria-label="选择年份"
            options={[year - 1, year, year + 1].map((value) => ({
              label: `${value} 年`,
              value,
            }))}
            onChange={setYear}
          />
          <button
            type="button"
            className="ant-btn ant-btn-default"
            onClick={() => void load()}
          >
            <ReloadOutlined /> 刷新
          </button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取营收数据"
          description={error}
        />
      )}
      <Space wrap style={{ marginBottom: 14 }}>
        <Card variant="borderless" size="small">
          <Statistic
            title="交付收入"
            value={
              deliverySummary.totalRevenue
                ? `${(deliverySummary.totalRevenue / 10000).toFixed(1)} 万`
                : '—'
            }
          />
        </Card>
        <Card variant="borderless" size="small">
          <Statistic
            title="交付成本"
            value={
              deliverySummary.totalCost
                ? `${(deliverySummary.totalCost / 10000).toFixed(1)} 万`
                : '—'
            }
          />
        </Card>
        <Card variant="borderless" size="small">
          <Statistic title="导入批次" value={importBatches.length} />
        </Card>
        <Card variant="borderless" size="small">
          <Statistic title="合同批次" value={contractBatches.length} />
        </Card>
      </Space>
      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          setActiveTab(key);
          const path =
            Object.entries(revenueTabByPath).find(
              ([, tab]) => tab === key,
            )?.[0] || '/revenue/worktime';
          if (location.pathname !== path) history.push(path);
        }}
        items={[
          {
            key: 'matrix',
            label: '工时 & 成本',
            children: (
              <Card variant="borderless" loading={loading}>
                {matrixLines.length ? (
                  <>
                    <div className="sw-matrix-filter">
                      <fieldset
                        className="sw-matrix-pills"
                        aria-label="业务线筛选"
                      >
                        <button
                          type="button"
                          className={filterLineIds.length ? '' : 'active'}
                          onClick={() => setFilterLineIds([])}
                        >
                          全部业务线
                        </button>
                        {matrixLines.map((line) => (
                          <button
                            type="button"
                            key={line.businessLineId}
                            className={
                              filterLineIds.includes(line.businessLineId)
                                ? 'active'
                                : ''
                            }
                            onClick={() =>
                              toggleLineFilter(line.businessLineId)
                            }
                          >
                            {line.businessLineName}
                          </button>
                        ))}
                      </fieldset>
                      <fieldset
                        className="sw-matrix-pills"
                        aria-label="项目筛选"
                      >
                        <button
                          type="button"
                          className={filterProjectIds.length ? '' : 'active'}
                          onClick={() => setFilterProjectIds([])}
                        >
                          全部项目
                        </button>
                        {projectFilterOptions.map((project) => (
                          <button
                            type="button"
                            key={project.id}
                            className={
                              filterProjectIds.includes(project.id)
                                ? 'active'
                                : ''
                            }
                            onClick={() =>
                              setFilterProjectIds((prev) =>
                                toggleInList(prev, project.id),
                              )
                            }
                          >
                            {project.name}
                          </button>
                        ))}
                        {filterLineIds.length || filterProjectIds.length ? (
                          <button
                            type="button"
                            className="reset"
                            onClick={resetMatrixFilters}
                          >
                            重置
                          </button>
                        ) : null}
                      </fieldset>
                      <Space wrap className="sw-matrix-switch">
                        <Segmented
                          value={showEstimates ? 'estimate' : 'actual'}
                          onChange={(value) =>
                            setShowEstimates(value === 'estimate')
                          }
                          options={[
                            { label: '含预估', value: 'estimate' },
                            { label: '只看实际', value: 'actual' },
                          ]}
                        />
                        <Segmented
                          value={displayMode}
                          onChange={(value) =>
                            setDisplayMode(value as 'merge' | 'hours' | 'cost')
                          }
                          options={[
                            { label: '工时 + 成本', value: 'merge' },
                            { label: '仅工时', value: 'hours' },
                            { label: '仅成本', value: 'cost' },
                          ]}
                        />
                      </Space>
                    </div>
                    <section
                      className="sw-matrix-overview"
                      aria-label="年度概览"
                    >
                      {matrixOverviewCells.map((item) => (
                        <div
                          className="sw-matrix-overview-cell"
                          key={item.label}
                        >
                          <span>{item.label}</span>
                          <strong>{item.value}</strong>
                          <small>{item.unit}</small>
                        </div>
                      ))}
                    </section>
                    <div className="sw-matrix-toolbar">
                      <Typography.Text type="secondary">
                        点击月份表头切换完结状态，点击单元格查看明细并维护估算、工时、成本。
                      </Typography.Text>
                      <span className="sw-matrix-legend">
                        <i className="sw-matrix-swatch actual" />
                        实际（已完结）
                        <i className="sw-matrix-swatch estimate" />
                        预估
                      </span>
                    </div>
                    {flatMatrixRows.length ? (
                      <Table
                        size="small"
                        bordered
                        rowKey="key"
                        columns={matrixCols}
                        dataSource={flatMatrixRows}
                        pagination={false}
                        scroll={{ x: 1720 }}
                        rowClassName={(record) =>
                          record.kind === 'line_total'
                            ? 'sw-matrix-line-total'
                            : record.kind === 'grand_total'
                              ? 'sw-matrix-grand-total'
                              : ''
                        }
                      />
                    ) : (
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description="无匹配数据，请调整筛选条件"
                      />
                    )}
                  </>
                ) : (
                  <Empty description="暂无营收数据，请先在「数据导入」中导入工时与成本明细" />
                )}
              </Card>
            ),
          },
          {
            key: 'delivery',
            label: '交付与利润',
            children: (
              <Space
                orientation="vertical"
                style={{ width: '100%' }}
                size="middle"
              >
                <Card
                  variant="borderless"
                  className="sw-revenue-delivery-toolbar"
                >
                  <Space
                    wrap
                    style={{ width: '100%', justifyContent: 'space-between' }}
                  >
                    <Typography.Text type="secondary">
                      金额按合同交付日期归集；卡片下方可直接维护项目交付计划与其他成本。
                    </Typography.Text>
                    <Space wrap>
                      <Segmented
                        value={deliveryPeriod}
                        onChange={(value) =>
                          setDeliveryPeriod(value as 'h1' | 'h2' | 'ytd')
                        }
                        options={[
                          { label: '上半年 H1', value: 'h1' },
                          { label: '下半年 H2', value: 'h2' },
                          { label: '全年 YTD', value: 'ytd' },
                        ]}
                      />
                      <Segmented
                        value={deliveryIncludeEstimate ? 'estimate' : 'actual'}
                        onChange={(value) =>
                          setDeliveryIncludeEstimate(value === 'estimate')
                        }
                        options={[
                          { label: '含预估', value: 'estimate' },
                          { label: '只看实际', value: 'actual' },
                        ]}
                      />
                      <Segmented
                        value={deliveryExcludeTax ? 'exclude' : 'include'}
                        onChange={(value) =>
                          setDeliveryExcludeTax(value === 'exclude')
                        }
                        options={[
                          { label: '未税', value: 'exclude' },
                          { label: '含税', value: 'include' },
                        ]}
                      />
                      <Segmented
                        value={deliveryViewMode}
                        onChange={(value) =>
                          setDeliveryViewMode(value as 'cards' | 'matrix')
                        }
                        options={[
                          { label: '业务线卡片', value: 'cards' },
                          { label: '利润明细', value: 'matrix' },
                        ]}
                      />
                    </Space>
                  </Space>
                </Card>
                <Row gutter={[12, 12]}>
                  {[
                    {
                      label: '合同总额（收款月）',
                      value: deliveryOverview.totalOaContractBySaleMonth,
                    },
                    { label: '已交付', value: deliveryOverview.totalDelivered },
                    {
                      label: '预估交付',
                      value: deliveryOverview.totalEstimated,
                    },
                    {
                      label: '人工成本',
                      value: deliveryOverview.totalLaborCost,
                    },
                    {
                      label: '其他成本',
                      value: deliveryOverview.totalOtherCost,
                    },
                    {
                      label: '真实利润',
                      value:
                        deliveryOverview.totalTrueProfit ??
                        deliveryOverview.totalProfit,
                    },
                  ].map((item) => (
                    <Col xs={12} sm={8} lg={4} key={item.label}>
                      <Card variant="borderless" size="small">
                        <Statistic
                          title={item.label}
                          value={formatWan(item.value)}
                          suffix={item.value ? '万' : undefined}
                        />
                      </Card>
                    </Col>
                  ))}
                </Row>
                {deliveryLines.length && deliveryViewMode === 'cards' ? (
                  deliveryLines.map((line: Record<string, any>) => {
                    const total = line.totals || {};
                    const period = deliveryPeriodOf(total);
                    return (
                      <Card
                        key={line.businessLineId}
                        variant="borderless"
                        title={
                          <Space>
                            <Typography.Text strong>
                              {line.businessLineName}
                            </Typography.Text>
                            <Tag color="blue">
                              {formatWan(period.delivered)} 万已交付
                            </Tag>
                            <Tag
                              color={
                                Number(
                                  period.trueProfit ?? period.grossProfit ?? 0,
                                ) < 0
                                  ? 'error'
                                  : 'success'
                              }
                            >
                              利润{' '}
                              {formatWan(
                                period.trueProfit ?? period.grossProfit,
                              )}{' '}
                              万
                            </Tag>
                          </Space>
                        }
                        extra={
                          <Space>
                            <Typography.Text type="secondary">
                              项目{' '}
                              {Array.isArray(line.projects)
                                ? line.projects.length
                                : 0}
                            </Typography.Text>
                            <Button
                              type="link"
                              size="small"
                              onClick={() =>
                                openDeliveryDetail(
                                  { ...line, ...total },
                                  `${line.businessLineName} · ${deliveryPeriod.toUpperCase()} 利润明细`,
                                )
                              }
                            >
                              利润明细
                            </Button>
                            <Button
                              type="link"
                              size="small"
                              onClick={() =>
                                void openDelivery(
                                  {
                                    kind: 'line',
                                    name: `${line.businessLineName}小计`,
                                    businessLineId: line.businessLineId,
                                    businessLineName: line.businessLineName,
                                    projectId: null,
                                  },
                                  'costs',
                                )
                              }
                            >
                              维护其他成本
                            </Button>
                          </Space>
                        }
                      >
                        <Row
                          gutter={[10, 10]}
                          className="sw-delivery-project-grid"
                        >
                          {(Array.isArray(line.projects)
                            ? line.projects
                            : []
                          ).map((project: Record<string, any>) => {
                            const projectPeriod = deliveryPeriodOf(project);
                            return (
                              <Col
                                xs={24}
                                md={12}
                                xl={8}
                                key={project.projectId || project.name}
                              >
                                <Card
                                  size="small"
                                  hoverable
                                  title={
                                    <Typography.Text
                                      ellipsis={{ tooltip: project.name }}
                                    >
                                      {project.name}
                                    </Typography.Text>
                                  }
                                  extra={
                                    <Space size={4}>
                                      <Tag
                                        color={
                                          Number(
                                            projectPeriod.trueProfit ??
                                              projectPeriod.grossProfit ??
                                              0,
                                          ) < 0
                                            ? 'error'
                                            : 'success'
                                        }
                                      >
                                        {formatWan(
                                          projectPeriod.trueProfit ??
                                            projectPeriod.grossProfit,
                                        )}{' '}
                                        万
                                      </Tag>
                                      <Button
                                        type="link"
                                        size="small"
                                        onClick={() =>
                                          openDeliveryDetail(
                                            project,
                                            `${project.name} · ${deliveryPeriod.toUpperCase()} 利润明细`,
                                          )
                                        }
                                      >
                                        明细
                                      </Button>
                                    </Space>
                                  }
                                >
                                  <Space
                                    orientation="vertical"
                                    style={{ width: '100%' }}
                                    size={4}
                                  >
                                    <Typography.Text type="secondary">
                                      已交付{' '}
                                      {formatWan(projectPeriod.delivered)} 万 ·
                                      预估 {formatWan(projectPeriod.estimated)}{' '}
                                      万
                                    </Typography.Text>
                                    <Typography.Text type="secondary">
                                      工时 {projectPeriod.projectHours || '—'}{' '}
                                      人月 · 其他成本{' '}
                                      {formatWan(
                                        projectPeriod.otherCosts?.total ??
                                          projectPeriod.otherCost,
                                      )}{' '}
                                      万
                                    </Typography.Text>
                                    <Space size={0}>
                                      <Button
                                        type="link"
                                        size="small"
                                        onClick={() =>
                                          void openDelivery(
                                            {
                                              ...project,
                                              kind: 'project',
                                              businessLineId:
                                                line.businessLineId,
                                              businessLineName:
                                                line.businessLineName,
                                            },
                                            'plans',
                                          )
                                        }
                                      >
                                        预估交付
                                      </Button>
                                      <Button
                                        type="link"
                                        size="small"
                                        onClick={() =>
                                          void openDelivery(
                                            {
                                              ...project,
                                              kind: 'project',
                                              businessLineId:
                                                line.businessLineId,
                                              businessLineName:
                                                line.businessLineName,
                                            },
                                            'costs',
                                          )
                                        }
                                      >
                                        其他成本
                                      </Button>
                                    </Space>
                                  </Space>
                                </Card>
                              </Col>
                            );
                          })}
                        </Row>
                      </Card>
                    );
                  })
                ) : deliveryViewMode === 'matrix' &&
                  deliveryMatrixRows.length ? (
                  <Card variant="borderless">
                    <Table
                      size="small"
                      rowKey="key"
                      pagination={false}
                      scroll={{ x: 980 }}
                      dataSource={deliveryMatrixRows.map((item) => ({
                        ...item,
                        ...deliveryPeriodOf(item.row),
                      }))}
                      columns={[
                        {
                          title: '业务线 / 项目',
                          dataIndex: 'label',
                          fixed: 'left',
                          width: 210,
                        },
                        {
                          title: '已交付',
                          dataIndex: 'delivered',
                          render: (value) => `${formatWan(value)} 万`,
                        },
                        {
                          title: '预估交付',
                          dataIndex: 'estimated',
                          render: (value) => `${formatWan(value)} 万`,
                        },
                        {
                          title: '工时',
                          dataIndex: 'projectHours',
                          render: (value) =>
                            `${Number(value || 0).toFixed(1)} 人月`,
                        },
                        {
                          title: '人工成本',
                          dataIndex: 'projectLaborCost',
                          render: (value) => `${formatWan(value)} 万`,
                        },
                        {
                          title: '其他成本',
                          dataIndex: ['otherCosts', 'total'],
                          render: (_value, row: Record<string, any>) =>
                            `${formatWan(row.otherCosts?.total)} 万`,
                        },
                        {
                          title: '真实利润',
                          dataIndex: 'trueProfit',
                          render: (value) => (
                            <Typography.Text
                              type={
                                Number(value || 0) < 0 ? 'danger' : undefined
                              }
                            >
                              {formatWan(value)} 万
                            </Typography.Text>
                          ),
                        },
                        {
                          title: '利润率',
                          dataIndex: 'trueProfitRate',
                          render: (value) =>
                            `${value == null ? '—' : Number(value).toFixed(2)}%`,
                        },
                        {
                          title: '操作',
                          fixed: 'right',
                          width: 100,
                          render: (_value, row: Record<string, any>) => (
                            <Button
                              type="link"
                              size="small"
                              onClick={() =>
                                openDeliveryDetail(
                                  row.line
                                    ? { ...row.line, ...row.row }
                                    : row.row,
                                  `${row.label} · ${deliveryPeriod.toUpperCase()} 利润明细`,
                                )
                              }
                            >
                              查看明细
                            </Button>
                          ),
                        },
                      ]}
                    />
                  </Card>
                ) : (
                  <Card variant="borderless">
                    <Empty description="暂无交付营收数据" />
                  </Card>
                )}
              </Space>
            ),
          },
          {
            key: 'import',
            label: '数据导入',
            children: (
              <Card variant="borderless">
                <Space orientation="vertical" style={{ width: '100%' }}>
                  <Alert
                    type="info"
                    showIcon
                    message="Excel 导入仅作兜底补录"
                    description={
                      <span>
                        合同 / 工时 / 成本已由系统每日自动从 OA
                        与工时系统拉取，同步状态与手动触发见
                        <a href="/system/sync">「系统管理 → 数据集成中心」</a>
                        ；仅历史补录或自动同步异常时使用本页导入。
                      </span>
                    }
                  />
                  <Space>
                    <Typography.Text>工时月份</Typography.Text>
                    <Input
                      value={importMonth}
                      onChange={(e) => setImportMonth(e.target.value)}
                      placeholder="YYYY-MM"
                      style={{ width: 120 }}
                    />
                    <input
                      type="file"
                      onChange={(e) => setWorklogFile(e.target.files?.[0])}
                    />
                    <Button
                      icon={<UploadOutlined />}
                      loading={importing}
                      onClick={() => void runImport('worklog')}
                    >
                      导入工时
                    </Button>
                  </Space>
                  <Space>
                    <input
                      type="file"
                      onChange={(e) => setCostFile(e.target.files?.[0])}
                    />
                    <Button
                      icon={<UploadOutlined />}
                      loading={importing}
                      onClick={() => void runImport('cost')}
                    >
                      导入成本
                    </Button>
                  </Space>
                  <Typography.Text type="secondary">
                    最近导入：
                    {importBatches
                      .slice(0, 3)
                      .map(
                        (b) => `${b.importType || '数据'} ${b.createdAt || ''}`,
                      )
                      .join(' · ') || '暂无'}
                  </Typography.Text>
                </Space>
              </Card>
            ),
          },
          {
            key: 'contracts',
            label: `交付合同 ${pendingContracts.length}`,
            children: (
              <Space
                orientation="vertical"
                style={{ width: '100%' }}
                size="middle"
              >
                <Card
                  variant="borderless"
                  title="合同导入"
                  extra={
                    <Space>
                      <input
                        type="file"
                        onChange={(event) =>
                          setContractFile(event.target.files?.[0])
                        }
                      />
                      <Button
                        loading={contractBusy}
                        icon={<UploadOutlined />}
                        onClick={() => void importContracts()}
                      >
                        导入合同
                      </Button>
                    </Space>
                  }
                >
                  <Typography.Text type="secondary">
                    导入后的未映射合同需要指定业务线和项目；已映射合同可在下方调整归属。
                  </Typography.Text>
                </Card>
                <Card
                  variant="borderless"
                  title={`待映射合同 ${pendingContracts.length}`}
                >
                  {pendingContracts.length ? (
                    <Table
                      size="small"
                      rowKey={(row) => String(row.id)}
                      dataSource={pendingContracts}
                      columns={[
                        ...Object.keys(pendingContracts[0])
                          .slice(0, 6)
                          .map((key) => ({
                            title: key,
                            dataIndex: key,
                            key,
                            render: (value: unknown) => String(value ?? '—'),
                          })),
                        {
                          title: '来源',
                          key: 'sourceSystem',
                          render: (_: unknown, row: Record<string, any>) => {
                            const source = row.sourceSystem as
                              | string
                              | undefined;
                            if (!source) return '—';
                            const color =
                              source === 'OA'
                                ? 'blue'
                                : source === 'WORKTIME'
                                  ? 'purple'
                                  : 'orange';
                            return <Tag color={color}>{source}</Tag>;
                          },
                        },
                        {
                          title: '业务线',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Select
                              style={{ width: 150 }}
                              placeholder="请选择"
                              value={
                                contractDrafts[String(row.id)]
                                  ?.businessLineId || row.businessLineId
                              }
                              options={businessLines.map((line) => ({
                                value: line.id,
                                label: line.name,
                              }))}
                              onChange={(value) =>
                                setContractDrafts((current) => ({
                                  ...current,
                                  [row.id]: {
                                    ...current[String(row.id)],
                                    businessLineId: value,
                                    projectId: undefined,
                                  },
                                }))
                              }
                            />
                          ),
                        },
                        {
                          title: '项目',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Select
                              allowClear
                              style={{ width: 180 }}
                              placeholder="业务线级"
                              value={
                                contractDrafts[String(row.id)]?.projectId ||
                                row.projectId
                              }
                              options={projects
                                .filter(
                                  (project) =>
                                    !contractDrafts[String(row.id)]
                                      ?.businessLineId ||
                                    project.businessLineId ===
                                      contractDrafts[String(row.id)]
                                        ?.businessLineId,
                                )
                                .map((project) => ({
                                  value: project.id,
                                  label: project.name,
                                }))}
                              onChange={(value) =>
                                setContractDrafts((current) => ({
                                  ...current,
                                  [row.id]: {
                                    ...current[String(row.id)],
                                    projectId: value ?? null,
                                  },
                                }))
                              }
                            />
                          ),
                        },
                        {
                          title: '操作',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Button
                              type="link"
                              loading={contractBusy}
                              onClick={() => void resolveContract(row)}
                            >
                              保存映射
                            </Button>
                          ),
                        },
                      ]}
                      pagination={{ pageSize: 8 }}
                    />
                  ) : (
                    <Empty description="暂无待映射合同" />
                  )}
                </Card>
                <Card
                  variant="borderless"
                  title={`已映射合同 ${mappedContracts.length}`}
                >
                  {mappedContracts.length ? (
                    <Table
                      size="small"
                      rowKey={(row) => String(row.id)}
                      dataSource={mappedContracts}
                      columns={[
                        ...Object.keys(mappedContracts[0])
                          .slice(0, 7)
                          .map((key) => ({
                            title: key,
                            dataIndex: key,
                            key,
                            render: (value: unknown) => String(value ?? '—'),
                          })),
                        {
                          title: '业务线',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Select
                              style={{ width: 150 }}
                              value={
                                contractDrafts[String(row.id)]
                                  ?.businessLineId || row.businessLineId
                              }
                              options={businessLines.map((line) => ({
                                value: line.id,
                                label: line.name,
                              }))}
                              onChange={(value) =>
                                setContractDrafts((current) => ({
                                  ...current,
                                  [row.id]: {
                                    ...current[String(row.id)],
                                    businessLineId: value,
                                  },
                                }))
                              }
                            />
                          ),
                        },
                        {
                          title: '项目',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Select
                              allowClear
                              style={{ width: 180 }}
                              value={
                                contractDrafts[String(row.id)]?.projectId ||
                                row.projectId
                              }
                              options={projects.map((project) => ({
                                value: project.id,
                                label: project.name,
                              }))}
                              onChange={(value) =>
                                setContractDrafts((current) => ({
                                  ...current,
                                  [row.id]: {
                                    ...current[String(row.id)],
                                    projectId: value ?? null,
                                  },
                                }))
                              }
                            />
                          ),
                        },
                        {
                          title: '操作',
                          render: (_: unknown, row: Record<string, any>) => (
                            <Button
                              type="link"
                              onClick={() => void saveMappedContract(row)}
                            >
                              保存
                            </Button>
                          ),
                        },
                      ]}
                      pagination={{ pageSize: 8 }}
                    />
                  ) : (
                    <Empty description="暂无已映射合同" />
                  )}
                </Card>
              </Space>
            ),
          },
          {
            key: 'pending',
            label: `待映射 ${pending.worklog.length + pending.cost.length}`,
            children: (
              <Space
                orientation="vertical"
                style={{ width: '100%' }}
                size="middle"
              >
                <Card variant="borderless">
                  <Tabs
                    items={[
                      {
                        key: 'worklog',
                        label: `工时 ${pending.worklog.length}`,
                        children: pendingTable('worklog', pending.worklog),
                      },
                      {
                        key: 'cost',
                        label: `成本 ${pending.cost.length}`,
                        children: pendingTable('cost', pending.cost),
                      },
                    ]}
                  />
                </Card>
                <Card
                  variant="borderless"
                  title={`销售项目 ${salesProjects.length}`}
                  extra={
                    <Typography.Text type="secondary">
                      将导入的销售项目关联到商机
                    </Typography.Text>
                  }
                >
                  {salesProjects.length ? (
                    <Table
                      size="small"
                      rowKey={(row) =>
                        String(
                          row.id ||
                            row.key ||
                            row.yearMonth ||
                            row.name ||
                            'row',
                        )
                      }
                      dataSource={salesProjects}
                      columns={[
                        {
                          title: '销售项目',
                          dataIndex: 'name',
                          render: (value: unknown) => String(value || '—'),
                        },
                        {
                          title: '业务线',
                          dataIndex: 'businessLineId',
                          render: (value: unknown) =>
                            businessLines.find(
                              (line) => line.id === Number(value),
                            )?.name || `#${value || '—'}`,
                        },
                        {
                          title: '关联商机',
                          key: 'opportunity',
                          width: 320,
                          render: (_: unknown, row: Record<string, any>) => (
                            <Select
                              allowClear
                              showSearch
                              optionFilterProp="label"
                              style={{ width: '100%' }}
                              value={row.opportunityId ?? undefined}
                              placeholder="选择商机"
                              loading={contractBusy}
                              options={opportunityOptions.map(
                                (opportunity) => ({
                                  value: opportunity.id,
                                  label: `${opportunity.name || '未命名'}${
                                    opportunity.customer
                                      ? ` · ${opportunity.customer}`
                                      : ''
                                  }`,
                                }),
                              )}
                              onChange={(value) =>
                                void bindOpportunity(row, value ?? null)
                              }
                            />
                          ),
                        },
                      ]}
                      pagination={{ pageSize: 8 }}
                    />
                  ) : (
                    <Empty description="暂无销售项目" />
                  )}
                </Card>
              </Space>
            ),
          },
          {
            key: 'estimates',
            label: `营收估算 ${estimates.length}`,
            children: (
              <Card variant="borderless">
                {estimates.length ? (
                  <Table
                    rowKey={(row) =>
                      String(
                        row.id || row.key || row.yearMonth || row.name || 'row',
                      )
                    }
                    dataSource={estimates}
                    columns={[
                      ...Object.keys(estimates[0])
                        .slice(0, 10)
                        .map((key) => ({
                          title: key,
                          dataIndex: key,
                          key,
                          render: (value: unknown) => String(value ?? '—'),
                        })),
                      {
                        title: '操作',
                        key: 'action',
                        render: (_: unknown, row: Record<string, any>) => (
                          <Button
                            danger
                            type="link"
                            icon={<DeleteOutlined />}
                            onClick={async () => {
                              if (!row.id) return;
                              try {
                                await superworkApi.deleteRevenueEstimate(
                                  Number(row.id),
                                );
                                message.success('估算已删除');
                                await load();
                              } catch (e) {
                                message.error(
                                  e instanceof Error
                                    ? e.message
                                    : '估算删除失败',
                                );
                              }
                            }}
                          >
                            删除
                          </Button>
                        ),
                      },
                    ]}
                    pagination={{ pageSize: 10 }}
                  />
                ) : (
                  <Empty description="暂无营收估算" />
                )}
              </Card>
            ),
          },
        ]}
      />
      <Drawer
        title={cellContext.title || '单元格明细'}
        size={720}
        open={cellOpen}
        onClose={() => setCellOpen(false)}
      >
        {cellLoading ? (
          <Typography.Text>加载中…</Typography.Text>
        ) : cellDetail?.closed ? (
          <Space orientation="vertical" size="large" style={{ width: '100%' }}>
            {cellDeviation ? (
              <section
                className="sw-matrix-deviation"
                aria-label="预估与实际偏差"
              >
                <h4>预估 vs 实际</h4>
                <div className="sw-matrix-deviation-grid">
                  <div className="sw-matrix-deviation-item">
                    <span>工时（人月）</span>
                    <p>
                      预估 {formatHours(cellDeviation.estHours)} · 实际{' '}
                      {formatHours(cellDeviation.actualHours)}
                    </p>
                    <strong className={`tone-${cellDeviation.hoursText.tone}`}>
                      {cellDeviation.hoursText.label}（
                      {cellDeviation.hoursText.pct}）
                    </strong>
                  </div>
                  <div className="sw-matrix-deviation-item">
                    <span>成本（万元）</span>
                    <p>
                      预估 {formatWan(cellDeviation.estCost)} · 实际{' '}
                      {formatWan(cellDeviation.actualCost)}
                    </p>
                    <strong className={`tone-${cellDeviation.costText.tone}`}>
                      {cellDeviation.costText.label}（
                      {cellDeviation.costText.pct}）
                    </strong>
                  </div>
                </div>
              </section>
            ) : null}
            <Card
              size="small"
              title={`工时明细（${drawerWorklogs.length}）`}
              extra={
                <Button
                  size="small"
                  type="primary"
                  onClick={() => openEntry('worklog')}
                >
                  新增工时
                </Button>
              }
            >
              {drawerWorklogs.length ? (
                <Table<RevenueWorklogEntry>
                  size="small"
                  rowKey="id"
                  dataSource={drawerWorklogs}
                  pagination={false}
                  scroll={{ x: 660 }}
                  columns={[
                    {
                      title: '姓名',
                      dataIndex: 'employeeName',
                      key: 'employeeName',
                      width: 90,
                      render: (value: string | undefined) => value || '—',
                    },
                    {
                      title: '部门',
                      dataIndex: 'department',
                      key: 'department',
                      width: 130,
                      ellipsis: true,
                      render: (value: string | undefined) => value || '—',
                    },
                    {
                      title: '人月',
                      dataIndex: 'hours',
                      key: 'hours',
                      width: 80,
                      render: (value: number) => formatHours(value),
                    },
                    {
                      title: '工作说明',
                      dataIndex: 'workNote',
                      key: 'workNote',
                      ellipsis: true,
                      render: (value: string | undefined) => value || '—',
                    },
                    {
                      title: '标签',
                      dataIndex: 'tags',
                      key: 'tags',
                      width: 130,
                      render: (tags: string | undefined) =>
                        (tags || '')
                          .split(',')
                          .filter(Boolean)
                          .map((tag) => (
                            <Tag key={tag} style={{ marginRight: 4 }}>
                              {tag}
                            </Tag>
                          )),
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 108,
                      render: (
                        _value: unknown,
                        record: RevenueWorklogEntry,
                      ) => (
                        <Space size={0}>
                          <Button
                            type="link"
                            size="small"
                            onClick={() => openEntry('worklog', record)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            type="link"
                            size="small"
                            onClick={() => removeEntry('worklog', record)}
                          >
                            删除
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="该月无工时明细，可点击右上角补录"
                />
              )}
            </Card>
            <Card
              size="small"
              title={`成本明细（${drawerCosts.length}）`}
              extra={
                <Button
                  size="small"
                  type="primary"
                  onClick={() => openEntry('cost')}
                >
                  新增成本
                </Button>
              }
            >
              {drawerCosts.length ? (
                <Table<RevenueCostEntry>
                  size="small"
                  rowKey="id"
                  dataSource={drawerCosts}
                  pagination={false}
                  scroll={{ x: 660 }}
                  columns={[
                    {
                      title: '项目',
                      dataIndex: 'projectNameRaw',
                      key: 'projectNameRaw',
                      width: 160,
                      ellipsis: true,
                    },
                    {
                      title: '人数',
                      dataIndex: 'employeeCount',
                      key: 'employeeCount',
                      width: 70,
                      render: (value: number | null | undefined) =>
                        value ?? '—',
                    },
                    {
                      title: '人月',
                      dataIndex: 'hours',
                      key: 'hours',
                      width: 80,
                      render: (value: number) => formatHours(value),
                    },
                    {
                      title: '成本（元）',
                      dataIndex: 'costAmount',
                      key: 'costAmount',
                      width: 108,
                    },
                    {
                      title: '人月成本（元）',
                      dataIndex: 'personMonthCost',
                      key: 'personMonthCost',
                      width: 118,
                      render: (value: number | null | undefined) =>
                        value ?? '—',
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 108,
                      render: (_value: unknown, record: RevenueCostEntry) => (
                        <Space size={0}>
                          <Button
                            type="link"
                            size="small"
                            onClick={() => openEntry('cost', record)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            type="link"
                            size="small"
                            onClick={() => removeEntry('cost', record)}
                          >
                            删除
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="该月无成本明细，可点击右上角补录"
                />
              )}
            </Card>
          </Space>
        ) : (
          <Card
            size="small"
            title={`预估明细（${drawerEstimates.length}）`}
            extra={
              <Button
                size="small"
                type="primary"
                onClick={() => openEstimate()}
              >
                新增预估
              </Button>
            }
          >
            <Typography.Paragraph
              type="secondary"
              className="sw-matrix-estimate-note"
            >
              {cellRow?.unitPrice == null
                ? '该行暂无完结历史，预估金额暂不计算。'
                : `当前行历史完结单价：${formatWan(cellRow.unitPrice)} 万/人月，金额按此自动计算。`}
            </Typography.Paragraph>
            {drawerEstimates.length ? (
              <Table<RevenueEstimateEntry>
                size="small"
                rowKey="id"
                dataSource={drawerEstimates}
                pagination={false}
                scroll={{ x: 620 }}
                columns={[
                  {
                    title: '说明',
                    dataIndex: 'description',
                    key: 'description',
                    ellipsis: true,
                  },
                  {
                    title: '人月',
                    dataIndex: 'personMonths',
                    key: 'personMonths',
                    width: 90,
                  },
                  {
                    title: '预估金额（元）',
                    dataIndex: 'amount',
                    key: 'amount',
                    width: 118,
                    render: (value: number | null | undefined) =>
                      value == null ? '—' : value,
                  },
                  {
                    title: '操作',
                    key: 'action',
                    width: 122,
                    render: (_value: unknown, record: RevenueEstimateEntry) => (
                      <Space size={0}>
                        <Button
                          type="link"
                          size="small"
                          onClick={() => openEstimate(record)}
                        >
                          编辑
                        </Button>
                        <Button
                          danger
                          type="link"
                          size="small"
                          onClick={() => removeEstimate(record)}
                        >
                          删除
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无预估明细，点击右上角新增"
              />
            )}
          </Card>
        )}
      </Drawer>
      <Modal
        title="处理待映射数据"
        open={Boolean(resolveState)}
        onCancel={() => setResolveState(undefined)}
        onOk={async () => {
          if (!resolveState) return;
          try {
            const values = await resolveForm.validateFields();
            await superworkApi.resolveRevenuePending(
              resolveState.type,
              Number(resolveState.row.id),
              Number(values.businessLineId),
              values.projectId ? Number(values.projectId) : undefined,
            );
            message.success('映射已保存');
            setResolveState(undefined);
            await load();
          } catch (e) {
            message.error(e instanceof Error ? e.message : '映射保存失败');
          }
        }}
      >
        <Form form={resolveForm} layout="vertical">
          <Form.Item
            name="businessLineId"
            label="业务线"
            rules={[{ required: true, message: '请选择业务线' }]}
          >
            <Select
              options={businessLines.map((line) => ({
                value: line.id,
                label: line.name,
              }))}
            />
          </Form.Item>
          <Form.Item name="projectId" label="项目">
            <Select
              allowClear
              options={projects.map((project) => ({
                value: project.id,
                label: project.name,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={estimateState.row ? '编辑营收预估' : '新增营收预估'}
        open={estimateState.open}
        onCancel={() => setEstimateState({ open: false })}
        onOk={() => void saveEstimate()}
        destroyOnHidden
      >
        <Form form={estimateForm} layout="vertical">
          <Form.Item
            name="description"
            label="预估说明"
            rules={[{ required: true, message: '请填写预估说明' }]}
          >
            <Input.TextArea rows={3} placeholder="例如：皇家标品升级" />
          </Form.Item>
          <Form.Item
            name="personMonths"
            label="人月"
            rules={[{ required: true, message: '请输入人月' }]}
          >
            <InputNumber min={0.01} precision={4} style={{ width: '100%' }} />
          </Form.Item>
          <Typography.Text type="secondary">
            归属：{cellContext.yearMonth} ·{' '}
            {cellRow?.name || cellContext.rowKey}
          </Typography.Text>
        </Form>
      </Modal>
      <Modal
        title={`${entryState.row ? '编辑' : '补录'}${
          entryState.kind === 'worklog' ? '工时' : '成本'
        }`}
        open={entryState.open}
        onCancel={() => setEntryState({ open: false, kind: entryState.kind })}
        onOk={() => void saveEntry()}
        destroyOnHidden
        width={600}
      >
        <Form form={entryForm} layout="vertical">
          <Form.Item
            name="projectNameRaw"
            label="项目原名"
            rules={[{ required: true, message: '请填写项目原名' }]}
          >
            <Input placeholder="用于保留导入或手工补录时的原始名称" />
          </Form.Item>
          <Form.Item
            name="hours"
            label="工时"
            rules={[{ required: true, message: '请输入工时' }]}
          >
            <InputNumber min={0} precision={4} style={{ width: '100%' }} />
          </Form.Item>
          {entryState.kind === 'worklog' ? (
            <>
              <Form.Item name="employeeName" label="人员">
                <Input placeholder="姓名" />
              </Form.Item>
              <Form.Item name="department" label="部门">
                <Input placeholder="部门" />
              </Form.Item>
              <Form.Item name="workNote" label="工作说明">
                <Input.TextArea rows={2} />
              </Form.Item>
              <Form.Item name="specialNote" label="特殊说明">
                <Input.TextArea rows={2} />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item name="employeeCount" label="人数">
                <InputNumber min={0} precision={2} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="costAmount"
                label="成本金额"
                rules={[{ required: true, message: '请输入成本金额' }]}
              >
                <InputNumber min={0} precision={2} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="personMonthCost" label="人月成本">
                <InputNumber min={0} precision={2} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
      <Drawer
        title={deliveryDetail.label || '交付利润明细'}
        open={deliveryDetail.open}
        onClose={() => setDeliveryDetail({ open: false })}
        size={520}
      >
        {deliveryDetail.row &&
          (() => {
            const row = deliveryDetail.row;
            const period = deliveryPeriodOf(row);
            const metricRows = [
              ['合同额', row.oaContract],
              ['已交付', period.delivered],
              ['预估交付', period.estimated],
              ['人工成本', period.projectLaborCost],
              ['销售成本', period.salesCost],
              ['其他成本', period.otherCosts?.total ?? period.otherCost],
              ['真实利润', period.trueProfit ?? period.grossProfit],
            ];
            const unallocated = Array.isArray(row.salesUnallocatedDetail)
              ? row.salesUnallocatedDetail
              : [];
            return (
              <Space
                orientation="vertical"
                style={{ width: '100%' }}
                size="middle"
              >
                <Row gutter={[10, 10]}>
                  {metricRows.map(([label, value]) => (
                    <Col span={12} key={String(label)}>
                      <Card size="small">
                        <Typography.Text type="secondary">
                          {label}
                        </Typography.Text>
                        <Typography.Title
                          level={4}
                          style={{ margin: '4px 0 0' }}
                        >
                          {formatWan(value)} 万
                        </Typography.Title>
                      </Card>
                    </Col>
                  ))}
                </Row>
                <Descriptions column={1} size="small" bordered>
                  <Descriptions.Item label="利润率">
                    {period.trueProfitRate == null && period.grossRate == null
                      ? '—'
                      : `${Number(period.trueProfitRate ?? period.grossRate).toFixed(2)}%`}
                  </Descriptions.Item>
                  <Descriptions.Item label="工时">
                    {Number(period.projectHours || 0).toFixed(1)} 人月
                  </Descriptions.Item>
                  <Descriptions.Item label="未交付日期合同">
                    {formatWan(row.noDeliveryDateContract)} 万
                  </Descriptions.Item>
                </Descriptions>
                {unallocated.length > 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message="销售成本存在未分配项"
                    description={
                      <Space orientation="vertical" size={2}>
                        {unallocated.map((item: Record<string, any>) => (
                          <Typography.Text
                            key={String(
                              item.reason || item.label || 'unallocated',
                            )}
                          >
                            {item.label || item.reason}：{formatWan(item.cost)}{' '}
                            万
                          </Typography.Text>
                        ))}
                      </Space>
                    }
                  />
                )}
                <Typography.Text type="secondary">
                  数据口径：{deliveryIncludeEstimate ? '含预估' : '只看实际'} ·{' '}
                  {deliveryExcludeTax ? '未税' : '含税'}
                </Typography.Text>
              </Space>
            );
          })()}
      </Drawer>
      <Modal
        title={`${deliveryState.row?.businessLineName || '业务线'} / ${deliveryState.row?.name || '项目'} · ${deliveryState.kind === 'plans' ? '预估交付计划' : '其他成本'}`}
        open={deliveryState.open}
        onCancel={() =>
          setDeliveryState({ open: false, kind: deliveryState.kind })
        }
        footer={null}
        width={860}
        destroyOnHidden
      >
        {deliveryState.kind === 'plans' ? (
          <Space orientation="vertical" style={{ width: '100%' }} size="middle">
            {deliveryUnitPrice != null && (
              <Typography.Text type="secondary">
                历史完结单价：{(deliveryUnitPrice / 10000).toFixed(2)} 万 /
                人月（仅作预估参考）
              </Typography.Text>
            )}
            <Table
              size="small"
              loading={deliveryBusy}
              rowKey={(record) => String(record.id)}
              dataSource={deliveryPlans}
              pagination={false}
              locale={{ emptyText: '暂无预估交付计划' }}
              columns={[
                { title: '月份', dataIndex: 'yearMonth', width: 110 },
                {
                  title: '金额（万）',
                  render: (_: unknown, record: Record<string, any>) =>
                    editingPlan?.id === record.id ? (
                      <InputNumber
                        min={0}
                        precision={2}
                        value={planEdit.amountWan}
                        onChange={(value) =>
                          setPlanEdit((state) => ({
                            ...state,
                            amountWan: Number(value || 0),
                          }))
                        }
                      />
                    ) : (
                      (Number(record.amountYuan || 0) / 10000).toFixed(2)
                    ),
                },
                {
                  title: '人月',
                  render: (_: unknown, record: Record<string, any>) =>
                    editingPlan?.id === record.id ? (
                      <InputNumber
                        min={0}
                        precision={2}
                        value={planEdit.personMonths}
                        onChange={(value) =>
                          setPlanEdit((state) => ({
                            ...state,
                            personMonths: Number(value || 0),
                          }))
                        }
                      />
                    ) : (
                      Number(record.personMonths || 0).toFixed(2)
                    ),
                },
                {
                  title: '人工成本（万）',
                  render: (_: unknown, record: Record<string, any>) =>
                    (Number(record.laborCostYuan || 0) / 10000).toFixed(2),
                },
                {
                  title: '操作',
                  render: (_: unknown, record: Record<string, any>) =>
                    editingPlan?.id === record.id ? (
                      <Space>
                        <Button
                          type="link"
                          loading={deliverySaving}
                          onClick={() => void savePlanEdit(record)}
                        >
                          保存
                        </Button>
                        <Button
                          type="link"
                          onClick={() => setEditingPlan(undefined)}
                        >
                          取消
                        </Button>
                      </Space>
                    ) : (
                      <Space>
                        <Button
                          type="link"
                          icon={<EditOutlined />}
                          onClick={() => {
                            setEditingPlan(record);
                            setPlanEdit({
                              amountWan: Number(record.amountYuan || 0) / 10000,
                              personMonths: Number(record.personMonths || 0),
                            });
                          }}
                        >
                          编辑
                        </Button>
                        <Button
                          danger
                          type="link"
                          icon={<DeleteOutlined />}
                          onClick={() => removePlan(record)}
                        >
                          删除
                        </Button>
                      </Space>
                    ),
                },
              ]}
            />
            <Card
              size="small"
              title="批量新增预估交付"
              extra={
                <Button
                  type="link"
                  onClick={() => setPlanRows((rows) => [...rows, newPlanRow()])}
                >
                  + 添加一条
                </Button>
              }
            >
              <Space orientation="vertical" style={{ width: '100%' }}>
                {planRows.map((item, index) => (
                  <Space key={item.key} wrap>
                    <Select
                      value={item.yearMonth}
                      options={yearMonths.map((value) => ({
                        label: value,
                        value,
                      }))}
                      onChange={(value) =>
                        setPlanRows((rows) =>
                          rows.map((row, rowIndex) =>
                            rowIndex === index
                              ? { ...row, yearMonth: value }
                              : row,
                          ),
                        )
                      }
                      style={{ width: 130 }}
                    />
                    <InputNumber
                      min={0}
                      precision={2}
                      addonBefore="金额万"
                      value={item.amountWan}
                      onChange={(value) =>
                        setPlanRows((rows) =>
                          rows.map((row, rowIndex) =>
                            rowIndex === index
                              ? { ...row, amountWan: Number(value || 0) }
                              : row,
                          ),
                        )
                      }
                    />
                    <InputNumber
                      min={0}
                      precision={2}
                      addonBefore="人月"
                      value={item.personMonths}
                      onChange={(value) =>
                        setPlanRows((rows) =>
                          rows.map((row, rowIndex) =>
                            rowIndex === index
                              ? { ...row, personMonths: Number(value || 0) }
                              : row,
                          ),
                        )
                      }
                    />
                    <Button
                      danger
                      type="link"
                      disabled={planRows.length <= 1}
                      onClick={() =>
                        setPlanRows((rows) =>
                          rows.filter((_row, rowIndex) => rowIndex !== index),
                        )
                      }
                    >
                      删除
                    </Button>
                  </Space>
                ))}
                <Button
                  type="primary"
                  loading={deliverySaving}
                  onClick={() => void savePlanBatch()}
                >
                  保存批量新增
                </Button>
              </Space>
            </Card>
          </Space>
        ) : (
          <Space orientation="vertical" style={{ width: '100%' }} size="middle">
            <Form
              form={costForm}
              layout="inline"
              onFinish={() => void saveCost()}
            >
              <Form.Item name="id" hidden>
                <Input />
              </Form.Item>
              <Form.Item
                name="yearMonth"
                rules={[{ required: true, message: '请选择月份' }]}
              >
                <Select
                  placeholder="月份"
                  options={yearMonths.map((value) => ({ label: value, value }))}
                  style={{ width: 130 }}
                />
              </Form.Item>
              <Form.Item
                name="costType"
                initialValue="partner"
                rules={[{ required: true }]}
              >
                <Select
                  options={[
                    { label: '协力成本', value: 'partner' },
                    { label: '服务器成本', value: 'server' },
                    { label: '短信成本', value: 'sms' },
                    { label: '其他成本', value: 'other' },
                  ]}
                  style={{ width: 140 }}
                />
              </Form.Item>
              <Form.Item
                name="amountWan"
                rules={[{ required: true, message: '请输入金额' }]}
              >
                <InputNumber min={0.01} precision={2} addonBefore="金额万" />
              </Form.Item>
              <Form.Item name="note">
                <Input placeholder="备注（可留空）" style={{ width: 170 }} />
              </Form.Item>
              <Button
                type="primary"
                loading={deliverySaving}
                onClick={() => void saveCost()}
              >
                {costForm.getFieldValue('id') ? '保存修改' : '添加'}
              </Button>
            </Form>
            <Table
              size="small"
              loading={deliveryBusy}
              rowKey={(record) => String(record.id)}
              dataSource={deliveryCosts}
              pagination={false}
              locale={{ emptyText: '暂无其他成本记录' }}
              columns={[
                { title: '月份', dataIndex: 'yearMonth' },
                { title: '类型', dataIndex: 'costType' },
                {
                  title: '金额（万）',
                  render: (_: unknown, record: Record<string, any>) =>
                    (Number(record.amountYuan || 0) / 10000).toFixed(2),
                },
                {
                  title: '备注',
                  dataIndex: 'note',
                  render: (value: unknown) => String(value || '—'),
                },
                {
                  title: '操作',
                  render: (_: unknown, record: Record<string, any>) => (
                    <Space>
                      <Button
                        type="link"
                        icon={<EditOutlined />}
                        onClick={() => editCost(record)}
                      >
                        编辑
                      </Button>
                      <Button
                        danger
                        type="link"
                        icon={<DeleteOutlined />}
                        onClick={() => removeCost(record)}
                      >
                        删除
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          </Space>
        )}
      </Modal>
    </div>
  );
}
