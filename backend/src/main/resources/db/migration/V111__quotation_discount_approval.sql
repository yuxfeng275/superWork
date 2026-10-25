-- ====================================
-- V111: 折扣审批链 + 状态流转审计 + 行级改价留痕
-- 背景：discountRate 目前前后端 0~1 自由输入，无底价校验、无审批、无审计。
-- ====================================

CREATE TABLE IF NOT EXISTS quotation_discount_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quotation_id BIGINT NOT NULL COMMENT '报价单ID',
    requested_by VARCHAR(100) COMMENT '申请人',
    requested_discount DECIMAL(5,4) COMMENT '申请的最低综合折扣',
    floor_discount DECIMAL(5,4) COMMENT '申请时的策略底价折扣',
    gross_margin_rate DECIMAL(6,4) COMMENT '申请时的首年毛利率',
    reason TEXT COMMENT '申请理由',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    approver VARCHAR(100) COMMENT '审批人',
    approved_at DATETIME COMMENT '审批时间',
    comment TEXT COMMENT '审批意见',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qdr_quotation_id (quotation_id),
    INDEX idx_qdr_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价折扣审批单';

CREATE TABLE IF NOT EXISTS quotation_approval_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quotation_id BIGINT NOT NULL COMMENT '报价单ID',
    from_status VARCHAR(20) COMMENT '流转前状态',
    to_status VARCHAR(20) COMMENT '流转后状态',
    action VARCHAR(50) COMMENT '动作: SUBMIT/APPROVE/REJECT/SEND/ACCEPT/EXPIRE/WITHDRAW',
    operator VARCHAR(100) COMMENT '操作人',
    comment TEXT COMMENT '意见',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qal_quotation_id (quotation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单状态流转审计';

ALTER TABLE quotation_line_item
    ADD COLUMN cost_price_ex_tax DECIMAL(15,2) NULL COMMENT '未税成本单价' AFTER unit_price_ex_tax,
    ADD COLUMN is_custom TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否手工新增行' AFTER price_description,
    ADD COLUMN is_first_year_free TINYINT(1) NOT NULL DEFAULT 0 COMMENT '首年免费，仅计入次年及以后' AFTER is_custom,
    ADD COLUMN price_override_reason VARCHAR(500) NULL COMMENT '单价手工覆盖原因' AFTER is_first_year_free,
    ADD COLUMN section_code VARCHAR(50) NULL COMMENT '分组编码，用于稳定排序' AFTER section;
