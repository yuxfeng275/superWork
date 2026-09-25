import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
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
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
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

interface FlatRow {
  key: string;
  block: ProjectProfitBlock;
  line: ProjectProfitLine;
  row: ProjectProfitRow;
}

const formatWan = (value?: number | null) => {
  if (value == null) return '—';
  const num = Number(value) / 10000;
  if (num === 0) return '—';
  return (Math.round(num * 100) / 100).toLocaleString('zh-CN');
};
const formatRate = (value?: number | null) =>
  value == null ? '—' : `${Number(value).toFixed(1)}%`;
const formatHours = (value?: number | null) => {
  if (value == null) return '—';
  const num = Number(value);
  if (num === 0) return '—';
  return String(Math.round(num * 100) / 100);
};
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

  // 平铺行：项目行… → 销售行 → 差额行（仅非零） → 合计行
  const dataSource = useMemo<FlatRow[]>(() => {
    if (!report) return [];
    const rows: FlatRow[] = [];
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
    return rows;
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

  const moneyColumn = (title: string, field: CostKey | 'revenue' | 'cost' | 'grossProfit') => ({
    title,
    align: 'right' as const,
    render: (_: unknown, record: FlatRow) => num(formatWan)(record.row[field]),
  });
  const columns: TableProps<FlatRow>['columns'] = [
    { title: '月份', width: 72, render: (_, record) => record.block.label },
    { title: '业务线', width: 190, render: (_, record) => record.line.businessLineName },
    {
      title: '分类',
      width: 72,
      render: (_, record) => record.row.category ?? '—',
    },
    {
      title: '项目',
      width: 170,
      render: (_, record) =>
        record.row.rowType === 'TOTAL' ? '合计' : record.row.projectName ?? '—',
    },
    moneyColumn('营业收入', 'revenue'),
    moneyColumn('短信成本', 'smsCost'),
    moneyColumn('直接成本', 'directCost'),
    moneyColumn('平台佣金&手续费', 'platformFee'),
    moneyColumn('赔付', 'compensation'),
    moneyColumn('协力&外包', 'outsourcing'),
    moneyColumn('软件赠送', 'softwareGift'),
    {
      title: '工时',
      align: 'right',
      render: (_, record) => num(formatHours)(record.row.hours),
    },
    moneyColumn('成本', 'cost'),
    moneyColumn('考核毛利', 'grossProfit'),
    {
      title: '考核毛利率(%)',
      align: 'right',
      render: (_, record) => num(formatRate)(record.row.grossProfitRate),
    },
    {
      title: '操作',
      width: 80,
      fixed: 'right',
      render: (_, record) =>
        record.row.rowType === 'PROJECT' &&
        record.row.editable &&
        !['H1', 'H2', 'YEAR'].includes(record.block.key) ? (
          <Button size="small" type="link" onClick={() => openDrawer(record.block, record.line, record.row)}>
            分配
          </Button>
        ) : null,
    },
  ];

  return (
    <div className="sw-page sw-project-profit">
      <div className="sw-page-header">
        <div>
          <div className="sw-eyebrow">FINANCE</div>
          <Typography.Title level={2} style={{ margin: 0 }}>
            项目利润
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            月份 × 业务线 × 分类 × 项目；合计取工时系统财报镜像（未税），项目行营收为 OA 已交付（含税÷(1+税率)换算未税），差额行暴露未分配口径差。
          </Typography.Paragraph>
        </div>
        <SyncCutoff domains={['contract', 'worklog', 'cost']} />
        <Space wrap>
          <Select
            aria-label="选择年份"
            style={{ width: 110 }}
            value={year}
            options={yearOptions}
            onChange={(value) => setYear(value)}
          />
          <Segmented<ViewMode>
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
            icon={<ReloadOutlined />}
            aria-label="刷新"
            onClick={() => void loadReport(year)}
          />
          <Button
            icon={<DownloadOutlined />}
            loading={syncingYear}
            onClick={() => void syncWholeYear()}
          >
            同步工时系统
          </Button>
          <Select
            aria-label="同步月份"
            style={{ width: 100 }}
            value={syncTargetMonth || undefined}
            options={monthOptions}
            placeholder="月份"
            onChange={(value) => setSyncTargetMonth(value)}
          />
          <Button
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
          <Space wrap style={{ marginBottom: 16 }}>
            {viewMode === 'month' && (
              <Select
                mode="multiple"
                allowClear
                placeholder="月份（默认全部）"
                style={{ minWidth: 200 }}
                value={selectedMonths}
                options={monthFilterOptions}
                onChange={(value) => setSelectedMonths(value)}
              />
            )}
            <Select
              mode="multiple"
              allowClear
              placeholder="分类（项目/销售）"
              style={{ minWidth: 160 }}
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
              placeholder="项目（仅过滤项目行）"
              style={{ minWidth: 240 }}
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
        <Card variant="borderless" className="sw-table-card">
          <Table<FlatRow>
            columns={columns}
            dataSource={dataSource}
            loading={loading}
            pagination={false}
            scroll={{ x: 1600 }}
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
              金额为财报未税口径（元）。「未分配余额」= 该月该业务线合计 − 各项目行已分配之和，保存后差额行联动。
            </Typography.Paragraph>
            {COST_TYPES.map(({ key, costType, label }) => {
              const total = Number(drawer.line.total[key] ?? 0);
              const allocated = drawer.line.rows
                .filter((row) => row.rowType === 'PROJECT')
                .reduce((sum, row) => sum + Number(row[key] ?? 0), 0);
              return (
                <div key={costType} className="sw-project-profit-alloc-item">
                  <div className="sw-project-profit-alloc-meta">
                    <span className="sw-project-profit-alloc-label">{label}</span>
                    <span className="sw-project-profit-alloc-nums">
                      合计 {formatWan(total)} 万 · 已分配 {formatWan(allocated)} 万 · 未分配{' '}
                      <Typography.Text type={total - allocated < -0.005 || total - allocated > 0.005 ? 'warning' : undefined}>
                        {formatWan(total - allocated)} 万
                      </Typography.Text>
                    </span>
                  </div>
                  <InputNumber
                    style={{ width: '100%' }}
                    value={allocAmounts[costType]}
                    placeholder="0"
                    precision={2}
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
