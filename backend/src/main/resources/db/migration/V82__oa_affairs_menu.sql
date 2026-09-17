-- ====================================
-- V81: OA 待办审批台菜单
-- 网页会话通道（JSESSIONID 授权）落地后，OA 待办/已办/批量审批的页面入口。
-- 挂在「工作台」分区；授权跟随「连接器管理 / 配置管理」。
-- ====================================

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-work' LIMIT 1) t),
       'OA 待办', 'Finished', '/oa-affairs', 'OaAffairsView', 7, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/oa-affairs');

-- 授权：跟随「连接器管理 / 配置管理」已有授权（管理员向）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, (SELECT id FROM sys_menu WHERE path = '/oa-affairs')
FROM sys_role_menu rm
WHERE rm.menu_id IN (SELECT id FROM sys_menu WHERE path IN ('/system/connectors', '/system/configs'));
