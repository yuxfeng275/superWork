-- 将侧边栏「线索商机管理」(/opportunities) 注册进 sys_menu，
-- 并分配给所有已有菜单授权的角色，保证注册前后可见性不变。

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '线索商机管理', 'Connection', '/opportunities', 'OpportunityView', 64, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/opportunities'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, menu.id
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.path = '/opportunities';
