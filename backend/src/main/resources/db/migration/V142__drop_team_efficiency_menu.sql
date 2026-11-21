-- ====================================
-- V142: 下线「团队效能」菜单（/team-efficiency）
-- 前端路由、页面与 e2e 步骤已移除，后端 TeamEfficiencyController/Service 已删除。
-- 只清入口：project_fact / ai_artifact / task 数据与该页无关，均不受影响。
-- ====================================

DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.path = '/team-efficiency' OR m.path LIKE '/team-efficiency/%';

DELETE FROM sys_menu WHERE path = '/team-efficiency' OR path LIKE '/team-efficiency/%';
