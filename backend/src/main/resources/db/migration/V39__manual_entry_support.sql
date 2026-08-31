-- ====================================
-- V39: 工时/成本明细支持手工补录（完结月可编辑）
-- batch_id 允许 NULL（手工录入无导入批次），记录录入人
-- ====================================

ALTER TABLE revenue_worklog_entry
    MODIFY COLUMN batch_id BIGINT NULL,
    ADD COLUMN created_by BIGINT NULL COMMENT '手工录入人' AFTER pending;

ALTER TABLE revenue_cost_entry
    MODIFY COLUMN batch_id BIGINT NULL,
    ADD COLUMN created_by BIGINT NULL COMMENT '手工录入人' AFTER pending;
