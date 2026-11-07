/**
 * 项目代码仓库绑定的前端归一与校验（镜像后端 ProjectGitRepoService 的规则）。
 *
 * 纯函数模块：不引 React，便于单测。校验先拦一层，再发请求；
 * 后端仍是最终裁决方（同项目同 repoUrl 只允许绑定一次等规则仍以后端为准）。
 */

/** 后端允许的 agent 白名单。 */
export const GIT_REPO_AGENTS = ['dsh', 'command', 'claude-code', 'codex'] as const;

export type GitRepoAgent = (typeof GIT_REPO_AGENTS)[number];

/** agent 空值时后端默认的 agent。 */
export const DEFAULT_GIT_REPO_AGENT = 'dsh';

/** defaultBranch 空值时后端默认的分支。 */
export const DEFAULT_GIT_REPO_BRANCH = 'main';

/** 仓库地址长度上限。 */
export const MAX_GIT_REPO_URL_LENGTH = 500;

/** agent 下拉选项。 */
export const AGENT_OPTIONS: { label: string; value: GitRepoAgent }[] = [
  { label: 'dsh（编码代理）', value: 'dsh' },
  { label: '侧车命令', value: 'command' },
  { label: 'claude-code', value: 'claude-code' },
  { label: 'codex', value: 'codex' },
];

/** 表单草稿（antd Form 的值）。 */
export interface GitRepoFormValues {
  id?: number;
  name?: string;
  repoUrl?: string;
  defaultBranch?: string;
  workDir?: string;
  agent?: string;
  credentialRef?: string;
  enabled?: boolean;
}

/** 归一后的提交值（字段与后端 RepoRequest 对齐）。 */
export interface GitRepoNormalizedForm {
  id?: number;
  name: string;
  repoUrl: string;
  defaultBranch: string;
  workDir: string | null;
  agent: string;
  credentialRef: string | null;
  /** 开关的布尔值，供 UI / Switch 使用。 */
  enabled: boolean;
  /** 开关的 0/1 表示，供 ProjectGitRepo.enabled 对比使用。 */
  enabledFlag: number;
}

/** 校验错误。 */
export interface GitRepoFormError {
  field: 'repoUrl' | 'agent' | 'workDir';
  message: string;
}

const isBlank = (value?: string | null): boolean =>
  value === null || value === undefined || value.trim() === '';

/** 反斜杠转正斜杠、去掉首尾斜杠（不判断 ..）。 */
export const normalizeWorkDirPath = (raw?: string | null): string => {
  if (raw === null || raw === undefined) return '';
  let dir = raw.trim().replace(/\\/g, '/');
  while (dir.startsWith('/')) dir = dir.slice(1);
  while (dir.endsWith('/')) dir = dir.slice(0, -1);
  return dir;
};

/**
 * 按后端规则归一：trim、workDir 反斜杠转正斜杠并去首尾斜杠（空转 null）、
 * defaultBranch 空转 main、agent 空转 dsh、name 空用 repoUrl。
 * 不做拒绝判断（由 validateGitRepoForm 负责）。
 */
export const normalizeGitRepoForm = (
  values: GitRepoFormValues,
): GitRepoNormalizedForm => {
  const repoUrl = (values.repoUrl ?? '').trim();
  const workDir = normalizeWorkDirPath(values.workDir);
  const agent = isBlank(values.agent)
    ? DEFAULT_GIT_REPO_AGENT
    : (values.agent ?? '').trim().toLowerCase();
  const defaultBranch = isBlank(values.defaultBranch)
    ? DEFAULT_GIT_REPO_BRANCH
    : (values.defaultBranch ?? '').trim();
  const name = isBlank(values.name) ? repoUrl : (values.name ?? '').trim();
  const credentialRef = isBlank(values.credentialRef)
    ? null
    : (values.credentialRef ?? '').trim();
  // Switch 未设置时视为启用（与后端 enabled 缺省 = 1 一致）。
  const enabled = values.enabled !== false;
  return {
    ...(values.id === undefined ? {} : { id: values.id }),
    name,
    repoUrl,
    defaultBranch,
    workDir: workDir === '' ? null : workDir,
    agent,
    credentialRef,
    enabled,
    enabledFlag: enabled ? 1 : 0,
  };
};

/**
 * 校验：覆盖后端会拒绝的几类输入，返回全部错误（顺序即展示顺序）。
 * 错误在发送请求前由调用方拦下。
 */
export const validateGitRepoForm = (
  values: GitRepoFormValues,
): GitRepoFormError[] => {
  const errors: GitRepoFormError[] = [];
  const repoUrl = (values.repoUrl ?? '').trim();

  if (isBlank(values.repoUrl)) {
    errors.push({ field: 'repoUrl', message: '仓库地址不能为空' });
  } else if (!repoUrl.toLowerCase().startsWith('https://')) {
    errors.push({ field: 'repoUrl', message: '只支持 https:// 开头的仓库地址' });
  } else if (repoUrl.includes('@')) {
    errors.push({
      field: 'repoUrl',
      message: '仓库地址不允许包含凭据(@)，请改用 credentialRef',
    });
  } else if (repoUrl.length > MAX_GIT_REPO_URL_LENGTH) {
    errors.push({
      field: 'repoUrl',
      message: `仓库地址过长（上限 ${MAX_GIT_REPO_URL_LENGTH} 字符）`,
    });
  }

  if (!isBlank(values.agent)) {
    const agent = (values.agent ?? '').trim().toLowerCase();
    if (!(GIT_REPO_AGENTS as readonly string[]).includes(agent)) {
      errors.push({
        field: 'agent',
        message: `不支持的 agent：${values.agent}（可选 ${GIT_REPO_AGENTS.join('/')}）`,
      });
    }
  }

  if (normalizeWorkDirPath(values.workDir).includes('..')) {
    errors.push({ field: 'workDir', message: '工作目录不允许包含 ..' });
  }

  return errors;
};
