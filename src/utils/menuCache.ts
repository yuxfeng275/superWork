import type { MenuTreeNode } from '@/services/superwork/api';

export type MenuCacheData = {
  menuAuth?: { paths: string[]; managedPaths: string[] };
  menuTree?: MenuTreeNode[];
  keyMatterAccess?: Record<string, unknown>;
};

const CACHE_PREFIX = 'sw-menu-cache:';

const cacheKey = (username?: string) =>
  `${CACHE_PREFIX}${username || 'anonymous'}`;
export const readMenuCache = (username?: string): MenuCacheData | undefined => {
  try {
    const raw = localStorage.getItem(cacheKey(username));
    return raw ? (JSON.parse(raw) as MenuCacheData) : undefined;
  } catch {
    return undefined;
  }
};

export const writeMenuCache = (
  username: string | undefined,
  data: MenuCacheData,
) => {
  try {
    localStorage.setItem(cacheKey(username), JSON.stringify(data));
  } catch {
    /* 存储失败不影响功能 */
  }
};
