import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyTextToClipboard } from './clipboard';

const setClipboard = (value: unknown) => {
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value });
};

describe('copyTextToClipboard', () => {
  const originalClipboard = navigator.clipboard;
  const originalExecCommand = document.execCommand;

  afterEach(() => {
    setClipboard(originalClipboard);
    document.execCommand = originalExecCommand;
  });

  it('安全上下文优先走 navigator.clipboard.writeText', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });

    const ok = await copyTextToClipboard('hello');

    expect(ok).toBe(true);
    expect(writeText).toHaveBeenCalledWith('hello');
  });

  it('writeText 被拒时回退 execCommand', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    setClipboard({ writeText });
    const execCommand = vi.fn().mockReturnValue(true);
    document.execCommand = execCommand;

    const ok = await copyTextToClipboard('hello');

    expect(ok).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('HTTP 兜底用 copy 事件显式写入文本，不依赖当前选区', async () => {
    setClipboard(undefined);
    const setData = vi.fn();
    const execCommand = vi.fn((command: string) => {
      const event = new Event('copy', { cancelable: true });
      Object.defineProperty(event, 'clipboardData', {
        configurable: true,
        value: { setData },
      });
      document.dispatchEvent(event);
      return command === 'copy';
    });
    document.execCommand = execCommand;

    const payload = ['合同号：CS-A', '客户：甲公司'].join('\n');
    const ok = await copyTextToClipboard(payload);

    expect(ok).toBe(true);
    expect(setData).toHaveBeenCalledWith('text/plain', payload);
  });

  it('execCommand 抛错时返回 false', async () => {
    setClipboard(undefined);
    document.execCommand = vi.fn(() => {
      throw new Error('blocked');
    });

    await expect(copyTextToClipboard('hello')).resolves.toBe(false);
  });
});
