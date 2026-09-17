CREATE TABLE sales_opportunity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    customer VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT '商机',
    status VARCHAR(30) NOT NULL DEFAULT '需求确认',
    amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    owner VARCHAR(100) NOT NULL,
    business_line VARCHAR(100),
    next_follow_up VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    probability INT NOT NULL DEFAULT 30,
    expected_close DATE,
    source VARCHAR(100),
    note VARCHAR(500),
    INDEX idx_sales_opportunity_status (status),
    INDEX idx_sales_opportunity_owner (owner),
    INDEX idx_sales_opportunity_created_at (created_at)
);
