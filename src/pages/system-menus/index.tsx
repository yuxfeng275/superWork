import {
  ApartmentOutlined,
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  LockOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Select,
  Space,
  Statistic,
  Switch,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type MenuRecord,
  type PermissionRecord,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type MenuFormValues = {
  parentId: number;
  name: string;
  icon?: string;
  path?: string;
  component?: string;
  sortOrder: number;
  visible: boolean;
  status: boolean;
};

const parentIdOf = (menu: MenuRecord) => menu.parentId ?? 0;

const sortMenus = (items: MenuRecord[]) =>
  [...items].sort(
    (a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id,
  );

const buildTree = (menus: MenuRecord[]): DataNode[] => {
  const map = new Map<number, MenuRecord & { children: MenuRecord[] }>();
  menus.forEach((menu) => {
    map.set(menu.id, { ...menu, children: [] });
  });
  const roots: (MenuRecord & { children: MenuRecord[] })[] = [];
  map.forEach((menu) => {
    const parent = parentIdOf(menu) ? map.get(parentIdOf(menu)) : undefined;
    if (parent) parent.children.push(menu);
    else roots.push(menu);
  });
  const sort = (items: (MenuRecord & { children: MenuRecord[] })[]) => {
    items.sort(
      (a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id,
    );
    items.forEach((item) => {
      sort(item.children as (MenuRecord & { children: MenuRecord[] })[]);
    });
  };
  sort(roots);
  return roots.map((menu) => ({
    key: String(menu.id),
    title: <MenuTitle menu={menu} />,
    children: menu.children.length
      ? buildTree(menu.children as MenuRecord[])
      : undefined,
  }));
};

const MenuTitle = ({ menu }: { menu: MenuRecord }) => (
  <Space>
    <Typography.Text strong>{menu.name}</Typography.Text>
    {menu.path && <Typography.Text code>{menu.path}</Typography.Text>}
    <Typography.Text type="secondary">
      顺序 {menu.sortOrder ?? 0}
    </Typography.Text>
    <Tag color={(menu.visible ?? 1) === 1 ? 'blue' : 'default'}>
      {(menu.visible ?? 1) === 1 ? '显示' : '隐藏'}
    </Tag>
    <Tag color={(menu.status ?? 1) === 1 ? 'success' : 'default'}>
      {(menu.status ?? 1) === 1 ? '启用' : '停用'}
    </Tag>
  </Space>
);

const collectDescendantIds = (menus: MenuRecord[], id: number) => {
  const descendants = new Set<number>();
  const visit = (parentId: number) => {
    menus
      .filter((menu) => parentIdOf(menu) === parentId)
      .forEach((menu) => {
        descendants.add(menu.id);
        visit(menu.id);
      });
  };
  visit(id);
  return descendants;
};

const buildParentOptions = (menus: MenuRecord[], excluded: Set<number>) => {
  const childrenByParent = new Map<number, MenuRecord[]>();
  menus.forEach((menu) => {
    const parentId = parentIdOf(menu);
    childrenByParent.set(parentId, [
      ...(childrenByParent.get(parentId) || []),
      menu,
    ]);
  });
  const walk = (
    parentId: number,
    depth: number,
  ): { label: string; value: number }[] =>
    sortMenus(childrenByParent.get(parentId) || []).flatMap((menu) => {
      if (excluded.has(menu.id)) return [];
      return [
        {
          label: `${'　'.repeat(depth)}${menu.name}`,
          value: menu.id,
        },
        ...walk(menu.id, depth + 1),
      ];
    });
  return [{ label: '顶级菜单', value: 0 }, ...walk(0, 0)];
};

export default function SystemMenusPage() {
  const [menus, setMenus] = useState<MenuRecord[]>([]);
  const [permissions, setPermissions] = useState<PermissionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<MenuRecord>();
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [movingId, setMovingId] = useState<number>();
  const [form] = Form.useForm<MenuFormValues>();
  const [messageApi, contextHolder] = message.useMessage();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [menuRows, permissionRows] = await Promise.all([
        superworkApi.getMenus(),
        superworkApi.getPermissions(),
      ]);
      setMenus(menuRows || []);
      setPermissions(permissionRows || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : '菜单加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const tree = useMemo(() => buildTree(menus), [menus]);
  const visible = menus.filter((menu) => (menu.visible ?? 1) === 1).length;
  const menuById = useMemo(
    () => new Map(menus.map((menu) => [menu.id, menu])),
    [menus],
  );
  const permissionByMenu = useMemo(() => {
    const map = new Map<number, PermissionRecord[]>();
    permissions.forEach((permission) => {
      if (permission.menuId != null)
        map.set(permission.menuId, [
          ...(map.get(permission.menuId) || []),
          permission,
        ]);
    });
    return map;
  }, [permissions]);
  const orphanPermissions = useMemo(
    () => permissions.filter((permission) => permission.menuId == null),
    [permissions],
  );
  const parentOptions = useMemo(() => {
    const excluded = editing
      ? new Set([editing.id, ...collectDescendantIds(menus, editing.id)])
      : new Set<number>();
    return buildParentOptions(menus, excluded);
  }, [editing, menus]);

  const openCreate = (parentId = 0) => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({
      parentId,
      sortOrder: 1,
      visible: true,
      status: true,
    });
    setModalOpen(true);
  };

  const openEdit = (menu: MenuRecord) => {
    setEditing(menu);
    form.setFieldsValue({
      parentId: parentIdOf(menu),
      name: menu.name,
      icon: menu.icon,
      path: menu.path,
      component: menu.component,
      sortOrder: menu.sortOrder ?? 0,
      visible: (menu.visible ?? 1) === 1,
      status: (menu.status ?? 1) === 1,
    });
    setModalOpen(true);
  };

  const handleSave = async (values: MenuFormValues) => {
    setSaving(true);
    try {
      const payload = {
        parentId: values.parentId || 0,
        name: values.name.trim(),
        icon: values.icon?.trim() || undefined,
        path: values.path?.trim() || undefined,
        component: values.component?.trim() || undefined,
        sortOrder: values.sortOrder ?? 0,
        visible: values.visible ? 1 : 0,
        status: values.status ? 1 : 0,
      };
      if (editing) {
        await superworkApi.updateMenu(editing.id, payload);
        messageApi.success('菜单已更新');
      } else {
        await superworkApi.createMenu(payload);
        messageApi.success('菜单已创建');
      }
      setModalOpen(false);
      await load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '菜单保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (menu: MenuRecord) => {
    try {
      await superworkApi.deleteMenu(menu.id);
      messageApi.success('菜单已删除');
      await load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '菜单删除失败');
    }
  };

  const moveMenu = async (menu: MenuRecord, direction: -1 | 1) => {
    if (movingId != null) return;
    const siblings = sortMenus(
      menus.filter((item) => parentIdOf(item) === parentIdOf(menu)),
    );
    const index = siblings.findIndex((item) => item.id === menu.id);
    const targetIndex = index + direction;
    if (index < 0 || targetIndex < 0 || targetIndex >= siblings.length) return;
    const orderedIds = siblings.map((item) => item.id);
    orderedIds.splice(index, 1);
    orderedIds.splice(targetIndex, 0, menu.id);
    setMovingId(menu.id);
    try {
      await superworkApi.reorderMenus(parentIdOf(menu), orderedIds);
      await load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '菜单排序失败');
    } finally {
      setMovingId(undefined);
    }
  };

  return (
    <div className="sw-page sw-system-menus">
      {contextHolder}
      <div className="sw-page-header">
        <div>
          <Typography.Title level={2}>菜单管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            管理菜单树、可见性、启用状态与关联按钮权限。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button
            icon={<PlusOutlined />}
            type="primary"
            onClick={() => openCreate()}
          >
            新增菜单
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </div>
      <div className="sw-menu-stats">
        <Card variant="borderless">
          <Statistic
            title="菜单总数"
            value={menus.length}
            prefix={<ApartmentOutlined />}
          />
        </Card>
        <Card variant="borderless">
          <Statistic
            title="已显示"
            value={visible}
            prefix={<EyeOutlined />}
            styles={{ content: { color: '#2563eb' } }}
          />
        </Card>
        <Card variant="borderless">
          <Statistic
            title="按钮权限"
            value={permissions.length}
            prefix={<LockOutlined />}
          />
        </Card>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          title="无法读取菜单数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Card
        variant="borderless"
        className="sw-menu-tree-card"
        loading={loading}
      >
        {tree.length ? (
          <Tree
            showLine
            defaultExpandAll
            treeData={tree}
            titleRender={(node) => {
              const id = Number(node.key);
              const menu = menuById.get(id);
              const itemPermissions = permissionByMenu.get(id) || [];
              if (!menu) return node.title as ReactNode;
              const siblings = sortMenus(
                menus.filter((item) => parentIdOf(item) === parentIdOf(menu)),
              );
              const siblingIndex = siblings.findIndex(
                (item) => item.id === menu.id,
              );
              return (
                <Space className="sw-menu-tree-title">
                  <span>{node.title as ReactNode}</span>
                  {itemPermissions.map((permission) => (
                    <Tag
                      key={permission.id}
                      color="purple"
                      title={permission.description}
                    >
                      {permission.name}
                    </Tag>
                  ))}
                  <Button
                    type="text"
                    size="small"
                    icon={<PlusOutlined />}
                    aria-label={`新增${menu.name}子菜单`}
                    onClick={(event) => {
                      event.stopPropagation();
                      openCreate(menu.id);
                    }}
                  />
                  <Button
                    type="text"
                    size="small"
                    icon={<EditOutlined />}
                    aria-label={`编辑${menu.name}`}
                    onClick={(event) => {
                      event.stopPropagation();
                      openEdit(menu);
                    }}
                  />
                  <Button
                    type="text"
                    size="small"
                    icon={<ArrowUpOutlined />}
                    disabled={siblingIndex <= 0 || movingId === menu.id}
                    aria-label={`${menu.name}上移`}
                    onClick={(event) => {
                      event.stopPropagation();
                      void moveMenu(menu, -1);
                    }}
                  />
                  <Button
                    type="text"
                    size="small"
                    icon={<ArrowDownOutlined />}
                    disabled={
                      siblingIndex < 0 ||
                      siblingIndex >= siblings.length - 1 ||
                      movingId === menu.id
                    }
                    aria-label={`${menu.name}下移`}
                    onClick={(event) => {
                      event.stopPropagation();
                      void moveMenu(menu, 1);
                    }}
                  />
                  <Popconfirm
                    title="确认删除该菜单？"
                    description="有子菜单时需要先移动或删除子菜单。"
                    okText="删除"
                    cancelText="取消"
                    okType="danger"
                    onConfirm={() => void handleDelete(menu)}
                  >
                    <Button
                      type="text"
                      danger
                      size="small"
                      icon={<DeleteOutlined />}
                      aria-label={`删除${menu.name}`}
                      onClick={(event) => event.stopPropagation()}
                    />
                  </Popconfirm>
                </Space>
              );
            }}
          />
        ) : (
          <Empty description="暂无菜单数据" />
        )}
      </Card>
      {orphanPermissions.length > 0 && (
        <Card
          variant="borderless"
          className="sw-orphan-permissions"
          title={
            <Space>
              <LockOutlined />
              <Typography.Text strong>未绑定菜单的权限</Typography.Text>
              <Tag color="warning">{orphanPermissions.length}</Tag>
            </Space>
          }
        >
          <Space wrap>
            {orphanPermissions.map((permission) => (
              <Tag key={permission.id} title={permission.description}>
                {permission.name}{' '}
                <Typography.Text code>{permission.code}</Typography.Text>
              </Tag>
            ))}
          </Space>
        </Card>
      )}
      <Modal
        title={editing ? '编辑菜单' : '新增菜单'}
        open={modalOpen}
        destroyOnHidden
        confirmLoading={saving}
        onCancel={() => setModalOpen(false)}
        onOk={() => void form.submit()}
      >
        <Form<MenuFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => void handleSave(values)}
        >
          <Form.Item
            name="name"
            label="菜单名称"
            rules={[{ required: true, message: '请输入菜单名称' }]}
          >
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="parentId" label="上级菜单">
            <Select options={parentOptions} />
          </Form.Item>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item name="path" label="路由路径" style={{ flex: 1 }}>
              <Input placeholder="/example" maxLength={200} />
            </Form.Item>
            <Form.Item name="icon" label="图标名称" style={{ flex: 1 }}>
              <Input placeholder="HomeOutlined" maxLength={100} />
            </Form.Item>
          </Space>
          <Form.Item name="component" label="组件标识">
            <Input placeholder="ExampleView" maxLength={200} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序值">
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Space size={32}>
            <Form.Item
              name="visible"
              label="是否显示"
              valuePropName="checked"
              noStyle
            >
              <Switch checkedChildren="显示" unCheckedChildren="隐藏" />
            </Form.Item>
            <Form.Item
              name="status"
              label="是否启用"
              valuePropName="checked"
              noStyle
            >
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  );
}
