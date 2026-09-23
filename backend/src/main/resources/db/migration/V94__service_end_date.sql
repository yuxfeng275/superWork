-- ====================================
-- V94: 待交付统计按服务结束月分组
-- revenue_contract_entry 增 service_end_date（收款-项目服务完成日期）。
-- 待交付口径（业务确认）：交付日期为空 且 服务结束时间在对应月；
-- 服务结束时间为空时回落 应收月份 → 收款销售月份。
-- 存量数据由工时同步下次跑批回填（或人工回填脚本）。
-- ====================================

ALTER TABLE revenue_contract_entry
    ADD COLUMN service_end_date DATE NULL
        COMMENT '服务结束时间（收款-项目服务完成日期）；待交付统计的月份维度'
        AFTER receivable_date;
