-- ====================================
-- V100: 下线「KPI周报」菜单（/kpi-report）
-- 前端路由、工作台入口已移除。kpi:view / kpi:manage 权限保留：
-- 工时集成接口与同步异常通知仍按 kpi:manage 鉴权，数据表与快照任务不动。
-- ====================================

DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.path = '/kpi-report' OR m.path LIKE '/kpi-report/%';

DELETE FROM sys_menu WHERE path = '/kpi-report' OR path LIKE '/kpi-report/%';
