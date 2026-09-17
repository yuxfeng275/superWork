-- ====================================
-- V45: 项目交付营收（利润）——合同明细导入 / 预估交付计划 / 其他成本
-- 营收管理「项目交付营收」：按 业务线×项目 展示 OA合同总额、已交付/预估交付、
-- 项目工时成本、销售工时成本（业务线级）、协力/服务器/其他成本与毛利。
-- 金额单位统一为元（页面展示时换算成万）。
-- ====================================

-- 合同明细（OA 合同导入，detail_no = Excel 明细表记录ID 天然去重键）
CREATE TABLE revenue_contract_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NULL COMMENT '导入批次 ID',
    contract_no VARCHAR(100) NULL COMMENT '合同ID',
    detail_no VARCHAR(100) NOT NULL COMMENT '明细表记录ID（唯一去重键）',
    contract_name VARCHAR(500) NULL COMMENT '合同名称',
    brand VARCHAR(200) NULL COMMENT '品牌',
    customer VARCHAR(300) NULL COMMENT '客户名称',
    item_desc VARCHAR(500) NULL COMMENT '款项内容',
    biz_line_raw VARCHAR(200) NULL COMMENT '收款款项类型（原始业务线名）',
    biz_line_id BIGINT NULL COMMENT '归属业务线',
    project_id BIGINT NULL COMMENT '归属项目；NULL=业务线级聚合行或待映射',
    receivable_amount DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '应收金额（元）',
    sale_month VARCHAR(7) NULL COMMENT '收款销售月份 YYYY-MM（用于按年视图隔离）',
    delivery_date DATE NULL COMMENT '项目交付日期；NULL=尚未交付',
    pending TINYINT NOT NULL DEFAULT 0 COMMENT '1=待人工映射项目',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_detail_no (detail_no),
    INDEX idx_biz_project (biz_line_id, project_id),
    INDEX idx_sale_month (sale_month),
    INDEX idx_delivery_date (delivery_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收合同明细（项目交付）';

-- 合同导入批次历史
CREATE TABLE revenue_contract_import_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    total_count INT NOT NULL DEFAULT 0 COMMENT '解析行数',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功落库行数',
    pending_count INT NOT NULL DEFAULT 0 COMMENT '待映射行数',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收合同导入批次';

-- 预估交付计划：项目×月份 批量录入；amount=预估交付金额，labor_cost=预估人月×单价快照
CREATE TABLE revenue_delivery_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `year_month` VARCHAR(7) NOT NULL COMMENT '预估交付月份 YYYY-MM',
    business_line_id BIGINT NOT NULL COMMENT '归属业务线',
    project_id BIGINT NULL COMMENT '归属项目；NULL=业务线聚合行（会员通项目集）',
    amount_yuan DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '预估交付金额（元）',
    person_months DECIMAL(10,4) NULL COMMENT '预估人月',
    labor_cost_yuan DECIMAL(16,2) NULL COMMENT '预估工时成本（元）= 人月 × 单价快照',
    unit_price_snapshot DECIMAL(14,2) NULL COMMENT '历史完结累计单价快照（元/人月）',
    note VARCHAR(500) NULL COMMENT '备注',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_line (`year_month`, business_line_id),
    INDEX idx_line_project (business_line_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预估交付计划';

-- 其他成本手动维护：协力成本/服务器成本/其他成本
CREATE TABLE revenue_other_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `year_month` VARCHAR(7) NOT NULL COMMENT '归属月份 YYYY-MM',
    business_line_id BIGINT NOT NULL COMMENT '归属业务线',
    project_id BIGINT NULL COMMENT '归属项目；NULL=业务线级',
    cost_type VARCHAR(20) NOT NULL COMMENT 'partner=协力/server=服务器/other=其他',
    amount_yuan DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '金额（元）',
    note VARCHAR(500) NULL COMMENT '备注',
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_line (`year_month`, business_line_id),
    INDEX idx_line_project (business_line_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收其他成本';
