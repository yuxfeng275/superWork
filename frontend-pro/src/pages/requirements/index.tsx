import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Steps,
  Table,
  Tabs,
  Tag,
  TreeSelect,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type BusinessLine,
  type ProjectRecord,
  type Requirement,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const statusColors: Record<string, string> = {
  已上线: 'success',
  已交付: 'success',
  已验收: 'success',
  待上线: 'warning',
  测试中: 'warning',
  已拒绝: 'error',
  开发中: 'processing',
  设计中: 'processing',
};
/** 看板列按需求生命周期排列，仅渲染有内容的列（对齐旧系统 kanbanColumns）。 */
const KANBAN_STATUSES = [
  '待评估',
  '评估中',
  '待设计',
  '设计中',
  '待确认',
  '开发中',
  '测试中',
  '待上线',
  '已上线',
  '已交付',
  '已验收',
];
const stageActions: Record<string, Array<[string, string]>> = {
  待评估: [['start_eval', '开始评估']],
  评估中: [
    ['submit_eval', '提交评估'],
    ['approve', '评估通过'],
    ['convert', '转产品需求'],
    ['reject', '拒绝'],
  ],
  待设计: [
    ['plan_design', '设计规划'],
    ['start_design', '开始设计'],
  ],
  设计中: [['start_test', '提测']],
  开发中: [['start_test', '提测']],
  测试中: [
    ['test_pass', '测试通过'],
    ['test_fail', '测试失败'],
  ],
  待上线: [['go_online', '确认上线']],
  已上线: [['deliver', '完成交付']],
  已交付: [['accept', '完成验收']],
};
type PlannerRow = {
  workType: string;
  selected: boolean;
  designerId?: number;
  estimatedHours?: number;
  plannedCompletedAt?: string;
};
const DESIGN_OPTIONS = ['原型设计', 'UI设计', '技术方案设计'];
const LIFECYCLE_STEPS = [
  '待评估',
  '评估中',
  '待设计',
  '设计中',
  '待确认',
  '开发中',
  '测试中',
  '待上线',
  '已上线',
  '已交付',
  '已验收',
];

const requirementIdOf = (record: Requirement) =>
  record.id ?? record.requirementId ?? record.recordKey;
const requirementKeyOf = (record: Requirement) =>
  String(
    record.recordKey ?? requirementIdOf(record) ?? `title:${record.title}`,
  );
const requirementNoOf = (record: Requirement) => {
  const value =
    record.reqNo ?? record.requirementNo ?? record.serialNumber ?? undefined;
  if (value) return String(value);
  const id = record.id ?? record.requirementId;
  return id == null ? '未编号' : `REQ-${id}`;
};

