import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Row,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { WORKFLOW_ROLE_OPTIONS } from '@/constants/roles';
import { superworkApi, type WorkflowConfig } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const CANVAS_WIDTH = 1280;
const CANVAS_HEIGHT = 680;
const NODE_WIDTH = 200;
const NODE_HEIGHT = 96;
const LAYOUT_STORAGE_KEY = 'workflow-canvas-layouts-v1';

type WorkflowForm = {
  requirementType: string;
  fromStatus: string;
  toStatus: string;
  conditionType: string;
  allowedRoles: string[];
  isActive: number;
  sortOrder: number;
};
type Position = { x: number; y: number };
type NormalizedWorkflow = WorkflowConfig & {
  fromStatus: string;
  toStatus: string;
  allowedRoles: string[];
};
type DragState = { status: string; offsetX: number; offsetY: number };
type ConnectionState = {
  fromStatus: string;
  startX: number;
  startY: number;
  currentX: number;
  currentY: number;
};

const normalizeAllowedRoles = (
  value: WorkflowConfig['allowedRoles'],
): string[] => {
  if (Array.isArray(value)) return value;
  if (typeof value !== 'string') return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
};
const normalize = (item: WorkflowConfig): NormalizedWorkflow => ({
  ...item,
  fromStatus: item.fromStatus || item.currentStatus || '',
  toStatus: item.toStatus || item.nextStatus || '',
  allowedRoles: normalizeAllowedRoles(item.allowedRoles),
});
const defaultPosition = (index: number): Position => ({
  x: 80 + (index % 4) * 280,
  y: 80 + Math.floor(index / 4) * 180,
});

