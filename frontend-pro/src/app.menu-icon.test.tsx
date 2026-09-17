import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@umijs/max', () => ({
  history: { location: { pathname: '/' }, replace: vi.fn() },
  Link: ({ children }: { children: React.ReactNode }) => children,
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {},
}));

vi.mock('@/components', () => ({
  AvatarDropdown: () => null,
  DocLink: () => null,
  ErrorBoundary: ({ children }: { children: React.ReactNode }) => children,
  Footer: () => null,
  LangDropdown: () => null,
  OfflineBanner: () => null,
  VersionDropdown: () => null,
}));

vi.mock('@ant-design/pro-components', () => ({
  SettingDrawer: () => null,
}));

vi.mock('./requestErrorConfig', () => ({
  errorConfig: {},
}));

vi.mock('../config/defaultSettings', () => ({
  default: { navTheme: 'light' },
}));

import { mapServerMenu } from './app';

describe('menu icons', () => {
  it('keeps home as a house and workbench as an app grid', () => {
    const items = mapServerMenu([
      {
        id: 1,
        name: '工作台',
        icon: 'HomeFilled',
        children: [
          { id: 11, name: '首页', icon: 'HomeFilled', path: '/workbench' },
          { id: 2, name: '需求管理', path: '/requirements' },
          { id: 3, name: '任务管理', path: '/tasks' },
        ],
      },
    ]);

    expect(items[0]?.name).toBe('首页');
    expect(items[1]?.name).toBe('工作台');

    const { container: home } = render(<>{items[0]?.icon}</>);
    expect(home.querySelector('[data-icon="home"]')).not.toBeNull();
    expect(home.querySelector('[data-icon="appstore"]')).toBeNull();

    const { container: workbench } = render(<>{items[1]?.icon}</>);
    expect(workbench.querySelector('[data-icon="appstore"]')).not.toBeNull();
    expect(workbench.querySelector('[data-icon="home"]')).toBeNull();
  });
});
