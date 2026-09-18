-- ====================================
-- V84: 日程菜单（会议 + 企微日程合并视图）
-- 新增「日程」页：日历视图 + 列表模式，聚合本地会议与企微日程（wecom-cli 日历）。
-- 与「会议管理」并列，权限与角色集合与 V81 保持一致。
-- ====================================

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '日程', 'Calendar', '/schedule', 'ScheduleView', 3, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/schedule');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'schedule:view', '查看日程', '查看日历视图与日程列表（本地会议 + 企微日程）', 'menu', menu.id
FROM sys_menu menu WHERE menu.path = '/schedule'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'schedule:view');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/schedule'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
JOIN sys_permission p ON p.code = 'schedule:view'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
