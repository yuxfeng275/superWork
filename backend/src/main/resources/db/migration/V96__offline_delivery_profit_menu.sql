-- ====================================
-- V96: 下线「交付与利润」菜单（/revenue/delivery）
-- 前端路由/页面与后端交付汇总、预估交付计划、财报收入导入接口已同步移除；
-- 仅删除菜单与角色授权。相关数据表（revenue_delivery_plan、revenue_financial_report 等）保留，
-- 其中 revenue_other_cost 仍被 KPI 周报读取，不做处理。
-- ====================================

DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.path = '/revenue/delivery';

DELETE FROM sys_menu WHERE path = '/revenue/delivery';
