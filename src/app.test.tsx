import { beforeEach, describe, expect, it, vi } from 'vitest';

// Mock all heavy dependencies before importing app
const mockReplace = vi.fn();
const mockHistory = {
  location: {
    pathname: '/welcome',
    search: '',
    hash: '',
  },
  replace: mockReplace,
};

const mockQueryCurrentUser = vi.fn();
const mockQueryMenus = vi.fn().mockResolvedValue(undefined);
const mockQueryMenuTree = vi.fn().mockResolvedValue(undefined);
const mockQueryRequirements = vi.fn().mockResolvedValue({ total: 0 });
const mockQueryKeyMatterAccess = vi.fn().mockResolvedValue({ canAccess: true });

vi.mock('@umijs/max', () => ({
  history: mockHistory,
  Link: ({ children }: any) => children,
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getCurrentUser: mockQueryCurrentUser,
    getMyMenus: mockQueryMenus,
    getMyMenuTree: mockQueryMenuTree,
    getRequirements: mockQueryRequirements,
    getKeyMatterAccess: mockQueryKeyMatterAccess,
  },
}));

vi.mock('@/components', () => ({
  AvatarDropdown: () => null,
  DocLink: () => null,
  ErrorBoundary: ({ children }: any) => children,
  Footer: () => null,
  LangDropdown: () => null,
  OfflineBanner: () => null,
  VersionDropdown: () => null,
}));

vi.mock('@ant-design/pro-components', () => ({
  SettingDrawer: () => null,
}));

vi.mock('@ant-design/icons', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@ant-design/icons')>()),
  ApartmentOutlined: () => null,
  BarChartOutlined: () => null,
  BellOutlined: () => null,
  HomeOutlined: () => null,
  LinkOutlined: () => null,
  LogoutOutlined: () => null,
  RiseOutlined: () => null,
  RobotOutlined: () => null,
  SettingOutlined: () => null,
}));

vi.mock('./requestErrorConfig', () => ({
  errorConfig: {},
}));

vi.mock('../config/defaultSettings', () => ({
  default: { navTheme: 'light' },
}));

describe('app getInitialState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockHistory.location = {
      pathname: '/welcome',
      search: '',
      hash: '',
    };
  });

  it('should fetch currentUser when not on login page', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockResolvedValue({
      id: 1,
      username: 'test',
      realName: 'Test User',
      role: 'DIRECTOR',
    });

    const state = await getInitialState();

    expect(mockQueryCurrentUser).toHaveBeenCalled();
    expect(state.currentUser).toEqual({
      id: 1,
      username: 'test',
      realName: 'Test User',
      role: 'DIRECTOR',
    });
    expect(state.settingDrawerOpen).toBe(false);
    expect(state.fetchUserInfo).toBeDefined();
    // 首屏只阻塞菜单必需的两个接口
    expect(mockQueryMenus).toHaveBeenCalled();
    expect(mockQueryMenuTree).toHaveBeenCalled();
    // 需求徽标与重点事项权限延后加载,不阻塞首屏渲染
    expect(mockQueryRequirements).not.toHaveBeenCalled();
    expect(mockQueryKeyMatterAccess).not.toHaveBeenCalled();
    expect(state.requirementTotal).toBeUndefined();
    expect(state.keyMatterAccess).toBeUndefined();
  });

  it('should redirect to login when currentUser fetch fails (401)', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockRejectedValue(new Error('401 Unauthorized'));

    const state = await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining('/user/login?redirect='),
    );
    expect(state.currentUser).toBeUndefined();
  });

  it('should not fetch currentUser on login page', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = {
      pathname: '/user/login',
      search: '',
      hash: '',
    };

    const state = await getInitialState();

    expect(mockQueryCurrentUser).not.toHaveBeenCalled();
    expect(state.currentUser).toBeUndefined();
    expect(state.fetchUserInfo).toBeDefined();
  });

  it('should encode redirect path correctly on 401', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = {
      pathname: '/admin/users',
      search: '?page=2',
      hash: '#section',
    };
    mockQueryCurrentUser.mockRejectedValue(new Error('401'));

    await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/admin/users?page=2#section')}`,
    );
  });

  it('should include default settings in initial state', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockResolvedValue({
      id: 1,
      username: 'user',
      realName: 'User',
      role: 'DIRECTOR',
    });

    const state = await getInitialState();

    expect(state.settings).toEqual({ navTheme: 'light' });
  });

  it('fetchUserInfo should return user data on success', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockResolvedValue({
      id: 2,
      username: 'fetched',
      realName: 'Fetched User',
      role: 'STAFF',
    });

    const state = await getInitialState();

    const user = await state.fetchUserInfo?.();
    expect(user).toEqual({
      id: 2,
      username: 'fetched',
      realName: 'Fetched User',
      role: 'STAFF',
    });
  });

  it('should return cached menu immediately without fetching when cache exists', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockResolvedValue({
      id: 1,
      username: 'cached-user',
      realName: 'Cached',
      role: 'DIRECTOR',
    });
    const cachedTree = [{ id: 1, name: '工作台', path: '/workbench' }];
    localStorage.setItem(
      'sw-menu-cache:cached-user',
      JSON.stringify({
        menuTree: cachedTree,
        menuAuth: { paths: ['/workbench'], managedPaths: ['/workbench'] },
        keyMatterAccess: { canAccess: false },
      }),
    );

    const state = await getInitialState();

    expect(state.menuTree).toEqual(cachedTree);
    expect(state.menuAuth).toEqual({
      paths: ['/workbench'],
      managedPaths: ['/workbench'],
    });
    expect(state.keyMatterAccess).toEqual({ canAccess: false });
    expect(mockQueryMenuTree).not.toHaveBeenCalled();
    expect(mockQueryMenus).not.toHaveBeenCalled();
  });

  it('should write menu cache after fetching menu tree', async () => {
    const { getInitialState } = await import('./app');
    mockQueryCurrentUser.mockResolvedValue({
      id: 2,
      username: 'fresh-user',
      realName: 'Fresh',
      role: 'STAFF',
    });
    const tree = [{ id: 2, name: '销售管理', path: '/sales' }];
    mockQueryMenuTree.mockResolvedValueOnce(tree);
    mockQueryMenus.mockResolvedValueOnce({
      paths: ['/sales'],
      managedPaths: ['/sales'],
    });

    const state = await getInitialState();

    expect(state.menuTree).toEqual(tree);
    const cached = JSON.parse(
      localStorage.getItem('sw-menu-cache:fresh-user') || '{}',
    );
    expect(cached.menuTree).toEqual(tree);
    expect(cached.menuAuth).toEqual({
      paths: ['/sales'],
      managedPaths: ['/sales'],
    });
  });
});
