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
  Empty,
  message,
  Segmented,
  Select,
  Space,
  Spin,
  Table,
  Tooltip,
  Typography,
} from 'antd';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import SyncCutoff from '@/components/SyncCutoff';
import {
  type BizLineProfitReport,
  superworkApi,
  type WorktimeSyncLog,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';
import {
  availableMonths,
  type BlFlatRow,
  type BlMetrics,
  type BlViewMode,
  buildBlRows,
} from './tableModel';

const currentYear = new Date().getFullYear();
const yearOptions = [currentYear - 1, currentYear, currentYear + 1].map(
  (value) => ({ label: `${value}年`, value }),
);

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
const isNegative = (value?: number | null) => value != null && Number(value) < 0;
/** 0 与空统一为灰色短横；仅真实数字着色 */
const cellNum =
  (render: (value?: number | null) => string) => (value: number | null) =>
    value == null || Number(value) === 0 ? (
      <Typography.Text className="sw-bl-profit-zero">-</Typography.Text>
    ) : (
      <Typography.Text type={isNegative(value) ? 'danger' : undefined}>
        {render(value)}
      </Typography.Text>
    );

const headerBreaks = (first: string, second: string) => (
  <>
    {first}
    <br />
    {second}
  </>
);

type MetricField = keyof BlMetrics;

const PERIOD_EMPTY: Record<BlViewMode, string> = {
  month: '所选月份暂无已同步数据',
  H1: '上半年暂无已同步数据',
  H2: '下半年暂无已同步数据',
  YEAR: '本年暂无已同步数据',
};

export default function BlProfitPage() {
  const [year, setYear] = useState(currentYear);
  const [viewMode, setViewMode] = useState<BlViewMode>('month');
  const [selectedMonths, setSelectedMonths] = useState<number[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [report, setReport] = useState<BizLineProfitReport>();
  const [syncLogs, setSyncLogs] = useState<WorktimeSyncLog[]>([]);
  const [filterLineNames, setFilterLineNames] = useState<string[]>([]);
  const [error, setError] = useState('');
  const tableCardRef = useRef<HTMLDivElement>(null);
  const [tableBodyHeight, setTableBodyHeight] = useState(320);

  const loadReport = useCallback(async (targetYear: number) => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getBlProfitReport(targetYear);
      setReport(result);
      const valid = new Set(result.lines.map((line) => line.businessLineName));
      setFilterLineNames((current) => current.filter((name) => valid.has(name)));
    } catch (e) {
      setError(e instanceof Error ? e.message : '业务线利润报表加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  const loadSyncLogs = useCallback(async () => {
    try {
      setSyncLogs(await superworkApi.getBlProfitSyncLogs(10));
    } catch {
      setSyncLogs([]);
    }
  }, []);
  useEffect(() => {
    void loadReport(year);
    void loadSyncLogs();
  }, [loadReport, loadSyncLogs, year]);

  const syncFromWorktime = async () => {
    setSyncing(true);
    try {
      const logs = await superworkApi.syncBlProfit({ year });
      const success = logs.filter((log) => log.status === 'success').length;
      const failed = logs.filter((log) => log.status === 'failed');
      if (failed.length > 0) {
        message.warning(
          `同步完成：${success} 个月成功，${failed.length} 个月失败（${failed[0].scope}: ${failed[0].message ?? ''}）`,
        );
      } else message.success(`同步完成：${success} 个月份已更新`);
      await Promise.all([loadReport(year), loadSyncLogs()]);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const months = useMemo(
    () => (report ? availableMonths(report.lines) : []),
    [report],
  );
  const dataSource = useMemo(
    () =>
      report
        ? buildBlRows({
            lines: report.lines,
            viewMode,
            selectedMonths,
            filterLineNames,
          })
        : [],
    [filterLineNames, report, selectedMonths, viewMode],
  );
  const latestSyncLog = syncLogs[0];

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
      setTableBodyHeight(Math.max(220, Math.round(available - 78)));
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
  }, [report, viewMode, error, filterLineNames, selectedMonths, dataSource.length]);

  const metricColumn = (
    title: ReactNode,
    field: MetricField,
    width: number,
    render: (value?: number | null) => string,
  ) => ({
    title,
    width,
    align: 'right' as const,
    render: (_: unknown, record: BlFlatRow) => cellNum(render)(record.metrics[field]),
  });

  const columns: TableProps<BlFlatRow>['columns'] = [
    {
      title: '月份',
      width: 44,
      onCell: (record) => ({ rowSpan: record.spans.period }),
      render: (_, record) => record.periodLabel,
    },
    {
      title: '业务线',
      width: 108,
      ellipsis: true,
      render: (_, record) => record.lineName,
    },
    metricColumn('营业收入', 'revenue', 72, formatWan),
    metricColumn('短信成本', 'smsCost', 52, formatWan),
    metricColumn('直接成本', 'directCost', 56, formatWan),
    metricColumn(headerBreaks('平台佣金&', '手续费'), 'platformFee', 58, formatWan),
    metricColumn('赔付', 'compensation', 44, formatWan),
    metricColumn(headerBreaks('协力&', '外包'), 'outsourcing', 60, formatWan),
    metricColumn('软件赠送', 'softwareGift', 52, formatWan),
    metricColumn('工时', 'totalHours', 60, formatHours),
    metricColumn(headerBreaks('工时', '占比(%)'), 'hoursRatio', 52, formatRate),
    metricColumn('费用1', 'expense1', 52, formatWan),
    metricColumn(headerBreaks('人工', '成本1'), 'laborCost1', 52, formatWan),
    metricColumn('考核毛利', 'grossProfit', 64, formatWan),
    metricColumn(headerBreaks('考核', '毛利率(%)'), 'grossProfitRate', 68, formatRate),
    metricColumn('净利润', 'netProfit', 64, formatWan),
    metricColumn(headerBreaks('净利率', '(%)'), 'netProfitRate', 52, formatRate),
  ];
  const tableWidth = columns.reduce((sum, column) => sum + Number(column?.width ?? 0), 0);

  return (
    <div className="sw-page sw-bl-profit">
      <div className="sw-page-header sw-bl-profit-header">
        <div>
          <Space align="baseline" size={8} wrap={false}>
            <span className="sw-eyebrow">FINANCE</span>
            <Typography.Title level={4} style={{ margin: 0 }}>
              业务线利润
            </Typography.Title>
            <Tooltip
              title={
                <span>
                  期间 × 业务线。月度按月展开，同一月份的业务线与「全部业务线」合计合并月份单元格；
                  H1 为 1–6 月、H2 为 7–12 月、全年为 1–12 月，金额与工时跨月求和，毛利率、净利率按合计重算。
                  工时占比的分母是公司全部业务线工时。金额单位：万，工时：人月。
                  数据源自工时系统业务线利润报表。
                </span>
              }
            >
              <InfoCircleOutlined className="sw-bl-profit-info" />
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
          <Segmented<BlViewMode>
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
            type="primary"
            icon={<DownloadOutlined />}
            loading={syncing}
            onClick={() => void syncFromWorktime()}
          >
            同步工时系统
          </Button>
        </Space>
      </div>

      {latestSyncLog && (
        <Typography.Paragraph
          type={latestSyncLog.status === 'failed' ? 'danger' : 'secondary'}
          className="sw-bl-profit-sync-status"
        >
          最近同步：{latestSyncLog.scope} ·{' '}
          {latestSyncLog.status === 'success'
            ? `成功 ${latestSyncLog.upsertCount} 行`
            : latestSyncLog.status === 'failed'
              ? `失败（${latestSyncLog.message ?? ''}）`
              : '进行中'}{' '}
          · {latestSyncLog.finishedAt || latestSyncLog.startedAt}
        </Typography.Paragraph>
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          message="业务线利润报表加载失败"
          description={error}
          style={{ marginBottom: 8 }}
          action={
            <Button size="small" onClick={() => void loadReport(year)}>
              重试
            </Button>
          }
        />
      )}

      {report && months.length > 0 && (
        <>
          <div className="sw-bl-profit-filter-row">
            <span className="sw-bl-profit-filter-label">业务线</span>
            <div className="sw-bl-profit-pills">
              <button
                type="button"
                className={!filterLineNames.length ? 'is-active' : ''}
                onClick={() => setFilterLineNames([])}
              >
                全部业务线
              </button>
              {report.lines.map((line) => (
                <button
                  type="button"
                  key={line.businessLineName}
                  className={
                    filterLineNames.includes(line.businessLineName) ? 'is-active' : ''
                  }
                  onClick={() =>
                    setFilterLineNames((current) =>
                      current.includes(line.businessLineName)
                        ? current.filter((name) => name !== line.businessLineName)
                        : [...current, line.businessLineName],
                    )
                  }
                >
                  {line.businessLineName}
                </button>
              ))}
            </div>
          </div>
          {viewMode === 'month' && (
            <div className="sw-bl-profit-filter-row">
              <span className="sw-bl-profit-filter-label">月份</span>
              <div className="sw-bl-profit-pills">
                <button
                  type="button"
                  className={selectedMonths.length === 0 ? 'is-active' : ''}
                  onClick={() => setSelectedMonths([])}
                >
                  全部月份
                </button>
                {months.map((yearMonth) => {
                  const month = Number(yearMonth.slice(5));
                  return (
                    <button
                      type="button"
                      key={yearMonth}
                      className={selectedMonths.includes(month) ? 'is-active' : ''}
                      onClick={() =>
                        setSelectedMonths((current) =>
                          current.includes(month)
                            ? current.filter((value) => value !== month)
                            : [...current, month],
                        )
                      }
                    >
                      {month}月
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </>
      )}

      {report && report.lines.length > 0 ? (
        dataSource.length > 0 ? (
          <Card
            variant="borderless"
            className="sw-table-card"
            ref={tableCardRef}
            styles={{ body: { padding: 12 } }}
          >
            <Table<BlFlatRow>
              rowKey={(row) => row.key}
              loading={loading}
              columns={columns}
              dataSource={dataSource}
              pagination={false}
              scroll={{ x: tableWidth, y: tableBodyHeight }}
              size="small"
              rowClassName={(row) => (row.summary ? 'sw-bl-profit-summary-row' : '')}
            />
          </Card>
        ) : (
          !loading && (
            <Card variant="borderless">
              <Empty description={PERIOD_EMPTY[viewMode]} />
            </Card>
          )
        )
      ) : loading ? (
        <Card variant="borderless">
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin />
          </div>
        </Card>
      ) : (
        !error && (
          <Card variant="borderless">
            <Empty description="暂无数据，点击右上角「同步工时系统」拉取业务线利润报表" />
          </Card>
        )
      )}
    </div>
  );
}
