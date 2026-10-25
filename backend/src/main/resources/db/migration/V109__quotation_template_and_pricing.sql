-- ====================================
-- V109: 报价模板体系与策略定价字段
-- 背景：报价说明/条款目前只能手填，strategies 无法表达"按比例计费"和底价管控。
-- ====================================

CREATE TABLE IF NOT EXISTS quotation_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL COMMENT '模板名称',
    type VARCHAR(50) NOT NULL COMMENT '适用类型: SAAS/PRIVATE_DEPLOYMENT/MEMBERSHIP/COMMON',
    title VARCHAR(200) COMMENT '报价单抬头标题',
    issuer VARCHAR(200) COMMENT '报价方落款',
    default_validity_days INT NOT NULL DEFAULT 30 COMMENT '默认有效期(天)',
    default_delivery_period VARCHAR(100) COMMENT '默认交货期',
    invoice_type VARCHAR(50) NOT NULL DEFAULT '增值税专用发票' COMMENT '默认发票类型',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    created_by VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qt_template_type (type),
    INDEX idx_qt_template_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价条款模板';

CREATE TABLE IF NOT EXISTS quotation_template_clause (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL COMMENT '模板ID',
    section VARCHAR(100) NOT NULL COMMENT '条款分组',
    title VARCHAR(200) COMMENT '条款标题',
    content TEXT COMMENT '条款正文，支持 {{customerName}} 等占位符',
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qtc_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价模板条款';

ALTER TABLE quotation_policy
    ADD COLUMN template_id BIGINT NULL COMMENT '绑定报价模板ID' AFTER type,
    ADD COLUMN min_discount_rate DECIMAL(5,4) NULL COMMENT '最低允许综合折扣(底价)，低于需审批' AFTER tax_mode,
    ADD COLUMN approval_required TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否开启折扣审批' AFTER min_discount_rate;

ALTER TABLE quotation_policy_item
    ADD COLUMN charge_base_section VARCHAR(100) NULL COMMENT '按比例计费时的基数分组(如 产品模块)' AFTER charge_method,
    ADD COLUMN charge_rate DECIMAL(5,4) NULL COMMENT '按比例计费费率(如 0.2000 = 产品模块总价20%)' AFTER charge_base_section,
    ADD COLUMN first_year_free TINYINT(1) NOT NULL DEFAULT 0 COMMENT '首年免费，仅计入次年及以后' AFTER charge_rate,
    ADD COLUMN cost_price DECIMAL(15,2) NULL COMMENT '成本单价(用于毛利测算)' AFTER unit_price;
