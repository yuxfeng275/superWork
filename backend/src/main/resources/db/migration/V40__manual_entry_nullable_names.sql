-- ====================================
-- V40: 手工补录明细允许缺省原始名称列（以 business_line_id / project_id 为准）
-- ====================================

ALTER TABLE revenue_worklog_entry
    MODIFY COLUMN business_line_name VARCHAR(200) NULL,
    MODIFY COLUMN project_name_raw VARCHAR(255) NULL;

ALTER TABLE revenue_cost_entry
    MODIFY COLUMN business_line_name VARCHAR(200) NULL,
    MODIFY COLUMN project_name_raw VARCHAR(255) NULL;
