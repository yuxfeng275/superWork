import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type * as SuperworkApiModule from '@/services/superwork/api';

const mocks = vi.hoisted(() => ({
  getOaSessionStatus: vi.fn(),
  authorizeOaSession: vi.fn(),
  clearOaSession: vi.fn(),
  getOaPendingAffairs: vi.fn(),
  getOaDoneAffairs: vi.fn(),
  approveOaAffair: vi.fn(),
  batchApproveOaAffairs: vi.fn(),
}));

vi.mock('@/services/superwork/api', async (importOriginal) => {
  const actual = await importOriginal<typeof SuperworkApiModule>();
  return { ...actual, superworkApi: mocks };
});

import { ApiRequestError } from '@/services/superwork/api';
import OaAffairsPage from './index';

const pendingAffairs = [
  {
    id: 'A-1',
    subject: '报销单-张三',
    senderName: '张三',
    createDate: '2026-09-17 09:30',
    appName: '费用报销',
  },
  {
    id: 'A-2',
    subject: '请假申请-李四',
    senderName: '李四',
    createDate: '2026-09-17 10:00',
    appName: '请假',
  },
];

const renderPage = () =>
  render(
    <App>
      <OaAffairsPage />
    </App>,
  );

describe('OaAffairsPage smoke', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getOaDoneAffairs.mockResolvedValue([]);
    mocks.clearOaSession.mockResolvedValue(undefined);
  });

  it('引导未授权用户先完成网页会话授权，不请求待办列表', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请粘贴 JSESSIONID',
    });

    renderPage();

    expect(await screen.findByText('未授权')).toBeInTheDocument();
    expect(screen.getByText('尚未完成 OA 网页会话授权')).toBeInTheDocument();
    expect(mocks.getOaPendingAffairs).not.toHaveBeenCalled();
    expect(mocks.getOaDoneAffairs).not.toHaveBeenCalled();
  });

  it('已授权时展示待办，批量同意后逐项展示审批结果', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: true,
      hint: '已授权（网页会话）',
    });
    mocks.getOaPendingAffairs.mockResolvedValue(pendingAffairs);
    mocks.batchApproveOaAffairs.mockResolvedValue([
      { affairId: 'A-1', success: true, result: '已执行：同意' },
      {
        affairId: 'A-2',
        success: false,
        result: '事项详情页未解析到可用审批动作',
      },
    ]);

    renderPage();

    expect(await screen.findByText('报销单-张三')).toBeInTheDocument();
    expect(screen.getByText('请假申请-李四')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /全\s*选/ }));
    fireEvent.click(screen.getByRole('button', { name: /批量同意/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认同意' }));

    await waitFor(() =>
      expect(mocks.batchApproveOaAffairs).toHaveBeenCalledWith(
        ['A-1', 'A-2'],
        'approve',
      ),
    );
    expect(await screen.findByText('已执行：同意')).toBeInTheDocument();
    expect(
      screen.getByText('事项详情页未解析到可用审批动作'),
    ).toBeInTheDocument();
    expect(screen.getByText('成功 1')).toBeInTheDocument();
    expect(screen.getByText('失败 1')).toBeInTheDocument();
  });

  it('列表加载遇到 400 会话失效时弹出授权弹窗', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: true,
      hint: '已授权（网页会话）',
    });
    mocks.getOaPendingAffairs.mockRejectedValue(
      new ApiRequestError(
        'OA 会话已失效，请在连接器管理 → OA（致远）重新粘贴 JSESSIONID 授权',
        400,
      ),
    );

    renderPage();

    expect(await screen.findByText('OA 网页会话授权')).toBeInTheDocument();
    expect(screen.getByText('尚未完成 OA 网页会话授权')).toBeInTheDocument();
  });
});
