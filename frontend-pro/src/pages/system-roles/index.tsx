import {
  DeleteOutlined,
  EditOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Divider,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { POSITION_ROLE_OPTIONS } from '@/constants/roles';
import {
  type BusinessLine,
  type MenuRecord,
  type PermissionRecord,
  type ProjectRecord,
  type RoleAuthorization,
  type RoleRecord,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type RoleForm = { name: string; description?: string; status: number };

const normalizeMenuTree = (menus: MenuRecord[]): MenuRecord[] => {
  const flat = new Map<number, MenuRecord>();
  const collect = (items: MenuRecord[]) =>
    items.forEach((item) => {
      flat.set(item.id, { ...item, children: [] });
      if (item.children?.length) collect(item.children);
    });
  collect(menus);
  const roots: MenuRecord[] = [];
  flat.forEach((item) => {
    const parent = item.parentId ? flat.get(item.parentId) : undefined;
    if (parent) parent.children = [...(parent.children || []), item];
    else roots.push(item);
  });
  const sort = (items: MenuRecord[]) => {
    items.sort((left, right) => (left.sortOrder || 0) - (right.sortOrder || 0));
    items.forEach((item) => {
      sort(item.children || []);
    });
  };
  sort(roots);
  return roots;
};

const collectLeafMenuIds = (menus: MenuRecord[]) => {
  const ids = new Set<number>();
  const walk = (items: MenuRecord[]) =>
    items.forEach((item) => {
      if (item.children?.length) walk(item.children);
      else ids.add(item.id);
    });
  walk(menus);
  return ids;
};

const collectMenuIds = (menus: MenuRecord[]): number[] =>
  menus.flatMap((item) => [item.id, ...collectMenuIds(item.children || [])]);

const collectParentMenuIds = (
  menuId: number,
  menus: MenuRecord[],
): number[] => {
  for (const item of menus) {
    if ((item.children || []).some((child) => child.id === menuId))
      return [item.id];
    const nested = collectParentMenuIds(menuId, item.children || []);
    if (nested.length) return [...nested, item.id];
  }
  return [];
};

type MenuTreeDataNode = {
  key: number;
  title: string;
  children?: MenuTreeDataNode[];
};

const toMenuTreeDataNode = (menu: MenuRecord): MenuTreeDataNode => ({
  key: menu.id,
  title: menu.name,
  children: menu.children?.length
    ? menu.children.map(toMenuTreeDataNode)
    : undefined,
});

export default function SystemRolesPage() {
  const [form] = Form.useForm<RoleForm>();
  const [roles, setRoles] = useState<RoleRecord[]>([]);
  const [menus, setMenus] = useState<MenuRecord[]>([]);
  const [permissions, setPermissions] = useState<PermissionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<RoleRecord>();
  const [formOpen, setFormOpen] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [authLoading, setAuthLoading] = useState(false);
  const [checkedMenus, setCheckedMenus] = useState<number[]>([]);
  const [checkedPermissions, setCheckedPermissions] = useState<number[]>([]);
  const [dataScope, setDataScope] = useState('SELF');
  const [scopeBusinessLines, setScopeBusinessLines] = useState<BusinessLine[]>(
    [],
  );
  const [scopeProjects, setScopeProjects] = useState<ProjectRecord[]>([]);
  const [selectedBusinessLineIds, setSelectedBusinessLineIds] = useState<
    number[]
  >([]);
  const [selectedProjectIds, setSelectedProjectIds] = useState<number[]>([]);
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [roleRows, menuRows, permissionRows] = await Promise.all([
        superworkApi.getRoles(),
        superworkApi.getMenus(),
        superworkApi.getPermissions(),
      ]);
      setRoles(roleRows || []);
      setMenus(menuRows || []);
      setPermissions(permissionRows || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : '角色数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const menuTree = useMemo(() => normalizeMenuTree(menus), [menus]);
  const menuTreeData = useMemo(
    () => menuTree.map(toMenuTreeDataNode),
    [menuTree],
  );
  const allMenuIds = useMemo(() => collectMenuIds(menuTree), [menuTree]);
  const openEdit = (role: RoleRecord) => {
    setEditing(role);
    form.setFieldsValue({
      name: role.name,
      description: role.description,
      status: role.status,
    });
    setFormOpen(true);
  };
  const initialize = async (preset: (typeof POSITION_ROLE_OPTIONS)[number]) => {
    try {
      await superworkApi.createRole({
        code: preset.value,
        name: preset.label,
        description: preset.description,
        status: 1,
      });
      message.success('默认角色已初始化');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '初始化失败');
    }
  };
  const saveRole = async () => {
    if (!editing) return;
    const values = await form.validateFields();
    try {
      await superworkApi.updateRole(editing.id, {
        name: editing.name,
        description: values.description,
        status: values.status,
      });
      message.success('角色信息已更新');
      setFormOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '角色更新失败');
    }
  };
  const removeRole = (role: RoleRecord) => {
    Modal.confirm({
      title: `删除角色「${role.name}」？`,
      content: '关联的用户、菜单、权限关系将一并清除。',
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      onOk: async () => {
        try {
          await superworkApi.deleteRole(role.id);
          message.success('角色已删除');
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '角色删除失败');
        }
      },
    });
  };
  const openAuthorization = async (role: RoleRecord) => {
    setEditing(role);
    setAuthOpen(true);
    setAuthLoading(true);
    try {
      const [auth, linePage, projectPage]: [
        RoleAuthorization,
        { records?: BusinessLine[] },
        { records?: ProjectRecord[] },
      ] = await Promise.all([
        superworkApi.getRoleAuthorization(role.id),
        superworkApi.getBusinessLines({ page: 1, size: 500 }),
        superworkApi.getProjects({ page: 1, size: 500 }),
      ]);
      const leafMenuIds = collectLeafMenuIds(menuTree);
      setCheckedMenus((auth.menuIds || []).filter((id) => leafMenuIds.has(id)));
      setCheckedPermissions(auth.permissionIds || []);
      setDataScope(auth.dataScope || 'SELF');
      setScopeBusinessLines(linePage.records || []);
      setScopeProjects(projectPage.records || []);
      const scopeIds = String(auth.dataScopeValue || '')
        .split(',')
        .map(Number)
        .filter(Boolean);
      setSelectedBusinessLineIds(auth.dataScope === 'BU_LINE' ? scopeIds : []);
      setSelectedProjectIds(auth.dataScope === 'PROJECT' ? scopeIds : []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '授权加载失败');
    } finally {
      setAuthLoading(false);
    }
  };
  const saveAuthorization = async () => {
    if (!editing) return;
    setAuthLoading(true);
    try {
      const scopeValue =
        dataScope === 'BU_LINE'
          ? selectedBusinessLineIds.join(',')
          : dataScope === 'PROJECT'
            ? selectedProjectIds.join(',')
            : '';
      const finalMenuIds = new Set(checkedMenus);
      checkedMenus.forEach((id) => {
        collectParentMenuIds(id, menuTree).forEach((parentId) => {
          finalMenuIds.add(parentId);
        });
      });
      await superworkApi.assignRoleAuthorization(
        editing.id,
        Array.from(finalMenuIds),
        checkedPermissions,
        dataScope,
        scopeValue,
      );
      message.success('授权配置已保存');
      setAuthOpen(false);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '授权保存失败');
    } finally {
      setAuthLoading(false);
    }
  };
  const roleRows = POSITION_ROLE_OPTIONS.map((preset) => ({
    preset,
    role: roles.find((role) => role.code === preset.value),
  }));
  const legacyRoles = roles.filter(
    (role) =>
      !POSITION_ROLE_OPTIONS.some((preset) => preset.value === role.code),
  );
  return (
    <div className="sw-page sw-system-roles">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / ROLES
          </Typography.Text>
          <Typography.Title level={2}>角色管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            维护岗位角色、启用状态与菜单/按钮/数据范围授权。
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
          message="无法读取角色授权数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Row gutter={[16, 16]}>
        {['management', 'execution'].map((category) => (
          <Col xs={24} xl={12} key={category}>
            <Card
              variant="borderless"
              title={category === 'management' ? '管理序列' : '执行序列'}
            >
              {roleRows
                .filter(({ preset }) => preset.category === category)
                .map(({ preset, role }) => (
                  <div className="sw-role-row" key={preset.value}>
                    <div>
                      <Space>
                        <Typography.Text strong>{preset.label}</Typography.Text>
                        <Tag>{preset.value}</Tag>
                        {role ? (
                          <Tag
                            color={role.status === 1 ? 'success' : 'default'}
                          >
                            {role.status === 1 ? '启用' : '停用'}
                          </Tag>
                        ) : (
                          <Tag color="warning">待初始化</Tag>
                        )}
                      </Space>
                      <Typography.Text type="secondary">
                        {preset.description}
                      </Typography.Text>
                    </div>
                    <Space>
                      {role ? (
                        <>
                          <Button
                            type="link"
                            icon={<SettingOutlined />}
                            onClick={() => void openAuthorization(role)}
                          >
                            配置授权
                          </Button>
                          <Button
                            type="link"
                            icon={<EditOutlined />}
                            onClick={() => openEdit(role)}
                          >
                            维护信息
                          </Button>
                          <Button
                            type="link"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => removeRole(role)}
                          >
                            删除
                          </Button>
                        </>
                      ) : (
                        <Button
                          type="link"
                          onClick={() => void initialize(preset)}
                        >
                          初始化
                        </Button>
                      )}
                    </Space>
                  </div>
                ))}
            </Card>
          </Col>
        ))}
      </Row>
      {legacyRoles.length > 0 && (
        <Card
          variant="borderless"
          title="历史兼容角色"
          className="sw-legacy-role-card"
        >
          <Typography.Paragraph type="secondary">
            仅用于历史数据兼容，新建用户请使用岗位角色。
          </Typography.Paragraph>
          {legacyRoles.map((role) => (
            <div className="sw-role-row" key={role.id}>
              <Space>
                <Typography.Text strong>{role.name}</Typography.Text>
                <Tag>{role.code}</Tag>
                <Tag>{role.status === 1 ? '启用' : '停用'}</Tag>
              </Space>
              <Space>
                <Button
                  type="link"
                  icon={<SettingOutlined />}
                  onClick={() => void openAuthorization(role)}
                >
                  配置授权
                </Button>
                <Button
                  type="link"
                  icon={<EditOutlined />}
                  onClick={() => openEdit(role)}
                >
                  编辑
                </Button>
                <Button
                  type="link"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => removeRole(role)}
                >
                  删除
                </Button>
              </Space>
            </div>
          ))}
        </Card>
      )}
      {!loading && !roles.length && <Empty description="暂无角色数据" />}
      <Modal
        title={`维护角色 · ${editing?.name || ''}`}
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void saveRole()}
        okText="保存"
        cancelText="取消"
      >
        <Form form={form} layout="vertical">
          <Form.Item label="角色编码">
            <Input value={editing?.code} disabled />
          </Form.Item>
          <Form.Item name="name" label="角色名称">
            <Input disabled />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item
            name="status"
            label="状态"
            valuePropName="checked"
            getValueFromEvent={(value) => (value ? 1 : 0)}
          >
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={`配置授权 · ${editing?.name || ''}`}
        open={authOpen}
        width={980}
        onCancel={() => setAuthOpen(false)}
        onOk={() => void saveAuthorization()}
        okText="保存授权"
        cancelText="取消"
        confirmLoading={authLoading}
        destroyOnHidden
      >
        <SpinSection loading={authLoading}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <div className="sw-auth-heading">
                <Typography.Title level={5}>菜单权限</Typography.Title>
                <Space>
                  <Button
                    type="link"
                    size="small"
                    onClick={() => setCheckedMenus(allMenuIds)}
                  >
                    全选
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    onClick={() => setCheckedMenus([])}
                  >
                    清空
                  </Button>
                </Space>
              </div>
              <Tree
                checkable
                selectable={false}
                defaultExpandAll
                treeData={menuTreeData}
                checkedKeys={checkedMenus}
                onCheck={(keys) => setCheckedMenus(keys as number[])}
              />
            </Col>
            <Col xs={24} md={12}>
              <Typography.Title level={5}>按钮权限</Typography.Title>
              <Checkbox.Group
                value={checkedPermissions}
                onChange={(values) => setCheckedPermissions(values as number[])}
              >
                <div className="sw-auth-list">
                  {permissions.map((permission) => (
                    <Checkbox key={permission.id} value={permission.id}>
                      {permission.name}
                      <Typography.Text type="secondary">
                        {' '}
                        · {permission.code}
                      </Typography.Text>
                    </Checkbox>
                  ))}
                </div>
              </Checkbox.Group>
            </Col>
          </Row>
          <Divider />
          <Typography.Title level={5}>数据范围</Typography.Title>
          <Space wrap>
            <Select
              value={dataScope}
              style={{ width: 180 }}
              options={[
                { value: 'ALL', label: '全部数据可见' },
                { value: 'BU_LINE', label: '按业务线' },
                { value: 'PROJECT', label: '按项目/部门' },
                { value: 'SELF', label: '仅本人数据' },
              ]}
              onChange={setDataScope}
            />
            {dataScope === 'BU_LINE' && (
              <Select
                mode="multiple"
                value={selectedBusinessLineIds}
                onChange={setSelectedBusinessLineIds}
                options={scopeBusinessLines.map((line) => ({
                  label: line.name,
                  value: line.id,
                }))}
                placeholder="选择业务线"
                style={{ minWidth: 300 }}
              />
            )}
            {dataScope === 'PROJECT' && (
              <Select
                mode="multiple"
                value={selectedProjectIds}
                onChange={setSelectedProjectIds}
                options={scopeProjects.map((project) => ({
                  label: project.name,
                  value: project.id,
                }))}
                placeholder="选择项目"
                style={{ minWidth: 300 }}
              />
            )}
          </Space>
        </SpinSection>
      </Modal>
    </div>
  );
}

function SpinSection({
  loading,
  children,
}: {
  loading: boolean;
  children: ReactNode;
}) {
  return loading ? (
    <div className="sw-auth-loading">
      <Typography.Text type="secondary">正在加载授权配置…</Typography.Text>
    </div>
  ) : (
    children
  );
}
