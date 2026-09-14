import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Empty,
  message,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type BizLineProfitReport,
  type BizLineProfitRow,
  superworkApi,
  type WorktimeSyncLog,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const currentYear = new Date().getFullYear();
const yearOptions = [currentYear - 1, currentYear, currentYear + 1].map(
  (value) => ({ label: `${value}年`, value }),
);

type ProfitRow = BizLineProfitRow & { lineName: string; isYtd: boolean };

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
const isNegative = (value?: number | null) =>
  value != null && Number(value) < 0;
const num =
  (render: (value?: number | null) => string) => (value: number | null) => (
    <Typography.Text type={isNegative(value) ? 'danger' : undefined}>
      {render(value)}
    </Typography.Text>
  );

export default function BlProfitPage() {
  const [year, setYear] = useState(currentYear);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [report, setReport] = useState<BizLineProfitReport>();
  const [syncLogs, setSyncLogs] = useState<WorktimeSyncLog[]>([]);
  const [filterLineNames, setFilterLineNames] = useState<string[]>([]);
  const [error, setError] = useState('');

  const loadReport = useCallback(async (targetYear: number) => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getBlProfitReport(targetYear);
      setReport(result);
      const valid = new Set(result.lines.map((line) => line.businessLineName));
      setFilterLineNames((current) =>
        current.filter((name) => valid.has(name)),
      );
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

  // 手动同步：整年逐月从工时系统拉取（整月覆盖），完成后刷新报表
  const syncFromWorktime = async () => {
    setSyncing(true);
    try {
      const logs = await superworkApi.syncBlProfit({ year });
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
      setSyncing(false);
    }
  };

  const filteredLines = useMemo(() => {
    if (!report) return [];
    if (!filterLineNames.length) return report.lines;
    return report.lines.filter((line) =>
      filterLineNames.includes(line.businessLineName),
    );
  }, [filterLineNames, report]);
  const latestSyncLog = syncLogs[0];

  const dataSource = useMemo<ProfitRow[]>(
    () =>
      filteredLines.flatMap((line) => [
        ...line.months.map((month) => ({
          ...month,
          lineName: line.businessLineName,
          isYtd: false,
        })),
        { ...line.ytd, lineName: line.businessLineName, isYtd: true },
      ]),
    [filteredLines],
  );

  const columns: TableProps<ProfitRow>['columns'] = [
    { title: '业务线', dataIndex: 'lineName', className: 'col-line' },
    {
      title: '月',
      dataIndex: 'yearMonth',
      width: 100,
      render: (value, row) => (row.isYtd ? 'YTD' : value || '—'),
    },
    {
      title: '营业收入(万)',
      dataIndex: 'revenue',
      align: 'right',
      render: num(formatWan),
    },
    {
      title: '考核毛利(万)',
      dataIndex: 'grossProfit',
      align: 'right',
      render: num(formatWan),
    },
    {
      title: '考核毛利率',
      dataIndex: 'grossProfitRate',
      align: 'right',
      render: num(formatRate),
    },
    {
      title: '净利润(万)',
      dataIndex: 'netProfit',
      align: 'right',
      render: num(formatWan),
    },
    {
      title: '净利率',
      dataIndex: 'netProfitRate',
      align: 'right',
      render: num(formatRate),
    },
    {
      title: '工时(人月)',
      dataIndex: 'totalHours',
      align: 'right',
      render: num(formatHours),
    },
  ];

  return (
    <div className="sw-page sw-bl-profit">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">FINANCE</Typography.Text>
          <Typography.Title level={2}>业务线利润</Typography.Title>
          <Typography.Paragraph type="secondary">
            业务线 × 月
            真实营收与利润，数据源自工时系统业务线利润报表；每业务线各月合计即
            YTD。
          </Typography.Paragraph>
        </div>
        <Space>
          <Select
            aria-label="选择年份"
            style={{ width: 130 }}
            value={year}
            options={yearOptions}
            onChange={(value) => setYear(value)}
          />
          <Button
            icon={<ReloadOutlined />}
            aria-label="刷新"
            onClick={() => void loadReport(year)}
          />
          <Button
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
          action={
            <Button size="small" onClick={() => void loadReport(year)}>
              重试
            </Button>
          }
        />
      )}
      {report && report.lines.length > 0 ? (
        <>
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
                  filterLineNames.includes(line.businessLineName)
                    ? 'is-active'
                    : ''
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
          <Card variant="borderless" className="sw-table-card">
            <Table<ProfitRow>
              rowKey={(row) => `${row.lineName}-${row.yearMonth ?? 'total'}`}
              loading={loading}
              columns={columns}
              dataSource={dataSource}
              pagination={false}
              scroll={{ x: 1000 }}
              rowClassName={(row) => (row.isYtd ? 'sw-bl-profit-ytd-row' : '')}
              summary={() => {
                const total = report.totalYtd;
                return (
                  <Table.Summary.Row className="sw-bl-profit-total-row">
                    <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
                    <Table.Summary.Cell index={1}>YTD</Table.Summary.Cell>
                    <Table.Summary.Cell index={2} align="right">
                      {num(formatWan)(total.revenue)}
                    </Table.Summary.Cell>
                    <Table.Summary.Cell index={3} align="right">
                      {num(formatWan)(total.grossProfit)}
                    </Table.Summary.Cell>
                    <Table.Summary.Cell index={4} align="right">
                      {num(formatRate)(total.grossProfitRate)}
                    </Table.Summary.Cell>
                    <Table.Summary.Cell index={5} align="right">
                      {num(formatWan)(total.netProfit)}
                    </Table.Summary.Cell>
                    <Table.Summary.Cell index={6} align="right">
                      {num(formatRate)(total.netProfitRate)}
                    </Table.Summary.Cell>
                    <Table.Summary.Cell index={7} align="right">
                      {num(formatHours)(total.totalHours)}
                    </Table.Summary.Cell>
                  </Table.Summary.Row>
                );
              }}
            />
          </Card>
        </>
      ) : (
        !loading &&
        !error && (
          <Card variant="borderless">
            <Empty description="暂无数据，点击右上角「同步工时系统」拉取业务线利润报表" />
          </Card>
        )
      )}
    </div>
  );
}
