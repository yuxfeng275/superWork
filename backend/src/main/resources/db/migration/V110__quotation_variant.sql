-- ====================================
-- V110: 报价方案 / 版本 / 商机首选方案
-- 背景：同一商机现在只能生成多张互不相关的报价单，没有"方案"和"版本"概念。
-- ====================================

CREATE TABLE IF NOT EXISTS quotation_variant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    opportunity_id BIGINT COMMENT '关联商机ID，无商机的独立报价为空',
    name VARCHAR(200) NOT NULL COMMENT '方案名称，如 标准版/旗舰版/客户指定版本',
    description VARCHAR(500) COMMENT '方案说明',
    latest_quotation_id BIGINT COMMENT '该方案最新版本报价单ID',
    variant_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED',
    created_by VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qv_opportunity_id (opportunity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价方案';

ALTER TABLE quotation
    ADD COLUMN variant_id BIGINT NULL COMMENT '所属方案ID' AFTER opportunity_id,
    ADD COLUMN version_no INT NOT NULL DEFAULT 1 COMMENT '方案内的版本号' AFTER variant_id,
    ADD COLUMN base_quotation_id BIGINT NULL COMMENT '上一版本报价单ID' AFTER version_no,
    ADD COLUMN template_id BIGINT NULL COMMENT '生成时使用的报价模板ID' AFTER policy_id,
    ADD COLUMN is_preferred TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否商机首选方案' AFTER status,
    ADD COLUMN preferred_at DATETIME NULL COMMENT '设为首选的时间' AFTER is_preferred,
    ADD COLUMN discount_override TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在低于底价的折扣' AFTER preferred_at,
    ADD COLUMN gross_margin_rate DECIMAL(6,4) NULL COMMENT '首年毛利率(未税)' AFTER discount_override,
    ADD COLUMN snapshot_json TEXT NULL COMMENT '生成时的策略/模板快照(JSON)' AFTER gross_margin_rate,
    ADD COLUMN bound_contract_no VARCHAR(100) NULL COMMENT '成交后绑定的合同号' AFTER snapshot_json;

ALTER TABLE quotation
    ADD INDEX idx_q_opportunity_status (opportunity_id, status),
    ADD INDEX idx_q_variant_id (variant_id),
    ADD INDEX idx_q_preferred (is_preferred);
