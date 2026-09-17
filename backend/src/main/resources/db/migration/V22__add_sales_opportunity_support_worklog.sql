CREATE TABLE sales_opportunity_support_worklog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    opportunity_id BIGINT NOT NULL,
    support_date DATE NOT NULL,
    supporter VARCHAR(100) NOT NULL,
    hours DECIMAL(8,2) NOT NULL DEFAULT 0,
    support_type VARCHAR(50) NOT NULL DEFAULT '方案支持',
    content VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sales_opportunity_support_opp (opportunity_id),
    INDEX idx_sales_opportunity_support_date (support_date)
);
