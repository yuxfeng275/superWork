-- ====================================
-- V35: 营收业务线级唯一键修复 + 导入历史
-- ====================================

-- MySQL 唯一索引在 project_id 为 NULL 时失效（NULL 互不相等），
-- 导致业务线级（project_id IS NULL）的月度行可被并发重复插入，
-- 进而使 upsert 的 selectOne 抛 TooManyResultsException。
-- 使用生成列把 NULL 投影为 -business_line_id，保证业务线级唯一。

-- 1) 清理历史重复行（保留最小 id）
DELETE c1 FROM revenue_monthly_cost c1
INNER JOIN revenue_monthly_cost c2
  ON c1.`year_month` = c2.`year_month`
 AND c1.category = c2.category
 AND IFNULL(c1.project_id, 0 - c1.business_line_id) = IFNULL(c2.project_id, 0 - c2.business_line_id)
 AND c1.id > c2.id;

DELETE i1 FROM revenue_monthly_income i1
INNER JOIN revenue_monthly_income i2
  ON i1.`year_month` = i2.`year_month`
 AND IFNULL(i1.project_id, 0 - i1.business_line_id) = IFNULL(i2.project_id, 0 - i2.business_line_id)
 AND i1.id > i2.id;

-- 2) 生成列 + 唯一键（新键完全覆盖旧的 project_id 唯一键，旧键在 NULL 时失效故移除）
ALTER TABLE revenue_monthly_cost
    DROP INDEX uk_month_project_cat,
    ADD COLUMN project_key BIGINT GENERATED ALWAYS AS (IFNULL(project_id, 0 - business_line_id)) STORED,
    ADD UNIQUE KEY uk_month_project_key_cat (`year_month`, project_key, category);

ALTER TABLE revenue_monthly_income
    DROP INDEX uk_month_project,
    ADD COLUMN project_key BIGINT GENERATED ALWAYS AS (IFNULL(project_id, 0 - business_line_id)) STORED,
    ADD UNIQUE KEY uk_month_project_key (`year_month`, project_key);

-- 3) 导入历史表
CREATE TABLE revenue_import_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_type VARCHAR(20) NOT NULL COMMENT 'cost/income',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    success_count INT NOT NULL DEFAULT 0,
    new_mapping_count INT NOT NULL DEFAULT 0,
    pending_mapping_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    created_by BIGINT NULL COMMENT '操作人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type_created (import_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收导入历史';
