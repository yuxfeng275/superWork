-- V103: 收敛工时系统同步为一个统一任务
-- 目标：T+1 定时 + 一个手动按钮，即把 合同明细 + 工时/成本 + 业务线利润月报 一次拉齐；
-- 项目利润为派生口径随之自动对齐。旧的 worktime-contract / worktime-monthly 两个任务下线。
INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'worktime-sync', '工时系统-全量同步（合同+工时/成本+业务线利润）', 'WORKTIME', 'worktime', '0 0 7 * * *', 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'worktime-sync');

DELETE FROM sync_task WHERE task_code IN ('worktime-contract', 'worktime-monthly');
