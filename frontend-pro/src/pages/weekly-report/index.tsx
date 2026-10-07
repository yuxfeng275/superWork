import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  superworkApi,
  type WeeklyReportStatus,
  type WeeklyReportVO,
} from '@/services/superwork/api';
import CreateReportModal from './CreateReportModal';
import ReportDrawer from './ReportDrawer';
import { rangeText, statusOf } from './shared';
import '../workbench/style.less';
import './style.less';

export default function WeeklyReportPage() {
  const [list, setList] = useState<WeeklyReportVO[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [error, setError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [drawer, setDrawer] = useState<{
    weekStart: string;
    anchor: 'content' | 'publish';
    autoGenerate: boolean;
  } | null>(null);

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
  }, [loadList]);

  const summary = useMemo(() => {
    const inFlight = list.filter((r) =>
      ['PENDING', 'GENERATING', 'DRAFT'].includes(r.status),
    ).length;
    return {
      total: list.length,
      inFlight,
      confirmed: list.filter((r) => r.status === 'CONFIRMED').length,
      published: list.filter((r) => r.status === 'PUBLISHED').length,
    };
  }, [list]);

  const openDetail = (weekStart: string) =>
    setDrawer({ weekStart, anchor: 'content', autoGenerate: false });
  const openPublish = (weekStart: string) =>
    setDrawer({ weekStart, anchor: 'publish', autoGenerate: false });
  const closeDrawer = () => {
    setDrawer(null);
    void loadList();
  };

  /** 新建弹窗回调：打开抽屉；generate=true 时由抽屉自动触发 AI 生成 */
  const onCreated = (weekStart: string, generate: boolean) => {
    void loadList();
    setDrawer({ weekStart, anchor: 'content', autoGenerate: generate });
  };

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
              ? `${row.generationMode} · ${
                  row.generationProvider
                    ? `${row.generationProviderName || row.generationProvider} / ${row.generationModel || ''}`
                    : row.generationModel || ''
                }`
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
      width: 120,
      render: (_: unknown, row: WeeklyReportVO) => (
        <Space
          onClick={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
        >
          <Button
            type="link"
            size="small"
            onClick={() => openDetail(row.weekStartDate)}
          >
            详情
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => openPublish(row.weekStartDate)}
          >
            发布
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
            onClick={() => setCreateOpen(true)}
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
              openDetail(row.weekStartDate);
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

      <CreateReportModal
        open={createOpen}
        existing={list}
        onClose={() => setCreateOpen(false)}
        onCreated={onCreated}
      />
      <ReportDrawer
        weekStart={drawer?.weekStart ?? null}
        open={!!drawer}
        anchor={drawer?.anchor}
        autoGenerate={drawer?.autoGenerate}
        onClose={closeDrawer}
      />
    </div>
  );
}
