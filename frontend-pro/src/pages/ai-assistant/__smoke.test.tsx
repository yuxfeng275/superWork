import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getAiAgentSessions: vi.fn(),
  getAiAgentModels: vi.fn(),
  getAiAgentConnectors: vi.fn(),
}));

vi.mock('@/services/superwork/api', () => ({
  superworkApi: {
    getAiAgentSessions: mocks.getAiAgentSessions,
    getAiAgentModels: mocks.getAiAgentModels,
    getAiAgentConnectors: mocks.getAiAgentConnectors,
  },
}));

import AiAssistantPage from './index';

describe('AI assistant Ant Design X migration', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getAiAgentSessions.mockResolvedValue([
      { id: 9, title: '工时分析', messageCount: 4 },
    ]);
    mocks.getAiAgentModels.mockResolvedValue([]);
    mocks.getAiAgentConnectors.mockResolvedValue([]);
  });

  it('uses Conversations for the session rail', async () => {
    const source = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'),
      'utf8',
    );
    expect(source).toMatch(/from '@ant-design\/x'/);
    expect(source).toMatch(/<Conversations/);
    expect(source).toMatch(/<Bubble.List/);
    expect(source).toMatch(/<Sender/);
    expect(source).toMatch(/<Welcome/);
    expect(source).toMatch(/<Prompts/);
    expect(source).toMatch(/<ThoughtChain/);
    expect(source).toMatch(/<Suggestion/);
    expect(source).toMatch(/<Attachments/);
    expect(source).not.toMatch(/sw-ai-session-list/);
    expect(source).not.toMatch(/Input.TextArea/);

    render(<AiAssistantPage />);
    expect(await screen.findByText('工时分析')).toBeInTheDocument();
    expect(
      document.querySelector('.ant-conversations-creation'),
    ).not.toBeNull();
  });
});
