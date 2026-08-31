-- ====================================
-- V41: 名称映射记忆——人工处理过的待映射行在后续导入时自动套用
-- ====================================

CREATE TABLE revenue_name_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    raw_business_line VARCHAR(200) NOT NULL COMMENT 'Excel 原始业务线名',
    raw_project_name VARCHAR(255) NOT NULL COMMENT 'Excel 原始项目名（含【类型】后缀）',
    business_line_id BIGINT NOT NULL,
    project_id BIGINT NULL COMMENT 'NULL=业务线级',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_raw (raw_business_line, raw_project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收导入名称映射记忆';
