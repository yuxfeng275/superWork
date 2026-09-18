import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type * as SuperworkApiModule from '@/services/superwork/api';

const mocks = vi.hoisted(() => ({
  getOaSessionStatus: vi.fn(),
  authorizeOaSession: vi.fn(),
  autoOaLogin: vi.fn(),
  getOaCaptcha: vi.fn(),
  loginOaSession: vi.fn(),
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

const captcha = (challengeId: string, imageBase64: string) => ({
  challengeId,
  imageBase64,
  expireAt: Date.now() + 5 * 60 * 1000,
});

const renderPage = () =>
  render(
    <App>
      <OaAffairsPage />
    </App>,
  );

/** 等待会话状态就绪后点「去授权」，弹窗打开即会自动尝试授权。 */
const openAuthModal = async () => {
  await screen.findByText('尚未完成 OA 网页会话授权');
  fireEvent.click(screen.getAllByRole('button', { name: '去授权' })[0]);
  return screen.findByText('OA 网页会话授权');
};

const submitCaptcha = async (code: string) => {
  fireEvent.change(await screen.findByPlaceholderText(/输入图中验证码/), {
    target: { value: code },
  });
  fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));
};

describe('OaAffairsPage smoke', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mocks.getOaDoneAffairs.mockResolvedValue([]);
    mocks.clearOaSession.mockResolvedValue(undefined);
    mocks.autoOaLogin.mockResolvedValue({
      authorized: false,
      hint: 'OA 需要验证码登录，请在授权弹窗输入验证码（或粘贴 JSESSIONID）',
    });
    mocks.getOaCaptcha.mockResolvedValue(captcha('c-1', 'IMG-1'));
  });

  it('引导未授权用户先完成网页会话授权，不请求待办列表', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请完成授权',
    });

    renderPage();

    expect(await screen.findByText('未授权')).toBeInTheDocument();
    expect(screen.getByText('尚未完成 OA 网页会话授权')).toBeInTheDocument();
    expect(mocks.getOaPendingAffairs).not.toHaveBeenCalled();
    expect(mocks.getOaDoneAffairs).not.toHaveBeenCalled();
  });

  it('打开授权弹窗先自动授权，成功即标记已授权并加载待办', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请完成授权',
    });
    mocks.autoOaLogin.mockResolvedValue({
      authorized: true,
      hint: '授权成功，OA 网页会话已生效',
    });
    mocks.getOaPendingAffairs.mockResolvedValue(pendingAffairs);

    renderPage();
    await openAuthModal();

    expect(await screen.findByText('已授权')).toBeInTheDocument();
    expect(await screen.findByText('报销单-张三')).toBeInTheDocument();
    expect(mocks.autoOaLogin).toHaveBeenCalledTimes(1);
    expect(mocks.getOaCaptcha).not.toHaveBeenCalled();
  });

  it('自动授权要求验证码时展示验证码图，刷新后按新挑战登录', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请完成授权',
    });
    mocks.getOaCaptcha
      .mockResolvedValueOnce(captcha('c-1', 'IMG-1'))
      .mockResolvedValueOnce(captcha('c-2', 'IMG-2'));
    mocks.loginOaSession.mockResolvedValue({
      authorized: true,
      hint: '授权成功，OA 网页会话已生效',
    });
    mocks.getOaPendingAffairs.mockResolvedValue(pendingAffairs);

    renderPage();
    await openAuthModal();

    expect(
      await screen.findByText(
        'OA 需要验证码登录，请在授权弹窗输入验证码（或粘贴 JSESSIONID）',
      ),
    ).toBeInTheDocument();
    expect(await screen.findByAltText('OA 登录验证码')).toHaveAttribute(
      'src',
      'data:image/png;base64,IMG-1',
    );

    fireEvent.click(screen.getByRole('button', { name: /刷新验证码/ }));
    await waitFor(() =>
      expect(screen.getByAltText('OA 登录验证码')).toHaveAttribute(
        'src',
        'data:image/png;base64,IMG-2',
      ),
    );

    await submitCaptcha('8888');

    await waitFor(() =>
      expect(mocks.loginOaSession).toHaveBeenCalledWith({
        challengeId: 'c-2',
        captcha: '8888',
      }),
    );
    expect(await screen.findByText('报销单-张三')).toBeInTheDocument();
    expect(mocks.authorizeOaSession).not.toHaveBeenCalled();
  });

  it('验证码登录失败时内联展示后端提示并换一张验证码', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请完成授权',
    });
    mocks.loginOaSession.mockRejectedValue(
      new ApiRequestError(
        'OA 登录失败（loginerror=11）：登录参数不完整（账号/密码字段未按网页表单提交）',
        400,
      ),
    );

    renderPage();
    await openAuthModal();
    await screen.findByAltText('OA 登录验证码');

    await submitCaptcha('0000');

    expect(
      await screen.findByText(
        'OA 登录失败（loginerror=11）：登录参数不完整（账号/密码字段未按网页表单提交）',
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(mocks.getOaCaptcha).toHaveBeenCalledTimes(2));
    expect(screen.getByPlaceholderText(/输入图中验证码/)).toHaveValue('');
  });

  it('验证码不可用时展开兜底区，粘贴 JSESSIONID 完成授权', async () => {
    mocks.getOaSessionStatus.mockResolvedValue({
      authorized: false,
      hint: '未授权：请完成授权',
    });
    mocks.autoOaLogin.mockResolvedValue({
      authorized: false,
      hint: '自动登录失败：OA 集成未配置账号密码，请先在连接器管理补全',
    });
    mocks.getOaCaptcha.mockRejectedValue(
      new ApiRequestError('OA 登录页不可达，请检查服务地址', 500),
    );
    mocks.authorizeOaSession.mockResolvedValue({
      authorized: true,
      hint: '授权成功，OA 网页会话已生效',
    });
    mocks.getOaPendingAffairs.mockResolvedValue(pendingAffairs);

    renderPage();
    await openAuthModal();

    expect(
      await screen.findByText(/验证码加载失败：OA 登录页不可达/),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByText('兜底：粘贴 JSESSIONID 授权'));
    fireEvent.change(
      await screen.findByPlaceholderText(/粘贴 JSESSIONID 的值/),
      {
        target: { value: ' JSESSIONID-ABC ' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: '保存授权' }));

    await waitFor(() =>
      expect(mocks.authorizeOaSession).toHaveBeenCalledWith('JSESSIONID-ABC'),
    );
    expect(await screen.findByText('报销单-张三')).toBeInTheDocument();
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
    expect(mocks.autoOaLogin).not.toHaveBeenCalled();
  });

  it('列表加载遇到 400 会话失效时弹出授权弹窗并自动尝试授权', async () => {
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
    await waitFor(() => expect(mocks.autoOaLogin).toHaveBeenCalledTimes(1));
  });
});