export default function SystemWorkflowPage() {
  const [form] = Form.useForm<WorkflowForm>();
  const canvasRef = useRef<HTMLDivElement>(null);
  const [configs, setConfigs] = useState<NormalizedWorkflow[]>([]);
  const [statusOptions, setStatusOptions] = useState<Record<string, string[]>>(
    {},
  );
  const [type, setType] = useState('');
  const [layouts, setLayouts] = useState<
    Record<string, Record<string, Position>>
  >({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<NormalizedWorkflow>();
  const [saving, setSaving] = useState(false);
  const [dragging, setDragging] = useState<DragState>();
  const [connection, setConnection] = useState<ConnectionState>();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [rows, options] = await Promise.all([
        superworkApi.getWorkflowConfigs(),
        superworkApi.getWorkflowStatusOptions(),
      ]);
      const normalized = (rows || []).map(normalize);
      const nextOptions = options || {};
      setConfigs(normalized);
      setStatusOptions(nextOptions);
      setType((current) =>
        current && nextOptions[current]
          ? current
          : Object.keys(nextOptions)[0] || normalized[0]?.requirementType || '',
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '工作流配置加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    try {
      const saved = localStorage.getItem(LAYOUT_STORAGE_KEY);
      if (saved)
        setLayouts(
          JSON.parse(saved) as Record<string, Record<string, Position>>,
        );
    } catch {
      setLayouts({});
    }
    void load();
  }, [load]);
  useEffect(() => {
    if (Object.keys(layouts).length)
      localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layouts));
  }, [layouts]);

  const statuses = useMemo(
    () => statusOptions[type] || [],
    [statusOptions, type],
  );
  const transitions = useMemo(
    () =>
      configs
        .filter((config) => config.requirementType === type)
        .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)),
    [configs, type],
  );
  const activeLayout = useMemo(() => {
    const saved = layouts[type] || {};
    return Object.fromEntries(
      statuses.map((status, index) => [
        status,
        saved[status] || defaultPosition(index),
      ]),
    ) as Record<string, Position>;
  }, [layouts, statuses, type]);
  const edges = useMemo(
    () =>
      transitions.flatMap((transition) => {
        const from = activeLayout[transition.fromStatus];
        const to = activeLayout[transition.toStatus];
        return from && to
          ? [
              {
                transition,
                startX: from.x + NODE_WIDTH,
                startY: from.y + NODE_HEIGHT / 2,
                endX: to.x,
                endY: to.y + NODE_HEIGHT / 2,
              },
            ]
          : [];
      }),
    [activeLayout, transitions],
  );
  const getCanvasPoint = useCallback(
    (event: PointerEvent | React.PointerEvent) => {
      const rect = canvasRef.current?.getBoundingClientRect();
      return rect
        ? { x: event.clientX - rect.left, y: event.clientY - rect.top }
        : { x: 0, y: 0 };
    },
    [],
  );
  const persistLayout = useCallback(
    (next: Record<string, Position>) =>
      setLayouts((current) => ({ ...current, [type]: next })),
    [type],
  );
  useEffect(() => {
    if (!dragging && !connection) return undefined;
    const move = (event: PointerEvent) => {
      const point = getCanvasPoint(event);
      if (dragging)
        persistLayout({
          ...activeLayout,
          [dragging.status]: {
            x: Math.max(
              24,
              Math.min(
                point.x - dragging.offsetX,
                CANVAS_WIDTH - NODE_WIDTH - 24,
              ),
            ),
            y: Math.max(
              24,
              Math.min(
                point.y - dragging.offsetY,
                CANVAS_HEIGHT - NODE_HEIGHT - 24,
              ),
            ),
          },
        });
      if (connection)
        setConnection((current) =>
          current
            ? { ...current, currentX: point.x, currentY: point.y }
            : current,
        );
    };
    const stop = () => {
      setDragging(undefined);
      setConnection(undefined);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
  }, [activeLayout, connection, dragging, getCanvasPoint, persistLayout]);

  const openCreate = (fromStatus: string, toStatus: string) => {
    setEditing(undefined);
    form.setFieldsValue({
      requirementType: type,
      fromStatus,
      toStatus,
      conditionType: '',
      allowedRoles: ['解决方案经理'],
      isActive: 1,
      sortOrder: transitions.length + 1,
    });
    setOpen(true);
  };
  const openEdit = (config: NormalizedWorkflow) => {
    setEditing(config);
    form.setFieldsValue({
      requirementType: config.requirementType,
      fromStatus: config.fromStatus,
      toStatus: config.toStatus,
      conditionType: config.conditionType || config.transitionName || '',
      allowedRoles: config.allowedRoles,
      isActive: config.isActive ?? 1,
      sortOrder: config.sortOrder || 0,
    });
    setOpen(true);
  };
  const startNodeDrag = (event: React.PointerEvent, status: string) => {
    const point = getCanvasPoint(event);
    const current = activeLayout[status];
    if (current)
      setDragging({
        status,
        offsetX: point.x - current.x,
        offsetY: point.y - current.y,
      });
  };
  const startConnection = (event: React.PointerEvent, status: string) => {
    event.stopPropagation();
    const position = activeLayout[status];
    if (position)
      setConnection({
        fromStatus: status,
        startX: position.x + NODE_WIDTH,
        startY: position.y + NODE_HEIGHT / 2,
        currentX: position.x + NODE_WIDTH,
        currentY: position.y + NODE_HEIGHT / 2,
      });
  };
  const finishConnection = (targetStatus: string) => {
    if (!connection || connection.fromStatus === targetStatus) {
      setConnection(undefined);
      return;
    }
    openCreate(connection.fromStatus, targetStatus);
    setConnection(undefined);
  };
  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = { ...values, isActive: values.isActive ? 1 : 0 };
      if (editing) await superworkApi.updateWorkflowConfig(editing.id, payload);
      else await superworkApi.createWorkflowConfig(payload);
      message.success(editing ? '流转规则已更新' : '流转规则已创建');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '工作流规则保存失败');
    } finally {
      setSaving(false);
    }
  };
  const remove = async () => {
    if (!editing) return;
    try {
      await superworkApi.deleteWorkflowConfig(editing.id);
      message.success('流转规则已删除');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '工作流规则删除失败');
    }
  };

  return (
    <div className="sw-page sw-system-workflow">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / WORKFLOW
          </Typography.Text>
          <Typography.Title level={2}>工作流配置</Typography.Title>
          <Typography.Paragraph type="secondary">
            拖拽状态节点调整布局，从节点右侧圆点拖到目标状态即可创建流转规则。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取工作流配置"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Space wrap className="sw-workflow-types">
        {Object.keys(statusOptions).map((key) => (
          <Button
            key={key}
            type={key === type ? 'primary' : 'default'}
            onClick={() => setType(key)}
          >
            {key}
          </Button>
        ))}
      </Space>
      <Card
        variant="borderless"
        loading={loading}
        className="sw-workflow-board"
      >
        {statuses.length ? (
          <div ref={canvasRef} className="sw-workflow-canvas-shell">
            <div
              className="sw-workflow-canvas"
              style={{ width: CANVAS_WIDTH, height: CANVAS_HEIGHT }}
            >
              <svg
                className="sw-workflow-lines"
                aria-hidden="true"
                viewBox={`0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}`}
              >
                {edges.map(({ transition, startX, startY, endX, endY }) => (
                  <path
                    key={transition.id}
                    d={`M ${startX} ${startY} L ${endX} ${endY}`}
                    className="sw-workflow-edge"
                  />
                ))}
                {connection && (
                  <path
                    d={`M ${connection.startX} ${connection.startY} L ${connection.currentX} ${connection.currentY}`}
                    className="sw-workflow-edge is-pending"
                  />
                )}
              </svg>
              {edges.map(({ transition, startX, startY, endX, endY }) => (
                <button
                  key={`badge-${transition.id}`}
                  type="button"
                  className="sw-workflow-edge-label"
                  style={{
                    left: (startX + endX) / 2 - 54,
                    top: (startY + endY) / 2 - 16,
                  }}
                  onClick={() => openEdit(transition)}
                >
                  {transition.conditionType ||
                    transition.transitionName ||
                    '手动流转'}
                </button>
              ))}
              {statuses.map((status) => {
                const position = activeLayout[status];
                const outgoing = transitions.filter(
                  (item) => item.fromStatus === status,
                ).length;
                const incoming = transitions.filter(
                  (item) => item.toStatus === status,
                ).length;
                return (
                  <article
                    key={status}
                    className="sw-workflow-node"
                    style={{
                      transform: `translate(${position.x}px, ${position.y}px)`,
                    }}
                    onPointerUp={() => finishConnection(status)}
                  >
                    <header
                      className="sw-workflow-node-head"
                      onPointerDown={(event) => {
                        event.preventDefault();
                        startNodeDrag(event, status);
                      }}
                    >
                      <Typography.Text strong>{status}</Typography.Text>
                      <button
                        type="button"
                        className="sw-workflow-connect"
                        aria-label={`从${status}创建流转`}
                        onPointerDown={(event) =>
                          startConnection(event, status)
                        }
                      />
                    </header>
                    <div className="sw-workflow-node-body">
                      <Tag color="blue">流出 {outgoing}</Tag>
                      <Tag>流入 {incoming}</Tag>
                    </div>
                  </article>
                );
              })}
            </div>
          </div>
        ) : (
          <Typography.Text type="secondary">暂无状态节点</Typography.Text>
        )}
      </Card>
      <Modal
        title={editing ? '编辑流转规则' : '新增流转规则'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => void save()}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="requirementType" label="需求类型">
            <Input disabled />
          </Form.Item>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item name="fromStatus" label="源状态">
                <Input disabled />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="toStatus" label="目标状态">
                <Input disabled />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="conditionType"
            label="流转说明"
            rules={[{ required: true, message: '请输入流转说明' }]}
          >
            <Input placeholder="例如：评估通过 / 进入设计阶段" />
          </Form.Item>
          <Form.Item
            name="allowedRoles"
            label="允许角色"
            rules={[{ required: true, message: '请选择至少一个角色' }]}
          >
            <Checkbox.Group options={WORKFLOW_ROLE_OPTIONS} />
          </Form.Item>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="isActive"
                label="启用状态"
                valuePropName="checked"
                getValueFromEvent={(value) => (value ? 1 : 0)}
              >
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sortOrder" label="排序">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
        {editing && (
          <Popconfirm
            title="删除这条流转规则吗？"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => void remove()}
          >
            <Button danger icon={<DeleteOutlined />}>
              删除规则
            </Button>
          </Popconfirm>
        )}
      </Modal>
    </div>
  );
}
