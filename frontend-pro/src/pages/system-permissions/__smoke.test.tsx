import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getPermissions: vi.fn(),
  getMenus: vi.fn(),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getPermissions: mocks.getPermissions,
    getMenus: mocks.getMenus,
  },
}));

import SystemPermissionsPage from './index';

describe('SystemPermissionsPage smoke', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getPermissions.mockResolvedValue([
      {
        id: 1,
        code: 'system:permission:list',
        name: '查看权限列表',
        description: '查看权限列表',
        type: 'menu',
        menuId: 11,
      },
      {
        id: 2,
        code: 'system:permission:assign',
        name: '分配权限',
        description: '为角色分配权限',
        type: 'button',
        menuId: 11,
      },
    ]);
    mocks.getMenus.mockResolvedValue([{ id: 11, name: '权限管理', path: '/system/permissions' }]);
  });

  it('renders permission rows and filters by keyword', async () => {
    render(<SystemPermissionsPage />);
    expect(await screen.findByText('查看权限列表')).toBeInTheDocument();
    expect(screen.getByText('分配权限')).toBeInTheDocument();
    expect(screen.getByText('system:permission:list')).toBeInTheDocument();
    expect(screen.getAllByText('权限管理').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByPlaceholderText('名称 / 编码 / 说明'), {
      target: { value: '分配' },
    });
    fireEvent.click(screen.getByRole('button', { name: '查询' }));

    expect(screen.queryByText('查看权限列表')).not.toBeInTheDocument();
    expect(screen.getByText('分配权限')).toBeInTheDocument();
  });
});
