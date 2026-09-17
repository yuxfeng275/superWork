ALTER TABLE business_line
    ADD COLUMN tax_rate DECIMAL(5,2) DEFAULT 0.00 COMMENT '税率(%)，用于未税营收换算（如 6.00=6%）';

-- 常见 SaaS/服务类企业增值税率 6%；所有营收线默认 6%
UPDATE business_line SET tax_rate = 6.00 WHERE status = 1 AND name NOT IN ('海外业务线', '全渠道产品');