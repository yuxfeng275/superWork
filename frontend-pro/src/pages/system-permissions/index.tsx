import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type MenuRecord,
  type PermissionRecord,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const typeLabel: Record<string, string> = {
  menu: '菜单',
  button: '按钮',
};
const typeColor: Record<string, string> = {
  menu: 'blue',
  button: 'purple',
};

const flattenMenus = (menus: MenuRecord[]): MenuRecord[] =>
  menus.flatMap((menu) => [menu, ...flattenMenus(menu.children || [])]);

export default function SystemPermissionsPage() {
  const [filterForm] = Form.useForm<{
    keyword?: string;
    type?: string;
    menuId?: number;
  }>();
  const [rows, setRows] = useState<PermissionRecord[]>([]);
  const [menus, setMenus] = useState<MenuRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [type, setType] = useState<string>();
  const [menuId, setMenuId] = useState<number>();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [permissionRows, menuRows] = await Promise.all([
        superworkApi.getPermissions(),
        superworkApi.getMenus(),
      ]);
      setRows(permissionRows || []);
      setMenus(flattenMenus(menuRows || []));
    } catch (e) {
      setRows([]);
      setMenus([]);
      setError(e instanceof Error ? e.message : '权限列表加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const menuNameById = useMemo(() => {
    const map = new Map<number, string>();
    menus.forEach((menu) => map.set(menu.id, menu.name));
    return map;
  }, [menus]);

  const visibleRows = useMemo(() => {
    const query = keyword.trim().toLowerCase();
    return rows.filter((row) => {
      if (type && (row.type || '') !== type) return false;
      if (menuId != null && row.menuId !== menuId) return false;
      if (!query) return true;
      return [row.name, row.code, row.description]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(query));
    });
  }, [keyword, menuId, rows, type]);

  const columns: TableProps<PermissionRecord>['columns'] = [
    {
      title: '权限名称',
      dataIndex: 'name',
      render: (value, row) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-permission-code">
            {row.code}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 110,
      render: (value) => (
        <Tag color={typeColor[value] || 'default'}>
          {typeLabel[value] || value || '—'}
        </Tag>
      ),
    },
    {
      title: '绑定菜单',
      dataIndex: 'menuId',
      width: 180,
      render: (value) =>
        value == null ? (
          <Typography.Text type="secondary">未绑定</Typography.Text>
        ) : (
          menuNameById.get(value) || `菜单 #${value}`
        ),
    },
    {
      title: '说明',
      dataIndex: 'description',
      render: (value) => value || '—',
    },
  ];

  return (
    <div className="sw-page sw-permissions">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SYSTEM / PERMISSIONS
          </Typography.Text>
          <Typography.Title level={2}>权限管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            查看按钮与菜单权限编码，分配操作请到角色管理完成。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
      </div>
      <Card variant="borderless" className="sw-filter-card">
        <Form
          form={filterForm}
          layout="inline"
          onFinish={(values) => {
            setKeyword(values.keyword || '');
            setType(values.type);
            setMenuId(values.menuId);
          }}
        >
          <Form.Item name="keyword" label="关键词">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="名称 / 编码 / 说明"
            />
          </Form.Item>
          <Form.Item name="type" label="类型">
            <Select
              allowClear
              placeholder="全部类型"
              style={{ width: 130 }}
              options={[
                { label: '菜单', value: 'menu' },
                { label: '按钮', value: 'button' },
              ]}
            />
          </Form.Item>
          <Form.Item name="menuId" label="绑定菜单">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部菜单"
              style={{ width: 220 }}
              options={menus.map((menu) => ({
                label: menu.name,
                value: menu.id,
              }))}
            />
          </Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button
              onClick={() => {
                filterForm.resetFields();
                setKeyword('');
                setType(undefined);
                setMenuId(undefined);
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取权限列表"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Card variant="borderless" className="sw-table-card">
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={visibleRows}
          locale={{ emptyText: <Empty description="暂无权限数据" /> }}
          pagination={{
            defaultPageSize: 20,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条权限`,
          }}
        />
      </Card>
    </div>
  );
}
