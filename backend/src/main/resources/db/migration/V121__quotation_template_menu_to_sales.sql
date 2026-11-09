-- ====================================
-- V121: 报价模板管理归入 销售管理 → 报价管理
-- V113 建菜单时 parent_id=0、sort_order=67，落成了顶级独立菜单；
-- 它和报价策略、报价单同属报价域，应作为三级菜单排在二者之后。
-- 菜单权限按 menu_id 绑定，改 parent_id 不影响 sys_role_menu。
-- ====================================

-- 1) 兜底：确保「销售管理 → 报价管理」分组存在（V71 已建，重复执行无副作用）
INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT p.id, '报价管理', 'Document', '', 2, 1, 1
FROM (SELECT id FROM sys_menu WHERE name = '销售管理' ORDER BY id LIMIT 1) p
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE name = '报价管理' AND COALESCE(path, '') = ''
);

-- 2) 归位：报价模板管理 → 报价管理 下第 3 位（报价策略 1、报价单 2）
UPDATE sys_menu
SET parent_id = COALESCE((
        SELECT gid FROM (
            SELECT id AS gid FROM sys_menu
            WHERE name = '报价管理' AND COALESCE(path, '') = ''
            ORDER BY id LIMIT 1
        ) t
    ), parent_id),
    sort_order = 3
WHERE path = '/quotation-templates';
