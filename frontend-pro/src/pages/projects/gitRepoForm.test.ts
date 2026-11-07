import { describe, expect, it } from 'vitest';
import {
  AGENT_OPTIONS,
  normalizeGitRepoForm,
  validateGitRepoForm,
} from './gitRepoForm';

const messagesOf = (repoUrl: string, extra: Record<string, string> = {}) =>
  validateGitRepoForm({ repoUrl, ...extra }).map((error) => error.message);

describe('validateGitRepoForm', () => {
  it('拒绝非 https 开头的仓库地址', () => {
    expect(messagesOf('http://git.example.com/a.git')).toEqual([
      '只支持 https:// 开头的仓库地址',
    ]);
    expect(messagesOf('git@example.com:a/b.git')).toEqual([
      '只支持 https:// 开头的仓库地址',
    ]);
  });

  it('拒绝包含凭据 @ 的仓库地址', () => {
    expect(messagesOf('https://user@example.com/a.git')).toEqual([
      '仓库地址不允许包含凭据(@)，请改用 credentialRef',
    ]);
  });

  it('拒绝超过 500 字符的仓库地址', () => {
    const longUrl = `https://example.com/${'a'.repeat(500)}.git`;
    expect(longUrl.length).toBeGreaterThan(500);
    expect(messagesOf(longUrl)).toEqual(['仓库地址过长（上限 500 字符）']);
  });

  it('拒绝空仓库地址', () => {
    expect(messagesOf('')).toEqual(['仓库地址不能为空']);
    expect(messagesOf('   ')).toEqual(['仓库地址不能为空']);
  });

  it('合法的 https 地址不产生错误', () => {
    expect(validateGitRepoForm({ repoUrl: 'https://git.example.com/a/b.git' })).toEqual(
      [],
    );
  });

  it('拒绝含 .. 的工作目录（反斜杠同样拦截）', () => {
    expect(messagesOf('https://git.example.com/a.git', { workDir: '../etc' })).toEqual(
      ['工作目录不允许包含 ..'],
    );
    expect(
      messagesOf('https://git.example.com/a.git', { workDir: 'sql\\..\\x' }),
    ).toEqual(['工作目录不允许包含 ..']);
  });

  it('拒绝白名单之外的 agent，白名单内不报错', () => {
    expect(
      validateGitRepoForm({
        repoUrl: 'https://git.example.com/a.git',
        agent: 'gpt-agent',
      }),
    ).toEqual([
      { field: 'agent', message: '不支持的 agent：gpt-agent（可选 dsh/command/claude-code/codex）' },
    ]);
    expect(
      validateGitRepoForm({
        repoUrl: 'https://git.example.com/a.git',
        agent: 'claude-code',
      }),
    ).toEqual([]);
  });
});

describe('normalizeGitRepoForm', () => {
  it('workDir 去掉首尾斜杠并把反斜杠转成正斜杠', () => {
    const normalized = normalizeGitRepoForm({
      repoUrl: 'https://git.example.com/a.git',
      workDir: '\\sql/scripts/',
    });
    expect(normalized.workDir).toBe('sql/scripts');
  });

  it('workDir 空串转 null', () => {
    expect(
      normalizeGitRepoForm({
        repoUrl: 'https://git.example.com/a.git',
        workDir: '   ',
      }).workDir,
    ).toBeNull();
    expect(
      normalizeGitRepoForm({ repoUrl: 'https://git.example.com/a.git' }).workDir,
    ).toBeNull();
  });

  it('agent 为空默认 dsh，并统一小写', () => {
    expect(
      normalizeGitRepoForm({ repoUrl: 'https://git.example.com/a.git' }).agent,
    ).toBe('dsh');
    expect(
      normalizeGitRepoForm({
        repoUrl: 'https://git.example.com/a.git',
        agent: '  Codex ',
      }).agent,
    ).toBe('codex');
  });

  it('defaultBranch 为空默认 main，name 为空回落到 repoUrl', () => {
    const normalized = normalizeGitRepoForm({
      repoUrl: '  https://git.example.com/a.git ',
      name: '  ',
    });
    expect(normalized.defaultBranch).toBe('main');
    expect(normalized.name).toBe('https://git.example.com/a.git');
    expect(normalized.repoUrl).toBe('https://git.example.com/a.git');
  });

  it('enabled 布尔值与 0/1 标记同步，缺省视为启用', () => {
    expect(normalizeGitRepoForm({ repoUrl: 'https://a.com/x.git' }).enabled).toBe(true);
    expect(
      normalizeGitRepoForm({ repoUrl: 'https://a.com/x.git', enabled: true }).enabledFlag,
    ).toBe(1);
    expect(
      normalizeGitRepoForm({ repoUrl: 'https://a.com/x.git', enabled: false }).enabledFlag,
    ).toBe(0);
  });

  it('AGENT_OPTIONS 覆盖后端 agent 白名单', () => {
    expect(AGENT_OPTIONS.map((option) => option.value)).toEqual([
      'dsh',
      'command',
      'claude-code',
      'codex',
    ]);
  });
});
