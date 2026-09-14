import {
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Link } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Progress,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  TreeSelect,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type BusinessLine,
  type OverviewResponse,
  type ProjectMember,
  type ProjectTreeNode,
  superworkApi,
  type WorkItemRecord,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const statusColor: Record<string, string> = {
  PENDING: 'default',
  IN_PROGRESS: 'processing',
  COMPLETED: 'success',
  OTHER: 'warning',
};
const statusText: Record<string, string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  OTHER: '其他',
};

export default function TasksPage() {
  const [createForm] = Form.useForm<Record<string, unknown>>();
  const [records, setRecords] = useState<WorkItemRecord[]>([]);
  const [summary, setSummary] = useState<OverviewResponse['summary']>({});
  const [mode, setMode] = useState<'project' | 'person'>('project');
  const [viewMode, setViewMode] = useState<'cards' | 'table'>('cards');
  const [mainView, setMainView] = useState<'detail' | 'analysis'>('detail');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [selectedProjectId, setSelectedProjectId] = useState<number>();
  const [selectedAssigneeId, setSelectedAssigneeId] = useState<number>();
  const [analysis, setAnalysis] = useState<Record<string, unknown>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<WorkItemRecord>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [requirements, setRequirements] = useState<
    Array<{
      id: number | string;
      title: string;
      reqNo?: string;
      projectId?: number | string;
      projectName?: string;
      projectFullPath?: string;
    }>
  >([]);
  const [projectTree, setProjectTree] = useState<ProjectTreeNode[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [projectMembers, setProjectMembers] = useState<ProjectMember[]>([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [users, setUsers] = useState<
    Array<{ id: number; realName?: string; username?: string }>
  >([]);
  const [saving, setSaving] = useState(false);
  const [updatingTaskId, setUpdatingTaskId] = useState<number | string>();
  const createProjectId = Form.useWatch('projectId', createForm) as
    | number
    | undefined;
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getTaskOverview({
        keyword: keyword || undefined,
        status: status || undefined,
        projectId: selectedProjectId,
        assigneeId: selectedAssigneeId,
      });
      setRecords(result.tasks || result.records || []);
      setSummary(result.summary || {});
      setAnalysis(result.analysis || {});
    } catch (e) {
      setError(e instanceof Error ? e.message : '任务数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, selectedAssigneeId, selectedProjectId, status]);
  useEffect(() => {
    void load();
    void Promise.allSettled([
      superworkApi.getRequirements({ page: 1, size: 200 }),
      superworkApi.getUsers({ page: 1, size: 200 }),
      superworkApi.getProjectTree(),
      superworkApi.getBusinessLines({ page: 1, size: 200, status: 1 }),
    ])
      .then(
        ([
          requirementResult,
          userResult,
          projectTreeResult,
          businessLineResult,
        ]) => {
          if (requirementResult.status === 'fulfilled')
            setRequirements(requirementResult.value.records || []);
          if (userResult.status === 'fulfilled')
            setUsers(userResult.value.records || []);
          if (projectTreeResult.status === 'fulfilled')
            setProjectTree(projectTreeResult.value || []);
          if (businessLineResult.status === 'fulfilled')
            setBusinessLines(businessLineResult.value.records || []);
        },
      )
      .catch(() => undefined);
  }, [load]);
  const projectTreeData = useMemo(() => {
    const mapProject = (node: ProjectTreeNode): Record<string, unknown> => ({
      ...node,
      key: `project-${node.id}`,
      value: node.id,
      title: node.name,
      children: node.children?.map(mapProject),
    });
    const groups = businessLines
      .map((line) => ({
        key: `business-line-${line.id}`,
        value: `business-line-${line.id}`,
        title: line.name,
        selectable: false,
        children: projectTree
          .filter((node) => node.businessLineId === line.id)
          .map(mapProject),
      }))
      .filter((line) => line.children.length > 0);
    return groups.length ? groups : projectTree.map(mapProject);
  }, [businessLines, projectTree]);
  const projectOptions = useMemo(() => {
    const flattened: ProjectTreeNode[] = [];
    const collect = (nodes: ProjectTreeNode[]) => {
      nodes.forEach((node) => {
        flattened.push(node);
        if (node.children?.length) collect(node.children);
      });
    };
    collect(projectTree);
    return flattened;
  }, [projectTree]);
  const assigneeOptions = useMemo(() => {
    if (!projectMembers.length) return users;
    const seen = new Set<number>();
    const options: Array<{ id: number; realName?: string; username?: string }> =
      [];
    projectMembers.forEach((member) => {
      if (!member.userId || seen.has(member.userId)) return;
      seen.add(member.userId);
      options.push({
        id: member.userId,
        realName: member.realName,
        username: member.username,
      });
    });
    return options;
  }, [projectMembers, users]);
  const loadProjectMembers = async (projectId?: number) => {
    setProjectMembers([]);
    if (!projectId) return;
    setMembersLoading(true);
    try {
      setProjectMembers(await superworkApi.getProjectMembers(projectId));
    } catch {
      setProjectMembers([]);
    } finally {
      setMembersLoading(false);
    }
  };
  const createRequirements = useMemo(() => {
    if (!createProjectId) return [];
    const projectIds = new Set<number>([createProjectId]);
    const collectChildren = (nodes: ProjectTreeNode[]) => {
      nodes.forEach((node) => {
        if (node.parentId != null && projectIds.has(node.parentId)) {
          projectIds.add(node.id);
        }
        if (node.children?.length) collectChildren(node.children);
      });
    };
    collectChildren(projectTree);
    return requirements.filter((item) => {
      const id = Number(item.projectId);
      return Number.isFinite(id) && projectIds.has(id);
    });
  }, [createProjectId, projectTree, requirements]);
  const openTaskDetail = async (record: WorkItemRecord) => {
    setDetail(record);
    if (record.readOnly || record.dataSource === 'YUNXIAO' || record.id == null)
      return;
    setDetailLoading(true);
    try {
      const full = await superworkApi.getTask(record.id);
      setDetail({ ...record, ...full });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '任务详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  };
  const groups = useMemo(() => {
    const map = new Map<string, WorkItemRecord[]>();
    records.forEach((item) => {
      const key =
        mode === 'project'
          ? item.projectName || '未关联项目'
          : item.assigneeName || '未分配';
      map.set(key, [...(map.get(key) || []), item]);
    });
    return Array.from(map.entries())
      .map(([label, items]) => ({ label, items }))
      .sort((a, b) => b.items.length - a.items.length);
  }, [mode, records]);
  const updateRecordStatus = async (
    record: WorkItemRecord,
    nextStatus: string,
  ) => {
    if (record.id == null || record.readOnly || record.dataSource === 'YUNXIAO')
      return;
    setUpdatingTaskId(record.id);
    try {
      const updated = await superworkApi.updateTask(record.id, {
        status: nextStatus,
      });
      if (detail?.id === record.id)
        setDetail({ ...detail, ...updated, status: nextStatus });
      message.success('任务状态已更新');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '任务状态更新失败');
    } finally {
      setUpdatingTaskId(undefined);
    }
  };
  const applyAnalysisFilter = (
    section: string,
    item: Record<string, unknown>,
  ) => {
    setMainView('detail');
    const key = String(item.key || '');
    if (section === '状态分布') setStatus(key);
    if (section === '项目分布' && /^\d+$/.test(key)) {
      setMode('project');
      setSelectedProjectId(Number(key));
      setSelectedAssigneeId(undefined);
    }
    if (section === '负责人分布') {
      setMode('person');
      if (/^\d+$/.test(key)) {
        setSelectedAssigneeId(Number(key));
        setSelectedProjectId(undefined);
      } else if (item.label && item.label !== '未分配') {
        setKeyword(String(item.label));
      }
    }
  };
  const columns: TableProps<WorkItemRecord>['columns'] = [
    {
      title: '任务',
      dataIndex: 'title',
      ellipsis: true,
      render: (value, record) => (
        <Button type="link" onClick={() => void openTaskDetail(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '项目',
      dataIndex: 'projectName',
      width: 170,
      ellipsis: true,
      render: (value) => value || '未关联项目',
    },
    {
      title: '负责人',
      dataIndex: 'assigneeName',
      width: 112,
      render: (value) => value || '未分配',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 126,
      render: (value, record) =>
        record.readOnly || record.dataSource === 'YUNXIAO' ? (
          <Tag color={statusColor[record.normalizedStatus || ''] || 'default'}>
            {value || statusText[record.normalizedStatus || ''] || '未设置'}
          </Tag>
        ) : (
          <Select
            size="small"
            value={value || '待开始'}
            loading={updatingTaskId === record.id}
            onChange={(next) => void updateRecordStatus(record, next)}
            options={['待开始', '进行中', '已完成', '已测试'].map((item) => ({
              label: item,
              value: item,
            }))}
          />
        ),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 82,
      render: (value) => value || '—',
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
    {
      title: '操作',
      key: 'action',
      width: 76,
      render: (_, record) => (
        <Button
          type="link"
          icon={<EyeOutlined />}
          onClick={() => void openTaskDetail(record)}
        >
          查看
        </Button>
      ),
    },
  ];
  const createTask = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      const actorId = (() => {
        try {
          return (
            Number(JSON.parse(localStorage.getItem('user') || '{}')?.id) || 1
          );
        } catch {
          return 1;
        }
      })();
      await superworkApi.createTask({
        requirementId: Number(values.requirementId),
        title: String(values.title || '').trim(),
        taskType: String(values.taskType || '开发任务'),
        description: values.description
          ? String(values.description).trim()
          : undefined,
        assigneeId: values.assigneeId ? Number(values.assigneeId) : undefined,
        estimatedHours:
          values.estimatedHours == null
            ? undefined
            : Number(values.estimatedHours),
        createdBy: actorId,
      });
      message.success('任务已创建');
      setCreateOpen(false);
      createForm.resetFields();
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setSaving(false);
    }
  };
  const updateStatus = async (nextStatus: string) => {
    if (!detail?.id || detail.readOnly || detail.dataSource === 'YUNXIAO')
      return;
    setSaving(true);
    try {
      const updated = await superworkApi.updateTask(detail.id, {
        status: nextStatus,
      });
      setDetail({ ...detail, ...updated });
      message.success('任务状态已更新');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败');
    } finally {
      setSaving(false);
    }
  };
  const readonly = Boolean(
    detail?.readOnly || detail?.dataSource === 'YUNXIAO',
  );
  return (
    <div className="sw-page">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            WORK ITEMS / TASKS
          </Typography.Text>
          <Typography.Title level={2}>任务管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            本地任务可创建和推进状态；云效任务保持只读并保留来源标识。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              createForm.resetFields();
              setProjectMembers([]);
              setCreateOpen(true);
            }}
          >
            新建任务
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取任务数据"
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
          placeholder="搜索任务标题"
          style={{ width: 260 }}
        />
        <Select
          value={status || undefined}
          onChange={(value) => {
            setStatus(value || '');
          }}
          allowClear
          placeholder="全部状态"
          style={{ width: 130 }}
          options={['待开始', '进行中', '已完成', '已测试'].map((value) => ({
            value,
            label: value,
          }))}
        />
        <Segmented
          value={mode}
          onChange={(value) => {
            const next = value as 'project' | 'person';
            setMode(next);
            if (next === 'project') setSelectedAssigneeId(undefined);
            else setSelectedProjectId(undefined);
          }}
          options={[
            { label: '按项目', value: 'project' },
            { label: '按负责人', value: 'person' },
          ]}
        />
        {mode === 'project' ? (
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            value={selectedProjectId}
            placeholder="选择项目"
            style={{ width: 220 }}
            onChange={setSelectedProjectId}
            options={projectOptions.map((project) => ({
              value: project.id,
              label: project.fullPath || project.name,
            }))}
          />
        ) : (
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            value={selectedAssigneeId}
            placeholder="选择负责人"
            style={{ width: 200 }}
            onChange={setSelectedAssigneeId}
            options={users.map((user) => ({
              value: user.id,
              label: user.realName || user.username || String(user.id),
            }))}
          />
        )}
        <Button type="primary" onClick={() => void load()}>
          查询
        </Button>
        <Button
          onClick={() => {
            setKeyword('');
            setStatus('');
            setSelectedProjectId(undefined);
            setSelectedAssigneeId(undefined);
          }}
        >
          重置
        </Button>
        <Segmented
          value={viewMode}
          onChange={(value) => setViewMode(value as 'cards' | 'table')}
          options={[
            { label: '卡片', value: 'cards' },
            { label: '表格', value: 'table' },
          ]}
        />
        <Segmented
          value={mainView}
          onChange={(value) => setMainView(value as 'detail' | 'analysis')}
          options={[
            { label: '任务明细', value: 'detail' },
            { label: '执行分析', value: 'analysis' },
          ]}
        />
      </Space>
      <div className="sw-summary-grid">
        <Card variant="borderless">
          <Statistic
            title="任务总数"
            value={summary?.totalCount || records.length}
          />
        </Card>
        <Card variant="borderless">
          <Statistic title="待开始" value={summary?.pendingCount || 0} />
        </Card>
        <Card variant="borderless">
          <Statistic title="进行中" value={summary?.inProgressCount || 0} />
        </Card>
        <Card variant="borderless">
          <Statistic
            title="完成 / 已测试"
            value={
              Number(summary?.completedCount || 0) +
              Number(summary?.testedCount || 0)
            }
          />
        </Card>
        <Card variant="borderless">
          <Statistic
            title="预估工时"
            value={Number(summary?.totalEstimatedHours || 0)}
            suffix="h"
          />
        </Card>
        <Card variant="borderless">
          <Statistic
            title="未分配"
            value={Number(summary?.unassignedCount || 0)}
          />
        </Card>
      </div>
      {mainView === 'detail' ? (
        loading ? (
          <Card variant="borderless" className="sw-table-card" loading />
        ) : groups.length ? (
          <Card variant="borderless" className="sw-table-card">
            <Collapse
              ghost
              className="sw-task-group-list"
              items={groups.map((group) => ({
                key: group.label,
                label: (
                  <Space>
                    <Typography.Text strong>{group.label}</Typography.Text>
                    <Tag>{group.items.length} 条</Tag>
                    <Tag>
                      {group.items
                        .reduce(
                          (sum, item) => sum + Number(item.estimatedHours || 0),
                          0,
                        )
                        .toFixed(1)}
                      h
                    </Tag>
                    <Tag color="success">
                      {group.items.length
                        ? Math.round(
                            (group.items.filter((item) =>
                              ['已完成', '已测试'].includes(
                                String(item.status),
                              ),
                            ).length /
                              group.items.length) *
                              100,
                          )
                        : 0}
                      %
                    </Tag>
                  </Space>
                ),
                children:
                  viewMode === 'table' ? (
                    <Table
                      rowKey={(record) => record.recordKey}
                      loading={loading}
                      columns={columns}
                      dataSource={group.items}
                      locale={{
                        emptyText: <Empty description="暂无任务数据" />,
                      }}
                      scroll={{ x: 1020 }}
                      pagination={false}
                    />
                  ) : (
                    <div className="sw-task-card-grid">
                      {group.items.map((item) => (
                        <Card
                          size="small"
                          key={item.recordKey}
                          hoverable
                          className="sw-task-card"
                          onClick={() => void openTaskDetail(item)}
                        >
                          <Space
                            orientation="vertical"
                            size={6}
                            style={{ width: '100%' }}
                          >
                            <Space
                              style={{
                                justifyContent: 'space-between',
                                width: '100%',
                              }}
                            >
                              <Typography.Text strong ellipsis>
                                {item.title}
                              </Typography.Text>
                              {item.readOnly ||
                              item.dataSource === 'YUNXIAO' ? (
                                <Tag
                                  color={
                                    statusColor[item.normalizedStatus || '']
                                  }
                                >
                                  {item.status ||
                                    statusText[item.normalizedStatus || ''] ||
                                    '未设置'}
                                </Tag>
                              ) : (
                                <Select
                                  size="small"
                                  value={String(item.status || '待开始')}
                                  loading={updatingTaskId === item.id}
                                  onClick={(event) => event.stopPropagation()}
                                  onChange={(next) =>
                                    void updateRecordStatus(item, next)
                                  }
                                  options={[
                                    '待开始',
                                    '进行中',
                                    '已完成',
                                    '已测试',
                                  ].map((value) => ({
                                    label: value,
                                    value,
                                  }))}
                                />
                              )}
                            </Space>
                            <Typography.Text type="secondary">
                              {item.projectName || '未关联项目'} ·{' '}
                              {item.assigneeName || '未分配'}
                            </Typography.Text>
                            <Typography.Text
                              type={
                                item.overdueIncomplete ? 'danger' : 'secondary'
                              }
                            >
                              {item.dueDate || '无截止日期'}
                            </Typography.Text>
                          </Space>
                        </Card>
                      ))}
                    </div>
                  ),
              }))}
            />
          </Card>
        ) : (
          <Card variant="borderless" className="sw-table-card">
            <Empty description="暂无任务数据" />
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
                      const percentage = Number(item.percentage || 0);
                      return (
                        <Button
                          key={`${String(title)}-${String(item.key || item.label || 'unknown')}`}
                          type="text"
                          block
                          style={{
                            height: 'auto',
                            padding: '6px 0',
                            textAlign: 'left',
                          }}
                          disabled={title === '数据来源'}
                          onClick={() =>
                            applyAnalysisFilter(String(title), item)
                          }
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
                              percent={Math.min(100, percentage)}
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
          <Col span={24}>
            <Card variant="borderless" title="工时执行率">
              <Progress
                percent={
                  Number(analysis.totalEstimatedHours || 0) > 0
                    ? Math.round(
                        (Number(analysis.totalActualHours || 0) /
                          Number(analysis.totalEstimatedHours)) *
                          1000,
                      ) / 10
                    : 0
                }
                status={
                  Number(analysis.totalActualHours || 0) >
                  Number(analysis.totalEstimatedHours || 0)
                    ? 'exception'
                    : 'active'
                }
              />
              <Typography.Text type="secondary">
                实际 {Number(analysis.totalActualHours || 0).toFixed(1)}h / 预估{' '}
                {Number(analysis.totalEstimatedHours || 0).toFixed(1)}h
              </Typography.Text>
            </Card>
          </Col>
        </Row>
      )}
      <Modal
        title="新建任务"
        open={createOpen}
        okText="创建"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
          setProjectMembers([]);
        }}
        onOk={() => createForm.submit()}
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={(values) => void createTask(values)}
        >
          <Form.Item
            name="projectId"
            label="所属项目"
            rules={[{ required: true, message: '请选择所属项目' }]}
          >
            <TreeSelect
              treeData={projectTreeData}
              treeDefaultExpandAll
              showSearch
              allowClear
              placeholder="选择项目或子项目"
              treeNodeFilterProp="title"
              onChange={(value) => {
                createForm.setFieldValue('requirementId', undefined);
                createForm.setFieldValue('assigneeId', undefined);
                void loadProjectMembers(value ? Number(value) : undefined);
              }}
              notFoundContent="暂无可选项目"
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item
            name="requirementId"
            label="所属需求"
            rules={[{ required: true, message: '请选择需求' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              disabled={!createProjectId}
              placeholder={
                createProjectId ? '选择项目下的需求' : '请先选择项目'
              }
              options={createRequirements
                .filter((item) => !(item as WorkItemRecord).readOnly)
                .map((item) => ({
                  value: Number(item.id),
                  label: `${item.reqNo || item.id} · ${item.title}${
                    item.projectFullPath || item.projectName
                      ? ` · ${item.projectFullPath || item.projectName}`
                      : ''
                  }`,
                }))}
            />
          </Form.Item>
          <Form.Item
            name="title"
            label="任务标题"
            rules={[{ required: true, message: '请输入任务标题' }]}
          >
            <Input />
          </Form.Item>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="assigneeId" label="负责人">
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                loading={membersLoading}
                style={{ width: 200 }}
                options={assigneeOptions.map((item) => ({
                  value: item.id,
                  label: item.realName || item.username || String(item.id),
                }))}
              />
            </Form.Item>
            <Form.Item name="taskType" label="任务类型" initialValue="开发任务">
              <Select
                style={{ width: 150 }}
                options={['开发任务', '测试任务', '设计任务', '其他'].map(
                  (value) => ({ value, label: value }),
                )}
              />
            </Form.Item>
          </Space>
          <Form.Item name="estimatedHours" label="预估工时">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="任务描述">
            <Input.TextArea rows={4} />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={
          <Space>
            {detail?.title || '任务详情'}
            {readonly && <Tag color="blue">云效只读</Tag>}
          </Space>
        }
        size={560}
        open={Boolean(detail)}
        onClose={() => setDetail(undefined)}
        loading={detailLoading}
      >
        {detail && (
          <>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="来源">
                {readonly ? '云效同步' : '本地任务'}
              </Descriptions.Item>
              <Descriptions.Item label="所属需求">
                {detail.requirementId ? (
                  <Link
                    to={`/requirements-standalone/${String(detail.requirementId)}`}
                  >
                    {detail.requirementNo
                      ? `${detail.requirementNo} · ${detail.requirementTitle || ''}`
                      : detail.requirementTitle ||
                        `需求${String(detail.requirementId)}`}
                  </Link>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="任务类型">
                {String(detail.taskType || '开发任务')}
              </Descriptions.Item>
              <Descriptions.Item label="项目">
                {detail.projectFullPath || detail.projectName || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="负责人">
                {detail.assigneeName || '未分配'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag
                  color={
                    statusColor[detail.normalizedStatus || ''] || 'default'
                  }
                >
                  {detail.status ||
                    statusText[detail.normalizedStatus || ''] ||
                    '未设置'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="预估 / 实际工时">
                {String(detail.estimatedHours ?? '—')} /{' '}
                {String(detail.actualHours ?? '—')}
              </Descriptions.Item>
              <Descriptions.Item label="截止日期">
                {detail.dueDate || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="开始日期">
                {String(detail.startDate || '—')}
              </Descriptions.Item>
              <Descriptions.Item label="超期情况">
                {detail.overdueIncomplete
                  ? `超期 ${String(detail.overdueDays || 0)} 天`
                  : '未超期'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {String(detail.createdAt || '—')}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {String(detail.updatedAt || '—')}
              </Descriptions.Item>
              <Descriptions.Item label="任务说明">
                {String(detail.description || '暂无')}
              </Descriptions.Item>
            </Descriptions>
            {!readonly && (
              <Space wrap style={{ marginTop: 20 }}>
                <Button
                  loading={saving}
                  onClick={() => void updateStatus('待开始')}
                >
                  待开始
                </Button>
                <Button
                  type="primary"
                  loading={saving}
                  onClick={() => void updateStatus('进行中')}
                >
                  开始处理
                </Button>
                <Button
                  loading={saving}
                  onClick={() => void updateStatus('已完成')}
                >
                  完成
                </Button>
                <Button
                  loading={saving}
                  onClick={() => void updateStatus('已测试')}
                >
                  已测试
                </Button>
              </Space>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
