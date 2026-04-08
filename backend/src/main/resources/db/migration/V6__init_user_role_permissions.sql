-- ====================================
-- V6: 初始化用户角色关联和角色权限数据
-- 修复：V4只分配了admin用户的角色，其他测试用户无角色导致全部403
-- ====================================

-- 为测试用户分配角色（跳过admin，V4已处理）
-- user_id: admin=1, pm_zhang=2, tech_li=3, product_wang=4, dev_zhao=5, dev_qian=6, test_sun=7, ui_zhou=8
-- role_id: BU_ADMIN=1, PM=2, TECH_MANAGER=3, PRODUCT_MANAGER=4, DEVELOPER=5, TESTER=6, UI_DESIGNER=7
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
(2, 2),  -- pm_zhang → PM
(3, 3),  -- tech_li → TECH_MANAGER
(4, 4),  -- product_wang → PRODUCT_MANAGER
(5, 5),  -- dev_zhao → DEVELOPER
(6, 5),  -- dev_qian → DEVELOPER
(7, 6),  -- test_sun → TESTER
(8, 7);  -- ui_zhou → UI_DESIGNER

-- ====================================
-- 为各角色分配权限
-- 权限code → permission_id 通过子查询获取，避免硬编码ID
-- ====================================

-- PM（项目经理）：需求、任务、事项的全权限 + 统计查看 + 组织查看
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission WHERE code IN (
    'requirement:list', 'requirement:create', 'requirement:edit', 'requirement:delete',
    'task:list', 'task:create', 'task:edit', 'task:assign',
    'issue:list', 'issue:create', 'issue:edit', 'issue:delete',
    'statistics:view', 'org:view'
);

-- TECH_MANAGER（技术经理）：与PM相同
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE code IN (
    'requirement:list', 'requirement:create', 'requirement:edit', 'requirement:delete',
    'task:list', 'task:create', 'task:edit', 'task:assign',
    'issue:list', 'issue:create', 'issue:edit', 'issue:delete',
    'statistics:view', 'org:view'
);

-- PRODUCT_MANAGER（产品经理）：需求全权限 + 事项查看/创建 + 统计 + 组织
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE code IN (
    'requirement:list', 'requirement:create', 'requirement:edit', 'requirement:delete',
    'issue:list', 'issue:create', 'issue:edit',
    'task:list',
    'statistics:view', 'org:view'
);

-- DEVELOPER（研发）：需求查看 + 任务全权限 + 事项创建/编辑 + 组织查看
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE code IN (
    'requirement:list',
    'task:list', 'task:create', 'task:edit',
    'issue:list', 'issue:create', 'issue:edit',
    'org:view'
);

-- TESTER（测试）：需求查看 + 任务查看 + 事项全权限 + 组织查看
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 6, id FROM sys_permission WHERE code IN (
    'requirement:list',
    'task:list',
    'issue:list', 'issue:create', 'issue:edit', 'issue:delete',
    'org:view'
);

-- UI_DESIGNER（UI设计）：需求查看 + 任务查看/创建/编辑 + 组织查看
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 7, id FROM sys_permission WHERE code IN (
    'requirement:list',
    'task:list', 'task:create', 'task:edit',
    'issue:list',
    'org:view'
);
