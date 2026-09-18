import {
  ApartmentOutlined,
  ApiOutlined,
  AppstoreOutlined,
  BarChartOutlined,
  BellOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CloudSyncOutlined,
  FieldTimeOutlined,
  FileTextOutlined,
  FlagOutlined,
  FolderOutlined,
  HomeOutlined,
  LinkOutlined,
  LockOutlined,
  LogoutOutlined,
  MenuOutlined,
  MessageOutlined,
  QuestionCircleOutlined,
  RiseOutlined,
  RobotOutlined,
  SearchOutlined,
  SettingOutlined,
  TagsOutlined,
  UploadOutlined,
  UserOutlined,
} from "@ant-design/icons";
import type {
  Settings as LayoutSettings,
  MenuDataItem,
} from "@ant-design/pro-components";
import type { RequestConfig, RunTimeLayoutConfig } from "@umijs/max";
import { history, Link, useModel } from "@umijs/max";
import {
  App as AntApp,
  Avatar,
  Badge,
  Button,
  Dropdown,
  Empty,
  Input,
  Popover,
  Space,
  Spin,
} from "antd";
import dayjs from "dayjs";
import relativeTime from "dayjs/plugin/relativeTime";
import React, { useEffect, useMemo, useState } from "react";
import defaultSettings from "../config/defaultSettings";
import ConsoleNavigation from "./components/ConsoleNavigation";
import { hasRoleAccess, type RoleAccess } from "./constants/roles";
import {
  type AiNotice,
  type CurrentUser,
  type MenuTreeNode,
  superworkApi,
} from "./services/superwork/api";
import { readMenuCache, writeMenuCache } from "./utils/menuCache";

const loginPath = "/user/login";
const initialStateRequestTimeout = 8000;
dayjs.extend(relativeTime);

/** 首屏关键请求带超时兜底,避免慢接口无限阻塞渲染。 */
const withInitialStateTimeout = <T,>(promise: Promise<T>) =>
  Promise.race([
    promise,
    new Promise<undefined>((resolve) =>
      window.setTimeout(() => resolve(undefined), initialStateRequestTimeout)
    ),
  ]).catch(() => undefined);

const menuGroupIcons = {
  home: <HomeOutlined />,
  workspace: <AppstoreOutlined />,
  foundation: <ApartmentOutlined />,
  sales: <RiseOutlined />,
  system: <SettingOutlined />,
  ai: <RobotOutlined />,
  analysis: <BarChartOutlined />,
};
const menuGroupIconByName: Record<string, React.ReactNode> = {
  工作台: menuGroupIcons.workspace,
  基础分类: menuGroupIcons.foundation,
  基础数据: menuGroupIcons.foundation,
  销售管理: menuGroupIcons.sales,
  系统管理: menuGroupIcons.system,
  系统: menuGroupIcons.system,
  "AI 与协作": menuGroupIcons.ai,
  数据分析: menuGroupIcons.analysis,
};
const menuIconByServerName: Record<string, React.ReactNode> = {
  HomeFilled: <HomeOutlined />,
  HomeOutlined: <HomeOutlined />,
  Document: <FileTextOutlined />,
  Finished: <CheckCircleOutlined />,
  checkCircle: <CheckCircleOutlined />,
  CheckSquare: <CheckCircleOutlined />,
  CircleCloseFilled: <CloseCircleOutlined />,
  Message: <MessageOutlined />,
  ChatDotRound: <RobotOutlined />,
  Flag: <FlagOutlined />,
  DataLine: <BarChartOutlined />,
  Connection: <ApiOutlined />,
  CloudSync: <CloudSyncOutlined />,
  DataAnalysis: <BarChartOutlined />,
  Timer: <FieldTimeOutlined />,
  Calendar: <CalendarOutlined />,
  calendar: <CalendarOutlined />,
  TrendCharts: <RiseOutlined />,
  Collection: <ApartmentOutlined />,
  CollectionTag: <TagsOutlined />,
  Folder: <FolderOutlined />,
  UserFilled: <UserOutlined />,
  User: <UserOutlined />,
  Lock: <LockOutlined />,
  Menu: <MenuOutlined />,
  Setting: <SettingOutlined />,
  Upload: <UploadOutlined />,
  Link: <LinkOutlined />,
};
const resolveMenuIcon = (icon: string | null | undefined, name: string) => {
  if (name === "工作台") return menuGroupIcons.workspace;
  if (name === "首页") return menuGroupIcons.home;
  return (
    menuGroupIconByName[name] ??
    menuIconByServerName[icon || ""] ?? <MenuOutlined />
  );
};

