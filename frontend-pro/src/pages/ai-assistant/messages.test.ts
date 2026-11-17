import { describe, expect, it } from 'vitest';
import { extractMessageText, extractMessageThinking } from './messages';

describe('assistant message parts', () => {
  it('keeps thinking out of the visible text', () => {
    const content = [
      { type: 'thinking', thinking: 'The tool only allows one status. Let me query all.' },
      { type: 'text', text: '你当前未完成的任务有 2 条。' },
      { type: 'toolCall', name: 'query_my_tasks', arguments: '{}' },
    ];
    expect(extractMessageText(content)).toBe('你当前未完成的任务有 2 条。');
    expect(extractMessageThinking(content)).toBe(
      'The tool only allows one status. Let me query all.',
    );
  });

  it('accepts legacy string content as text', () => {
    expect(extractMessageText('纯文本')).toBe('纯文本');
    expect(extractMessageThinking('纯文本')).toBe('');
  });

  it('returns empty text when the message only has thinking/tool parts', () => {
    expect(extractMessageText([{ type: 'thinking', thinking: '推理' }])).toBe('');
    expect(extractMessageThinking([{ type: 'text', text: '正文' }])).toBe('');
  });
});
