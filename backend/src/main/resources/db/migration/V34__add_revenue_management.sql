-- ====================================
-- V34: 全渠道项目营收与成本管理
-- ====================================

CREATE TABLE revenue_project_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL COMMENT 'cost_project / contract_brand',
    source_name VARCHAR(200) NOT NULL COMMENT '工时系统项目名或合同品牌名',
    project_id BIGINT NULL COMMENT '关联项目ID；NULL=业务线级',
    business_line_id BIGINT NULL COMMENT '直接归属业务线（会员通等不拆项目的）',
    category VARCHAR(20) NOT NULL DEFAULT 'delivery' COMMENT 'delivery/sales/product',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source (source_type, source_name),
    INDEX idx_mapping_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收项目映射';

CREATE TABLE revenue_monthly_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    category VARCHAR(20) NOT NULL DEFAULT 'delivery' COMMENT 'delivery/sales/product',
    work_hours DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '工时（人月）',
    work_cost BIGINT NOT NULL DEFAULT 0 COMMENT '工时成本（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project_cat (year_month, project_id, category),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度成本';

CREATE TABLE revenue_monthly_income (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '收款月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    contract_count INT NOT NULL DEFAULT 0,
    receivable_amount BIGINT NOT NULL DEFAULT 0 COMMENT '应收金额（元）',
    received_amount BIGINT NOT NULL DEFAULT 0 COMMENT '实收金额（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project (year_month, project_id),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度交付';

CREATE TABLE revenue_manual_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    entry_type VARCHAR(30) NOT NULL COMMENT 'h2_estimate/partner_cost/server_cost/other_cost',
    amount BIGINT NOT NULL DEFAULT 0 COMMENT '金额（元）',
    remark VARCHAR(500) DEFAULT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_bl (year_month, business_line_id),
    INDEX idx_month_project (year_month, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收手动维护项';

INSERT INTO sys_permission (code, name, description, type, menu_id)
VALUES
    ('revenue:view', '查看营收管理', '查看全渠道营收看板', 'menu', NULL),
    ('revenue:manage', '管理营收数据', '导入/维护营收成本数据', 'button', NULL)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    type = VALUES(type),
    menu_id = VALUES(menu_id),
    status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission
  ON permission.code IN ('revenue:view', 'revenue:manage')
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER');
