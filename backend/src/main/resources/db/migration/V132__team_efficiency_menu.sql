-- 侧边栏增加「团队效能」(/team-efficiency)，只授权给能看到「模型管理」的管理角色：
-- 后端同为管理岗位校验，菜单只是入口收口，不作为权限边界。
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '团队效能', 'TeamOutlined', '/team-efficiency', 'TeamEfficiencyView', 66, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/team-efficiency');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT admin_menu.role_id, menu.id
FROM sys_role_menu admin_menu
JOIN sys_menu admin_path ON admin_path.id = admin_menu.menu_id AND admin_path.path = '/system/models'
JOIN sys_menu menu ON menu.path = '/team-efficiency';
