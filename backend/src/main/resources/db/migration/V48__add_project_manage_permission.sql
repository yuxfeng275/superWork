-- ====================================
-- V48: 项目管理写权限收紧
-- 新增 project:manage 权限，仅授予管理序列角色（DIRECTOR/DEPUTY_DIRECTOR/
-- BUSINESS_OWNER/EFFECTIVENESS_OWNER/BU_ADMIN）。项目管理（项目、业务线、
-- 项目成员）的写操作从 org:edit 收紧到 project:manage，其他人员只读。
-- ====================================

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'project:manage', '维护项目管理', '创建、编辑、删除项目及管理项目成员', 'button', menu.id
FROM sys_menu menu WHERE menu.path = '/projects'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'project:manage');

-- 管理序列角色（含 BU_ADMIN）授予 project:manage
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'project:manage'
WHERE role.code IN (
    'DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN'
);

-- 回收执行序列角色误持有的 project:manage（防御性，正常不存在）
DELETE rp
FROM sys_role_permission rp
JOIN sys_permission permission ON permission.id = rp.permission_id
JOIN sys_role role ON role.id = rp.role_id
WHERE permission.code = 'project:manage'
  AND role.code IN (
    'SOLUTION_MANAGER', 'TECH_ARCHITECT', 'FULL_STACK_ENGINEER', 'QUALITY_ENGINEER',
    'AI_OPERATIONS_ENGINEER', 'AI_CUSTOMER_SERVICE', 'EXPERIENCE_CONTENT_DESIGNER'
  );

-- 回收解决方案经理历史持有的 org:edit（项目写权限已收紧到 project:manage）
DELETE rp
FROM sys_role_permission rp
JOIN sys_permission permission ON permission.id = rp.permission_id
JOIN sys_role role ON role.id = rp.role_id
WHERE permission.code = 'org:edit'
  AND role.code = 'SOLUTION_MANAGER';
