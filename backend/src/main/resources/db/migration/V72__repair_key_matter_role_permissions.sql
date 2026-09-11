-- V72: 补齐新增执行序列角色的大事儿访问权限
-- V33 在新岗位角色落库前执行，FULL_STACK_ENGINEER 未继承查看与反馈权限。
-- 仅补齐普通访问能力，不授予全量管理权限。

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission
  ON permission.code IN ('bu:key-matter:view', 'bu:key-matter:feedback')
WHERE role.code = 'FULL_STACK_ENGINEER'
  AND role.status = 1;
