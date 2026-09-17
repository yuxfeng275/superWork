-- ====================================
-- V12: 对齐 2026 年中新岗位体系
-- 管理 4 岗 + 执行 7 岗
-- ====================================

-- 1. 初始化新岗位角色
INSERT INTO sys_role (code, name, description, status, data_scope, data_scope_value) VALUES
('DIRECTOR', '总监', '部门第一负责人，对整体经营结果、能力建设质量负责', 1, 'ALL', NULL),
('DEPUTY_DIRECTOR', '副总监', '协助总监统筹日常运营与专项体系建设', 1, 'ALL', NULL),
('BUSINESS_OWNER', '经营负责人', '业务发展一号位，对业务域经营结果负责', 1, 'ALL', NULL),
('EFFECTIVENESS_OWNER', '成效负责人', '服务支撑一号位，对服务域成效结果负责', 1, 'ALL', NULL),
('SOLUTION_MANAGER', '解决方案经理', '负责客户需求、方案、计划、实施复核与项目交付', 1, 'PROJECT', NULL),
('TECH_ARCHITECT', '技术架构师', '负责技术栈设计、规划、实施、升级与技术赋能', 1, 'PROJECT', NULL),
('FULL_STACK_ENGINEER', '全栈工程师', '负责需求、方案、开发、自测、交付、监控维护完整链路', 1, 'PROJECT', NULL),
('QUALITY_ENGINEER', '质量工程师', '负责测试体系搭建、验收执行与全链路质量保障', 1, 'PROJECT', NULL),
('AI_OPERATIONS_ENGINEER', '智能运营工程师', '负责业务运营、数据分析与 AI 工具落地推广', 1, 'BU_LINE', NULL),
('AI_CUSTOMER_SERVICE', '智能客服专员', '负责全渠道客户咨询、反馈、报修、SLA 与知识库', 1, 'SELF', NULL),
('EXPERIENCE_CONTENT_DESIGNER', '体验与内容设计师', '负责 UI、交互、平面与内容设计交付，沉淀设计规范', 1, 'PROJECT', NULL)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    status = VALUES(status),
    data_scope = VALUES(data_scope),
    data_scope_value = VALUES(data_scope_value);

-- 2. 停用旧岗位角色，保留历史记录
UPDATE sys_role
SET status = 0
WHERE code IN ('BU_ADMIN', 'PM', 'TECH_MANAGER', 'PRODUCT_MANAGER', 'PRODUCT', 'DEVELOPER', 'TESTER', 'UI_DESIGNER', 'UI_DESIGN');

-- 3. 迁移用户主角色
UPDATE user
SET role = CASE role
    WHEN 'BU_ADMIN' THEN 'DIRECTOR'
    WHEN 'PM' THEN 'SOLUTION_MANAGER'
    WHEN 'PRODUCT' THEN 'SOLUTION_MANAGER'
    WHEN 'PRODUCT_MANAGER' THEN 'SOLUTION_MANAGER'
    WHEN 'TECH_MANAGER' THEN 'TECH_ARCHITECT'
    WHEN 'DEVELOPER' THEN 'FULL_STACK_ENGINEER'
    WHEN 'TESTER' THEN 'QUALITY_ENGINEER'
    WHEN 'UI_DESIGN' THEN 'EXPERIENCE_CONTENT_DESIGNER'
    WHEN 'UI_DESIGNER' THEN 'EXPERIENCE_CONTENT_DESIGNER'
    ELSE role
END
WHERE role IN ('BU_ADMIN', 'PM', 'PRODUCT', 'PRODUCT_MANAGER', 'TECH_MANAGER', 'DEVELOPER', 'TESTER', 'UI_DESIGN', 'UI_DESIGNER');

-- 4. 迁移用户-角色关联
DELETE user_role
FROM sys_user_role user_role
JOIN sys_role role ON role.id = user_role.role_id
WHERE role.code IN ('BU_ADMIN', 'PM', 'TECH_MANAGER', 'PRODUCT_MANAGER', 'PRODUCT', 'DEVELOPER', 'TESTER', 'UI_DESIGNER', 'UI_DESIGN');

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, role.id
FROM user u
JOIN sys_role role ON role.code = u.role
WHERE u.role IN (
    'DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER',
    'SOLUTION_MANAGER', 'TECH_ARCHITECT', 'FULL_STACK_ENGINEER', 'QUALITY_ENGINEER',
    'AI_OPERATIONS_ENGINEER', 'AI_CUSTOMER_SERVICE', 'EXPERIENCE_CONTENT_DESIGNER'
);

