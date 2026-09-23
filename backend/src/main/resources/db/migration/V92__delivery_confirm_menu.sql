-- ====================================
-- V92: 「待交付确认」子菜单（数据 组下，与 工时&成本/交付与利润 并列）
-- 角色授权复制自「工时 & 成本」(menu_id=34)。
-- ====================================

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 23, '待交付确认', 'CheckSquare', '/revenue/delivery-confirm', 'RevenueView', 4, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue/delivery-confirm');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.id
FROM sys_role_menu r
CROSS JOIN (SELECT id FROM sys_menu WHERE path = '/revenue/delivery-confirm') m
WHERE r.menu_id = 34
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = r.role_id AND x.menu_id = m.id);
