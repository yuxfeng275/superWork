ALTER TABLE design_work_log
    ADD COLUMN planned_completed_at TIMESTAMP NULL COMMENT '计划完成时间' AFTER work_content;
