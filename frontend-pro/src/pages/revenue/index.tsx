import { DeleteOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useLocation } from '@umijs/max';
import type { TableColumnsType } from 'antd';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
    '/revenue/import': 'import',
    '/revenue/pending': 'pending',
  };
  const [activeTab, setActiveTab] = useState('matrix');
  /** 每个营收子页（路由即页面，不再用 Tab 切换） */
  const sectionMeta = (
    {
      matrix: { title: '工时 & 成本', desc: '业务线 × 项目的工时、成本与营收矩阵，含月份结账、单元格明细与营收估算。' },
      import: { title: '数据导入', desc: '工时 / 成本 / 合同明细的 Excel 兜底导入、批次历史与合同归属映射。' },
      pending: { title: '待映射与销售项目', desc: '待人工映射归属的工时/成本记录，以及销售项目与商机的绑定。' },
    } as Record<string, { title: string; desc: string }>
  )[activeTab] ?? { title: '营收管理', desc: '' };
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
  const matrixTableRef = useRef<HTMLDivElement>(null);
  const [matrixBodyHeight, setMatrixBodyHeight] = useState(360);
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
  }, [year]);
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
  useEffect(() => {
    if (activeTab !== 'matrix') return undefined;
    const card = matrixTableRef.current;
    const scroller =
      (card?.closest('.ant-layout-content') as HTMLElement | null) ?? null;
    const compute = () => {
      const el = matrixTableRef.current;
      if (!el) return;
      const top = scroller
        ? el.getBoundingClientRect().top - scroller.getBoundingClientRect().top
        : el.getBoundingClientRect().top;
      const available = (scroller?.clientHeight ?? window.innerHeight) - top;
      setMatrixBodyHeight(Math.max(220, Math.round(available - 48)));
    };
    compute();
    const observer = new ResizeObserver(compute);
    observer.observe(scroller ?? document.body);
    if (card) observer.observe(card);
    window.addEventListener('resize', compute);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', compute);
    };
  }, [
    activeTab,
    loading,
    matrix,
    error,
    filterLineIds,
    filterProjectIds,
    showEstimates,
    displayMode,
    flatMatrixRows.length,
  ]);
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
    <div
      className={`sw-page sw-revenue${activeTab === 'matrix' ? ' sw-revenue-matrix' : ''}`}
    >
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA / REVENUE
          </Typography.Text>
          <Typography.Title level={2}>{sectionMeta.title}</Typography.Title>
          <Typography.Paragraph type="secondary">
            {sectionMeta.desc}
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
      {activeTab === 'matrix' && (
      <Space wrap className="sw-matrix-stats">
        <Card variant="borderless" size="small">
          <Statistic title="导入批次" value={importBatches.length} />
        </Card>
        <Card variant="borderless" size="small">
          <Statistic title="合同批次" value={contractBatches.length} />
        </Card>
      </Space>
      )}
      {activeTab === 'matrix' && (
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
                      <div ref={matrixTableRef} className="sw-matrix-table">
                        <Table
                          size="small"
                          bordered
                          rowKey="key"
                          columns={matrixCols}
                          dataSource={flatMatrixRows}
                          pagination={false}
                          scroll={{ x: 1720, y: matrixBodyHeight }}
                          rowClassName={(record) =>
                            record.kind === 'line_total'
                              ? 'sw-matrix-line-total'
                              : record.kind === 'grand_total'
                                ? 'sw-matrix-grand-total'
                                : ''
                          }
                        />
                      </div>
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
      )}
      {activeTab === 'import' && (
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
      )}
      {activeTab === 'import' && (
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
      )}
      {activeTab === 'pending' && (
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
      )}
      {activeTab === 'matrix' && (
              <Card variant="borderless" title="营收估算" className="sw-revenue-estimates">
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
      )}
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
    </div>
  );
}
