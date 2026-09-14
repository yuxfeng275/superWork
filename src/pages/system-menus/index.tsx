import {
  ApartmentOutlined,
  EyeOutlined,
  LockOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Space,
  Statistic,
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

const buildTree = (menus: MenuRecord[]): DataNode[] => {
  const map = new Map<number, MenuRecord & { children: MenuRecord[] }>();
  menus.forEach((menu) => {
    map.set(menu.id, { ...menu, children: [] });
  });
  const roots: (MenuRecord & { children: MenuRecord[] })[] = [];
  map.forEach((menu) => {
    const parent = menu.parentId ? map.get(menu.parentId) : undefined;
    if (parent) parent.children.push(menu);
    else roots.push(menu);
  });
  const sort = (items: (MenuRecord & { children: MenuRecord[] })[]) => {
    items.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
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

export default function SystemMenusPage() {
  const [menus, setMenus] = useState<MenuRecord[]>([]);
  const [permissions, setPermissions] = useState<PermissionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
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
  return (
    <div className="sw-page sw-system-menus">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / MENUS
          </Typography.Text>
          <Typography.Title level={2}>菜单管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            查看菜单树、可见性、启用状态与关联按钮权限。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
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
          message="无法读取菜单数据"
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
              const itemPermissions = permissionByMenu.get(id) || [];
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
    </div>
  );
}
