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

vi.mock('@ant-design/icons', () => ({
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
    expect(mockQueryRequirements).toHaveBeenCalledWith({ page: 1, size: 1 });
    expect(mockQueryKeyMatterAccess).toHaveBeenCalled();
    expect(state.requirementTotal).toBe(0);
    expect(state.keyMatterAccess).toEqual({ canAccess: true });
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
});
