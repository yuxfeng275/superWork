import {
  ApartmentOutlined,
  BarChartOutlined,
  BellOutlined,
  HomeOutlined,
  LogoutOutlined,
  RiseOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import type {
  Settings as LayoutSettings,
  MenuDataItem,
} from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import {
  App as AntApp,
  Avatar,
  Badge,
  Button,
  Dropdown,
  Empty,
  Popover,
  Space,
  Spin,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React, { useEffect, useState } from 'react';
import defaultSettings from '../config/defaultSettings';
import { hasRoleAccess, type RoleAccess } from './constants/roles';
import {
  type AiNotice,
  type CurrentUser,
  type MenuTreeNode,
  superworkApi,
} from './services/superwork/api';

const loginPath = '/user/login';
dayjs.extend(relativeTime);

const menuGroupIcons = {
  workspace: <HomeOutlined />,
  foundation: <ApartmentOutlined />,
  sales: <RiseOutlined />,
  system: <SettingOutlined />,
  ai: <RobotOutlined />,
  analysis: <BarChartOutlined />,
};
const menuGroupIconByName: Record<string, React.ReactNode> = {
  工作台: menuGroupIcons.workspace,
  基础分类: menuGroupIcons.foundation,
  销售管理: menuGroupIcons.sales,
  系统管理: menuGroupIcons.system,
  'AI 与协作': menuGroupIcons.ai,
  数据分析: menuGroupIcons.analysis,
};

/** 菜单按业务域组织；未迁移页面暂不挂空壳入口。 */
const migratedMenu: MenuDataItem[] = [
  {
    key: 'workspace',
    name: '工作台',
    icon: menuGroupIcons.workspace,
    children: [
      { key: '/workbench', path: '/workbench', name: '首页' },
      { key: '/requirements', path: '/requirements', name: '需求管理' },
      { key: '/tasks', path: '/tasks', name: '任务管理' },
      { key: '/defects', path: '/defects', name: '缺陷管理' },
      { key: '/weekly-report', path: '/weekly-report', name: '周报中心' },
    ],
  },
  {
    key: 'foundation',
    name: '基础分类',
    icon: menuGroupIcons.foundation,
    children: [
      { key: '/business-lines', path: '/business-lines', name: '业务线管理' },
      { key: '/projects', path: '/projects', name: '项目管理' },
      { key: '/customers', path: '/customers', name: '客户信息管理' },
    ],
  },
  {
    key: 'sales',
    name: '销售管理',
    icon: menuGroupIcons.sales,
    children: [
      { key: '/opportunities', path: '/opportunities', name: '线索商机管理' },
      {
        key: '/quotation-policies',
        path: '/quotation-policies',
        name: '报价策略管理',
      },
      { key: '/quotations', path: '/quotations', name: '报价单管理' },
    ],
  },
  {
    key: 'system',
    name: '系统管理',
    icon: menuGroupIcons.system,
    children: [
      { key: '/system/users', path: '/system/users', name: '用户管理' },
      { key: '/system/roles', path: '/system/roles', name: '角色管理' },
      { key: '/system/menus', path: '/system/menus', name: '菜单管理' },
      { key: '/system/workflow', path: '/system/workflow', name: '工作流配置' },
      { key: '/system/configs', path: '/system/configs', name: '配置管理' },
    ],
  },
  {
    key: 'ai',
    name: 'AI 与协作',
    icon: menuGroupIcons.ai,
    children: [
      { key: '/ai-connectors', path: '/ai-connectors', name: 'AI 连接器' },
      { key: '/ai-assistant', path: '/ai-assistant', name: 'AI 助手' },
      { key: '/emails', path: '/emails', name: '邮件管理' },
      { key: '/key-matters', path: '/key-matters', name: '大事儿管理' },
      {
        key: '/key-matters-meeting',
        path: '/key-matters-meeting',
        name: '大事儿会议视图',
      },
    ],
  },
  {
    key: 'analysis',
    name: '数据分析',
    icon: menuGroupIcons.analysis,
    children: [
      { key: '/statistics', path: '/statistics', name: 'BU 驾驶舱' },
      {
        key: '/revenue/worktime',
        path: '/revenue/worktime',
        name: '工时 & 成本',
      },
      {
        key: '/revenue/delivery',
        path: '/revenue/delivery',
        name: '交付与利润',
      },
      { key: '/kpi-report', path: '/kpi-report', name: 'KPI 周报' },
      { key: '/bl-profit', path: '/bl-profit', name: '业务线利润' },
      {
        key: 'revenue-config',
        name: '配置',
        type: 'group',
        children: [
          { key: '/revenue/import', path: '/revenue/import', name: '数据导入' },
          {
            key: '/revenue/pending',
            path: '/revenue/pending',
            name: '待映射与销售项目',
          },
        ],
      },
    ],
  },
];

type MenuAuth = { paths: string[]; managedPaths: string[] };
const migratedPaths = new Set<string>();
const collectMenuPaths = (items: MenuDataItem[]) =>
  items.forEach((item) => {
    if (item.path) migratedPaths.add(item.path);
    if (item.children) collectMenuPaths(item.children);
  });
collectMenuPaths(migratedMenu);
const filterMenuByAuth = (
  items: MenuDataItem[],
  auth?: MenuAuth,
  keyMatterAccess?: Record<string, unknown>,
): MenuDataItem[] => {
  const hasAuth = Boolean(auth?.paths.length);
  if (!hasAuth && keyMatterAccess?.canAccess !== false) return items;
  const allowed = new Set(auth?.paths || []);
  const managed = new Set(auth?.managedPaths || []);
  return items.reduce<MenuDataItem[]>((result, item) => {
    if (
      (item.path === '/key-matters' || item.path === '/key-matters-meeting') &&
      keyMatterAccess?.canAccess === false
    )
      return result;
    const children = item.children
      ? filterMenuByAuth(item.children, auth, keyMatterAccess)
      : undefined;
    const governed = item.path && managed.has(item.path);
    if (governed && !allowed.has(item.path as string)) return result;
    if (item.children && !children?.length) return result;
    result.push(children ? { ...item, children } : item);
    return result;
  }, []);
};
const mapServerMenu = (nodes?: MenuTreeNode[]): MenuDataItem[] => {
  if (!nodes?.length) return migratedMenu;
  const mapNode = (node: MenuTreeNode, depth = 0): MenuDataItem | undefined => {
    const normalizedPath =
      node.path === '/home' ? '/workbench' : node.path || undefined;
    const children = (node.children || [])
      .map((child) => mapNode(child, depth + 1))
      .filter(Boolean) as MenuDataItem[];
    if (normalizedPath && migratedPaths.has(normalizedPath))
      return { key: normalizedPath, path: normalizedPath, name: node.name };
    if (children.length)
      return {
        key: `server-menu-${node.id}`,
        name: node.name,
        icon: menuGroupIconByName[node.name],
        // 二级及以下的分组平铺展示（type: group），不做折叠子菜单，对齐旧系统三级模式
        ...(depth >= 1 ? { type: 'group' as const } : {}),
        children,
      };
    return undefined;
  };
  const mapped = nodes
    .map((node) => mapNode(node))
    .filter(Boolean) as MenuDataItem[];
  const mappedPaths = new Set<string>();
  const collectMappedPaths = (items: MenuDataItem[]) =>
    items.forEach((item) => {
      if (item.path) mappedPaths.add(item.path);
      if (item.children) collectMappedPaths(item.children);
    });
  collectMappedPaths(mapped);
  // 后端菜单树可能来自旧版本，只返回部分入口；不完整时保留新工程的完整业务分组，
  // 再由 filterMenuByAuth 根据服务端授权路径收口，避免迁移页面在侧栏静默消失。
  const hasEveryMigratedPath = [...migratedPaths].every((path) =>
    mappedPaths.has(path),
  );
  return mapped.length && hasEveryMigratedPath ? mapped : migratedMenu;
};

export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: CurrentUser;
  menuAuth?: MenuAuth;
  menuTree?: MenuTreeNode[];
  requirementTotal?: number;
  keyMatterAccess?: Record<string, unknown>;
  fetchUserInfo?: () => Promise<CurrentUser | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    try {
      return await superworkApi.getCurrentUser();
    } catch {
      return undefined;
    }
  };
  if (history.location.pathname !== loginPath) {
    const currentUser = await fetchUserInfo();
    if (!currentUser) {
      const redirect = `${history.location.pathname}${history.location.search || ''}${history.location.hash || ''}`;
      history.replace(`${loginPath}?redirect=${encodeURIComponent(redirect)}`);
      return {
        fetchUserInfo,
        settings: defaultSettings as Partial<LayoutSettings>,
        settingDrawerOpen: false,
      };
    }
    const [menuAuth, menuTree, requirementPage, keyMatterAccess] =
      await Promise.all([
        Promise.race([
          superworkApi.getMyMenus(),
          new Promise<undefined>((resolve) =>
            window.setTimeout(() => resolve(undefined), 1800),
          ),
        ]).catch(() => undefined),
        Promise.race([
          superworkApi.getMyMenuTree(),
          new Promise<undefined>((resolve) =>
            window.setTimeout(() => resolve(undefined), 1800),
          ),
        ]).catch(() => undefined),
        Promise.race([
          superworkApi.getRequirements({ page: 1, size: 1 }),
          new Promise<undefined>((resolve) =>
            window.setTimeout(() => resolve(undefined), 1800),
          ),
        ]).catch(() => undefined),
        Promise.race([
          superworkApi.getKeyMatterAccess(),
          new Promise<undefined>((resolve) =>
            window.setTimeout(() => resolve(undefined), 1800),
          ),
        ]).catch(() => undefined),
      ]);
    return {
      currentUser,
      menuAuth,
      menuTree,
      requirementTotal:
        requirementPage && typeof requirementPage.total === 'number'
          ? requirementPage.total
          : undefined,
      keyMatterAccess,
      fetchUserInfo,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }
  return {
    fetchUserInfo,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

const Brand = () => (
  <Link to="/workbench" className="sw-brand" aria-label="返回工作台">
    <span className="sw-brand-mark">BU</span>
    <span>
      <strong>电商BU</strong>
      <small>管理系统</small>
    </span>
  </Link>
);

const NoticeAction = () => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [notices, setNotices] = useState<AiNotice[]>([]);
  const [unread, setUnread] = useState(0);
  const load = async () => {
    setLoading(true);
    try {
      const timeout = new Promise<never>((_, reject) =>
        window.setTimeout(() => reject(new Error('notice-timeout')), 2500),
      );
      const [items, count] = await Promise.race([
        Promise.all([
          superworkApi.getAiNotices(),
          superworkApi.getAiNoticeUnreadCount(),
        ]),
        timeout,
      ]);
      setNotices(items || []);
      setUnread(Number(count?.count || 0));
    } catch {
      setNotices([]);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 60000);
    return () => window.clearInterval(timer);
  }, []);
  const openNotice = async (notice: AiNotice) => {
    try {
      await superworkApi.markAiNoticeRead(notice.kind, notice.date);
    } catch {
      /* 已读失败不阻断跳转 */
    }
    setOpen(false);
    await load();
    if (notice.link) {
      const prefillByKind: Record<string, string> = {
        WORKLOG_MISSING: '帮我分析一下我最近几个月的工时填报情况',
        WORKTIME_MONTH_MISSING: '帮我分析一下我最近几个月的工时填报情况',
      };
      const prefill = prefillByKind[notice.kind];
      const target =
        notice.link === '/ai-assistant' && prefill
          ? `${notice.link}?prefill=${encodeURIComponent(prefill)}`
          : notice.link;
      history.push(target);
    }
  };
  const content = loading ? (
    <div style={{ padding: 24, textAlign: 'center' }}>
      <Spin size="small" />
    </div>
  ) : notices.length ? (
    <ul className="sw-notice-list">
      {notices.slice(0, 8).map((notice) => (
        <li
          className="sw-notice-item"
          key={`${notice.kind || 'notice'}-${notice.date || notice.title || 'item'}`}
        >
          <div className="sw-notice-copy">
            <strong>{notice.title || notice.kind || 'AI 通知'}</strong>
            <span>
              {notice.message || notice.body || notice.date || '暂无详情'}
            </span>
          </div>
          {notice.link && !notice.read && (
            <Button
              type="link"
              size="small"
              onClick={() => void openNotice(notice)}
            >
              去处理
            </Button>
          )}
        </li>
      ))}
    </ul>
  ) : (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待办通知" />
  );
  return (
    <Popover
      title="AI 待办通知"
      trigger="click"
      open={open}
      onOpenChange={(value) => {
        setOpen(value);
        if (value) void load();
      }}
      content={<div style={{ width: 340, maxWidth: '80vw' }}>{content}</div>}
    >
      <Badge count={unread} size="small" overflowCount={99}>
        <Button type="text" aria-label="AI 待办通知" icon={<BellOutlined />} />
      </Badge>
    </Popover>
  );
};

export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  const user = initialState?.currentUser;
  const accessForPath = (pathname: string): RoleAccess | undefined => {
    if (pathname === '/projects') return 'project';
    if (pathname === '/customers') return 'customer';
    if (
      [
        '/statistics',
        '/revenue',
        '/kpi-report',
        '/bl-profit',
        '/system/users',
        '/system/roles',
        '/system/menus',
        '/system/workflow',
        '/system/configs',
        '/ai-connectors',
      ].some((path) => pathname === path || pathname.startsWith(`${path}/`))
    )
      return 'management';
    return undefined;
  };
  return {
    ...(initialState?.settings as Partial<LayoutSettings>),
    menuDataRender: () =>
      filterMenuByAuth(
        mapServerMenu(initialState?.menuTree),
        initialState?.menuAuth,
        initialState?.keyMatterAccess,
      ),
    menuHeaderRender: () => <Brand />,
    menuItemRender: (item, dom) => {
      const content = item.path ? <Link to={item.path}>{dom}</Link> : dom;
      if (
        item.path === '/requirements' &&
        (initialState?.requirementTotal || 0) > 0
      ) {
        return (
          <Badge
            count={initialState?.requirementTotal}
            overflowCount={99}
            offset={[8, 0]}
          >
            {content}
          </Badge>
        );
      }
      return content;
    },
    actionsRender: () => [<NoticeAction key="notice" />],
    avatarProps: {
      src: user?.avatar,
      title: user?.realName || user?.username || '未登录',
      render: () => (
        <Dropdown
          menu={{
            items: [
              {
                key: 'logout',
                icon: <LogoutOutlined />,
                label: '退出登录',
                onClick: () => {
                  localStorage.removeItem('token');
                  localStorage.removeItem('user');
                  localStorage.removeItem('refreshToken');
                  history.replace(loginPath);
                },
              },
            ],
          }}
        >
          <Space className="sw-user-menu" size={8}>
            <Avatar size={32} src={user?.avatar}>
              {(user?.realName || user?.username || 'U').slice(0, 1)}
            </Avatar>
            <span className="sw-user-copy">
              <strong>{user?.realName || user?.username || '未登录'}</strong>
              <small>{user?.role || '访客'}</small>
            </span>
          </Space>
        </Dropdown>
      ),
    },
    onPageChange: () => {
      if (
        !initialState?.currentUser &&
        history.location.pathname !== loginPath
      ) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(history.location.pathname)}`,
        );
        return;
      }
      if (
        (history.location.pathname === '/key-matters' ||
          history.location.pathname === '/key-matters-meeting' ||
          history.location.pathname.startsWith('/key-matters/')) &&
        initialState?.keyMatterAccess?.canAccess === false
      ) {
        history.replace('/workbench');
        return;
      }
      const requiredAccess = accessForPath(history.location.pathname);
      if (requiredAccess && !hasRoleAccess(user?.role, requiredAccess)) {
        history.replace('/workbench');
      }
    },
    footerRender: () => (
      <Typography.Text type="secondary">
        SuperWork · 前端迁移工作区
      </Typography.Text>
    ),
  };
};

export const request: RequestConfig = {
  timeout: 15000,
};

export function rootContainer(container: React.ReactNode) {
  return <AntApp>{container}</AntApp>;
}
