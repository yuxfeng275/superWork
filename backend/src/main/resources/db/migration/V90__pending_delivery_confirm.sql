-- ====================================
-- V90: 按月待交付统计与销售确认
-- 1) revenue_contract_entry 增 sales_owner（销售：承接人，缺省取报价人），
--    由工时同步 / OA 采集 / Excel 导入三条链路写入，存量数据随下次同步回填。
-- 2) 新增 revenue_delivery_confirmation：按 月份×业务线×销售 记录人为确认结果与备注。
-- ====================================

ALTER TABLE revenue_contract_entry
    ADD COLUMN sales_owner VARCHAR(64) NULL
        COMMENT '销售（承接人，缺省取报价人）'
        AFTER biz_line_raw;

CREATE TABLE IF NOT EXISTS revenue_delivery_confirmation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month CHAR(7) NOT NULL COMMENT 'YYYY-MM（取 revenue_contract_entry.sale_month）',
    biz_line_id BIGINT NOT NULL COMMENT '业务线 ID',
    sales_owner VARCHAR(64) NOT NULL COMMENT '销售姓名（承接人/报价人），空串行用 _UNSET_ 占位',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING 待确认 / CONFIRMABLE 可交付 / UNCONFIRMABLE 无法按期交付',
    remark VARCHAR(500) NULL COMMENT '确认备注（与销售的沟通结论）',
    confirmed_by BIGINT NULL,
    confirmed_by_name VARCHAR(64) NULL,
    confirmed_at DATETIME NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery_confirm (year_month, biz_line_id, sales_owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='按月待交付的销售确认（人为与销售一一确认）';
