-- ====================================
-- V101: 下线旧「待交付确认」菜单（/revenue/delivery-confirm）
-- 旧功能为 月×业务线×销售 聚合确认（V92 上线，未曾使用）；
-- 已由项目利润页内的明细级待交付确认（V100 delivery_confirmation）取代。
-- 后端接口 /api/revenue/pending-delivery/* 与前端页面/路由已同步移除；
-- 数据表 revenue_delivery_confirmation 保留不处理（无使用数据）。
-- ====================================

DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.path = '/revenue/delivery-confirm';

DELETE FROM sys_menu WHERE path = '/revenue/delivery-confirm';
