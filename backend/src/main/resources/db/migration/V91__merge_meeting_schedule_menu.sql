-- ====================================
-- V91: 「会议与日程」合并菜单（会议/日程降为二级）
-- 1) 顶级菜单「会议与日程」（path=/meeting-schedule，sort=2，与 /sec-* 组同为伪路径分组）
-- 2) 会议(/meetings)、日程(/schedule) 归为其子项（sort 1/2）
-- 3) 父菜单授权五管理角色（与会议/日程既有角色集合一致；子项授权不变，权限 menu_id 不受影响）
-- ====================================

-- 1. 父菜单
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '会议与日程', 'Calendar', '/meeting-schedule', NULL, 2, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/meeting-schedule');

-- 2. 归并子项
UPDATE sys_menu SET parent_id = (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/meeting-schedule') t),
       sort_order = 1
WHERE path = '/meetings';

UPDATE sys_menu SET parent_id = (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/meeting-schedule') t),
       sort_order = 2
WHERE path = '/schedule';

-- 3. 授权（父菜单；子项各自的 sys_role_menu 授权保持不动）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/meeting-schedule'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
