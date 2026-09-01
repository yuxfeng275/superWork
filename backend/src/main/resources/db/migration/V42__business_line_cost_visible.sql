-- ====================================
-- V42: 业务线成本可见性（与展示模式解耦）
-- 全渠道产品/海外业务线成本计入公司公共投入，只统计工时
-- ====================================

ALTER TABLE business_line
    ADD COLUMN cost_visible TINYINT NOT NULL DEFAULT 1
    COMMENT '营收矩阵是否展示成本：1=展示，0=只统计工时';

UPDATE business_line SET cost_visible = 0
WHERE name LIKE '%产品%' OR name LIKE '%海外%';
