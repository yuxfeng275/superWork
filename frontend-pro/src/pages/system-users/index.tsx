import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { getRoleLabel, POSITION_ROLE_OPTIONS } from '@/constants/roles';
import {
  superworkApi,
  type PasswordChangeStatus,
  type SeedAccount,
  type UserRecord,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type UserForm = {
  username: string;
  password?: string;
  realName: string;
  email?: string;
  phone?: string;
  role: string;
};
const roleOptions = POSITION_ROLE_OPTIONS.map((role) => ({
  label: `${role.label} · ${role.categoryLabel}`,
  value: role.value,
}));
const statusLabel = (value: UserRecord['status']) => {
  const normalized = String(value ?? '').toLowerCase();
  return !normalized ||
    normalized === '1' ||
    normalized === 'enabled' ||
    normalized === 'active'
    ? '启用'
    : '停用';
};

export default function SystemUsersPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(initialState?.currentUser?.role || '');
  const [form] = Form.useForm<UserForm>();
  const [rows, setRows] = useState<UserRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filter, setFilter] = useState('');
  const [editing, setEditing] = useState<UserRecord>();
  const [open, setOpen] = useState(false);
  const [userDraft, setUserDraft] = useState<UserForm>();
  const [saving, setSaving] = useState(false);
  const [seedAccounts, setSeedAccounts] = useState<SeedAccount[]>([]);
  const [passwordStatus, setPasswordStatus] = useState<PasswordChangeStatus | null>(null);
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await superworkApi.getUsers({ page: 1, size: 500 });
      setRows(result.records || []);
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : '用户加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (!canManage) return;
    superworkApi
      .getSeedPasswordAccounts()
      .then(setSeedAccounts)
      .catch(() => undefined);
    superworkApi
      .getPasswordChangeStatus()
      .then(setPasswordStatus)
      .catch(() => undefined);
  }, [canManage]);
  useEffect(() => {
    if (open && userDraft) form.setFieldsValue(userDraft);
  }, [form, open, userDraft]);
  const filtered = useMemo(
    () =>
      rows.filter(
        (user) =>
          !filter ||
          `${user.realName}${user.username}${getRoleLabel(user.role)}`
            .toLowerCase()
            .includes(filter.toLowerCase()),
      ),
    [filter, rows],
  );
  const grouped = useMemo(() => {
    const groups = new Map<string, UserRecord[]>();
    filtered.forEach((user) => {
      const key = user.role || 'UNKNOWN';
      groups.set(key, [...(groups.get(key) || []), user]);
    });
    return Array.from(groups.entries()).sort((a, b) =>
      getRoleLabel(a[0]).localeCompare(getRoleLabel(b[0]), 'zh-CN'),
    );
  }, [filtered]);
  const openCreate = () => {
    setEditing(undefined);
    setUserDraft({
      username: '',
      password: '',
      realName: '',
      email: '',
      phone: '',
      role: 'SOLUTION_MANAGER',
    });
    setOpen(true);
  };
  const openEdit = (user: UserRecord) => {
    setEditing(user);
    setUserDraft({
      username: user.username || '',
      realName: user.realName,
      email: user.email,
      phone: user.phone,
      role: user.role || '',
    });
    setOpen(true);
  };
  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        const payload = { ...values };
        if (!payload.password) delete payload.password;
        await superworkApi.updateUser(editing.id, payload);
      } else {
        if (!values.password) {
          message.warning('请输入初始密码');
          return;
        }
        await superworkApi.createUser(
          values as UserForm & { password: string },
        );
      }
      message.success(editing ? '用户已更新' : '用户已创建');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '用户保存失败');
    } finally {
      setSaving(false);
    }
  };
  const resetAllSeedPasswords = async () => {
    try {
      const credentials = await superworkApi.resetSeedPasswords();
      setSeedAccounts([]);
      Modal.success({
        title: `已重置 ${credentials.length} 个账号`,
        width: 640,
        content: (
          <div style={{ maxHeight: 420, overflow: 'auto' }}>
            <Typography.Paragraph type="secondary">
              新密码只显示这一次，请立即复制并分别转交本人；旧密码已失效。
            </Typography.Paragraph>
            {credentials.map((item) => (
              <div key={item.id} style={{ marginBottom: 6 }}>
                <Typography.Text strong>
                  {item.realName || item.username}（@{item.username}）
                </Typography.Text>
                <Typography.Text copyable style={{ marginLeft: 12 }}>
                  {item.password}
                </Typography.Text>
              </div>
            ))}
          </div>
        ),
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '批量重置失败');
    }
  };

  const resetPassword = async (user: UserRecord) => {
    try {
      const result = await superworkApi.resetUserPassword(user.id);
      Modal.success({
        title: '密码已重置',
        content: (
          <Space direction="vertical" size={6}>
            <Typography.Text type="secondary">
              旧密码已失效，请立即复制并转交给本人，关闭后不再显示。
            </Typography.Text>
            <Typography.Text copyable strong>{result.password}</Typography.Text>
          </Space>
        ),
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重置密码失败');
    }
  };

  const remove = async (user: UserRecord) => {
    try {
      await superworkApi.deleteUser(user.id);
      message.success('用户已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '用户删除失败');
    }
  };
  return (
    <div className="sw-page sw-system-users">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / USERS
          </Typography.Text>
          <Typography.Title level={2}>用户管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            按岗位序列组织成员，维护账号、联系方式与角色归属。
          </Typography.Paragraph>
          {passwordStatus && passwordStatus.flagged > 0 ? (
            <Tag color="orange">{passwordStatus.flagged} 人待强制改密</Tag>
          ) : null}
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增用户
            </Button>
          )}
        </Space>
      </div>
      {canManage && seedAccounts.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`发现 ${seedAccounts.length} 个账号仍在使用初始密码`}
          description="建议全部重置；新密码只显示一次，请立即分别转交本人。"
          action={
            <Popconfirm
              title={`将重置 ${seedAccounts.length} 个账号，旧密码立即失效，确定？`}
              okText="全部重置"
              cancelText="取消"
              onConfirm={() => void resetAllSeedPasswords()}
            >
              <Button danger>全部重置</Button>
            </Popconfirm>
          }
        />
      )}
      <Row gutter={16} className="sw-stat-row">
        <Col xs={12} md={6}>
          <Card variant="borderless" className="sw-stat-card">
            <Statistic title="总成员" value={loading ? '-' : rows.length} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless" className="sw-stat-card">
            <Statistic
              title="启用账号"
              value={
                loading
                  ? '-'
                  : rows.filter((user) => statusLabel(user.status) === '启用')
                      .length
              }
              styles={{ content: { color: '#059669' } }}
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless" className="sw-stat-card">
            <Statistic title="角色分组" value={grouped.length} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card variant="borderless" className="sw-stat-card">
            <Statistic
              title="管理序列"
              value={
                rows.filter((user) =>
                  [
                    'DIRECTOR',
                    'DEPUTY_DIRECTOR',
                    'BUSINESS_OWNER',
                    'EFFECTIVENESS_OWNER',
                    'BU_ADMIN',
                  ].includes(user.role || ''),
                ).length
              }
            />
          </Card>
        </Col>
      </Row>
      <Card variant="borderless" className="sw-filter-card">
        <Space>
          <Input
            allowClear
            prefix={<UserOutlined />}
            placeholder="搜索姓名、账号或角色"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            style={{ width: 320 }}
          />
        </Space>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取用户数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <div className="sw-user-groups">
        {grouped.map(([role, users]) => (
          <Card
            variant="borderless"
            key={role}
            className="sw-user-group"
            title={
              <Space>
                <Tag color="blue">{getRoleLabel(role)}</Tag>
                <Typography.Text type="secondary">
                  {users.length} 人
                </Typography.Text>
              </Space>
            }
            extra={
              <Typography.Text type="secondary">
                {POSITION_ROLE_OPTIONS.find((item) => item.value === role)
                  ?.categoryLabel || '兼容角色'}
              </Typography.Text>
            }
          >
            <div className="sw-user-grid">
              {users.map((user) => (
                <Card key={user.id} size="small" className="sw-user-card">
                  <div className="sw-user-main">
                    <Avatar>
                      {(user.realName || user.username || 'U').slice(-2)}
                    </Avatar>
                    <div>
                      <Typography.Text strong>
                        {user.realName || user.username}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        @{user.username || '—'}
                      </Typography.Text>
                    </div>
                  </div>
                  <Space
                    orientation="vertical"
                    size={2}
                    className="sw-user-meta"
                  >
                    <Typography.Text type="secondary">
                      {user.email || '未填写邮箱'}
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      {user.phone || '未填写手机号'}
                    </Typography.Text>
                    <Tag
                      color={
                        statusLabel(user.status) === '启用'
                          ? 'success'
                          : 'default'
                      }
                    >
                      {statusLabel(user.status)}
                    </Tag>
                  </Space>
                  {canManage && (
                    <Space size={0} className="sw-user-actions">
                      <Button
                        type="link"
                        icon={<EditOutlined />}
                        onClick={() => openEdit(user)}
                      >
                        编辑
                      </Button>
                      <Popconfirm
                        title={`重置「${user.realName || user.username}」的密码？旧密码将立即失效`}
                        okText="重置"
                        cancelText="取消"
                        onConfirm={() => void resetPassword(user)}
                      >
                        <Button type="link">重置密码</Button>
                      </Popconfirm>
                      <Popconfirm
                        title={`删除「${user.realName || user.username}」吗？`}
                        okText="删除"
                        cancelText="取消"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => void remove(user)}
                      >
                        <Button type="link" danger icon={<DeleteOutlined />}>
                          删除
                        </Button>
                      </Popconfirm>
                    </Space>
                  )}
                </Card>
              ))}
            </div>
          </Card>
        ))}
        {!loading && !grouped.length && (
          <Card variant="borderless">
            <Typography.Text type="secondary">暂无用户数据</Typography.Text>
          </Card>
        )}
      </div>
      <Modal
        title={editing ? '编辑用户' : '新增用户'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => void save()}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item
                name="username"
                label="用户名"
                rules={[{ required: true, message: '请输入用户名' }]}
              >
                <Input autoComplete="username" disabled={Boolean(editing)} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="password"
                label={editing ? '密码（留空不修改）' : '初始密码'}
                rules={
                  editing ? [] : [{ required: true, message: '请输入初始密码' }]
                }
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="realName"
            label="真实姓名"
            rules={[{ required: true, message: '请输入真实姓名' }]}
          >
            <Input />
          </Form.Item>
          <Row gutter={14}>
            <Col span={12}>
              <Form.Item name="email" label="邮箱">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="phone" label="手机号">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="role"
            label="角色"
            rules={[{ required: true, message: '请选择角色' }]}
          >
            <Select showSearch options={roleOptions} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
