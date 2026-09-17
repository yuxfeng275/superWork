-- V73: 营收管理菜单拆分
-- 「工时 & 成本」「交付与利润」提升为数据分析下的二级菜单；
-- 「数据导入」「待映射与销售项目」归入数据分析 → 配置 分组，作为三级菜单；
-- 旧「营收管理」(/revenue) 菜单退役（前端 /revenue 重定向到 /revenue/worktime）。

-- ============ 1. 新增二级菜单：工时 & 成本、交付与利润 ============

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t),
       '工时 & 成本', 'Timer', '/revenue/worktime', 'RevenueView', 2, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue/worktime');

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t),
       '交付与利润', 'TrendCharts', '/revenue/delivery', 'RevenueView', 3, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue/delivery');

-- KPI周报 顺延排序（原 sort_order=3 与交付与利润冲突）
UPDATE sys_menu SET sort_order = 4 WHERE path = '/kpi-report'
  AND parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t);

-- ============ 2. 新增二级分组：配置 ============

INSERT INTO sys_menu (parent_id, name, icon, path, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t),
       '配置', 'Setting', '', 5, 1, 1 FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu
    WHERE name = '配置'
      AND parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t)
);

-- ============ 3. 新增三级菜单：数据导入、待映射与销售项目 ============

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE name = '配置'
        AND parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t2)) t),
       '数据导入', 'Upload', '/revenue/import', 'RevenueView', 1, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue/import');

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE name = '配置'
        AND parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t2)) t),
       '待映射与销售项目', 'Link', '/revenue/pending', 'RevenueView', 2, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/revenue/pending');

-- ============ 4. 退役旧「营收管理」菜单（保留授权记录，前端路径重定向兼容） ============

UPDATE sys_menu SET visible = 0 WHERE path = '/revenue';

-- ============ 5. 角色授权：复制旧「营收管理」的授权到新菜单与配置分组 ============

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.id
FROM sys_role_menu rm
JOIN sys_menu old_menu ON old_menu.id = rm.menu_id AND old_menu.path = '/revenue'
JOIN sys_menu m ON m.path IN ('/revenue/worktime', '/revenue/delivery', '/revenue/import', '/revenue/pending');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, g.id
FROM sys_role_menu rm
JOIN sys_menu old_menu ON old_menu.id = rm.menu_id AND old_menu.path = '/revenue'
JOIN sys_menu g ON g.name = '配置'
  AND g.parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t);