const sanitizeRichText = (value?: string) => {
  if (!value) return '';
  return value
    .replace(/<script[\s\S]*?>[\s\S]*?<\/script>/gi, '')
    .replace(/\son[a-z]+\s*=\s*(["']).*?\1/gi, '')
    .replace(/javascript:/gi, '');
};

type RichTextEditorProps = {
  value?: string;
  onChange?: (value: string) => void;
};

/** 复用旧版需求编辑器能力，保持富文本描述可编辑，同时不引入额外编辑器依赖。 */
function RichTextEditor({ value = '', onChange }: RichTextEditorProps) {
  const editorRef = useRef<HTMLDivElement>(null);
  const lastValue = useRef(value);
  useEffect(() => {
    if (
      editorRef.current &&
      value !== lastValue.current &&
      editorRef.current.innerHTML !== value
    ) {
      editorRef.current.innerHTML = value;
    }
    lastValue.current = value;
  }, [value]);
  const exec = (
    command:
      | 'bold'
      | 'italic'
      | 'underline'
      | 'insertUnorderedList'
      | 'insertOrderedList',
  ) => {
    editorRef.current?.focus();
    document.execCommand(command, false);
    const html = editorRef.current?.innerHTML || '';
    lastValue.current = html;
    onChange?.(html);
  };
  return (
    <div className="sw-rich-editor">
      <Space size={4} className="sw-rich-editor-toolbar">
        <Button size="small" type="text" onClick={() => exec('bold')}>
          <strong>B</strong>
        </Button>
        <Button size="small" type="text" onClick={() => exec('italic')}>
          <em>I</em>
        </Button>
        <Button size="small" type="text" onClick={() => exec('underline')}>
          <u>U</u>
        </Button>
        <Button
          size="small"
          type="text"
          onClick={() => exec('insertUnorderedList')}
        >
          • 列表
        </Button>
        <Button
          size="small"
          type="text"
          onClick={() => exec('insertOrderedList')}
        >
          1. 列表
        </Button>
      </Space>
      <div
        ref={editorRef}
        className="sw-rich-editor-content"
        contentEditable
        suppressContentEditableWarning
        onInput={(event) => {
          const html = event.currentTarget.innerHTML;
          lastValue.current = html;
          onChange?.(html);
        }}
      />
    </div>
  );
}

export default function RequirementsPage() {
  const location = useLocation();
  const [form] = Form.useForm<{
    title?: string;
    dataSource?: string;
    type?: string;
    status?: string[];
    priority?: string;
    businessLineId?: number;
    projectId?: number;
  }>();
  const [createForm] = Form.useForm<Record<string, unknown>>();
  const [evaluationForm] = Form.useForm<Record<string, unknown>>();
  const [worklogForm] = Form.useForm<Record<string, unknown>>();
  const [taskForm] = Form.useForm<Record<string, unknown>>();
  const [data, setData] = useState<{
    records: Requirement[];
    total: number;
    current: number;
    size: number;
  }>({ records: [], total: 0, current: 1, size: 20 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<Requirement>();
  const [detailExtras, setDetailExtras] = useState<{
    evaluation?: Record<string, unknown> | null;
    confirmation?: Record<string, unknown> | null;
    delivery?: Record<string, unknown> | null;
    logs: Record<string, unknown>[];
    tasks: Record<string, unknown>[];
  }>({ logs: [], tasks: [] });
  const [createOpen, setCreateOpen] = useState(false);
  const [evaluationOpen, setEvaluationOpen] = useState(false);
  const [worklogOpen, setWorklogOpen] = useState(false);
  const [plannerOpen, setPlannerOpen] = useState(false);
  const [plannerSaving, setPlannerSaving] = useState(false);
  const [plannerRows, setPlannerRows] = useState<PlannerRow[]>([]);
  const [editingWorklog, setEditingWorklog] =
    useState<Record<string, unknown>>();
  const [taskOpen, setTaskOpen] = useState(false);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [customerContacts, setCustomerContacts] = useState<
    Array<{ id: number; name?: string; contactName?: string; phone?: string }>
  >([]);
  const [createType, setCreateType] = useState('产品需求');
  const [users, setUsers] = useState<
    Array<{ id: number; realName?: string; username?: string }>
  >([]);
  const [actionLoading, setActionLoading] = useState('');
  const [viewMode, setViewMode] = useState<'cards' | 'kanban' | 'table'>(
    'table',
  );
  const [mainView, setMainView] = useState<'detail' | 'analysis'>('detail');
  const [analysis, setAnalysis] = useState<Record<string, unknown>>({});
  const filterBusinessLineId = Form.useWatch('businessLineId', form) as
    | number
    | undefined;
  const createBusinessLineId = Form.useWatch('businessLineId', createForm) as
    | number
    | undefined;
  const projectTreeData = useMemo(() => {
    const childrenByParent = new Map<number, ProjectRecord[]>();
    projects.forEach((project) => {
      if (project.parentId == null) return;
      const children = childrenByParent.get(project.parentId) || [];
      children.push(project);
      childrenByParent.set(project.parentId, children);
    });
    const build = (project: ProjectRecord): Record<string, unknown> => ({
      key: project.id,
      value: project.id,
      title: project.fullPath || project.name,
      children: (childrenByParent.get(project.id) || []).map(build),
    });
    return projects
      .filter((project) => project.parentId == null)
      .filter(
        (project) =>
          !createBusinessLineId ||
          project.businessLineId === createBusinessLineId,
      )
      .map(build);
  }, [createBusinessLineId, projects]);
  const projectById = useMemo(
    () => new Map(projects.map((project) => [project.id, project])),
    [projects],
  );
  const rootProjectId = (projectId?: number) => {
    let project = projectId == null ? undefined : projectById.get(projectId);
    const visited = new Set<number>();
    while (project?.parentId != null && !visited.has(project.id)) {
      visited.add(project.id);
      project = projectById.get(project.parentId);
    }
    return project?.id;
  };
  const openWorklog = (row?: Record<string, unknown>) => {
    setEditingWorklog(row);
    worklogForm.setFieldsValue(
      row
        ? { ...row }
        : {
            workType: 'UI设计',
            status: '待开始',
            estimatedHours: undefined,
            plannedCompletedAt: undefined,
            workContent: '',
          },
    );
    setWorklogOpen(true);
  };
  const openPlanner = async () => {
    if (!detail || isReadOnly(detail)) return;
    const existing = await superworkApi
      .getDesignWorkLogs(Number(detail.id))
      .catch(() => []);
    setPlannerRows(
      DESIGN_OPTIONS.map((workType) => {
        const matched = (existing || []).find(
          (item: Record<string, unknown>) => item.workType === workType,
        );
        return {
          workType,
          selected: Boolean(matched),
          designerId: matched?.designerId as number | undefined,
          estimatedHours: matched?.estimatedHours as number | undefined,
          plannedCompletedAt: matched?.plannedCompletedAt
            ? String(matched.plannedCompletedAt).slice(0, 10)
            : '',
        };
      }),
    );
    setPlannerOpen(true);
  };
  const loadCustomerContacts = async (projectId?: number) => {
    if (!projectId) {
      setCustomerContacts([]);
      return;
    }
    try {
      const result = await superworkApi.getCustomerContacts({
        projectId,
        page: 1,
        size: 200,
        isActive: 1,
      });
      setCustomerContacts(result.records || []);
    } catch {
      setCustomerContacts([]);
    }
  };
  const openCreateRequirement = () => {
    createForm.resetFields();
    createForm.setFieldValue('type', '产品需求');
    setCreateType('产品需求');
    setCustomerContacts([]);
    setCreateOpen(true);
  };
  const load = useCallback(
    async (page = 1, values = form.getFieldsValue()) => {
      setLoading(true);
      setError('');
      try {
        const result = await superworkApi.getRequirementOverview({
          page,
          size: 20,
          keyword: values.title,
          dataSource: values.dataSource,
          type: values.type,
          priority: values.priority,
          businessLineId: values.businessLineId,
          projectId: values.projectId,
          normalizedStatus: (() => {
            const status = Array.isArray(values.status)
              ? values.status[0]
              : values.status;
            return status &&
              ['待评估', '已拒绝', '待设计', '待确认', '待上线'].includes(
                status,
              )
              ? 'PENDING'
              : status &&
                  ['评估中', '设计中', '开发中', '测试中'].includes(status)
                ? 'IN_PROGRESS'
                : status && ['已上线', '已交付', '已验收'].includes(status)
                  ? 'COMPLETED'
                  : undefined;
          })(),
        });
        const statusFilter: string[] = values.status || [];
        const filtered = statusFilter.length
          ? (result.records || []).filter((record) =>
              statusFilter.includes(String((record as Requirement).status)),
            )
          : result.records || [];
        const records = filtered.map((record) => {
          const source = record as Requirement;
          const id = requirementIdOf(source);
          return {
            ...source,
            id,
            recordKey:
              source.recordKey ||
              (source.dataSource === 'YUNXIAO'
                ? String(id)
                : `local:${String(id)}`),
            reqNo: requirementNoOf(source),
          } as Requirement;
        });
        setData({
          records,
          total: result.total,
          current: result.current,
          size: result.size,
        });
        setAnalysis(result.analysis || {});
      } catch (e) {
        setError(e instanceof Error ? e.message : '需求数据加载失败');
      } finally {
        setLoading(false);
      }
    },
    [form],
  );
  useEffect(() => {
    void load();
    void Promise.allSettled([
      superworkApi.getProjects({ page: 1, size: 200 }),
      superworkApi.getBusinessLines({ page: 1, size: 200 }),
      superworkApi.getUsers({ page: 1, size: 200 }),
    ])
      .then(([projectResult, businessLineResult, userResult]) => {
        if (projectResult.status === 'fulfilled')
          setProjects(projectResult.value.records || []);
        if (businessLineResult.status === 'fulfilled')
          setBusinessLines(businessLineResult.value.records || []);
        if (userResult.status === 'fulfilled')
          setUsers(userResult.value.records || []);
      })
      .catch(() => undefined);
  }, [load]);
  const isReadOnly = (record?: Requirement) =>
    Boolean(record?.readOnly || record?.dataSource === 'YUNXIAO');
  const openDetail = async (record: Requirement) => {
    setDetail(record);
    setDetailExtras({ logs: [], tasks: [] });
    if (isReadOnly(record)) return;
    const id = Number(record.id);
    const [evaluation, confirmation, delivery, logs, tasks] = await Promise.all(
      [
        superworkApi.getRequirementEvaluation(id).catch(() => null),
        superworkApi.getRequirementConfirmation(id).catch(() => null),
        superworkApi.getRequirementDelivery(id).catch(() => null),
        superworkApi.getDesignWorkLogs(id).catch(() => []),
        superworkApi.getRequirementTasks(id).catch(() => []),
      ],
    );
    setDetailExtras({ evaluation, confirmation, delivery, logs, tasks });
  };
  const deepLinkId = useMemo(() => {
    const matched = location.pathname.match(
      /^\/requirements(?:-standalone)?\/(\d+)$/,
    );
    return matched ? matched[1] : undefined;
  }, [location.pathname]);
  useEffect(() => {
    if (!deepLinkId) return undefined;
    let active = true;
    void superworkApi
      .getRequirementById(deepLinkId)
      .then((record) => {
        if (active) void openDetail(record);
      })
      .catch(() => {
        if (active) message.error('需求详情加载失败');
      });
    return () => {
      active = false;
    };
  }, [deepLinkId]);
  const updateDesignStatus = async (
    item: Record<string, unknown>,
    status: string,
  ) => {
    if (!detail || isReadOnly(detail) || item.id == null) return;
    setActionLoading(`design:${String(item.id)}`);
    try {
      await superworkApi.updateDesignWorkLog(item.id as number, { status });
      message.success('设计工作状态已更新');
      await openDetail(detail);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设计工作状态更新失败');
    } finally {
      setActionLoading('');
    }
  };
  const updateRelatedTaskStatus = async (
    item: Record<string, unknown>,
    status: string,
  ) => {
    if (!detail || isReadOnly(detail) || item.id == null) return;
    setActionLoading(`task:${String(item.id)}`);
    try {
      await superworkApi.updateTask(item.id as number, { status });
      message.success('任务状态已更新');
      await openDetail(detail);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '任务状态更新失败');
    } finally {
      setActionLoading('');
    }
  };
  const currentUserId = () => {
    try {
      return Number(JSON.parse(localStorage.getItem('user') || '{}')?.id) || 1;
    } catch {
      return 1;
    }
  };
  const runStageAction = async (action: string) => {
    if (!detail || isReadOnly(detail)) return;
    if (action === 'submit_eval') {
      setEvaluationOpen(true);
      return;
    }
    if (action === 'plan_design') {
      void openPlanner();
      return;
    }
    setActionLoading(action);
    try {
      const id = Number(detail.id);
      if (action === 'deliver')
        await superworkApi.createRequirementDelivery({
          requirementId: id,
          deliveredBy: currentUserId(),
          deliveryNotes: '',
        });
      else if (action === 'accept')
        await superworkApi.acceptRequirementDelivery(id, {
          acceptedBy: currentUserId(),
          acceptanceNotes: '',
        });
      else if (
        action === 'approve' ||
        action === 'convert' ||
        action === 'reject'
      )
        await superworkApi.submitBuDecision({
          requirementId: id,
          decision:
            action === 'approve'
              ? '通过'
              : action === 'convert'
                ? '转产品需求'
                : '拒绝',
          decisionReason: '',
        });
      else await superworkApi.executeRequirementStageAction(id, action);
      message.success('需求阶段已更新');
      const refreshed = await superworkApi.getRequirementById(id);
      await load(data.current);
      await openDetail(refreshed);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActionLoading('');
    }
  };
  const createRequirement = async (values: Record<string, unknown>) => {
    try {
      const project = projects.find((item) => item.id === values.projectId);
      await superworkApi.createRequirement({
        title: String(values.title || ''),
        type: String(values.type || '产品需求'),
        priority: String(values.priority || '中'),
        description: String(values.description || ''),
        source: String(values.source || '内部'),
        projectId: values.projectId ? Number(values.projectId) : null,
        businessLineId:
          project?.businessLineId || values.businessLineId || null,
        expectedOnlineDate: values.expectedOnlineDate || null,
        customerContactId:
          createType === '项目需求' && values.customerContactId
            ? Number(values.customerContactId)
            : null,
      });
      message.success('需求已创建');
      setCreateOpen(false);
      createForm.resetFields();
      await load(1);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    }
  };
  const deleteRequirement = async () => {
    if (!detail || isReadOnly(detail)) return;
    try {
      await superworkApi.deleteRequirement(detail.id);
      message.success('需求已删除');
      setDetail(undefined);
      await load(data.current);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };
  const submitEvaluation = async (values: Record<string, unknown>) => {
    if (!detail) return;
    setActionLoading('submit_eval');
    try {
      await superworkApi.submitRequirementEvaluation({
        requirementId: Number(detail.id),
        ...values,
      });
      message.success('评估已提交');
      setEvaluationOpen(false);
      evaluationForm.resetFields();
      await openDetail(await superworkApi.getRequirementById(detail.id));
      await load(data.current);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交失败');
    } finally {
      setActionLoading('');
    }
  };
  const submitWorklog = async (values: Record<string, unknown>) => {
    if (!detail) return;
    setActionLoading('plan_design');
    try {
      const payload = { requirementId: Number(detail.id), ...values };
      if (editingWorklog?.id) {
        await superworkApi.updateDesignWorkLog(
          Number(editingWorklog.id),
          payload,
        );
      } else {
        await superworkApi.createDesignWorkLog(payload);
      }
      message.success(editingWorklog ? '设计规划已更新' : '设计规划已保存');
      setWorklogOpen(false);
      setEditingWorklog(undefined);
      worklogForm.resetFields();
      await openDetail(await superworkApi.getRequirementById(detail.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setActionLoading('');
    }
  };
  const savePlanner = async () => {
    if (!detail) return;
    const selected = plannerRows.filter((row) => row.selected);
    if (!selected.length) {
      message.warning('请至少选择一个设计环节');
      return;
    }
    if (
      selected.some(
        (row) =>
          !row.designerId || !row.estimatedHours || !row.plannedCompletedAt,
      )
    ) {
      message.warning('请为已选设计环节补齐负责人、预估工时和计划完成日期');
      return;
    }
    setPlannerSaving(true);
    try {
      const existing = await superworkApi
        .getDesignWorkLogs(Number(detail.id))
        .catch(() => []);
      const existingByType = new Map(
        (existing || []).map((item: Record<string, unknown>) => [
          String(item.workType),
          item,
        ]),
      );
      for (const row of selected) {
        const payload = {
          requirementId: Number(detail.id),
          workType: row.workType,
          designerId: row.designerId,
          estimatedHours: row.estimatedHours,
          plannedCompletedAt: row.plannedCompletedAt,
        };
        const matched = existingByType.get(row.workType);
        if (matched?.id)
          await superworkApi.updateDesignWorkLog(Number(matched.id), payload);
        else await superworkApi.createDesignWorkLog(payload);
      }
      for (const row of plannerRows.filter((item) => !item.selected)) {
        const matched = existingByType.get(row.workType);
        if (matched?.id)
          await superworkApi.deleteDesignWorkLog(Number(matched.id));
      }
      message.success('设计规划已保存');
      setPlannerOpen(false);
      await openDetail(await superworkApi.getRequirementById(detail.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设计规划保存失败');
    } finally {
      setPlannerSaving(false);
    }
  };
  const removeWorklog = (row: Record<string, unknown>) => {
    if (!row.id) return;
    Modal.confirm({
      title: '删除这条设计规划？',
      content: String(row.workType || '设计工作'),
      okType: 'danger',
      okText: '删除',
      cancelText: '取消',
      onOk: async () => {
        await superworkApi.deleteDesignWorkLog(Number(row.id));
        message.success('设计规划已删除');
        if (detail)
          await openDetail(await superworkApi.getRequirementById(detail.id));
      },
    });
  };
  const submitTask = async (values: Record<string, unknown>) => {
    if (!detail) return;
    setActionLoading('add_task');
    try {
      await superworkApi.createTask({
        requirementId: Number(detail.id),
        createdBy: currentUserId(),
        taskType: '开发任务',
        ...values,
      });
      message.success('任务已创建');
      setTaskOpen(false);
      taskForm.resetFields();
      await openDetail(detail);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    } finally {
      setActionLoading('');
    }
  };
  const columns: TableProps<Requirement>['columns'] = [
    {
      title: '需求编号',
      dataIndex: 'reqNo',
      width: 136,
      render: (value, record) => (
        <Typography.Text code>
          {value || requirementNoOf(record)}
        </Typography.Text>
      ),
    },
    {
      title: '需求标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (value, record) => (
        <Button type="link" onClick={() => void openDetail(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '项目',
      dataIndex: 'projectName',
      width: 160,
      ellipsis: true,
      render: (_, record) => record.projectName || record.project || '—',
    },
    {
      title: '来源',
      dataIndex: 'dataSource',
      width: 82,
      render: (value, record) =>
        isReadOnly(record) || value === 'YUNXIAO' ? (
          <Tag color="blue">云效</Tag>
        ) : (
          <Tag>本地</Tag>
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 106,
      render: (value) => (
        <Tag color={statusColors[value] || 'default'}>{value || '未设置'}</Tag>
      ),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 84,
      render: (value) =>
        value ? (
          <Tag
            color={
              value === '高' ? 'error' : value === '中' ? 'warning' : 'default'
            }
          >
            {value}
          </Tag>
        ) : (
          '—'
        ),
    },
    {
      title: '负责人',
      dataIndex: 'owner',
      width: 112,
      render: (value) => value || '未分配',
    },
    {
      title: '操作',
      key: 'action',
      width: 96,
      render: (_, record) => (
        <Button
          type="link"
          icon={<EyeOutlined />}
          onClick={() => void openDetail(record)}
        >
          查看
        </Button>
      ),
    },
  ];
  return (
    <div className="sw-page sw-requirements">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            WORK ITEMS / REQUIREMENTS
          </Typography.Text>
          <Typography.Title level={2}>需求管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            本地需求支持阶段推进与交付闭环；云效同步需求保持只读。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={openCreateRequirement}
          >
            新建需求
          </Button>
        </Space>
      </div>
      <Card variant="borderless" className="sw-filter-card">
        <Form form={form} layout="inline" onFinish={() => void load(1)}>
          <Form.Item name="title" label="关键词">
            <Input
              allowClear
              placeholder="标题 / 编号"
              prefix={<SearchOutlined />}
            />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              mode="multiple"
              placeholder="全部状态"
              style={{ minWidth: 220 }}
              maxTagCount="responsive"
              options={[
                '待评估',
                '评估中',
                '待设计',
                '设计中',
                '待确认',
                '开发中',
                '测试中',
                '待上线',
                '已上线',
                '已交付',
                '已验收',
                '已拒绝',
              ].map((value) => ({ label: value, value }))}
            />
          </Form.Item>
          <Form.Item name="dataSource" label="来源">
            <Select
              allowClear
              placeholder="全部来源"
              style={{ width: 110 }}
              options={[
                { label: '本地', value: 'LOCAL' },
                { label: '云效', value: 'YUNXIAO' },
              ]}
            />
          </Form.Item>
          <Form.Item name="type" label="类型">
            <Select
              allowClear
              placeholder="全部类型"
              style={{ width: 120 }}
              options={['产品需求', '项目需求'].map((value) => ({
                label: value,
                value,
              }))}
            />
          </Form.Item>
          <Form.Item name="priority" label="优先级">
            <Select
              allowClear
              placeholder="全部"
              style={{ width: 100 }}
              options={['高', '中', '低'].map((value) => ({
                label: value,
                value,
              }))}
            />
          </Form.Item>
          <Form.Item name="businessLineId" label="业务线">
            <Select
              allowClear
              placeholder="全部业务线"
              style={{ width: 150 }}
              options={businessLines.map((line) => ({
                label: line.name,
                value: line.id,
              }))}
              onChange={() => form.setFieldValue('projectId', undefined)}
            />
          </Form.Item>
          <Form.Item name="projectId" label="项目">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部项目"
              style={{ width: 180 }}
              options={projects
                .filter(
                  (project) =>
                    !filterBusinessLineId ||
                    project.businessLineId === filterBusinessLineId,
                )
                .map((project) => ({
                  label: project.fullPath || project.name,
                  value: project.id,
                }))}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button
                onClick={() => {
                  form.resetFields();
                  void load(1, {});
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
          <Form.Item>
            <Segmented
              value={viewMode}
              onChange={(value) =>
                setViewMode(value as 'cards' | 'kanban' | 'table')
              }
              options={[
                { label: '列表', value: 'table' },
                { label: '卡片', value: 'cards' },
                { label: '看板', value: 'kanban' },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Segmented
              value={mainView}
              onChange={(value) => setMainView(value as 'detail' | 'analysis')}
              options={[
                { label: '需求明细', value: 'detail' },
                { label: '结构分析', value: 'analysis' },
              ]}
            />
          </Form.Item>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取需求数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Row gutter={[12, 12]} className="sw-requirement-analysis">
        {[
          ['总需求', analysis.totalCount ?? data.total],
          [
            '完成率',
            analysis.completionRate == null
              ? undefined
              : `${analysis.completionRate}%`,
          ],
          ['逾期未完成', analysis.overdueIncompleteCount],
          ['未分配', analysis.unassignedCount],
          ['缺少截止日期', analysis.missingDueDateCount],
          ['实际工时', analysis.totalActualHours],
        ].map(([title, value]) => (
          <Col xs={12} sm={8} lg={4} key={String(title)}>
            <Card variant="borderless" size="small">
              <Statistic
                title={title as string}
                value={(value as string | number | undefined) ?? '—'}
              />
            </Card>
          </Col>
        ))}
      </Row>
      {mainView === 'detail' ? (
        viewMode === 'table' ? (
          <Card variant="borderless" className="sw-table-card">
            <Table
              rowKey={requirementKeyOf}
              loading={loading}
              columns={columns}
              dataSource={data.records}
              locale={{ emptyText: <Empty description="暂无符合条件的需求" /> }}
              scroll={{ x: 980 }}
              pagination={{
                current: data.current,
                pageSize: data.size,
                total: data.total,
                showSizeChanger: false,
                showTotal: (total) => `共 ${total} 条`,
                onChange: (page) => void load(page),
              }}
            />
          </Card>
        ) : viewMode === 'kanban' ? (
          loading ? (
            <Card variant="borderless" loading />
          ) : (
            <div className="sw-requirement-kanban">
              {(() => {
                const onPage = Array.from(
                  new Set(
                    data.records
                      .map((record) => String(record.status || '未设置'))
                      .filter((status) => !KANBAN_STATUSES.includes(status)),
                  ),
                );
                return [...KANBAN_STATUSES, ...onPage].map((status) => {
                  const cards = data.records.filter(
                    (record) => String(record.status || '未设置') === status,
                  );
                  if (!cards.length) return null;
                  return (
                    <div className="sw-kanban-column" key={status}>
                      <div className="sw-kanban-column-head">
                        <span className="sw-kanban-title">{status}</span>
                        <span className="sw-kanban-count">{cards.length}</span>
                      </div>
                      <div className="sw-kanban-cards">
                        {cards.map((card) => (
                          <button
                            type="button"
                            key={requirementKeyOf(card)}
                            className="sw-kanban-card"
                            onClick={() => void openDetail(card)}
                          >
                            <span className="sw-kanban-card-no">
                              {requirementNoOf(card)}
                            </span>
                            <span className="sw-kanban-card-title">
                              {card.title}
                            </span>
                            <span className="sw-kanban-card-footer">
                              <span>
                                {card.projectName || card.project || '未关联'}
                              </span>
                              <span>{card.priority || '—'}</span>
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  );
                });
              })()}
              {!data.records.length && (
                <Empty description="暂无符合条件的需求" />
              )}
            </div>
          )
        ) : loading ? (
          <Card variant="borderless" loading />
        ) : data.records.length ? (
          <div className="sw-requirement-grid">
            {data.records.map((record) => (
              <Card
                key={requirementKeyOf(record)}
                hoverable
                className="sw-requirement-card"
                onClick={() => void openDetail(record)}
              >
                <Space
                  orientation="vertical"
                  style={{ width: '100%' }}
                  size={10}
                >
                  <Space
                    style={{ width: '100%', justifyContent: 'space-between' }}
                  >
                    <Typography.Text code>
                      {requirementNoOf(record)}
                    </Typography.Text>
                    <Tag color={statusColors[record.status || ''] || 'default'}>
                      {record.status || '未设置'}
                    </Tag>
                  </Space>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {record.title}
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    {record.projectName || record.project || '未关联项目'}
                  </Typography.Text>
                  <Typography.Paragraph
                    type="secondary"
                    ellipsis={{ rows: 2 }}
                    style={{ minHeight: 42, margin: 0 }}
                  >
                    {record.description || '暂无描述'}
                  </Typography.Paragraph>
                  <Space>
                    <Tag
                      color={
                        record.priority === '高'
                          ? 'error'
                          : record.priority === '中'
                            ? 'warning'
                            : 'default'
                      }
                    >
                      {record.priority || '未设置优先级'}
                    </Tag>
                    <Typography.Text type="secondary">
                      负责人：{record.owner || '未分配'}
                    </Typography.Text>
                  </Space>
                </Space>
              </Card>
            ))}
          </div>
        ) : (
          <Empty description="暂无符合条件的需求" />
        )
      ) : (
        <Row gutter={[12, 12]} className="sw-requirement-analysis-board">
          {[
            ['状态分布', analysis.statusDistribution],
            ['项目分布', analysis.projectDistribution],
            ['数据来源', analysis.sourceDistribution],
            ['优先级分布', analysis.priorityDistribution],
          ].map(([title, values]) => (
            <Col xs={24} md={12} key={String(title)}>
              <Card variant="borderless" title={String(title)}>
                <Space
                  orientation="vertical"
                  style={{ width: '100%' }}
                  size={8}
                >
                  {Array.isArray(values) && values.length ? (
                    values.slice(0, 8).map((item) => {
                      const row = item as Record<string, unknown>;
                      const count = Number(row.count || 0);
                      const percentage = Number(row.percentage || 0);
                      return (
                        <div
                          key={`${String(title)}-${String(row.key || row.label || 'unknown')}`}
                          className="sw-analysis-row"
                        >
                          <Space
                            style={{
                              width: '100%',
                              justifyContent: 'space-between',
                            }}
                          >
                            <Typography.Text>
                              {String(row.label || row.key || '未设置')}
                            </Typography.Text>
                            <Typography.Text strong>{count}</Typography.Text>
                          </Space>
                          <div className="sw-analysis-bar">
                            <span
                              style={{ width: `${Math.min(100, percentage)}%` }}
                            />
                          </div>
                        </div>
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
      <Modal
        title="新建需求"
        open={createOpen}
        okText="创建"
        cancelText="取消"
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
      >
        <Form
          form={createForm}
          layout="vertical"
          onValuesChange={(changed) => {
            if (changed.type) {
              setCreateType(String(changed.type));
              createForm.resetFields(['customerContactId']);
            }
            if (changed.projectId) {
              const selectedProjectId = Number(changed.projectId);
              void loadCustomerContacts(
                rootProjectId(selectedProjectId) || selectedProjectId,
              );
            }
            if (changed.businessLineId) {
              createForm.setFieldValue('projectId', undefined);
              setCustomerContacts([]);
            }
          }}
          onFinish={(values) => void createRequirement(values)}
        >
          <Form.Item
            name="title"
            label="需求标题"
            rules={[{ required: true, message: '请输入需求标题' }]}
          >
            <Input />
          </Form.Item>
          <Space style={{ display: 'flex' }}>
            <Form.Item
              name="type"
              label="需求类型"
              initialValue="产品需求"
              rules={[{ required: true }]}
            >
              <Select
                onChange={(value) => setCreateType(value)}
                style={{ width: 180 }}
                options={['产品需求', '项目需求'].map((value) => ({
                  label: value,
                  value,
                }))}
              />
            </Form.Item>
            <Form.Item name="priority" label="优先级" initialValue="中">
              <Select
                style={{ width: 120 }}
                options={['高', '中', '低'].map((value) => ({
                  label: value,
                  value,
                }))}
              />
            </Form.Item>
          </Space>
          <Space style={{ display: 'flex', width: '100%' }} align="start">
            <Form.Item
              name="source"
              label="需求来源"
              initialValue="内部"
              style={{ flex: 1 }}
            >
              <Select
                options={['内部', '外部'].map((value) => ({
                  label: value,
                  value,
                }))}
              />
            </Form.Item>
            <Form.Item name="owner" label="负责人" style={{ flex: 1 }}>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                placeholder="请选择负责人"
                options={users.map((user) => ({
                  value: user.realName || user.username,
                  label: user.realName || user.username || String(user.id),
                }))}
              />
            </Form.Item>
          </Space>
          <Form.Item name="businessLineId" label="业务线">
            <Select
              allowClear
              placeholder="全部业务线"
              options={businessLines.map((line) => ({
                label: line.name,
                value: line.id,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="customerContactId"
            label={
              createType === '项目需求'
                ? '客户联系人'
                : '关联客户联系人（可选）'
            }
            rules={
              createType === '项目需求'
                ? [{ required: true, message: '项目需求请选择客户联系人' }]
                : undefined
            }
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={
                createForm.getFieldValue('projectId')
                  ? '请选择联系人'
                  : '先选择项目'
              }
              options={customerContacts.map((item) => ({
                value: item.id,
                label: `${item.name || item.contactName || '未命名'}${
                  item.phone ? ` · ${item.phone}` : ''
                }`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="projectId"
            label="所属项目"
            rules={[{ required: true, message: '请选择项目' }]}
          >
            <TreeSelect
              treeData={projectTreeData}
              treeDefaultExpandAll
              showSearch
              allowClear
              treeNodeFilterProp="title"
              placeholder="选择项目或子项目"
              style={{ width: '100%' }}
            />
          </Form.Item>
          {createType === '产品需求' && (
            <Form.Item name="requester" label="提出人">
              <Input placeholder="请输入提出人" />
            </Form.Item>
          )}
          <Form.Item name="expectedOnlineDate" label="预计上线日期">
            <Input type="date" />
          </Form.Item>
          <Form.Item name="description" label="需求描述">
            <RichTextEditor />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="提交需求评估"
        open={evaluationOpen}
        okText="提交"
        cancelText="取消"
        confirmLoading={actionLoading === 'submit_eval'}
        onCancel={() => setEvaluationOpen(false)}
        onOk={() => evaluationForm.submit()}
      >
        <Form
          form={evaluationForm}
          layout="vertical"
          onFinish={(values) => void submitEvaluation(values)}
        >
          <Form.Item name="isFeasible" label="是否可行" initialValue={1}>
            <Select
              options={[
                { label: '可行', value: 1 },
                { label: '不可行', value: 0 },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="estimatedWorkload"
            label="预估工时（小时）"
            rules={[{ required: true, message: '请输入预估工时' }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="estimatedCost" label="预估报价">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="feasibilityDesc" label="评估说明">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="配置设计规划"
        open={plannerOpen}
        onCancel={() => setPlannerOpen(false)}
        onOk={() => void savePlanner()}
        okText="保存规划"
        cancelText="取消"
        confirmLoading={plannerSaving}
        width={760}
        destroyOnHidden
      >
        <Space orientation="vertical" style={{ width: '100%' }}>
          {plannerRows.map((row, index) => (
            <Card size="small" key={row.workType}>
              <Checkbox
                checked={row.selected}
                onChange={(event) =>
                  setPlannerRows((rows) =>
                    rows.map((item, rowIndex) =>
                      rowIndex === index
                        ? { ...item, selected: event.target.checked }
                        : item,
                    ),
                  )
                }
              >
                {row.workType}
              </Checkbox>
              {row.selected && (
                <Row gutter={12} style={{ marginTop: 12 }}>
                  <Col xs={24} md={8}>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      value={row.designerId}
                      onChange={(value) =>
                        setPlannerRows((rows) =>
                          rows.map((item, rowIndex) =>
                            rowIndex === index
                              ? { ...item, designerId: value }
                              : item,
                          ),
                        )
                      }
                      placeholder="负责人"
                      style={{ width: '100%' }}
                      options={users.map((user) => ({
                        value: user.id,
                        label:
                          user.realName || user.username || String(user.id),
                      }))}
                    />
                  </Col>
                  <Col xs={24} md={7}>
                    <InputNumber
                      min={1}
                      value={row.estimatedHours}
                      onChange={(value) =>
                        setPlannerRows((rows) =>
                          rows.map((item, rowIndex) =>
                            rowIndex === index
                              ? {
                                  ...item,
                                  estimatedHours:
                                    value == null ? undefined : Number(value),
                                }
                              : item,
                          ),
                        )
                      }
                      addonBefore="工时"
                      style={{ width: '100%' }}
                    />
                  </Col>
                  <Col xs={24} md={9}>
                    <Input
                      value={row.plannedCompletedAt}
                      onChange={(event) =>
                        setPlannerRows((rows) =>
                          rows.map((item, rowIndex) =>
                            rowIndex === index
                              ? {
                                  ...item,
                                  plannedCompletedAt: event.target.value,
                                }
                              : item,
                          ),
                        )
                      }
                      placeholder="计划完成日期 YYYY-MM-DD"
                    />
                  </Col>
                </Row>
              )}
            </Card>
          ))}
        </Space>
      </Modal>
      <Modal
        title={editingWorklog ? '编辑设计工作规划' : '设计工作规划'}
        open={worklogOpen}
        okText="保存"
        cancelText="取消"
        confirmLoading={actionLoading === 'plan_design'}
        onCancel={() => {
          setWorklogOpen(false);
          setEditingWorklog(undefined);
          worklogForm.resetFields();
        }}
        onOk={() => worklogForm.submit()}
      >
        <Form
          form={worklogForm}
          layout="vertical"
          onFinish={(values) => void submitWorklog(values)}
        >
          <Form.Item
            name="workType"
            label="工作类型"
            initialValue="UI设计"
            rules={[{ required: true }]}
          >
            <Select
              options={['原型设计', 'UI设计', '技术方案'].map((value) => ({
                value,
                label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue="待开始">
            <Select
              options={['待开始', '进行中', '已完成', '已取消'].map(
                (value) => ({
                  value,
                  label: value,
                }),
              )}
            />
          </Form.Item>
          <Form.Item
            name="designerId"
            label="负责人"
            rules={[{ required: true, message: '请选择负责人' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={users.map((item) => ({
                value: item.id,
                label: item.realName || item.username || String(item.id),
              }))}
            />
          </Form.Item>
          <Form.Item name="estimatedHours" label="预估工时">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="plannedCompletedAt" label="计划完成日期">
            <Input placeholder="YYYY-MM-DD" />
          </Form.Item>
          <Form.Item name="workContent" label="工作内容">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="新增关联任务"
        open={taskOpen}
        okText="创建"
        cancelText="取消"
        confirmLoading={actionLoading === 'add_task'}
        onCancel={() => setTaskOpen(false)}
        onOk={() => taskForm.submit()}
      >
        <Form
          form={taskForm}
          layout="vertical"
          onFinish={(values) => void submitTask(values)}
        >
          <Form.Item
            name="title"
            label="任务标题"
            rules={[{ required: true, message: '请输入任务标题' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="assigneeId" label="负责人">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={users.map((item) => ({
                value: item.id,
                label: item.realName || item.username || String(item.id),
              }))}
            />
          </Form.Item>
          <Form.Item name="estimatedHours" label="预估工时">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="任务描述">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={
          <Space>
            {detail?.title || '需求详情'}
            {isReadOnly(detail) && <Tag color="blue">只读</Tag>}
          </Space>
        }
        size={680}
        open={Boolean(detail)}
        onClose={() => {
          setDetail(undefined);
          if (location.pathname !== '/requirements')
            history.push('/requirements');
        }}
        extra={
          detail && !isReadOnly(detail) ? (
            <Popconfirm
              title="确认删除这条需求？"
              onConfirm={() => void deleteRequirement()}
            >
              <Button danger>删除</Button>
            </Popconfirm>
          ) : undefined
        }
      >
        {detail && (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="需求编号">
                {requirementNoOf(detail)}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColors[detail.status || ''] || 'default'}>
                  {detail.status || '未设置'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="项目" span={2}>
                {detail.projectName || detail.project || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="业务线">
                {detail.businessLine || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="优先级">
                {detail.priority || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="负责人">
                {detail.owner || '未分配'}
              </Descriptions.Item>
              <Descriptions.Item label="需求来源">
                {detail.source || '内部'}
              </Descriptions.Item>
              <Descriptions.Item label="提出人">
                {String(detail.requester || '—')}
              </Descriptions.Item>
              <Descriptions.Item label="预计上线">
                {String(detail.expectedOnlineDate || detail.expectDate || '—')}
              </Descriptions.Item>
              <Descriptions.Item label="客户联系人">
                {String(
                  detail.customerContactName || detail.customerContact || '—',
                )}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {detail.createdAt || '—'}
              </Descriptions.Item>
            </Descriptions>
            <Card size="small" style={{ marginTop: 16 }}>
              <Typography.Text strong>生命周期</Typography.Text>
              <Steps
                size="small"
                current={Math.max(
                  0,
                  LIFECYCLE_STEPS.indexOf(String(detail.status || '待评估')),
                )}
                status={detail.status === '已拒绝' ? 'error' : undefined}
                items={LIFECYCLE_STEPS.map((step) => ({ title: step }))}
                responsive
                style={{ marginTop: 14 }}
              />
            </Card>
            <Typography.Title level={5} style={{ marginTop: 24 }}>
              需求描述
            </Typography.Title>
            {detail.description ? (
              <div
                className="sw-requirement-description"
                // biome-ignore lint/security/noDangerouslySetInnerHtml: content is sanitized before rendering
                dangerouslySetInnerHTML={{
                  __html: sanitizeRichText(String(detail.description)),
                }}
              />
            ) : (
              <Typography.Paragraph type="secondary">
                暂无描述
              </Typography.Paragraph>
            )}
            {!isReadOnly(detail) && (
              <Space wrap>
                {(stageActions[detail.status || ''] || []).map(
                  ([action, label]) => (
                    <Button
                      key={action}
                      type="primary"
                      loading={actionLoading === action}
                      onClick={() => void runStageAction(action)}
                    >
                      {label}
                    </Button>
                  ),
                )}
                {detail.status === '待确认' && (
                  <Button
                    onClick={() =>
                      void superworkApi
                        .createRequirementConfirmation({
                          requirementId: Number(detail.id),
                          confirmationType:
                            detail.type === '项目需求'
                              ? '客户确认'
                              : '内部确认',
                          confirmedBy: currentUserId(),
                          confirmationNotes: '',
                        })
                        .then(() => openDetail(detail))
                        .then(() => message.success('确认已记录'))
                        .catch((e) =>
                          message.error(
                            e instanceof Error ? e.message : '确认失败',
                          ),
                        )
                    }
                  >
                    完成确认
                  </Button>
                )}
                <Button onClick={() => setTaskOpen(true)}>新增任务</Button>
              </Space>
            )}
            <Tabs
              style={{ marginTop: 18 }}
              items={[
                {
                  key: 'evaluation',
                  label: '评估',
                  children: (
                    <Descriptions column={1} size="small">
                      <Descriptions.Item label="可行性">
                        {detailExtras.evaluation?.isFeasible == null
                          ? '暂无'
                          : detailExtras.evaluation.isFeasible
                            ? '可行'
                            : '不可行'}
                      </Descriptions.Item>
                      <Descriptions.Item label="预估工时">
                        {String(
                          detailExtras.evaluation?.estimatedWorkload ?? '—',
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="预估报价">
                        {String(detailExtras.evaluation?.estimatedCost ?? '—')}
                      </Descriptions.Item>
                      <Descriptions.Item label="预估上线">
                        {String(
                          detailExtras.evaluation?.estimatedOnlineDate ?? '—',
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="评估人">
                        {String(detailExtras.evaluation?.evaluator ?? '—')}
                      </Descriptions.Item>
                    </Descriptions>
                  ),
                },
                {
                  key: 'design',
                  label: `设计工时（${detailExtras.logs.length}）`,
                  children: detailExtras.logs.length ? (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      {detailExtras.logs.map((item) => (
                        <Card
                          key={String(
                            item.id ||
                              `${item.workType || 'design'}-${item.createdAt || item.updatedAt || 'entry'}`,
                          )}
                          size="small"
                          style={{ marginBottom: 8 }}
                          title={String(item.workType || '设计工作')}
                          extra={
                            !isReadOnly(detail) && (
                              <Space size={0}>
                                <Button
                                  type="text"
                                  size="small"
                                  icon={<EditOutlined />}
                                  aria-label="编辑设计规划"
                                  onClick={() => openWorklog(item)}
                                />
                                <Button
                                  type="text"
                                  danger
                                  size="small"
                                  icon={<DeleteOutlined />}
                                  aria-label="删除设计规划"
                                  onClick={() => removeWorklog(item)}
                                />
                              </Space>
                            )
                          }
                        >
                          <Space wrap>
                            <Tag>{String(item.status || '未设置')}</Tag>
                            <Typography.Text type="secondary">
                              预估 {String(item.estimatedHours || 0)}h
                            </Typography.Text>
                            <Typography.Text type="secondary">
                              负责人{' '}
                              {String(
                                item.designerName ||
                                  item.designerId ||
                                  '未分配',
                              )}
                            </Typography.Text>
                            {item.plannedCompletedAt != null && (
                              <Typography.Text type="secondary">
                                截止 {String(item.plannedCompletedAt)}
                              </Typography.Text>
                            )}
                            {!isReadOnly(detail) &&
                              detail.status === '设计中' &&
                              item.id != null && (
                                <Select
                                  size="small"
                                  value={String(item.status || '待开始')}
                                  loading={
                                    actionLoading ===
                                    `design:${String(item.id)}`
                                  }
                                  onChange={(value) =>
                                    void updateDesignStatus(item, value)
                                  }
                                  options={[
                                    '待开始',
                                    '进行中',
                                    '已完成',
                                    '已取消',
                                  ].map((value) => ({ value, label: value }))}
                                />
                              )}
                          </Space>
                        </Card>
                      ))}
                      {!isReadOnly(detail) && (
                        <Button
                          type="dashed"
                          block
                          onClick={() => openWorklog()}
                        >
                          新增设计工作
                        </Button>
                      )}
                    </Space>
                  ) : (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description="暂无设计工时"
                      />
                      {!isReadOnly(detail) && (
                        <Button
                          type="dashed"
                          block
                          onClick={() => openWorklog()}
                        >
                          新增设计工作
                        </Button>
                      )}
                    </Space>
                  ),
                },
                {
                  key: 'tasks',
                  label: `关联任务（${detailExtras.tasks.length}）`,
                  children: detailExtras.tasks.length ? (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      {detailExtras.tasks.map((item) => (
                        <Card
                          key={String(
                            item.id ||
                              `${item.title || 'task'}-${item.assigneeName || item.assignee || 'unassigned'}`,
                          )}
                          size="small"
                          style={{ marginBottom: 8 }}
                        >
                          <Space
                            style={{
                              width: '100%',
                              justifyContent: 'space-between',
                            }}
                            wrap
                          >
                            <Typography.Text strong>
                              {String(item.title || '未命名任务')}
                            </Typography.Text>
                            {!isReadOnly(detail) &&
                            ['开发中', '测试中'].includes(
                              String(detail.status),
                            ) &&
                            item.id != null ? (
                              <Select
                                size="small"
                                value={String(item.status || '待开始')}
                                loading={
                                  actionLoading === `task:${String(item.id)}`
                                }
                                onChange={(value) =>
                                  void updateRelatedTaskStatus(item, value)
                                }
                                options={[
                                  '待开始',
                                  '进行中',
                                  '已完成',
                                  '已测试',
                                ].map((value) => ({ value, label: value }))}
                              />
                            ) : (
                              <Tag>{String(item.status || '未设置')}</Tag>
                            )}
                          </Space>
                          <Typography.Text type="secondary">
                            {String(
                              item.assigneeName || item.assignee || '未分配',
                            )}{' '}
                            · {String(item.estimatedHours ?? '—')}h
                          </Typography.Text>
                        </Card>
                      ))}
                    </Space>
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="暂无关联任务"
                    />
                  ),
                },
                {
                  key: 'delivery',
                  label: '交付验收',
                  children: (
                    <Descriptions column={1} size="small">
                      <Descriptions.Item label="确认记录">
                        {detailExtras.confirmation ? '已确认' : '未确认'}
                      </Descriptions.Item>
                      <Descriptions.Item label="交付记录">
                        {detailExtras.delivery
                          ? detailExtras.delivery.acceptedAt
                            ? '已验收'
                            : '已交付'
                          : '未交付'}
                      </Descriptions.Item>
                    </Descriptions>
                  ),
                },
              ]}
            />
            <Row gutter={12} style={{ marginTop: 18 }}>
              <Col xs={24} md={12}>
                <Card size="small" title="附件">
                  {Array.isArray(detail.attachments) &&
                  detail.attachments.length ? (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      {detail.attachments.map((attachment) => {
                        const item = attachment as Record<string, unknown>;
                        return (
                          <Space
                            key={String(
                              item.id ||
                                `${item.name || item.fileName || 'attachment'}-${item.size || ''}`,
                            )}
                            style={{
                              justifyContent: 'space-between',
                              width: '100%',
                            }}
                          >
                            <Typography.Text>
                              {String(
                                item.name || item.fileName || '未命名附件',
                              )}
                            </Typography.Text>
                            <Typography.Text type="secondary">
                              {String(item.size || '')}
                            </Typography.Text>
                          </Space>
                        );
                      })}
                    </Space>
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="暂无附件"
                    />
                  )}
                </Card>
              </Col>
              <Col xs={24} md={12}>
                <Card size="small" title="活动日志">
                  <Space orientation="vertical" style={{ width: '100%' }}>
                    <Typography.Text type="secondary">
                      需求创建 · {String(detail.createdAt || '—')}
                    </Typography.Text>
                    {detail.updatedAt && (
                      <Typography.Text type="secondary">
                        最近更新 · {String(detail.updatedAt)}
                      </Typography.Text>
                    )}
                    {detailExtras.logs.slice(0, 4).map((item) => (
                      <Typography.Text
                        type="secondary"
                        key={String(
                          item.id ||
                            `${item.workType || 'design'}-${item.updatedAt || item.createdAt || 'entry'}`,
                        )}
                      >
                        {String(item.workType || '设计工作')} ·{' '}
                        {String(item.updatedAt || item.createdAt || '—')}
                      </Typography.Text>
                    ))}
                  </Space>
                </Card>
              </Col>
            </Row>
          </>
        )}
      </Drawer>
    </div>
  );
}
