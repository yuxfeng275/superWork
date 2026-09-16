import {
  CloudSyncOutlined,
  EditOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  App,
  Button,
  Card,
  Col,
  Empty,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import {
  type DataSyncLog,
  type SyncOverviewItem,
  type SyncTask,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const DOMAIN_LABELS: Record<string, string> = {
  contract: '合同',
  worklog: '工时',
  cost: '成本',
  org: '组织',
  member: '人员',
};

const SOURCE_COLORS: Record<string, string> = {
  OA: 'blue',
  WORKTIME: 'purple',
  EXCEL: 'orange',
};

const STATUS_TAG: Record<string, { color: string; text: string }> = {
  success: { color: 'success', text: '成功' },
  failed: { color: 'error', text: '失败' },
  running: { color: 'processing', text: '执行中' },
};

const TRIGGER_LABELS: Record<string, string> = {
  schedule: '定时',
  manual: '手动',
  'fallback-import': '兜底导入',
};

/** 各任务手动触发时的 scope 提示（空=不需要 scope） */
const SCOPE_HINTS: Record<string, { label: string; placeholder: string }> = {
  'worktime-contract': {
    label: '同步年份',
    placeholder: '如 2026，留空为当年',
  },
  'worktime-monthly': {
    label: '强制月份',
    placeholder: '如 2026-08，留空按已确认月份自动扫描',
  },
  'oa-contract': { label: '同步范围', placeholder: '留空为当前年' },
};

const canManageRole = (role?: string) =>
  [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(role || '');

const fmtTime = (value?: string | null) =>
  value ? dayjs(value).format('MM-DD HH:mm:ss') : '-';

export default function SystemSyncPage() {
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const canManage = canManageRole(initialState?.currentUser?.role);

  const [overview, setOverview] = useState<SyncOverviewItem[]>([]);
  const [tasks, setTasks] = useState<SyncTask[]>([]);
  const [logs, setLogs] = useState<DataSyncLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [logLoading, setLogLoading] = useState(false);
  const [logDomain, setLogDomain] = useState<string>();
  const [logStatus, setLogStatus] = useState<string>();

  const [runTask, setRunTask] = useState<SyncTask>();
  const [runScope, setRunScope] = useState('');
  const [running, setRunning] = useState(false);
  const [editTask, setEditTask] = useState<SyncTask>();
  const [editCron, setEditCron] = useState('');
  const [savingCron, setSavingCron] = useState(false);

  const loadLogs = useCallback(async (domain?: string, status?: string) => {
    setLogLoading(true);
    try {
      setLogs(await superworkApi.getSyncLogs({ domain, status }));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步日志加载失败');
    } finally {
      setLogLoading(false);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [taskList, overviewList] = await Promise.all([
        superworkApi.listSyncTasks(),
        superworkApi.getSyncOverview(),
      ]);
      setTasks(taskList);
      setOverview(overviewList);
      await loadLogs(logDomain, logStatus);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步任务加载失败');
    } finally {
      setLoading(false);
    }
  }, [loadLogs, logDomain, logStatus]);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggleEnabled = async (task: SyncTask, enabled: boolean) => {
    try {
      await superworkApi.updateSyncTask(task.taskCode, { enabled });
      message.success(
        enabled ? `已启用「${task.taskName}」` : `已停用「${task.taskName}」`,
      );
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败');
    }
  };

  const confirmRun = async () => {
    if (!runTask) return;
    setRunning(true);
    try {
      const result = await superworkApi.runSyncTask(
        runTask.taskCode,
        runScope.trim() || undefined,
      );
      const failed = result.filter((item) => item.status === 'failed');
      if (result.length === 0) {
        message.info('任务未执行（未配置来源系统或已停用）');
      } else if (failed.length > 0) {
        message.warning(
          `同步完成但有失败：${failed[0].message || failed[0].domain}`,
        );
      } else {
        message.success(`「${runTask.taskName}」同步成功`);
      }
      setRunTask(undefined);
      setRunScope('');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setRunning(false);
    }
  };

  const saveCron = async () => {
    if (!editTask) return;
    setSavingCron(true);
    try {
      await superworkApi.updateSyncTask(editTask.taskCode, {
        cron: editCron.trim() || null,
      });
      message.success('定时配置已更新');
      setEditTask(undefined);
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingCron(false);
    }
  };

  const taskColumns = [
    {
      title: '任务',
      dataIndex: 'taskName',
      render: (text: string, record: SyncTask) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong>{text}</Typography.Text>
          <Typography.Text type="secondary" className="sw-sync-task-code">
            {record.taskCode}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '来源',
      dataIndex: 'sourceSystem',
      width: 110,
      render: (value: string) => (
        <Tag color={SOURCE_COLORS[value] || 'default'}>{value}</Tag>
      ),
    },
    {
      title: '数据域',
      dataIndex: 'domain',
      width: 90,
      render: (value: string) => DOMAIN_LABELS[value] || value,
    },
    {
      title: '定时规则',
      dataIndex: 'cron',
      width: 170,
      render: (value: string | null) =>
        value ? <Typography.Text code>{value}</Typography.Text> : '仅手动',
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (value: number, record: SyncTask) => (
        <Switch
          checked={value === 1}
          disabled={!canManage}
          onChange={(checked) => toggleEnabled(record, checked)}
        />
      ),
    },
    {
      title: '最近状态',
      dataIndex: 'lastStatus',
      width: 100,
      render: (value: string | null) =>
        value ? (
          <Tag color={STATUS_TAG[value]?.color}>
            {STATUS_TAG[value]?.text || value}
          </Tag>
        ) : (
          '-'
        ),
    },
    {
      title: '最近执行',
      dataIndex: 'lastRunAt',
      width: 150,
      render: fmtTime,
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: unknown, record: SyncTask) => (
        <Space>
          <Button
            size="small"
            type="link"
            icon={<PlayCircleOutlined />}
            disabled={!canManage}
            onClick={() => {
              setRunTask(record);
              setRunScope('');
            }}
          >
            手动同步
          </Button>
          <Button
            size="small"
            type="link"
            icon={<EditOutlined />}
            disabled={!canManage}
            onClick={() => {
              setEditTask(record);
              setEditCron(record.cron || '');
            }}
          >
            改定时
          </Button>
        </Space>
      ),
    },
  ];

  const logColumns = [
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      width: 150,
      render: fmtTime,
    },
    { title: '任务', dataIndex: 'taskCode', width: 150 },
    {
      title: '数据域',
      dataIndex: 'domain',
      width: 80,
      render: (value: string) => DOMAIN_LABELS[value] || value,
    },
    {
      title: '范围',
      dataIndex: 'scope',
      width: 90,
      render: (v: string) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: string) => (
        <Tag color={STATUS_TAG[value]?.color}>
          {STATUS_TAG[value]?.text || value}
        </Tag>
      ),
    },
    {
      title: '触发',
      dataIndex: 'triggeredBy',
      width: 100,
      render: (value: string) => TRIGGER_LABELS[value] || value,
    },
    {
      title: '总数/写入/待映射',
      key: 'counts',
      width: 140,
      render: (_: unknown, r: DataSyncLog) =>
        `${r.totalCount ?? '-'} / ${r.upsertCount ?? '-'} / ${r.pendingCount ?? '-'}`,
    },
    {
      title: '消息',
      dataIndex: 'message',
      ellipsis: true,
      render: (value: string | null) => value || '-',
    },
  ];

  return (
    <div className="sw-page sw-system-sync">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA INTEGRATION
          </Typography.Text>
          <h2>数据集成中心</h2>
          <Typography.Text type="secondary">
            合同 / 工时 / 成本 / 组织 / 人员 统一从 OA 与工时系统定时拉取，Excel
            导入仅作兜底补录
          </Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
      </div>

      <Row gutter={[16, 16]} className="sw-stat-row">
        {overview.map((item) => (
          <Col xs={12} md={8} xl={4} key={item.domain}>
            <Card className="sw-stat-card">
              <Space orientation="vertical" size={4}>
                <Space>
                  <Typography.Text type="secondary">
                    {DOMAIN_LABELS[item.domain] || item.domain}
                  </Typography.Text>
                  {item.lastSuccess && (
                    <Tag
                      color={
                        SOURCE_COLORS[item.lastSuccess.sourceSystem] ||
                        'default'
                      }
                    >
                      {item.lastSuccess.sourceSystem}
                    </Tag>
                  )}
                </Space>
                {item.lastSuccess ? (
                  <>
                    <Typography.Text strong className="sw-sync-scope">
                      {item.lastSuccess.scope || '全量'}
                    </Typography.Text>
                    <Typography.Text type="secondary" className="sw-sync-time">
                      截至 {fmtTime(item.lastSuccess.finishedAt)}
                    </Typography.Text>
                  </>
                ) : (
                  <Typography.Text type="secondary">
                    暂无同步记录
                  </Typography.Text>
                )}
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Card
        title={
          <Space>
            <CloudSyncOutlined />
            <span>同步任务</span>
          </Space>
        }
        className="sw-sync-tasks"
      >
        <Table<SyncTask>
          rowKey="taskCode"
          columns={taskColumns}
          dataSource={tasks}
          loading={loading}
          pagination={false}
        />
      </Card>

      <Card
        title="同步日志"
        className="sw-sync-logs"
        extra={
          <Space>
            <Select
              allowClear
              placeholder="数据域"
              style={{ width: 110 }}
              value={logDomain}
              onChange={(value) => {
                setLogDomain(value);
                loadLogs(value, logStatus);
              }}
              options={Object.entries(DOMAIN_LABELS).map(([value, label]) => ({
                value,
                label,
              }))}
            />
            <Select
              allowClear
              placeholder="状态"
              style={{ width: 100 }}
              value={logStatus}
              onChange={(value) => {
                setLogStatus(value);
                loadLogs(logDomain, value);
              }}
              options={[
                { value: 'success', label: '成功' },
                { value: 'failed', label: '失败' },
              ]}
            />
            <Button
              icon={<ReloadOutlined />}
              onClick={() => loadLogs(logDomain, logStatus)}
              loading={logLoading}
            />
          </Space>
        }
      >
        {logs.length === 0 && !logLoading ? (
          <Empty description="暂无同步日志" />
        ) : (
          <Table<DataSyncLog>
            rowKey="id"
            columns={logColumns}
            dataSource={logs}
            loading={logLoading}
            pagination={{ pageSize: 20, showSizeChanger: false }}
          />
        )}
      </Card>

      <Modal
        title={runTask ? `手动同步：${runTask.taskName}` : ''}
        open={Boolean(runTask)}
        onOk={confirmRun}
        onCancel={() => setRunTask(undefined)}
        confirmLoading={running}
        okText="开始同步"
        cancelText="取消"
      >
        {runTask && SCOPE_HINTS[runTask.taskCode] ? (
          <Space orientation="vertical" className="sw-sync-run-form">
            <Typography.Text>
              {SCOPE_HINTS[runTask.taskCode].label}
            </Typography.Text>
            <Input
              value={runScope}
              onChange={(e) => setRunScope(e.target.value)}
              placeholder={SCOPE_HINTS[runTask.taskCode].placeholder}
            />
          </Space>
        ) : (
          <Typography.Text>
            确认立即执行「{runTask?.taskName}」？
          </Typography.Text>
        )}
      </Modal>

      <Modal
        title={editTask ? `定时规则：${editTask.taskName}` : ''}
        open={Boolean(editTask)}
        onOk={saveCron}
        onCancel={() => setEditTask(undefined)}
        confirmLoading={savingCron}
        okText="保存"
        cancelText="取消"
      >
        <Space orientation="vertical" className="sw-sync-run-form">
          <Typography.Text>
            cron 表达式（秒 分 时 日 月 周），留空表示仅手动触发
          </Typography.Text>
          <Input
            value={editCron}
            onChange={(e) => setEditCron(e.target.value)}
            placeholder="如 0 30 6 * * * （每天 06:30）"
          />
        </Space>
      </Modal>
    </div>
  );
}
