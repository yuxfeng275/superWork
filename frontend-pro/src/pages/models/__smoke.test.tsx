import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  getAiModels: vi.fn(),
  getAiConnectors: vi.fn(),
  createAiModel: vi.fn(),
  updateAiModel: vi.fn(),
  deleteAiModel: vi.fn(),
}));

vi.mock('@umijs/max', () => ({ history: { push: mocks.push } }));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getAiModels: mocks.getAiModels,
    getAiConnectors: mocks.getAiConnectors,
    createAiModel: mocks.createAiModel,
    updateAiModel: mocks.updateAiModel,
    deleteAiModel: mocks.deleteAiModel,
  },
}));

import ModelsPage from './index';

const models = [
  {
    id: 1,
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    providerReady: true,
    model: 'deepseek-v4-flash',
    displayName: 'deepseek-v4-flash',
    assistantEnabled: true,
    digestEnabled: false,
    decisionEnabled: false,
    isDefault: true,
    enabled: true,
    sortOrder: 10,
  },
  {
    id: 2,
    providerCode: 'glm',
    providerName: '智谱 GLM',
    providerReady: false,
    model: 'glm-5.3',
    displayName: 'GLM 5.3',
    assistantEnabled: false,
    digestEnabled: true,
    decisionEnabled: false,
    isDefault: false,
    enabled: true,
    sortOrder: 40,
  },
];

describe('ModelsPage smoke', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getAiModels.mockResolvedValue(models);
    mocks.getAiConnectors.mockResolvedValue([
      {
        id: 11,
        code: 'deepseek',
        name: 'DeepSeek',
        authType: 'TOKEN',
        baseUrl: 'https://api.deepseek.com',
        extraConfig: {},
        usernameConfigured: false,
        passwordConfigured: false,
        tokenConfigured: true,
        enabled: true,
        ready: true,
        hint: '',
        builtIn: true,
        sortOrder: 10,
      },
    ]);
    mocks.updateAiModel.mockResolvedValue(models[1]);
    mocks.createAiModel.mockResolvedValue(models[1]);
  });

  it('renders rows, unready hint and sends single-field PUT on switches', async () => {
    render(<ModelsPage />);

    expect(
      (await screen.findAllByText('deepseek-v4-flash')).length,
    ).toBeGreaterThan(0);
    expect(screen.getByText('GLM 5.3')).toBeInTheDocument();
    expect(screen.getByText('连接未就绪')).toBeInTheDocument();
    expect(screen.getByText(/提供方连接未就绪：glm/)).toBeInTheDocument();

    const glmRow = screen
      .getAllByRole('row')
      .find((row) => row.textContent?.includes('glm-5.3'));
    expect(glmRow).toBeTruthy();
    const glmSwitches = within(glmRow as HTMLElement).getAllByRole('switch');
    // 列序：助手可用 / 摘要使用 / 决策 / 默认 / 启用
    expect(glmSwitches).toHaveLength(5);
    expect(mocks.getAiModels).toHaveBeenCalledTimes(1);

    fireEvent.click(glmSwitches[3]);
    await waitFor(() =>
      expect(mocks.updateAiModel).toHaveBeenCalledWith(2, { isDefault: true }),
    );
    await waitFor(() => expect(mocks.getAiModels).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByText('已设为默认模型')).toBeInTheDocument(),
    );
  });

  it('creates a model with displayName defaulting to the model name', async () => {
    render(<ModelsPage />);
    await screen.findAllByText('deepseek-v4-flash');

    fireEvent.click(screen.getByRole('button', { name: /新\s*建\s*模\s*型/ }));
    fireEvent.change(screen.getByPlaceholderText('如 deepseek-v4-flash'), {
      target: { value: 'deepseek-v4' },
    });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() =>
      expect(mocks.createAiModel).toHaveBeenCalledWith({
        providerCode: 'deepseek',
        model: 'deepseek-v4',
        displayName: 'deepseek-v4',
        assistantEnabled: true,
        digestEnabled: false,
        decisionEnabled: false,
        isDefault: false,
        enabled: true,
        sortOrder: 100,
      }),
    );
  });
});
