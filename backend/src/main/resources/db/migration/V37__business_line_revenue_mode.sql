-- ====================================
-- V37: 业务线营收展示模式
-- full=项目+销售明细行（默认）/ aggregate=项目销售两行聚合（会员通）/ simple=单行汇总（产品、精准、海外）
-- ====================================

ALTER TABLE business_line
    ADD COLUMN revenue_mode VARCHAR(20) NOT NULL DEFAULT 'full'
    COMMENT '营收矩阵展示模式：full/aggregate/simple';

UPDATE business_line SET revenue_mode = 'aggregate' WHERE name LIKE '%会员通%';
UPDATE business_line SET revenue_mode = 'simple'
WHERE name LIKE '%产品%' OR name LIKE '%精准%' OR name LIKE '%海外%';
