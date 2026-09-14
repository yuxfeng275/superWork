export default function access(initialState?: {
  currentUser?: Record<string, unknown>;
}) {
  const role =
    typeof initialState?.currentUser?.role === 'string'
      ? initialState.currentUser.role
      : typeof initialState?.currentUser?.access === 'string'
        ? initialState.currentUser.access
        : undefined;
  return {
    canAccessSystem: Boolean(initialState?.currentUser),
    canManage: role === 'admin' || role === '系统管理员',
    canAdmin: role === 'admin',
  };
}