type MenuAuth = { paths: string[]; managedPaths: string[] };
const filterMenuByAuth = (
  items: MenuDataItem[],
  auth?: MenuAuth,
  keyMatterAccess?: Record<string, unknown>
): MenuDataItem[] => {
  const hasAuth = Boolean(auth?.paths.length);
  if (!hasAuth && keyMatterAccess?.canAccess !== false) return items;
  const allowed = new Set(auth?.paths || []);
  const managed = new Set(auth?.managedPaths || []);
  return items.reduce<MenuDataItem[]>((result, item) => {
    if (
      (item.path === "/key-matters" || item.path === "/key-matters-meeting") &&
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
const splitHomeMenu = (items: MenuDataItem[]): MenuDataItem[] => {
  let home: MenuDataItem | undefined;
  const visit = (entries: MenuDataItem[]): MenuDataItem[] =>
    entries.reduce<MenuDataItem[]>((result, item) => {
      if (item.path === "/workbench") {
        home ??= {
          ...item,
          key: "/workbench",
          name: "首页",
          icon: menuGroupIcons.home,
        };
        return result;
      }
      if (!item.children?.length) {
        result.push(item);
        return result;
      }
      const children = visit(item.children);
      if (children.length) result.push({ ...item, children });
      else if (item.path) result.push({ ...item, children: undefined });
      return result;
    }, []);
  const rest = visit(items);
  return home ? [home, ...rest] : rest;
};

const mapServerMenu = (nodes?: MenuTreeNode[]): MenuDataItem[] => {
  if (!nodes?.length) return [];
  const mapNode = (node: MenuTreeNode, depth = 0): MenuDataItem | undefined => {
    const normalizedPath =
      node.path === "/home" ? "/workbench" : node.path || undefined;
    const children = (node.children || [])
      .map((child) => mapNode(child, depth + 1))
      .filter(Boolean) as MenuDataItem[];
    if (!normalizedPath && !children.length) return undefined;
    return {
      key: normalizedPath || `server-menu-${node.id}`,
      name: node.name,
      icon: resolveMenuIcon(node.icon, node.name),
      ...(normalizedPath ? { path: normalizedPath } : {}),
      ...(children.length ? { children } : {}),
      ...(children.length && depth >= 1 ? { type: "group" as const } : {}),
    };
  };
  const mapped = nodes
    .map((node) => mapNode(node))
    .filter(Boolean) as MenuDataItem[];
  return splitHomeMenu(mapped);
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
      const redirect = `${history.location.pathname}${
        history.location.search || ""
      }${history.location.hash || ""}`;
      history.replace(`${loginPath}?redirect=${encodeURIComponent(redirect)}`);
      return {
        fetchUserInfo,
        settings: defaultSettings as Partial<LayoutSettings>,
        settingDrawerOpen: false,
      };
    }
    const cached = readMenuCache(currentUser.username);
    if (cached?.menuTree?.length) {
      // 命中缓存:立即返回,菜单秒开;最新数据由 DeferredInitialState 后台校验。
      return {
        currentUser,
        menuAuth: cached.menuAuth,
        menuTree: cached.menuTree,
        keyMatterAccess: cached.keyMatterAccess,
        fetchUserInfo,
        settings: defaultSettings as Partial<LayoutSettings>,
        settingDrawerOpen: false,
      };
    }
    // 无缓存:首屏只阻塞菜单必需的两个接口,徽标/重点事项权限延后加载。
    const [menuAuth, menuTree] = await Promise.all([
      withInitialStateTimeout(superworkApi.getMyMenus()),
      withInitialStateTimeout(superworkApi.getMyMenuTree()),
    ]);
    if (menuTree?.length)
      writeMenuCache(currentUser.username, { menuAuth, menuTree });
    return {
      currentUser,
      menuAuth,
      menuTree,
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

const NoticeAction = () => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [notices, setNotices] = useState<AiNotice[]>([]);
  const [unread, setUnread] = useState(0);
  const load = async () => {
    setLoading(true);
    try {
      const timeout = new Promise<never>((_, reject) =>
        window.setTimeout(() => reject(new Error("notice-timeout")), 2500)
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
        WORKLOG_MISSING: "帮我分析一下我最近几个月的工时填报情况",
        WORKTIME_MONTH_MISSING: "帮我分析一下我最近几个月的工时填报情况",
      };
      const prefill = prefillByKind[notice.kind];
      const target =
        notice.link === "/ai-assistant" && prefill
          ? `${notice.link}?prefill=${encodeURIComponent(prefill)}`
          : notice.link;
      history.push(target);
    }
  };
  const content = loading ? (
    <div style={{ padding: 24, textAlign: "center" }}>
      <Spin size="small" />
    </div>
  ) : notices.length ? (
    <ul className="sw-notice-list">
      {notices.slice(0, 8).map((notice) => (
        <li
          className="sw-notice-item"
          key={`${notice.kind || "notice"}-${
            notice.date || notice.title || "item"
          }`}
        >
          <div className="sw-notice-copy">
            <strong>{notice.title || notice.kind || "AI 通知"}</strong>
            <span>
              {notice.message || notice.body || notice.date || "暂无详情"}
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
      content={<div style={{ width: 340, maxWidth: "80vw" }}>{content}</div>}
    >
      <Badge count={unread} size="small" overflowCount={99}>
        <Button
          className="sw-notice-button"
          type="text"
          aria-label="消息"
          icon={<BellOutlined />}
        >
          <span>消息</span>
        </Button>
      </Badge>
    </Popover>
  );
};

const deferredStateLoaded = new Set<string>();

/**
 * 首屏渲染完成后异步补齐非关键数据(需求徽标、重点事项权限),
 * 并后台校验菜单缓存,通过 setInitialState 增量更新,不阻塞渲染。
 */
const DeferredInitialState: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const { initialState, setInitialState } = useModel("@@initialState");
  const username = initialState?.currentUser?.username;
  useEffect(() => {
    if (!username || deferredStateLoaded.has(username)) return;
    deferredStateLoaded.add(username);
    void (async () => {
      const [menuAuth, menuTree, requirementPage, keyMatterAccess] =
        await Promise.all([
          withInitialStateTimeout(superworkApi.getMyMenus()),
          withInitialStateTimeout(superworkApi.getMyMenuTree()),
          withInitialStateTimeout(
            superworkApi.getRequirements({ page: 1, size: 1 })
          ),
          withInitialStateTimeout(superworkApi.getKeyMatterAccess()),
        ]);
      if (menuTree?.length)
        writeMenuCache(username, { menuAuth, menuTree, keyMatterAccess });
      setInitialState((prev) => ({
        ...prev,
        ...(menuAuth ? { menuAuth } : {}),
        ...(menuTree?.length ? { menuTree } : {}),
        requirementTotal:
          requirementPage && typeof requirementPage.total === "number"
            ? requirementPage.total
            : prev?.requirementTotal,
        ...(keyMatterAccess ? { keyMatterAccess } : {}),
      }));
    })();
  }, [username, setInitialState]);
  return <>{children}</>;
};

type SearchEntry = { name: string; path: string; parents: string[] };

const collectSearchEntries = (
  items: MenuDataItem[],
  parents: string[] = []
): SearchEntry[] =>
  items.flatMap((item) => [
    ...(item.path && !item.children?.length
      ? [{ name: String(item.name || ""), path: item.path, parents }]
      : []),
    ...(item.children
      ? collectSearchEntries(item.children, [
          ...parents,
          String(item.name || ""),
        ])
      : []),
  ]);

const SearchAction: React.FC<{ items: MenuDataItem[] }> = ({ items }) => {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const entries = useMemo(() => collectSearchEntries(items), [items]);
  const results = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return entries.slice(0, 8);
    return entries
      .filter((entry) =>
        [entry.name, ...entry.parents].join(" ").toLowerCase().includes(keyword)
      )
      .slice(0, 8);
  }, [entries, query]);

  return (
    <Popover
      title="搜索页面"
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      content={
        <div className="sw-search-popover">
          <Input
            autoFocus
            allowClear
            value={query}
            prefix={<SearchOutlined />}
            placeholder="搜索菜单或页面"
            onChange={(event) => setQuery(event.target.value)}
          />
          <div className="sw-search-results">
            {results.length ? (
              results.map((entry) => (
                <button
                  className="sw-search-result"
                  key={entry.path}
                  type="button"
                  onClick={() => {
                    history.push(entry.path);
                    setOpen(false);
                    setQuery("");
                  }}
                >
                  <strong>{entry.name}</strong>
                  <small>{entry.parents.join(" / ") || entry.path}</small>
                </button>
              ))
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="未找到页面"
              />
            )}
          </div>
        </div>
      }
    >
      <Button
        className="sw-search-trigger"
        type="text"
        aria-label="搜索页面"
        icon={<SearchOutlined />}
      >
        <span>搜索菜单或页面</span>
      </Button>
    </Popover>
  );
};

const AiEntryAction = () => (
  <Button
    className="sw-ai-entry"
    type="text"
    aria-label="打开 AI 助手"
    icon={<RobotOutlined />}
    onClick={() => history.push("/ai-assistant")}
  >
    <span>AI 搜索</span>
  </Button>
);

const topUtilityLinks = [
  { path: "/requirements", label: "文档", icon: <FileTextOutlined /> },
  { path: "/customers", label: "客户", icon: <UserOutlined /> },
  { path: "/ai-assistant", label: "帮助", icon: <QuestionCircleOutlined /> },
  { path: "/tasks", label: "协作", icon: <MessageOutlined /> },
];

const TopUtilityLinks = ({ items }: { items: MenuDataItem[] }) => (
  <nav className="sw-topbar-utilities" aria-label="工具入口">
    {topUtilityLinks
      .filter((item) =>
        collectSearchEntries(items).some((entry) => entry.path === item.path)
      )
      .map((item) => (
        <Link
          className="sw-topbar-utility"
          key={item.path}
          to={item.path}
          aria-label={item.label}
          title={item.label}
        >
          {item.icon}
          <span>{item.label}</span>
        </Link>
      ))}
  </nav>
);

const TopBar: React.FC<{
  user?: CurrentUser;
  items: MenuDataItem[];
}> = ({ user, items }) => {
  return (
    <div className="sw-topbar">
      <div className="sw-topbar-left">
        <Link
          to="/workbench"
          className="sw-topbar-brand"
          aria-label="返回工作台"
        >
          <img className="sw-topbar-logo" src="/logo.png" alt="合拍" />
          <strong className="sw-topbar-brand-name">电商BU管理系统</strong>
        </Link>
      </div>
      <div className="sw-topbar-actions">
        <div className="sw-topbar-search">
          <SearchAction items={items} />
          <AiEntryAction />
        </div>
        <TopUtilityLinks items={items} />
        <NoticeAction />
        {user && (
          <Dropdown
            menu={{
              items: [
                {
                  key: "logout",
                  icon: <LogoutOutlined />,
                  label: "退出登录",
                  onClick: () => {
                    deferredStateLoaded.delete(user?.username || "");
                    localStorage.removeItem("token");
                    localStorage.removeItem("user");
                    localStorage.removeItem("refreshToken");
                    history.replace(loginPath);
                  },
                },
              ],
            }}
          >
            <button
              className="sw-user-menu"
              type="button"
              aria-label="账号菜单"
            >
              <Avatar size={24} src={user.avatar}>
                {(user.realName || user.username || "U").slice(0, 1)}
              </Avatar>
              <span className="sw-user-copy">
                <strong>{user.realName || user.username || "未登录"}</strong>
              </span>
            </button>
          </Dropdown>
        )}
      </div>
    </div>
  );
};

export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  const user = initialState?.currentUser;
  const accessForPath = (pathname: string): RoleAccess | undefined => {
    if (pathname === "/projects") return "project";
    if (pathname === "/customers") return "customer";
    if (
      [
        "/statistics",
        "/revenue",
        "/kpi-report",
        "/bl-profit",
        "/system/users",
        "/system/roles",
        "/system/menus",
        "/system/permissions",
        "/system/workflow",
        "/system/configs",
        "/system/connectors",
        "/system/models",
        "/system/sync",
        "/oa-affairs",
      ].some((path) => pathname === path || pathname.startsWith(`${path}/`))
    )
      return "management";
    return undefined;
  };
  const menuItems = filterMenuByAuth(
    mapServerMenu(initialState?.menuTree),
    initialState?.menuAuth,
    initialState?.keyMatterAccess
  );
  return {
    ...(initialState?.settings as Partial<LayoutSettings>),
    // 顶部导航参照抖店开放平台：品牌、搜索、AI、消息和账号操作集中在顶栏。
    headerRender: () => <TopBar user={user} items={menuItems} />,
    menuContentRender: () => (
      <DeferredInitialState>
        <ConsoleNavigation
          items={menuItems}
          requirementTotal={initialState?.requirementTotal}
        />
      </DeferredInitialState>
    ),
    siderWidth: 281,
    className: "sw-console-layout",
    bgLayoutImgList: [],
    collapsedButtonRender: false,
    defaultCollapsed: false,
    breakpoint: false,
    menuDataRender: () => menuItems,
    menuItemRender: (item, dom) => {
      const content = item.path ? <Link to={item.path}>{dom}</Link> : dom;
      if (
        item.path === "/requirements" &&
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
    actionsRender: false,
    avatarProps: {
      src: user?.avatar,
      title: user?.realName || user?.username || "未登录",
      render: () => (
        <Dropdown
          menu={{
            items: [
              {
                key: "logout",
                icon: <LogoutOutlined />,
                label: "退出登录",
                onClick: () => {
                  deferredStateLoaded.delete(user?.username || "");
                  localStorage.removeItem("token");
                  localStorage.removeItem("user");
                  localStorage.removeItem("refreshToken");
                  history.replace(loginPath);
                },
              },
            ],
          }}
        >
          <Space className="sw-user-menu" size={8}>
            <Avatar size={32} src={user?.avatar}>
              {(user?.realName || user?.username || "U").slice(0, 1)}
            </Avatar>
            <span className="sw-user-copy">
              <strong>{user?.realName || user?.username || "未登录"}</strong>
              <small>{user?.role || "访客"}</small>
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
          `${loginPath}?redirect=${encodeURIComponent(
            history.location.pathname
          )}`
        );
        return;
      }
      if (
        (history.location.pathname === "/key-matters" ||
          history.location.pathname === "/key-matters-meeting" ||
          history.location.pathname.startsWith("/key-matters/")) &&
        initialState?.keyMatterAccess?.canAccess === false
      ) {
        history.replace("/workbench");
        return;
      }
      const requiredAccess = accessForPath(history.location.pathname);
      if (requiredAccess && !hasRoleAccess(user?.role, requiredAccess)) {
        history.replace("/workbench");
      }
    },
  };
};

export const request: RequestConfig = {
  timeout: 15000,
};

export function rootContainer(container: React.ReactNode) {
  return <AntApp>{container}</AntApp>;
}
