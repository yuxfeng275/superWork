-- V71: 三级菜单重构
-- 新增二级分组菜单，重新归类现有菜单

-- ============ 1. 新增二级分组菜单 ============

-- 销售管理 → 商机管理 (parent=22)
INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT 22, '商机管理', 'Connection', '', 1, 1, 1
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=22 AND name='商机管理');

-- 销售管理 → 报价管理 (parent=22)
INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT 22, '报价管理', 'Document', '', 2, 1, 1
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=22 AND name='报价管理');

-- 系统 → 权限与人员 (parent=7)
INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT 7, '权限与人员', 'Lock', '', 1, 1, 1
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=7 AND name='权限与人员');

-- 系统 → 系统配置 (parent=7)
INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT 7, '系统配置', 'Setting', '', 2, 1, 1
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=7 AND name='系统配置');


-- ============ 2. 重新设定 parent_id 归属 ============

-- 销售管理 → 商机管理
UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=22 AND name='商机管理') AS t), sort_order = 1
WHERE id = 19;  -- 线索商机管理

-- 销售管理 → 报价管理
UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=22 AND name='报价管理') AS t), sort_order = 1
WHERE id = 28;  -- 报价策略管理

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=22 AND name='报价管理') AS t), sort_order = 2
WHERE id = 29;  -- 报价单管理

-- 系统 → 权限与人员
UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='权限与人员') AS t), sort_order = 1
WHERE id = 8;   -- 用户管理

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='权限与人员') AS t), sort_order = 2
WHERE id = 9;   -- 角色管理

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='权限与人员') AS t), sort_order = 3
WHERE id = 10;  -- 菜单管理

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='权限与人员') AS t), sort_order = 4
WHERE id = 11;  -- 权限管理

-- 系统 → 系统配置
UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='系统配置') AS t), sort_order = 1
WHERE id = 12;  -- 工作流配置

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='系统配置') AS t), sort_order = 2
WHERE id = 18;  -- 配置管理

UPDATE sys_menu SET parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE parent_id=7 AND name='系统配置') AS t), sort_order = 3
WHERE id = 26;  -- AI连接器


-- ============ 3. 重命名 ============

UPDATE sys_menu SET name = '基础数据' WHERE id = 13;  -- 原"基础分类"


-- ============ 4. 修复权限管理的 visible ============

UPDATE sys_menu SET visible = 1 WHERE id = 11;  -- 权限管理显示