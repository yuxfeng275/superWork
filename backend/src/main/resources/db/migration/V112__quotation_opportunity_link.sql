-- ====================================
-- V112: 商机 <- 报价 联动字段
-- 背景：目前生成报价只在 opportunity.amount 为空时回写首年未税价，阶段/概率完全不联动。
-- ====================================

ALTER TABLE sales_opportunity
    ADD COLUMN quoted_amount DECIMAL(15,2) NULL COMMENT '最新有效报价首年含税金额' AFTER amount,
    ADD COLUMN quotation_count INT NOT NULL DEFAULT 0 COMMENT '关联报价单数量' AFTER quoted_amount,
    ADD COLUMN last_quotation_at DATETIME NULL COMMENT '最近一次报价时间' AFTER quotation_count,
    ADD COLUMN won_quotation_id BIGINT NULL COMMENT '成交时采纳的报价单ID' AFTER last_quotation_at,
    ADD COLUMN gross_margin_rate DECIMAL(6,4) NULL COMMENT '首选报价首年毛利率' AFTER won_quotation_id;

ALTER TABLE sales_opportunity
    ADD INDEX idx_so_won_quotation (won_quotation_id);
