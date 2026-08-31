-- ====================================
-- V36: 营收管理重做（工时与成本管理）
-- 废弃旧的映射/月度成本/月度交付/手动维护/导入历史模型，
-- 重建为：工时明细 + 成本明细 + 预估明细 + 月结标记 + 销售项目注册表 + 导入批次
-- ====================================

DROP TABLE IF EXISTS revenue_project_mapping;
DROP TABLE IF EXISTS revenue_monthly_cost;
DROP TABLE IF EXISTS revenue_monthly_income;
DROP TABLE IF EXISTS revenue_manual_entry;
DROP TABLE IF EXISTS revenue_import_record;

CREATE TABLE revenue_import_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_type VARCHAR(20) NOT NULL COMMENT 'worklog/cost',
    `year_month` VARCHAR(7) NOT NULL COMMENT '归属月份 YYYY-MM',
    file_name VARCHAR(255) NOT NULL,
    total_count INT NOT NULL DEFAULT 0 COMMENT '解析行数',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功落库行数',
    pending_count INT NOT NULL DEFAULT 0 COMMENT '待映射行数',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type_month (import_type, `year_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收导入批次';

CREATE TABLE revenue_month_close (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `year_month` VARCHAR(7) NOT NULL COMMENT 'YYYY-MM',
    closed_at DATETIME NULL,
    closed_by BIGINT NULL,
    UNIQUE KEY uk_month (`year_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月结标记';

CREATE TABLE revenue_worklog_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    `year_month` VARCHAR(7) NOT NULL,
    business_line_name VARCHAR(200) NOT NULL COMMENT 'Excel 原始业务线名',
    business_line_id BIGINT NULL COMMENT '系统业务线；NULL=待映射',
    project_name_raw VARCHAR(255) NOT NULL COMMENT 'Excel 原始项目名',
    project_id BIGINT NULL COMMENT '系统项目；NULL=业务线级（项目集）或待映射',
    work_type VARCHAR(20) NOT NULL COMMENT 'project=项目（交付+产研）/sales=销售',
    sales_kind VARCHAR(20) NULL COMMENT 'specific=具体销售项目/pool=商机集合/other=其他',
    sales_project_id BIGINT NULL COMMENT 'specific 时指向 revenue_sales_project',
    employee_no VARCHAR(50) NULL,
    employee_name VARCHAR(100) NULL,
    department VARCHAR(100) NULL,
    hours DECIMAL(10,4) NOT NULL COMMENT '人月',
    work_note TEXT NULL COMMENT '工作说明',
    special_note VARCHAR(500) NULL COMMENT '特殊说明',
    tags VARCHAR(500) NULL COMMENT '商机集合自动标签，逗号分隔',
    pending TINYINT NOT NULL DEFAULT 0 COMMENT '1=待映射',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_month_line (`year_month`, business_line_id),
    INDEX idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收工时明细';

CREATE TABLE revenue_cost_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    `year_month` VARCHAR(7) NOT NULL,
    business_line_name VARCHAR(200) NOT NULL,
    business_line_id BIGINT NULL,
    project_name_raw VARCHAR(255) NOT NULL,
    project_id BIGINT NULL,
    work_type VARCHAR(20) NOT NULL COMMENT 'project/sales',
    sales_kind VARCHAR(20) NULL,
    sales_project_id BIGINT NULL,
    employee_count INT NULL,
    hours DECIMAL(10,4) NOT NULL COMMENT '人月',
    cost_amount DECIMAL(14,2) NOT NULL COMMENT '工时成本（元）',
    person_month_cost DECIMAL(14,2) NULL COMMENT '人月成本（元/人月）',
    pending TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_month_line (`year_month`, business_line_id),
    INDEX idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收成本明细';

CREATE TABLE revenue_estimate_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `year_month` VARCHAR(7) NOT NULL,
    business_line_id BIGINT NOT NULL,
    project_id BIGINT NULL COMMENT 'NULL=业务线级（项目集/商机集合/其他）',
    work_type VARCHAR(20) NOT NULL DEFAULT 'project' COMMENT 'project/sales',
    sales_kind VARCHAR(20) NULL,
    sales_project_id BIGINT NULL COMMENT '预估归属具体销售项目',
    description VARCHAR(500) NOT NULL,
    person_months DECIMAL(10,4) NOT NULL COMMENT '预估人月',
    unit_price DECIMAL(14,2) NULL COMMENT '元/人月，录入时按历史完结单价快照',
    amount DECIMAL(14,2) NULL COMMENT '预估金额（元）= 人月 × 单价',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_line (`year_month`, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收预估明细';

CREATE TABLE revenue_sales_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_line_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL COMMENT '销售项目名，如 京博',
    opportunity_id BIGINT NULL COMMENT '手动关联商机',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_line_name (business_line_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收销售项目注册表';
