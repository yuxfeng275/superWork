CREATE TABLE IF NOT EXISTS revenue_financial_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    year_month VARCHAR(7) NOT NULL COMMENT '财报月份，如 2026-01',
    business_line_id BIGINT NOT NULL COMMENT '业务线ID',
    revenue_amount DECIMAL(16,2) NOT NULL DEFAULT 0.00 COMMENT '财报未税收入',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_line (year_month, business_line_id),
    INDEX idx_year_month (year_month),
    INDEX idx_business_line_id (business_line_id)
) COMMENT '财报月度收入数据，用于与系统收入对齐';

-- 插入 H1 财报数据（来源：业务线营收利润月度表.xlsx）
-- 会员通 (id=3)
INSERT INTO revenue_financial_report (year_month, business_line_id, revenue_amount) VALUES
('2026-01', 3, 360520),
('2026-02', 3, 158120),
('2026-03', 3, 106321),
('2026-04', 3, 89053),
('2026-05', 3, 192104),
('2026-06', 3, 239841);

-- 全域精准 (id=6)
INSERT INTO revenue_financial_report (year_month, business_line_id, revenue_amount) VALUES
('2026-01', 6, 7170),
('2026-02', 6, 0),
('2026-03', 6, 0),
('2026-04', 6, 4438),
('2026-05', 6, 104300),
('2026-06', 6, 13491);

-- 定制 (id=1) + SAAS (id=2) 合计按财报口径
INSERT INTO revenue_financial_report (year_month, business_line_id, revenue_amount) VALUES
('2026-01', 1, 137516),
('2026-02', 1, 323632),
('2026-03', 1, 119238),
('2026-04', 1, 400124),
('2026-05', 1, 351085),
('2026-06', 1, 290422),
('2026-01', 2, 0),
('2026-02', 2, 58596),
('2026-03', 2, 0),
('2026-04', 2, 0),
('2026-05', 2, 35158),
('2026-06', 2, 4245);