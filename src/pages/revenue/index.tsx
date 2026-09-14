import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
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
import { superworkApi } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';
export default function RevenuePage() {
  const year = new Date().getFullYear();
  const location = useLocation();
  const revenueTabByPath: Record<string, string> = {
    '/revenue/worktime': 'matrix',
    '/revenue/delivery': 'delivery',
    '/revenue/import': 'import',
    '/revenue/pending': 'pending',
  };
  const [activeTab, setActiveTab] = useState('matrix');
  const [matrix, setMatrix] = useState<Record<string, any>>({});
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
  const [monthBusy, setMonthBusy] = useState(false);
  const [displayMode, setDisplayMode] = useState<'merge' | 'hours' | 'cost'>(
    'merge',
  );
  const [cellOpen, setCellOpen] = useState(false);
  const [cellLoading, setCellLoading] = useState(false);
  const [cellRow, setCellRow] = useState<Record<string, any>>({});
  const [cellContext, setCellContext] = useState({
    yearMonth: '',
    businessLineId: 0,
    rowKey: '',
    title: '',
  });
  const [cellDetail, setCellDetail] = useState<Record<string, any>>({});
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
    row?: Record<string, any>;
  }>({ open: false });
  const [entryState, setEntryState] = useState<{
    open: boolean;
    kind: 'worklog' | 'cost';
    row?: Record<string, any>;
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
  const rows = Array.isArray(matrix.rows)
    ? matrix.rows
    : Array.isArray(matrix.records)
      ? matrix.records
      : Array.isArray(matrix.lines)
        ? (matrix.lines as any[]).flatMap((line: any) =>
            (line.sections || []).flatMap((section: any) =>
              (section.rows || []).map((row: any) => ({
                ...row,
                businessLineId: line.businessLineId,
                businessLineName: line.businessLineName,
              })),
            ),
          )
        : [];
  const cols = rows.length
    ? [
        'businessLineName',
        'name',
        ...((matrix.months || []) as any[])
          .slice(0, 10)
          .map((m: any) => m.yearMonth),
      ]
    : [];
  const openCell = async (row: any, yearMonth: string) => {
    const businessLineId = Number(
      row.businessLineId || row.lineId || row.businessLine?.id || 0,
    );
    const rowKey = String(row.rowKey || row.key || row.name || '');
    if (!businessLineId || !rowKey || !yearMonth) {
      message.info('该矩阵行缺少单元格明细定位信息');
      return;
    }
    setCellContext({
      businessLineId,
      rowKey,
      yearMonth,
      title: `${row.businessLineName || '业务线'} / ${
        row.name || rowKey
      } / ${yearMonth}`,
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
    return kindMap[cellRow.kind || 'project'] || kindMap.project;
  };
  const openEstimate = (row?: Record<string, any>) => {
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
        projectId: cellRow.projectId ?? null,
        salesProjectId: cellRow.salesProjectId ?? null,
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
  const removeEstimate = (row: Record<string, any>) => {
    if (!row.id) return;
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
  const openEntry = (kind: 'worklog' | 'cost', row?: Record<string, any>) => {
    setEntryState({ open: true, kind, row });
    entryForm.setFieldsValue(
      kind === 'worklog'
        ? {
            employeeName: row?.employeeName || '',
            department: row?.department || '',
            hours: row?.hours ?? 0.1,
            workNote: row?.workNote || '',
            specialNote: row?.specialNote || '',
            projectNameRaw:
              row?.projectNameRaw || `${cellRow.name || '项目'}（手工补录）`,
          }
        : {
            employeeCount: row?.employeeCount ?? undefined,
            hours: row?.hours ?? 0,
            costAmount: row?.costAmount ?? 0,
            personMonthCost: row?.personMonthCost ?? undefined,
            projectNameRaw:
              row?.projectNameRaw || `${cellRow.name || '项目'}（手工补录）`,
          },
    );
  };
  const saveEntry = async () => {
    try {
      const values = await entryForm.validateFields();
      const kind = buildEntryKind();
      const base = {
        yearMonth: cellContext.yearMonth,
        businessLineId: cellContext.businessLineId,
        projectId: cellRow.projectId ?? null,
        salesProjectId: cellRow.salesProjectId ?? null,
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
  const removeEntry = (kind: 'worklog' | 'cost', row: Record<string, any>) => {
    if (!row.id) return;
    Modal.confirm({
      title: '删除这条明细？',
      content: row.projectNameRaw || row.employeeName || '删除后不可恢复',
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
  const tableCols = cols.map((k) => ({
    title:
      k === 'businessLineName' ? '业务线' : k === 'name' ? '项目 / 销售项' : k,
    dataIndex: k,
    key: k,
    render: (v: unknown, row: any) =>
      k.includes('-')
        ? (() => {
            const cell = row.months?.find(
              (_m: any, i: number) => (matrix.months || [])[i]?.yearMonth === k,
            );
            const value =
              displayMode === 'hours'
                ? cell?.hours
                : displayMode === 'cost'
                  ? cell?.cost
                  : (cell?.hours ?? cell?.cost);
            return (
              <Button
                type="link"
                icon={<EyeOutlined />}
                onClick={() => void openCell(row, k)}
              >
                {value === undefined || value === null
                  ? '—'
                  : Number(value).toLocaleString()}
              </Button>
            );
          })()
        : typeof v === 'number'
          ? v.toLocaleString()
          : String(v ?? '—'),
  }));
  tableCols.push({
    title: '操作',
    dataIndex: '__actions',
    key: '__actions',
    render: (_value: unknown, row: Record<string, any>) =>
      row.kind === 'project' || row.kind === 'line' || row.projectId != null ? (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            onClick={() => void openDelivery(row, 'plans')}
          >
            预估交付
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => void openDelivery(row, 'costs')}
          >
            其他成本
          </Button>
        </Space>
      ) : null,
  } as any);
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
  const closeMonth = async (reopen: boolean) => {
    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: reopen
          ? `确认重开 ${importMonth}？`
          : `确认结账 ${importMonth}？`,
        content: reopen
          ? '重开后该月份恢复为可维护状态，矩阵可能重新显示预估数据。'
          : '结账后该月份将锁定实际数据，后续导入和预估修改可能受到限制。',
        okText: reopen ? '确认重开' : '确认结账',
        cancelText: '取消',
        okType: reopen ? 'default' : 'primary',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!confirmed) return;
    setMonthBusy(true);
    try {
      if (reopen) await superworkApi.reopenRevenueMonth(importMonth);
      else await superworkApi.closeRevenueMonth(importMonth);
      message.success(reopen ? '月份已重开' : '月份已结账');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '月份操作失败');
    } finally {
      setMonthBusy(false);
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
        </div>
        <Space>
          <Tag color="blue">{year} 年</Tag>
          <Input
            value={importMonth}
            onChange={(e) => setImportMonth(e.target.value)}
            placeholder="YYYY-MM"
            style={{ width: 110 }}
          />
          <Button loading={monthBusy} onClick={() => void closeMonth(false)}>
            结账
          </Button>
          <Button loading={monthBusy} onClick={() => void closeMonth(true)}>
            重开
          </Button>
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
                <Space style={{ marginBottom: 14 }} wrap>
                  <Typography.Text type="secondary">矩阵显示</Typography.Text>
                  <Select
                    value={displayMode}
                    options={[
                      { label: '工时 + 成本', value: 'merge' },
                      { label: '仅工时', value: 'hours' },
                      { label: '仅成本', value: 'cost' },
                    ]}
                    onChange={setDisplayMode}
                  />
                  <Typography.Text type="secondary">
                    点击月份数值查看明细并维护估算、工时、成本。
                  </Typography.Text>
                </Space>
                {rows.length ? (
                  <Table
                    rowKey={(r) =>
                      String(r.id || r.key || r.yearMonth || r.name || 'row')
                    }
                    dataSource={rows}
                    columns={tableCols}
                    scroll={{ x: 1100 }}
                    pagination={false}
                  />
                ) : (
                  <Empty description="暂无矩阵数据" />
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
                <Space orientation="vertical">
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
        ) : (
          <Space orientation="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="月份">
                {cellContext.yearMonth}
              </Descriptions.Item>
              <Descriptions.Item label="工时">
                {cellDetail.totalHours ?? cellDetail.hours ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="成本">
                {cellDetail.totalCost ?? cellDetail.cost ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="来源">
                {cellDetail.source ?? '—'}
              </Descriptions.Item>
            </Descriptions>
            <Card
              size="small"
              title="营收估算"
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
              {Array.isArray(cellDetail.estimates) &&
              cellDetail.estimates.length ? (
                <Table
                  size="small"
                  rowKey={(row) =>
                    String(
                      row.id || row.key || row.yearMonth || row.name || 'row',
                    )
                  }
                  dataSource={cellDetail.estimates}
                  columns={[
                    ...Object.keys(cellDetail.estimates[0])
                      .filter((key) =>
                        [
                          'description',
                          'personMonths',
                          'amount',
                          'unitPrice',
                        ].includes(key),
                      )
                      .map((key) => ({
                        title:
                          key === 'personMonths'
                            ? '人月'
                            : key === 'unitPrice'
                              ? '单价'
                              : key === 'amount'
                                ? '金额'
                                : '说明',
                        dataIndex: key,
                        key,
                        render: (value: unknown) => String(value ?? '—'),
                      })),
                    {
                      title: '操作',
                      key: 'action',
                      render: (_: unknown, row: Record<string, any>) => (
                        <Space size="small">
                          <Button
                            type="link"
                            size="small"
                            onClick={() => openEstimate(row)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            type="link"
                            size="small"
                            onClick={() => removeEstimate(row)}
                          >
                            删除
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                  pagination={{ pageSize: 5 }}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无估算"
                />
              )}
            </Card>
            <Card
              size="small"
              title="工时明细"
              extra={
                <Button
                  size="small"
                  type="primary"
                  onClick={() => openEntry('worklog')}
                >
                  补录工时
                </Button>
              }
            >
              {Array.isArray(cellDetail.worklogEntries) &&
              cellDetail.worklogEntries.length ? (
                <Table
                  size="small"
                  rowKey={(row) =>
                    String(
                      row.id || row.key || row.yearMonth || row.name || 'row',
                    )
                  }
                  dataSource={cellDetail.worklogEntries}
                  columns={[
                    ...Object.keys(cellDetail.worklogEntries[0])
                      .filter((key) =>
                        [
                          'employeeName',
                          'department',
                          'hours',
                          'workNote',
                          'specialNote',
                        ].includes(key),
                      )
                      .map((key) => ({
                        title:
                          key === 'employeeName'
                            ? '人员'
                            : key === 'department'
                              ? '部门'
                              : key === 'hours'
                                ? '工时'
                                : key === 'workNote'
                                  ? '工作说明'
                                  : '备注',
                        dataIndex: key,
                        key,
                        render: (value: unknown) => String(value ?? '—'),
                      })),
                    {
                      title: '操作',
                      key: 'action',
                      render: (_: unknown, row: Record<string, any>) => (
                        <Space size="small">
                          <Button
                            type="link"
                            size="small"
                            onClick={() => openEntry('worklog', row)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            type="link"
                            size="small"
                            onClick={() => removeEntry('worklog', row)}
                          >
                            删除
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                  pagination={{ pageSize: 5 }}
                  scroll={{ x: 620 }}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无工时明细"
                />
              )}
            </Card>
            <Card
              size="small"
              title="成本明细"
              extra={
                <Button
                  size="small"
                  type="primary"
                  onClick={() => openEntry('cost')}
                >
                  补录成本
                </Button>
              }
            >
              {Array.isArray(cellDetail.costEntries) &&
              cellDetail.costEntries.length ? (
                <Table
                  size="small"
                  rowKey={(row) =>
                    String(
                      row.id || row.key || row.yearMonth || row.name || 'row',
                    )
                  }
                  dataSource={cellDetail.costEntries}
                  columns={[
                    ...Object.keys(cellDetail.costEntries[0])
                      .filter((key) =>
                        [
                          'employeeCount',
                          'hours',
                          'costAmount',
                          'personMonthCost',
                          'projectNameRaw',
                        ].includes(key),
                      )
                      .map((key) => ({
                        title:
                          key === 'employeeCount'
                            ? '人数'
                            : key === 'hours'
                              ? '工时'
                              : key === 'costAmount'
                                ? '成本金额'
                                : key === 'personMonthCost'
                                  ? '人月成本'
                                  : '项目原名',
                        dataIndex: key,
                        key,
                        render: (value: unknown) => String(value ?? '—'),
                      })),
                    {
                      title: '操作',
                      key: 'action',
                      render: (_: unknown, row: Record<string, any>) => (
                        <Space size="small">
                          <Button
                            type="link"
                            size="small"
                            onClick={() => openEntry('cost', row)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            type="link"
                            size="small"
                            onClick={() => removeEntry('cost', row)}
                          >
                            删除
                          </Button>
                        </Space>
                      ),
                    },
                  ]}
                  pagination={{ pageSize: 5 }}
                  scroll={{ x: 620 }}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无成本明细"
                />
              )}
            </Card>
          </Space>
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
            归属：{cellContext.yearMonth} · {cellRow.name || cellContext.rowKey}
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
