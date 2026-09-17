-- 注册报价策略管理和报价单管理菜单，并分配给所有已有菜单授权的角色

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '报价策略管理', 'Setting', '/quotation-policies', 'QuotationPolicyView', 65, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/quotation-policies'
);

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '报价单管理', 'Document', '/quotations', 'QuotationView', 66, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/quotations'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, menu.id
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.path IN ('/quotation-policies', '/quotations');