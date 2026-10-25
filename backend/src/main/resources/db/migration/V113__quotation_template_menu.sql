-- ====================================
-- V113: 报价模板菜单 + 策略按类型绑定模板
-- 模板数据由 QuotationPolicyDataInitializer 幂等播种，这里只补菜单绑定。
-- ====================================

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '报价模板管理', 'FileText', '/quotation-templates', 'QuotationTemplateView', 67, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/quotation-templates'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, menu.id
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.path = '/quotation-templates';

-- 把已存在的策略按类型绑定到同名类型模板（幂等）
UPDATE quotation_policy p
JOIN quotation_template t ON t.type = p.type AND t.status = 'PUBLISHED'
SET p.template_id = t.id
WHERE p.template_id IS NULL;
