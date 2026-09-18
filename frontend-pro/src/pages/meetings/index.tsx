import {
  ArrowLeftOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  SoundOutlined,
  ThunderboltOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { Pie } from '@ant-design/plots';
import { history } from '@umijs/max';
import type { TableProps, UploadFile } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
  Upload,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type MeetingDetail,
  type MeetingListItem,
  type MeetingSpeaker,
  type MeetingStatus,
  type MeetingTodo,
  type MeetingTodoConvertPayload,
  type ProjectMember,
  type ProjectRecord,
  type ProjectTreeNode,
  type Requirement,
  superworkApi,
  type UserRecord,
} from '@/services/superwork/api';
import { computeSpeakerStats } from './stats';
import '../workbench/style.less';
import './style.less';

const PAGE_SIZE = 10;
const POLL_INTERVAL_MS = 3000;
const HIGHLIGHT_MS = 2000;
const AUDIO_ACCEPT = '.mp3,.wav,.m4a,.aac,.ogg,.flac,.mp4,.webm';
const TASK_TYPE_OPTIONS = ['开发任务', '测试任务', '设计任务', '其他'];
const SEVERITY_OPTIONS = ['P0', 'P1', 'P2', 'P3'];

const STATUS_META: Record<MeetingStatus, { label: string; color: string }> = {
  UPLOADED: { label: '待处理', color: 'default' },
  TRANSCRIBING: { label: '转写中', color: 'processing' },
  SUMMARIZING: { label: '总结中', color: 'processing' },
  DRAFT: { label: '草稿', color: 'warning' },
  CONFIRMED: { label: '已确认', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
};

/** 处理中的状态（含刚上传待入队）；列表与详情的轮询只在终态停止。 */
const IN_FLIGHT_STATUSES: MeetingStatus[] = [
  'UPLOADED',
  'TRANSCRIBING',
  'SUMMARIZING',
];

const isInFlight = (status: MeetingStatus) =>
  IN_FLIGHT_STATUSES.includes(status);
const statusMetaOf = (status: MeetingStatus) =>
  STATUS_META[status] || STATUS_META.UPLOADED;

const msToClock = (ms?: number | null) => {
  if (ms == null || ms < 0) return '--:--';
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
};

const flattenProjects = (nodes: ProjectTreeNode[]) => {
  const flattened: ProjectTreeNode[] = [];
  const collect = (list: ProjectTreeNode[]) => {
    list.forEach((node) => {
      flattened.push(node);
      if (node.children?.length) collect(node.children);
    });
  };
  collect(nodes);
  return flattened;
};

const collectProjectIds = (rootId: number, nodes: ProjectTreeNode[]) => {
  const ids = new Set<number>([rootId]);
  const collect = (list: ProjectTreeNode[]) => {
    list.forEach((node) => {
      if (node.parentId != null && ids.has(node.parentId)) ids.add(node.id);
      if (node.children?.length) collect(node.children);
    });
  };
  collect(nodes);
  return ids;
};

export default function MeetingsPage() {
  const deepLinkId = useMemo(() => {
    const matched = location.pathname.match(/^\/meetings\/(\d+)$/);
    return matched ? Number(matched[1]) : undefined;
  }, [location.pathname]);
  return (
    <div className="sw-page sw-meetings">
      {deepLinkId ? (
        <MeetingDetailPanel id={deepLinkId} />
      ) : (
        <MeetingListPanel />
      )}
    </div>
  );
}

function MeetingListPanel() {
  const [rows, setRows] = useState<MeetingListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<MeetingStatus>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadFiles, setUploadFiles] = useState<UploadFile[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [uploadForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getMeetings({
        page,
        size: PAGE_SIZE,
        status: statusFilter,
      });
      setRows(result.records || []);
      setTotal(result.total || 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '会议列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void Promise.allSettled([
      superworkApi.getProjects({ page: 1, size: 200 }),
    ]).then(([projectResult]) => {
      if (projectResult.status === 'fulfilled')
        setProjects(projectResult.value.records || []);
    });
  }, []);

  const inFlightKey = rows
    .filter((row) => isInFlight(row.status))
    .map((row) => row.id)
    .join(',');

  useEffect(() => {
    if (!inFlightKey) return undefined;
    const ids = inFlightKey.split(',').map(Number);
    const timer = setInterval(() => {
      void (async () => {
        try {
          const snapshots = await Promise.all(
            ids.map((id) => superworkApi.getMeetingStatus(id)),
          );
          const settled = snapshots.filter(
            (snapshot) => !isInFlight(snapshot.status),
          );
          if (!settled.length) return;
          const failure = settled.find(
            (snapshot) => snapshot.status === 'FAILED',
          );
          if (failure)
            message.error(`处理失败：${failure.generationError || '未知错误'}`);
          else message.success('会议处理已完成');
          await load();
        } catch {
          // 单次轮询失败不影响下一轮
        }
      })();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [inFlightKey, load]);

  const summary = {
    total,
    processing: rows.filter((row) => isInFlight(row.status)).length,
    draft: rows.filter((row) => row.status === 'DRAFT').length,
    confirmed: rows.filter((row) => row.status === 'CONFIRMED').length,
  };

  const projectOptions = projects.map((project) => ({
    value: project.id,
    label: project.fullPath || project.name,
  }));

  const submitUpload = async (values: {
    title: string;
    meetingDate: Dayjs;
    projectId?: number;
  }) => {
    const file = uploadFiles[0]?.originFileObj as File | undefined;
    if (!file) {
      message.warning('请选择音频文件');
      return;
    }
    setUploading(true);
    try {
      await superworkApi.uploadMeeting({
        file,
        title: values.title.trim(),
        meetingDate: values.meetingDate.format('YYYY-MM-DD'),
        projectId: values.projectId,
      });
      message.success('上传成功，正在转写');
      setUploadOpen(false);
      setUploadFiles([]);
      uploadForm.resetFields();
      if (page === 1) await load();
      else setPage(1);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const removeMeeting = async (row: MeetingListItem) => {
    try {
      await superworkApi.deleteMeeting(row.id);
      message.success('会议已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  const columns: TableProps<MeetingListItem>['columns'] = [
    {
      title: '会议标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (_: string, row: MeetingListItem) => (
        <div className="sw-meeting-title-cell">
          <Typography.Text strong>{row.title}</Typography.Text>
          {row.status === 'FAILED' && row.generationError && (
            <Typography.Text type="danger" className="sw-meeting-subline">
              {row.generationError}
            </Typography.Text>
          )}
        </div>
      ),
    },
    {
      title: '会议日期',
      dataIndex: 'meetingDate',
      width: 130,
      render: (value?: string) => value || '—',
    },
    {
      title: '时长',
      dataIndex: 'durationSeconds',
      width: 100,
      render: (value?: number | null) =>
        value == null ? '—' : msToClock(value * 1000),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: MeetingStatus) => (
        <Tag color={statusMetaOf(value).color}>{statusMetaOf(value).label}</Tag>
      ),
    },
    {
      title: '操作',
      width: 150,
      render: (_: unknown, row: MeetingListItem) => (
        <Space>
          <Button
            type="link"
            size="small"
            onClick={() => history.push(`/meetings/${row.id}`)}
          >
            查看
          </Button>
          <Popconfirm
            title="删除该会议？"
            description="转写、纪要与待办将一并删除。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => void removeMeeting(row)}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            OPERATIONS / MEETING
          </Typography.Text>
          <Typography.Title level={2}>会议管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            上传录音 → 转写分人 → 人工校正 → AI 纪要（章节/决策/风险/待办）。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button
            icon={<CalendarOutlined />}
            onClick={() => history.push("/schedule")}
          >
            日历视图
          </Button>
          <Button
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => void load()}
          >
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setUploadFiles([]);
              uploadForm.resetFields();
              setUploadOpen(true);
            }}
          >
            上传录音
          </Button>
        </Space>
      </div>

      <Row gutter={[12, 12]} className="sw-meeting-summary">
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="全部会议" value={summary.total} suffix="场" />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic
              title="处理中"
              value={summary.processing}
              prefix={<LoadingOutlined />}
              suffix="场"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="待确认草稿" value={summary.draft} suffix="场" />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless">
            <Statistic title="已确认" value={summary.confirmed} suffix="场" />
          </Card>
        </Col>
      </Row>

      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取会议数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
          className="sw-meeting-alert"
        />
      )}

      <Card variant="borderless" className="sw-meeting-table">
        <div className="sw-meeting-toolbar">
          <Select
            allowClear
            placeholder="全部状态"
            style={{ width: 160 }}
            value={statusFilter}
            onChange={(value) => {
              setStatusFilter(value as MeetingStatus | undefined);
              setPage(1);
            }}
            options={Object.entries(STATUS_META).map(([value, meta]) => ({
              value,
              label: meta.label,
            }))}
          />
        </div>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          locale={{
            emptyText: <Empty description="暂无会议记录，点击右上角上传录音" />,
          }}
          scroll={{ x: 760 }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
            showTotal: (count) => `共 ${count} 场`,
            onChange: (nextPage) => setPage(nextPage),
          }}
        />
      </Card>

      <Modal
        title="上传会议录音"
        open={uploadOpen}
        onCancel={() => setUploadOpen(false)}
        onOk={() => uploadForm.submit()}
        confirmLoading={uploading}
        okText="上传"
        cancelText="取消"
      >
        <Form
          form={uploadForm}
          layout="vertical"
          onFinish={(values) => void submitUpload(values)}
        >
          <Form.Item label="音频文件" required>
            <Upload
              accept={AUDIO_ACCEPT}
              maxCount={1}
              beforeUpload={() => false}
              fileList={uploadFiles}
              onChange={({ fileList }) => setUploadFiles(fileList.slice(-1))}
              onRemove={() => setUploadFiles([])}
            >
              <Button icon={<UploadOutlined />}>选择音频</Button>
            </Upload>
          </Form.Item>
          <Form.Item
            name="title"
            label="会议标题"
            rules={[{ required: true, message: '请输入会议标题' }]}
          >
            <Input placeholder="例如：CDP 项目周会" />
          </Form.Item>
          <Form.Item
            name="meetingDate"
            label="会议日期"
            initialValue={dayjs()}
            rules={[{ required: true, message: '请选择会议日期' }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="projectId" label="关联项目">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选"
              options={projectOptions}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function MeetingDetailPanel({ id }: { id: number }) {
  const [detail, setDetail] = useState<MeetingDetail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [audioUrl, setAudioUrl] = useState<string>();
  const [highlightSeq, setHighlightSeq] = useState<number>();
  const [segmentDrafts, setSegmentDrafts] = useState<Record<number, string>>(
    {},
  );
  const [savingTranscript, setSavingTranscript] = useState(false);
  const [speakerOpen, setSpeakerOpen] = useState(false);
  const [speakerDrafts, setSpeakerDrafts] = useState<MeetingSpeaker[]>([]);
  const [savingSpeakers, setSavingSpeakers] = useState(false);
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [projects, setProjects] = useState<ProjectTreeNode[]>([]);
  const [requirements, setRequirements] = useState<Requirement[]>([]);
  const [projectMembers, setProjectMembers] = useState<ProjectMember[]>([]);
  const [convertState, setConvertState] = useState<{
    todo: MeetingTodo;
    actionType: 'TASK' | 'ISSUE';
  }>();
  const [converting, setConverting] = useState(false);
  const [todoDrafts, setTodoDrafts] = useState<
    Record<number, { title: string; dueText: string; dueDate?: Dayjs }>
  >({});
  const [savingTodoId, setSavingTodoId] = useState<number>();
  const [confirming, setConfirming] = useState(false);
  const [summarizing, setSummarizing] = useState(false);
  const [reprocessing, setReprocessing] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [convertForm] = Form.useForm();
  const convertProjectId = Form.useWatch('projectId', convertForm) as
    | number
    | undefined;

  const loadDetail = useCallback(async (targetId: number) => {
    setLoading(true);
    setError('');
    try {
      setDetail(await superworkApi.getMeeting(targetId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '会议详情加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadDetail(id);
  }, [id, loadDetail]);

  useEffect(() => {
    let objectUrl: string | undefined;
    let active = true;
    void superworkApi
      .getMeetingAudioBlob(id)
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setAudioUrl(objectUrl);
      })
      .catch(() => undefined);
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      setAudioUrl(undefined);
    };
  }, [id]);

  useEffect(() => {
    void Promise.allSettled([
      superworkApi.getUsers({ page: 1, size: 200 }),
      superworkApi.getProjectTree(),
      superworkApi.getRequirements({ page: 1, size: 200 }),
    ]).then(([userResult, projectResult, requirementResult]) => {
      if (userResult.status === 'fulfilled')
        setUsers(userResult.value.records || []);
      if (projectResult.status === 'fulfilled')
        setProjects(projectResult.value || []);
      if (requirementResult.status === 'fulfilled')
        setRequirements(requirementResult.value.records || []);
    });
  }, []);

  useEffect(() => {
    if (highlightSeq == null) return undefined;
    const timer = setTimeout(() => setHighlightSeq(undefined), HIGHLIGHT_MS);
    return () => clearTimeout(timer);
  }, [highlightSeq]);

  const status = detail?.status;
  useEffect(() => {
    if (!status || !isInFlight(status)) return undefined;
    const timer = setInterval(() => {
      void superworkApi
        .getMeetingStatus(id)
        .then(async (snapshot) => {
          if (isInFlight(snapshot.status)) return;
          if (snapshot.status === 'FAILED')
            message.error(
              `处理失败：${snapshot.generationError || '未知错误'}`,
            );
          else message.success('会议处理已完成');
          await loadDetail(id);
        })
        .catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [id, status, loadDetail]);

  const segments = detail?.segments ?? [];
  const todos = detail?.todos ?? [];
  const summary = detail?.summary ?? null;
  const keywords = summary?.keywords ?? [];
  const sections = summary?.sections ?? [];
  const decisions = summary?.decisions ?? [];
  const risks = summary?.risks ?? [];
  const speakerPoints = summary?.speakerPoints ?? [];
  const speakerStats = computeSpeakerStats(segments);

  const speakerName = (label: string) =>
    detail?.speakers?.find((item) => item.speakerLabel === label)
      ?.displayName || label;

  const seekTo = (ms?: number | null) => {
    if (ms == null) return;
    const audio = audioRef.current;
    if (!audio) return;
    try {
      audio.currentTime = Math.max(0, ms / 1000);
      const played = audio.play();
      if (played && typeof played.catch === 'function')
        played.catch(() => undefined);
    } catch {
      // 受限环境下播放可能不可用
    }
  };

  const focusSegment = (seq?: number | null, ms?: number | null) => {
    if (seq != null) {
      setHighlightSeq(seq);
      if (ms == null)
        ms = segments.find((segment) => segment.seq === seq)?.startMs;
    } else if (ms == null) {
      return;
    }
    seekTo(ms);
  };

  const focusKeyword = (keyword: string) => {
    const hit = segments.find((segment) => segment.text.includes(keyword));
    if (hit) focusSegment(hit.seq, hit.startMs);
  };

  const dirtyCount = segments.filter(
    (segment) => (segmentDrafts[segment.seq] ?? segment.text) !== segment.text,
  ).length;

  const saveTranscript = async () => {
    if (!detail || !dirtyCount) return;
    setSavingTranscript(true);
    try {
      const updated = await superworkApi.updateMeetingTranscript(
        detail.id,
        detail.segments.map((segment) => ({
          seq: segment.seq,
          speaker: segment.speaker,
          text: segmentDrafts[segment.seq] ?? segment.text,
        })),
      );
      setDetail({ ...detail, segments: updated });
      setSegmentDrafts({});
      message.success('校正稿已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '校正稿保存失败');
    } finally {
      setSavingTranscript(false);
    }
  };

  const openSpeakerModal = () => {
    if (!detail) return;
    const speakers: MeetingSpeaker[] = detail.speakers?.length
      ? detail.speakers
      : Array.from(new Set(detail.segments.map((item) => item.speaker))).map(
          (speakerLabel) => ({ speakerLabel }),
        );
    setSpeakerDrafts(speakers.map((speaker) => ({ ...speaker })));
    setSpeakerOpen(true);
  };

  const saveSpeakers = async () => {
    if (!detail) return;
    setSavingSpeakers(true);
    try {
      const updated = await superworkApi.updateMeetingSpeakers(
        detail.id,
        speakerDrafts.map((speaker) => ({
          speakerLabel: speaker.speakerLabel,
          displayName: speaker.displayName || null,
          mappedUserId: speaker.mappedUserId ?? null,
        })),
      );
      setDetail({ ...detail, speakers: updated });
      setSpeakerOpen(false);
      message.success('说话人已更新');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '说话人保存失败');
    } finally {
      setSavingSpeakers(false);
    }
  };

  const projectOptions = flattenProjects(projects).map((project) => ({
    value: project.id,
    label: project.fullPath || project.name,
  }));

  const convertRequirements = convertProjectId
    ? requirements.filter((item) =>
        collectProjectIds(Number(convertProjectId), projects).has(
          Number(item.projectId),
        ),
      )
    : [];

  const assigneeOptions = projectMembers.length
    ? Array.from(
        projectMembers.reduce((options, member) => {
          if (member.userId && !options.has(member.userId)) {
            options.set(
              member.userId,
              member.realName || member.username || String(member.userId),
            );
          }
          return options;
        }, new Map<number, string>()),
      ).map(([value, label]) => ({ value, label }))
    : users.map((user) => ({
        value: user.id,
        label: user.realName || user.username || String(user.id),
      }));

  const loadProjectMembers = async (projectId?: number) => {
    setProjectMembers([]);
    if (!projectId) return;
    try {
      setProjectMembers(await superworkApi.getProjectMembers(projectId));
    } catch {
      setProjectMembers([]);
    }
  };

  const todoDraftOf = (todo: MeetingTodo) =>
    todoDrafts[todo.id] ?? {
      title: todo.title,
      dueText: todo.dueText || '',
      dueDate: todo.dueDate ? dayjs(todo.dueDate) : undefined,
    };

  const updateTodoDraft = (
    todo: MeetingTodo,
    patch: Partial<{ title: string; dueText: string; dueDate?: Dayjs }>,
  ) =>
    setTodoDrafts((previous) => ({
      ...previous,
      [todo.id]: { ...todoDraftOf(todo), ...patch },
    }));

  const replaceTodo = (todo: MeetingTodo) => {
    setDetail((previous) =>
      previous
        ? {
            ...previous,
            todos: previous.todos.map((item) =>
              item.id === todo.id ? todo : item,
            ),
          }
        : previous,
    );
    setTodoDrafts((previous) => {
      const next = { ...previous };
      delete next[todo.id];
      return next;
    });
  };

  const saveTodo = async (todo: MeetingTodo) => {
    if (!detail) return;
    const draft = todoDraftOf(todo);
    if (!draft.title.trim()) {
      message.warning('待办标题不能为空');
      return;
    }
    setSavingTodoId(todo.id);
    try {
      const updated = await superworkApi.updateMeetingTodo(detail.id, todo.id, {
        title: draft.title.trim(),
        dueText: draft.dueText.trim() || undefined,
        dueDate: draft.dueDate ? draft.dueDate.format('YYYY-MM-DD') : null,
      });
      replaceTodo(updated);
      message.success('待办已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '待办保存失败');
    } finally {
      setSavingTodoId(undefined);
    }
  };

  const dismissTodo = async (todo: MeetingTodo) => {
    if (!detail) return;
    setSavingTodoId(todo.id);
    try {
      const updated = await superworkApi.updateMeetingTodo(detail.id, todo.id, {
        dismiss: true,
      });
      replaceTodo(updated);
      message.success('待办已忽略');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setSavingTodoId(undefined);
    }
  };

  const openConvert = (todo: MeetingTodo, actionType: 'TASK' | 'ISSUE') => {
    setConvertState({ todo, actionType });
    setProjectMembers([]);
    convertForm.resetFields();
    convertForm.setFieldsValue({
      taskType: actionType === 'TASK' ? '开发任务' : undefined,
    });
  };

  const submitConvert = async (values: {
    projectId?: number;
    requirementId?: number;
    assigneeId?: number;
    severity?: string;
    taskType?: string;
  }) => {
    if (!detail || !convertState) return;
    setConverting(true);
    try {
      const payload: MeetingTodoConvertPayload = {
        actionType: convertState.actionType,
        requirementId: values.requirementId ?? undefined,
        assigneeId: values.assigneeId ?? undefined,
        severity: values.severity ?? undefined,
        taskType: values.taskType ?? undefined,
      };
      const updated = await superworkApi.convertMeetingTodo(
        detail.id,
        convertState.todo.id,
        payload,
      );
      replaceTodo(updated);
      setConvertState(undefined);
      message.success(
        convertState.actionType === 'TASK' ? '已转为任务' : '已转为事项',
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '转化失败');
    } finally {
      setConverting(false);
    }
  };

  const confirmMeeting = async () => {
    if (!detail) return;
    setConfirming(true);
    try {
      const snapshot = await superworkApi.confirmMeeting(detail.id);
      setDetail({ ...detail, status: snapshot.status });
      message.success('纪要已确认');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '确认失败');
    } finally {
      setConfirming(false);
    }
  };

  const rerunSummary = async () => {
    if (!detail) return;
    setSummarizing(true);
    try {
      const snapshot = await superworkApi.summarizeMeeting(detail.id);
      setDetail({ ...detail, status: snapshot.status });
      if (isInFlight(snapshot.status)) await loadDetail(detail.id);
      message.info('正在重新生成纪要');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重新总结失败');
    } finally {
      setSummarizing(false);
    }
  };

  const retryProcessing = async () => {
    if (!detail) return;
    setReprocessing(true);
    try {
      const snapshot = await superworkApi.reprocessMeeting(detail.id);
      setDetail({ ...detail, status: snapshot.status });
      if (isInFlight(snapshot.status)) await loadDetail(detail.id);
      message.info('已重新排入转写队列');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重试失败');
    } finally {
      setReprocessing(false);
    }
  };

  if (!detail && loading)
    return (
      <div className="sw-meeting-loading">
        <LoadingOutlined /> 正在加载会议详情…
      </div>
    );

  if (!detail)
    return (
      <Alert
        type="error"
        showIcon
        message="会议详情加载失败"
        description={error}
        action={
          <Space>
            <Button size="small" onClick={() => void loadDetail(id)}>
              重试
            </Button>
            <Button size="small" onClick={() => history.push('/meetings')}>
              返回列表
            </Button>
          </Space>
        }
      />
    );

  return (
    <>
      <div className="sw-page-header">
        <div>
          <Space size={8}>
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/meetings')}
            >
              返回
            </Button>
            <Typography.Title level={2}>{detail.title}</Typography.Title>
            <Tag color={statusMetaOf(detail.status).color}>
              {statusMetaOf(detail.status).label}
            </Tag>
          </Space>
          <Typography.Paragraph type="secondary">
            {detail.meetingDate} ·{' '}
            {detail.durationSeconds == null
              ? '—'
              : msToClock(detail.durationSeconds * 1000)}
            {detail.generationModel ? ` · ${detail.generationModel}` : ''}
          </Typography.Paragraph>
        </div>
      </div>

      {detail.status === 'FAILED' && detail.generationError && (
        <Alert
          type="error"
          showIcon
          message="处理失败"
          description={detail.generationError}
          className="sw-meeting-alert"
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Card
            title="转写校正"
            variant="borderless"
            className="sw-meeting-card"
            extra={
              <Space>
                <Button size="small" onClick={openSpeakerModal}>
                  说话人命名
                </Button>
                <Button
                  size="small"
                  type="primary"
                  loading={savingTranscript}
                  disabled={!dirtyCount}
                  onClick={() => void saveTranscript()}
                >
                  保存校正稿{dirtyCount ? `（${dirtyCount}）` : ''}
                </Button>
              </Space>
            }
          >
            {segments.length ? (
              <div className="sw-meeting-transcript">
                {segments.map((segment) => {
                  const draft = segmentDrafts[segment.seq] ?? segment.text;
                  return (
                    <div
                      key={segment.seq}
                      className={`sw-meeting-segment${
                        highlightSeq === segment.seq
                          ? ' sw-meeting-segment-active'
                          : ''
                      }`}
                    >
                      <div className="sw-meeting-segment-head">
                        <Button
                          type="link"
                          size="small"
                          icon={<SoundOutlined />}
                          onClick={() => seekTo(segment.startMs)}
                        >
                          {msToClock(segment.startMs)}
                        </Button>
                        <Tag color="blue">{speakerName(segment.speaker)}</Tag>
                        {segment.edited && <Tag color="orange">已校正</Tag>}
                        {draft !== segment.text && (
                          <Tag color="gold">未保存</Tag>
                        )}
                      </div>
                      <Input.TextArea
                        autoSize={{ minRows: 1 }}
                        value={draft}
                        onChange={(event) =>
                          setSegmentDrafts((previous) => ({
                            ...previous,
                            [segment.seq]: event.target.value,
                          }))
                        }
                      />
                    </div>
                  );
                })}
              </div>
            ) : (
              <Empty
                description={
                  isInFlight(detail.status) ? '转写进行中…' : '暂无转写内容'
                }
              />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <div className="sw-meeting-aside">
            <Card
              title="录音回放"
              variant="borderless"
              className="sw-meeting-card"
            >
              {/* biome-ignore lint/a11y/useMediaCaption: 录音无字幕文件，转写文本在左侧校正区呈现 */}
              <audio
                ref={audioRef}
                src={audioUrl}
                controls
                preload="metadata"
                className="sw-meeting-audio"
              />
              {!audioUrl && (
                <Typography.Text type="secondary">音频加载中…</Typography.Text>
              )}
            </Card>

            {summary && (
              <Card
                title="纪要概览"
                variant="borderless"
                className="sw-meeting-card"
              >
                <Typography.Paragraph>{summary.summary}</Typography.Paragraph>
                {keywords.length > 0 && (
                  <div className="sw-meeting-keywords">
                    {keywords.map((keyword) => (
                      <Tag
                        key={keyword}
                        color="geekblue"
                        className="sw-meeting-keyword"
                        onClick={() => focusKeyword(keyword)}
                      >
                        {keyword}
                      </Tag>
                    ))}
                  </div>
                )}
              </Card>
            )}

            {summary && speakerStats.length > 0 && (
              <Card
                title="发言统计"
                variant="borderless"
                className="sw-meeting-card"
              >
                <Pie
                  height={240}
                  radius={0.8}
                  innerRadius={0.5}
                  angleField="y"
                  colorField="x"
                  data={speakerStats.map((stat) => ({
                    x: speakerName(stat.speaker),
                    y: stat.seconds,
                  }))}
                  legend={false}
                  label={{
                    position: 'spider',
                    text: (item: { x: string; y: number }) =>
                      `${item.x}: ${item.y}s`,
                  }}
                />
                <div className="sw-meeting-speakers">
                  {speakerStats.map((stat) => {
                    const entry = speakerPoints.find(
                      (item) => item.speaker === stat.speaker,
                    );
                    return (
                      <div key={stat.speaker} className="sw-meeting-speaker">
                        <Space size={6}>
                          <Tag color="geekblue">
                            {speakerName(stat.speaker)}
                          </Tag>
                          <Typography.Text type="secondary">
                            {stat.seconds}s · {Math.round(stat.ratio * 100)}%
                          </Typography.Text>
                        </Space>
                        {entry?.points?.length ? (
                          <ul className="sw-meeting-points">
                            {entry.points.map((point) => (
                              <li key={point}>
                                <Typography.Link
                                  onClick={() =>
                                    focusSegment(entry.segmentRefs?.[0])
                                  }
                                >
                                  {point}
                                </Typography.Link>
                              </li>
                            ))}
                          </ul>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </Card>
            )}

            {summary && sections.length > 0 && (
              <Card
                title="章节速览"
                variant="borderless"
                className="sw-meeting-card"
              >
                <Timeline
                  items={sections.map((section) => ({
                    key: `${section.title}-${section.startMs ?? ''}`,
                    children: (
                      <Button
                        type="text"
                        block
                        className="sw-meeting-section"
                        onClick={() => seekTo(section.startMs)}
                      >
                        <span className="sw-meeting-section-head">
                          <Typography.Text strong>
                            {section.title}
                          </Typography.Text>
                          <Typography.Text type="secondary">
                            {msToClock(section.startMs)} –{' '}
                            {msToClock(section.endMs)}
                          </Typography.Text>
                        </span>
                        <Typography.Paragraph type="secondary">
                          {section.content}
                        </Typography.Paragraph>
                      </Button>
                    ),
                  }))}
                />
              </Card>
            )}

            {summary && decisions.length > 0 && (
              <Card
                title="会议决策"
                variant="borderless"
                className="sw-meeting-card"
              >
                {decisions.map((item) => (
                  <div
                    className="sw-meeting-evidence"
                    key={`${item.content}-${item.startMs ?? ''}`}
                  >
                    <Typography.Text strong>{item.content}</Typography.Text>
                    {item.excerpt && (
                      <Typography.Paragraph
                        type="secondary"
                        className="sw-meeting-excerpt"
                      >
                        {item.excerpt}
                      </Typography.Paragraph>
                    )}
                    <Button
                      type="link"
                      size="small"
                      onClick={() => seekTo(item.startMs)}
                    >
                      {msToClock(item.startMs)}
                    </Button>
                  </div>
                ))}
              </Card>
            )}

            {summary && risks.length > 0 && (
              <Card
                title="风险与问题"
                variant="borderless"
                className="sw-meeting-card"
              >
                {risks.map((item) => (
                  <div
                    className="sw-meeting-evidence"
                    key={`${item.content}-${item.startMs ?? ''}`}
                  >
                    <Space size={6}>
                      {item.severity && (
                        <Tag color="volcano">{item.severity}</Tag>
                      )}
                      <Typography.Text strong>{item.content}</Typography.Text>
                    </Space>
                    {item.excerpt && (
                      <Typography.Paragraph
                        type="secondary"
                        className="sw-meeting-excerpt"
                      >
                        {item.excerpt}
                      </Typography.Paragraph>
                    )}
                    <Button
                      type="link"
                      size="small"
                      onClick={() => seekTo(item.startMs)}
                    >
                      {msToClock(item.startMs)}
                    </Button>
                  </div>
                ))}
              </Card>
            )}

            <Card
              title="待办草稿"
              variant="borderless"
              className="sw-meeting-card"
            >
              {todos.length ? (
                todos.map((todo) => {
                  if (todo.status !== 'DRAFT')
                    return (
                      <div className="sw-meeting-todo" key={todo.id}>
                        <Space size={6} wrap>
                          <Tag
                            color={
                              todo.status === 'CREATED' ? 'success' : 'default'
                            }
                          >
                            {todo.status === 'CREATED' ? '已转化' : '已忽略'}
                          </Tag>
                          <Typography.Text strong>{todo.title}</Typography.Text>
                        </Space>
                        {todo.targetTitle && (
                          <Typography.Text type="secondary">
                            → {todo.targetTitle}
                          </Typography.Text>
                        )}
                      </div>
                    );
                  const draft = todoDraftOf(todo);
                  return (
                    <div className="sw-meeting-todo" key={todo.id}>
                      <Input
                        value={draft.title}
                        onChange={(event) =>
                          updateTodoDraft(todo, { title: event.target.value })
                        }
                      />
                      <Space wrap>
                        <Input
                          value={draft.dueText}
                          placeholder="截止原话，如“下周五”"
                          style={{ width: 180 }}
                          onChange={(event) =>
                            updateTodoDraft(todo, {
                              dueText: event.target.value,
                            })
                          }
                        />
                        <DatePicker
                          value={draft.dueDate}
                          placeholder="截止日期"
                          onChange={(value) =>
                            updateTodoDraft(todo, {
                              dueDate: value ?? undefined,
                            })
                          }
                        />
                        <Button
                          size="small"
                          loading={savingTodoId === todo.id}
                          onClick={() => void saveTodo(todo)}
                        >
                          保存
                        </Button>
                        <Button
                          size="small"
                          type="primary"
                          onClick={() => openConvert(todo, 'TASK')}
                        >
                          转任务
                        </Button>
                        <Button
                          size="small"
                          onClick={() => openConvert(todo, 'ISSUE')}
                        >
                          转事项
                        </Button>
                        <Button
                          size="small"
                          danger
                          onClick={() => void dismissTodo(todo)}
                        >
                          忽略
                        </Button>
                      </Space>
                      {todo.assigneeHint && (
                        <Typography.Text type="secondary">
                          建议负责人：{todo.assigneeHint}
                        </Typography.Text>
                      )}
                      {todo.sourceExcerpt && (
                        <Typography.Paragraph
                          type="secondary"
                          className="sw-meeting-excerpt"
                        >
                          {todo.sourceExcerpt}
                        </Typography.Paragraph>
                      )}
                    </div>
                  );
                })
              ) : (
                <Empty description="暂无待办草稿" />
              )}
            </Card>

            <Card
              title="纪要操作"
              variant="borderless"
              className="sw-meeting-card"
            >
              <Space wrap>
                <Button
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  loading={confirming}
                  disabled={detail.status !== 'DRAFT'}
                  onClick={() => void confirmMeeting()}
                >
                  确认纪要
                </Button>
                <Button
                  icon={<ThunderboltOutlined />}
                  loading={summarizing}
                  disabled={!['DRAFT', 'CONFIRMED'].includes(detail.status)}
                  onClick={() => void rerunSummary()}
                >
                  重新总结
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  loading={reprocessing}
                  disabled={detail.status !== 'FAILED'}
                  onClick={() => void retryProcessing()}
                >
                  失败重试
                </Button>
              </Space>
            </Card>
          </div>
        </Col>
      </Row>

      <Modal
        title="说话人命名"
        open={speakerOpen}
        onCancel={() => setSpeakerOpen(false)}
        onOk={() => void saveSpeakers()}
        confirmLoading={savingSpeakers}
        okText="保存"
        cancelText="取消"
      >
        {speakerDrafts.length ? (
          speakerDrafts.map((speaker, index) => (
            <div className="sw-meeting-speaker-row" key={speaker.speakerLabel}>
              <Tag color="blue">{speaker.speakerLabel}</Tag>
              <Input
                placeholder="显示名，如：张三"
                value={speaker.displayName || ''}
                onChange={(event) =>
                  setSpeakerDrafts((previous) =>
                    previous.map((item, itemIndex) =>
                      itemIndex === index
                        ? { ...item, displayName: event.target.value }
                        : item,
                    ),
                  )
                }
              />
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                placeholder="映射系统用户（可选）"
                style={{ width: 200 }}
                value={speaker.mappedUserId ?? undefined}
                onChange={(value) =>
                  setSpeakerDrafts((previous) =>
                    previous.map((item, itemIndex) =>
                      itemIndex === index
                        ? { ...item, mappedUserId: value ?? null }
                        : item,
                    ),
                  )
                }
                options={users.map((user) => ({
                  value: user.id,
                  label: user.realName || user.username || String(user.id),
                }))}
              />
            </div>
          ))
        ) : (
          <Empty description="暂无说话人" />
        )}
      </Modal>

      <Modal
        title={
          convertState
            ? `转为${convertState.actionType === 'TASK' ? '任务' : '事项'}`
            : '转化待办'
        }
        open={Boolean(convertState)}
        onCancel={() => setConvertState(undefined)}
        onOk={() => convertForm.submit()}
        confirmLoading={converting}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={convertForm}
          layout="vertical"
          onFinish={(values) => void submitConvert(values)}
        >
          <Form.Item name="projectId" label="归属项目">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选"
              options={projectOptions}
              onChange={(value) => {
                convertForm.setFieldsValue({
                  requirementId: undefined,
                  assigneeId: undefined,
                });
                void loadProjectMembers(value ? Number(value) : undefined);
              }}
            />
          </Form.Item>
          <Form.Item name="requirementId" label="所属需求">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              disabled={!convertProjectId}
              placeholder={
                convertProjectId ? '选择项目下的需求' : '不关联需求可直接提交'
              }
              options={convertRequirements.map((item) => ({
                value: Number(item.id),
                label: `${item.reqNo || item.id} · ${item.title}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="assigneeId" label="负责人">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选"
              options={assigneeOptions}
            />
          </Form.Item>
          {convertState?.actionType === 'ISSUE' && (
            <Form.Item name="severity" label="缺陷级别">
              <Select
                allowClear
                options={SEVERITY_OPTIONS.map((value) => ({
                  value,
                  label: value,
                }))}
              />
            </Form.Item>
          )}
          {convertState?.actionType === 'TASK' && (
            <Form.Item name="taskType" label="任务类型">
              <Select
                options={TASK_TYPE_OPTIONS.map((value) => ({
                  value,
                  label: value,
                }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </>
  );
}
