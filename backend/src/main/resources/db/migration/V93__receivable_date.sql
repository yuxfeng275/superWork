-- ====================================
-- V93: 待交付统计按应收月份分组
-- revenue_contract_entry 增 receivable_date（应收日期），
-- 待交付统计的月份维度由 收款销售月份 改为 应收月份（空回落收款销售月份）。
-- 存量数据由工时同步下次跑批回填（或人工回填脚本）。
-- ====================================

ALTER TABLE revenue_contract_entry
    ADD COLUMN receivable_date DATE NULL
        COMMENT '应收日期；待交付统计的月份维度'
        AFTER sale_month;
