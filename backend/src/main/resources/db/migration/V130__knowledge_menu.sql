-- 侧边栏增加「知识库」(/knowledge)，并沿用商机菜单的授权方式：
-- 注册时把所有已有菜单授权的角色一并授权，保证上线前后可见性不变。
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '知识库', 'BookOutlined', '/knowledge', 'KnowledgeView', 65, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/knowledge');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, menu.id
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.path = '/knowledge';
