-- ====================================
-- V57: 侧边栏菜单全量动态化
-- 1) 新增分区组（工作台/销售管理/数据分析），既有页面挂载到分区
-- 2) 补注册缺失页面（AI助手/营收管理/AI连接器），修正图标与命名
-- 3) 按当前前端硬编码可见性等价种子授权，此后角色管理-菜单授权为唯一权威
-- ====================================

-- 1. 分区组（path 以 /sec- 前缀伪路径标识，前端不跳转）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '工作台', 'HomeFilled', '/sec-work', NULL, 1, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/sec-work');
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '销售管理', 'Connection', '/sec-sales', NULL, 4, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/sec-sales');
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '数据分析', 'DataAnalysis', '/sec-analytics', NULL, 5, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/sec-analytics');

-- 基础分类组排序对齐（既有 id=13，path=/base）
UPDATE sys_menu SET sort_order = 6 WHERE path = '/base' AND parent_id = 0;
-- 系统管理组：命名与侧边栏一致
UPDATE sys_menu SET name = '系统', sort_order = 100 WHERE path = '/system' AND parent_id = 0;

-- 2. 既有页面挂载到分区 + 排序/图标/命名对齐侧边栏
UPDATE sys_menu SET parent_id = (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-work') t)
WHERE parent_id = 0 AND path IN ('/home', '/requirements', '/tasks', '/defects', '/emails', '/key-matters');
UPDATE sys_menu SET parent_id = (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-sales') t)
WHERE parent_id = 0 AND path = '/opportunities';
UPDATE sys_menu SET parent_id = (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t)
WHERE parent_id = 0 AND path IN ('/statistics', '/kpi-report');

UPDATE sys_menu SET icon = 'HomeFilled', sort_order = 1 WHERE path = '/home';
UPDATE sys_menu SET sort_order = 2 WHERE path = '/requirements';
UPDATE sys_menu SET icon = 'Finished', sort_order = 3 WHERE path = '/tasks';
UPDATE sys_menu SET sort_order = 4 WHERE path = '/defects';
UPDATE sys_menu SET sort_order = 5 WHERE path = '/emails';
UPDATE sys_menu SET sort_order = 7 WHERE path = '/key-matters';
UPDATE sys_menu SET sort_order = 1 WHERE path = '/opportunities';
UPDATE sys_menu SET name = 'BU驾驶舱', icon = 'DataAnalysis', sort_order = 1 WHERE path = '/statistics';
UPDATE sys_menu SET sort_order = 3 WHERE path = '/kpi-report';
UPDATE sys_menu SET icon = 'Lock' WHERE path = '/system/roles';
UPDATE sys_menu SET icon = 'Connection' WHERE path = '/system/workflow';

-- 3. 补注册缺失页面
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-work') t),
       'AI助手', 'ChatDotRound', '/ai-assistant', 'AiAssistantView', 6, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ai-assistant');

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t),
       '营收管理', 'Coin', '/revenue', 'RevenueView', 2, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue');

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/system') t),
       'AI连接器', 'Connection', '/ai-connectors', 'AiConnectorManageView', 107, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ai-connectors');

-- 4. 授权种子（等价当前前端硬编码可见性）
-- 4.1 全员通用：工作台及其页面、基础分类组+业务线管理、销售管理组+线索商机、大事儿管理
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path IN (
    '/sec-work', '/home', '/requirements', '/tasks', '/defects', '/emails', '/ai-assistant', '/key-matters',
    '/base', '/business-lines',
    '/sec-sales', '/opportunities'
);

-- 4.2 项目管理（原 access=project 集合）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/projects'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN',
                 'SOLUTION_MANAGER', 'TECH_ARCHITECT', 'FULL_STACK_ENGINEER',
                 'QUALITY_ENGINEER', 'AI_OPERATIONS_ENGINEER', 'EXPERIENCE_CONTENT_DESIGNER');

-- 4.3 客户信息管理（原 access=customer 集合）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/customers'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN',
                 'SOLUTION_MANAGER', 'TECH_ARCHITECT', 'QUALITY_ENGINEER',
                 'AI_OPERATIONS_ENGINEER', 'AI_CUSTOMER_SERVICE', 'EXPERIENCE_CONTENT_DESIGNER');

-- 4.4 管理角色全量（数据分析组+营收/KPI/BU驾驶舱、系统组及全部子项等）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
