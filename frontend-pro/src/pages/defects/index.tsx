import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Progress,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type OverviewResponse,
  superworkApi,
  type WorkItemRecord,
} from '@/services/superwork/api';
import '../workbench/style.less';
import '../tasks/style.less';

const statusText: Record<string, string> = {
  PENDING: '待处理',
  IN_PROGRESS: '处理中',
  COMPLETED: '已解决',
  OTHER: '其他',
};
const statusColor: Record<string, string> = {
  PENDING: 'default',
  IN_PROGRESS: 'processing',
  COMPLETED: 'success',
  OTHER: 'warning',
};

export default function DefectsPage() {
  const [data, setData] = useState<OverviewResponse>({
    records: [],
    total: 0,
    current: 1,
    size: 20,
  });
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [projectId, setProjectId] = useState<number>();
  const [assigneeId, setAssigneeId] = useState<number>();
  const [page, setPage] = useState(1);
  const [mainView, setMainView] = useState<'detail' | 'analysis'>('detail');
  const [selectedDefect, setSelectedDefect] = useState<WorkItemRecord>();
  const [lastSyncedAt, setLastSyncedAt] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [viewMode, setViewMode] = useState<'board' | 'list'>('board');
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getDefectOverview({
        page,
        size: 20,
        keyword: keyword || undefined,
        normalizedStatus: status || undefined,
        projectId,
        assigneeId,
      });
      setData(result);
      setLastSyncedAt(
        String(
          (result as OverviewResponse & { lastSyncedAt?: string })
            .lastSyncedAt || '',
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '缺陷数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [assigneeId, keyword, page, projectId, status]);
  useEffect(() => {
    void load();
  }, [load]);
  const analysis = data.analysis || {};
  const projectOptions = useMemo(
    () =>
      (Array.isArray(analysis.projectDistribution)
        ? analysis.projectDistribution
        : []
      )
        .map((item) => item as Record<string, unknown>)
        .filter((item) => /^\d+$/.test(String(item.key || '')))
        .map((item) => ({
          id: Number(item.key),
          name: String(item.label || item.key),
        })),
    [analysis.projectDistribution],
  );
  const assigneeOptions = useMemo(
    () =>
      (Array.isArray(analysis.ownerDistribution)
        ? analysis.ownerDistribution
        : []
      )
        .map((item) => item as Record<string, unknown>)
        .filter((item) => /^\d+$/.test(String(item.key || '')))
        .map((item) => ({
          id: Number(item.key),
          name: String(item.label || item.key),
        })),
    [analysis.ownerDistribution],
  );
  const projectLabel = (record: WorkItemRecord) =>
    Array.isArray(record.projectNames) && record.projectNames.length
      ? record.projectNames.join(' / ')
      : record.projectName || '未映射项目';
  const assigneeLabel = (record: WorkItemRecord) =>
    String(record.assigneeName || record.assigneeUsername || '未分配');
  const formatDateTime = (value?: unknown) =>
    value ? String(value).replace('T', ' ').slice(0, 16) : '—';
  const columns: TableProps<WorkItemRecord>['columns'] = [
    {
      title: '编号',
      dataIndex: 'serialNumber',
      width: 120,
      render: (value) => <Typography.Text code>{value || '—'}</Typography.Text>,
    },
    {
      title: '缺陷标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (value, record) => (
        <Button type="link" onClick={() => setSelectedDefect(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '项目',
      dataIndex: 'projectName',
      width: 180,
      ellipsis: true,
      render: (_, record) => projectLabel(record),
    },
    {
      title: '负责人',
      dataIndex: 'assigneeName',
      width: 110,
      render: (_, record) => assigneeLabel(record),
    },
    {
      title: '状态',
      dataIndex: 'normalizedStatus',
      width: 100,
      render: (value) => (
        <Tag color={statusColor[value] || 'default'}>
          {statusText[value] || value || '未设置'}
        </Tag>
      ),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 84,
      render: (value) => value || '—',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (value) => formatDateTime(value),
    },
    {
      title: '截止日期',
      dataIndex: 'dueDate',
      width: 112,
      render: (value, record) => (
        <span className={record.overdueIncomplete ? 'sw-overdue' : ''}>
          {value || '—'}
        </span>
      ),
    },
  ];
  const summary = data.summary || {};
  return (
    <div className="sw-page">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            QUALITY / DEFECTS
          </Typography.Text>
          <Typography.Title level={2}>缺陷管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            聚焦未关闭缺陷和超期风险，数据更新于 {formatDateTime(lastSyncedAt)}
            。
          </Typography.Paragraph>
        </div>
        <Space>
          <Tag color="warning">云效只读</Tag>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取缺陷数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Space wrap className="sw-toolbar">
        <Input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={() => void load()}
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索缺陷标题"
          style={{ width: 260 }}
        />
        <Select
          value={status || undefined}
          onChange={(value) => setStatus(value || '')}
          allowClear
          placeholder="全部状态"
          style={{ width: 130 }}
          options={Object.entries(statusText).map(([value, label]) => ({
            value,
            label,
          }))}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          value={projectId}
          onChange={(value) => {
            setProjectId(value);
            setPage(1);
          }}
          placeholder="全部项目"
          style={{ width: 190 }}
          options={projectOptions.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          value={assigneeId}
          onChange={(value) => {
            setAssigneeId(value);
            setPage(1);
          }}
          placeholder="全部负责人"
          style={{ width: 160 }}
          options={assigneeOptions.map((item) => ({
            value: item.id,
            label: item.name,
          }))}
        />
        <Button type="primary" onClick={() => void load()}>
          查询
        </Button>
        <Button
          onClick={() => {
            setKeyword('');
            setStatus('');
            setProjectId(undefined);
            setAssigneeId(undefined);
            setPage(1);
          }}
        >
          重置
        </Button>
        <Segmented
          value={viewMode}
          onChange={(value) => setViewMode(value as 'board' | 'list')}
          options={[
            { value: 'board', label: '风险看板' },
            { value: 'list', label: '列表' },
          ]}
        />
        <Segmented
          value={mainView}
          onChange={(value) => setMainView(value as 'detail' | 'analysis')}
          options={[
            { value: 'detail', label: '缺陷明细' },
            { value: 'analysis', label: '结构分析' },
          ]}
        />
      </Space>
      <div className="sw-summary-grid">
        <Card variant="borderless">
          <Statistic
            title="缺陷总数"
            value={summary.totalCount || data.total || data.records.length}
          />
        </Card>
        <Card variant="borderless">
          <Statistic title="待处理" value={summary.pendingCount || 0} />
        </Card>
        <Card variant="borderless">
          <Statistic title="处理中" value={summary.inProgressCount || 0} />
        </Card>
        <Card variant="borderless">
          <Statistic title="已解决" value={summary.completedCount || 0} />
        </Card>
      </div>
      {mainView === 'detail' ? (
        viewMode === 'list' ? (
          <Card variant="borderless" className="sw-table-card">
            <Table
              rowKey={(record) => record.recordKey}
              loading={loading}
              columns={columns}
              dataSource={data.records}
              locale={{ emptyText: <Empty description="暂无缺陷数据" /> }}
              scroll={{ x: 900 }}
              pagination={{
                current: page,
                pageSize: 20,
                total: data.total,
                showSizeChanger: false,
                showTotal: (total) => `共 ${total} 条`,
                onChange: setPage,
              }}
            />
          </Card>
        ) : loading ? (
          <Card variant="borderless" className="sw-table-card" loading />
        ) : data.records.length ? (
          <div className="sw-defect-board">
            {(['PENDING', 'IN_PROGRESS', 'COMPLETED', 'OTHER'] as const).map(
              (stage) => (
                <Card
                  key={stage}
                  variant="borderless"
                  title={
                    <Space>
                      <Tag color={statusColor[stage]}>{statusText[stage]}</Tag>
                      <Typography.Text type="secondary">
                        {
                          data.records.filter(
                            (r) => r.normalizedStatus === stage,
                          ).length
                        }
                      </Typography.Text>
                    </Space>
                  }
                  className="sw-defect-column"
                >
                  <Space orientation="vertical" style={{ width: '100%' }}>
                    {data.records
                      .filter((r) => r.normalizedStatus === stage)
                      .map((r) => (
                        <Card
                          key={r.recordKey}
                          size="small"
                          hoverable
                          className="sw-defect-card"
                          onClick={() => setSelectedDefect(r)}
                        >
                          <Typography.Text strong ellipsis>
                            {r.title}
                          </Typography.Text>
                          <Typography.Text type="secondary" ellipsis>
                            {r.projectName || '未关联项目'}
                          </Typography.Text>
                          <Space>
                            <Tag>{r.priority || '—'}</Tag>
                            <Typography.Text
                              type={
                                r.overdueIncomplete ? 'danger' : 'secondary'
                              }
                            >
                              {r.dueDate || '无截止日期'}
                            </Typography.Text>
                          </Space>
                          <Typography.Text type="secondary">
                            负责人：{r.assigneeName || '未分配'}
                          </Typography.Text>
                        </Card>
                      ))}
                  </Space>
                </Card>
              ),
            )}
          </div>
        ) : (
          <Card variant="borderless" className="sw-table-card">
            <Empty description="暂无缺陷数据" />
          </Card>
        )
      ) : (
        <Row gutter={[12, 12]}>
          {[
            ['状态分布', analysis.statusDistribution],
            ['项目分布', analysis.projectDistribution],
            ['负责人分布', analysis.ownerDistribution],
            ['数据来源', analysis.sourceDistribution],
          ].map(([title, values]) => (
            <Col xs={24} md={12} key={String(title)}>
              <Card variant="borderless" title={String(title)}>
                <Space orientation="vertical" style={{ width: '100%' }}>
                  {Array.isArray(values) && values.length ? (
                    values.slice(0, 10).map((value) => {
                      const item = value as Record<string, unknown>;
                      const selectable = title !== '数据来源';
                      return (
                        <Button
                          key={`${String(title)}-${String(item.key || item.label || 'unknown')}`}
                          type="text"
                          block
                          disabled={!selectable}
                          style={{
                            height: 'auto',
                            padding: '6px 0',
                            textAlign: 'left',
                          }}
                          onClick={() => {
                            const key = String(item.key || '');
                            setMainView('detail');
                            setPage(1);
                            if (title === '状态分布') setStatus(key);
                            if (title === '项目分布' && /^\d+$/.test(key))
                              setProjectId(Number(key));
                            if (title === '负责人分布') {
                              if (/^\d+$/.test(key)) setAssigneeId(Number(key));
                              else if (item.label && item.label !== '未分配')
                                setKeyword(String(item.label));
                            }
                          }}
                        >
                          <Space
                            orientation="vertical"
                            size={4}
                            style={{ width: '100%' }}
                          >
                            <Space
                              style={{
                                width: '100%',
                                justifyContent: 'space-between',
                              }}
                            >
                              <Typography.Text>
                                {String(item.label || item.key || '未设置')}
                              </Typography.Text>
                              <Typography.Text strong>
                                {Number(item.count || 0)}
                              </Typography.Text>
                            </Space>
                            <Progress
                              percent={Math.min(
                                100,
                                Number(item.percentage || 0),
                              )}
                              showInfo={false}
                              size="small"
                            />
                          </Space>
                        </Button>
                      );
                    })
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="暂无分析数据"
                    />
                  )}
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      )}
      <Drawer
        title={
          selectedDefect
            ? `${String(selectedDefect.serialNumber || '云效缺陷')} · ${selectedDefect.title}`
            : '缺陷详情'
        }
        size={520}
        open={Boolean(selectedDefect)}
        onClose={() => setSelectedDefect(undefined)}
      >
        {selectedDefect && (
          <>
            <Alert
              type="warning"
              showIcon
              message="只读数据，以云效为准"
              style={{ marginBottom: 16 }}
            />
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="原始状态">
                {selectedDefect.status || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="所属项目">
                {projectLabel(selectedDefect)}
              </Descriptions.Item>
              <Descriptions.Item label="负责人">
                {assigneeLabel(selectedDefect)}
              </Descriptions.Item>
              <Descriptions.Item label="预估工时">
                {selectedDefect.estimatedHours == null
                  ? '—'
                  : `${Number(selectedDefect.estimatedHours).toFixed(1)}h`}
              </Descriptions.Item>
              <Descriptions.Item label="实际工时">
                {selectedDefect.actualHours == null
                  ? '—'
                  : `${Number(selectedDefect.actualHours).toFixed(1)}h`}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatDateTime(selectedDefect.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="计划完成">
                {selectedDefect.dueDate || '未设置'}
              </Descriptions.Item>
              <Descriptions.Item label="超期情况">
                {selectedDefect.overdueIncomplete
                  ? `超期 ${String(selectedDefect.overdueDays || 0)} 天`
                  : '未超期'}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatDateTime(selectedDefect.updatedAt)}
              </Descriptions.Item>
            </Descriptions>
            <Card
              variant="borderless"
              title="缺陷描述"
              style={{ marginTop: 16 }}
            >
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                {String(selectedDefect.description || '暂无描述')}
              </Typography.Paragraph>
            </Card>
          </>
        )}
      </Drawer>
    </div>
  );
}
