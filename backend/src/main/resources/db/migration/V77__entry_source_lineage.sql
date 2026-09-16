-- V77: 明细数据血缘列（来源系统 + 同步日志关联）
-- source_system: OA / WORKTIME / EXCEL；存量数据为 NULL（历史上导入/拉取混用，不追溯）
-- sync_log_id 预留（P3 事件关联使用），当前仅填充 source_system

ALTER TABLE revenue_contract_entry
    ADD COLUMN source_system VARCHAR(16) DEFAULT NULL COMMENT '来源：OA/WORKTIME/EXCEL' AFTER pending,
    ADD COLUMN sync_log_id BIGINT DEFAULT NULL COMMENT '写入该行的 data_sync_log.id（预留）' AFTER source_system;

ALTER TABLE revenue_worklog_entry
    ADD COLUMN source_system VARCHAR(16) DEFAULT NULL COMMENT '来源：OA/WORKTIME/EXCEL',
    ADD COLUMN sync_log_id BIGINT DEFAULT NULL COMMENT '写入该行的 data_sync_log.id（预留）';

ALTER TABLE revenue_cost_entry
    ADD COLUMN source_system VARCHAR(16) DEFAULT NULL COMMENT '来源：OA/WORKTIME/EXCEL',
    ADD COLUMN sync_log_id BIGINT DEFAULT NULL COMMENT '写入该行的 data_sync_log.id（预留）';

-- Excel 兜底导入在统一日志中的归属任务（无定时，仅承接日志）
INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'excel-import', 'Excel 兜底导入（补录）', 'EXCEL', 'contract', NULL, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'excel-import');