-- 5. 迁移项目成员内的中文岗位名称
UPDATE project_member
SET role = CASE role
    WHEN 'BU负责人' THEN '经营负责人'
    WHEN 'BU管理员' THEN '经营负责人'
    WHEN '项目经理' THEN '解决方案经理'
    WHEN '产品经理' THEN '解决方案经理'
    WHEN '技术经理' THEN '技术架构师'
    WHEN '前端开发' THEN '全栈工程师'
    WHEN '后端开发' THEN '全栈工程师'
    WHEN '开发' THEN '全栈工程师'
    WHEN '研发' THEN '全栈工程师'
    WHEN '测试' THEN '质量工程师'
    WHEN 'UI设计' THEN '体验与内容设计师'
    ELSE role
END
WHERE role IN ('BU负责人', 'BU管理员', '项目经理', '产品经理', '技术经理', '前端开发', '后端开发', '开发', '研发', '测试', 'UI设计');

-- 6. 迁移工作流配置中的允许角色
UPDATE workflow_config
SET allowed_roles = REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(
    REPLACE(allowed_roles,
        '"BU负责人"', '"经营负责人"'),
        '"BU管理员"', '"经营负责人"'),
        '"项目经理"', '"解决方案经理"'),
        '"产品经理"', '"解决方案经理"'),
        '"技术经理"', '"技术架构师"'),
        '"开发"', '"全栈工程师"'),
        '"研发"', '"全栈工程师"'),
        '"测试"', '"质量工程师"'),
        '"UI设计"', '"体验与内容设计师"'),
        '"系统管理员"', '"总监"')
WHERE allowed_roles IS NOT NULL;

-- 7. 管理序列默认拥有全部菜单与权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.status = 1
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.status = 1
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER');

-- 8. 执行序列默认菜单与权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path IN ('/home', '/requirements', '/tasks', '/base', '/business-lines', '/projects', '/customers', '/statistics')
WHERE role.code IN (
    'SOLUTION_MANAGER', 'TECH_ARCHITECT', 'FULL_STACK_ENGINEER', 'QUALITY_ENGINEER',
    'AI_OPERATIONS_ENGINEER', 'AI_CUSTOMER_SERVICE', 'EXPERIENCE_CONTENT_DESIGNER'
);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list', 'requirement:create', 'requirement:edit', 'requirement:delete',
    'task:list', 'task:create', 'task:edit', 'task:assign',
    'issue:list', 'issue:create', 'issue:edit', 'issue:delete',
    'statistics:view', 'org:view', 'org:edit', 'project:view', 'customer-contact:view'
)
WHERE role.code = 'SOLUTION_MANAGER';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list', 'requirement:edit',
    'task:list', 'task:create', 'task:edit', 'task:assign',
    'issue:list', 'issue:create', 'issue:edit',
    'statistics:view', 'org:view', 'project:view', 'customer-contact:view'
)
WHERE role.code = 'TECH_ARCHITECT';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list',
    'task:list', 'task:create', 'task:edit',
    'issue:list', 'issue:create', 'issue:edit',
    'org:view', 'project:view'
)
WHERE role.code = 'FULL_STACK_ENGINEER';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list',
    'task:list',
    'issue:list', 'issue:create', 'issue:edit', 'issue:delete',
    'org:view', 'project:view', 'customer-contact:view'
)
WHERE role.code = 'QUALITY_ENGINEER';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list', 'requirement:create', 'requirement:edit',
    'task:list',
    'issue:list', 'issue:create', 'issue:edit',
    'statistics:view', 'org:view', 'project:view', 'customer-contact:view'
)
WHERE role.code = 'AI_OPERATIONS_ENGINEER';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list',
    'task:list',
    'issue:list', 'issue:create', 'issue:edit',
    'org:view', 'customer-contact:view'
)
WHERE role.code = 'AI_CUSTOMER_SERVICE';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'requirement:list',
    'task:list', 'task:create', 'task:edit',
    'issue:list',
    'org:view', 'project:view', 'customer-contact:view'
)
WHERE role.code = 'EXPERIENCE_CONTENT_DESIGNER';
