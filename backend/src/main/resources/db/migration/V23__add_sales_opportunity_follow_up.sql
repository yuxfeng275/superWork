CREATE TABLE sales_opportunity_follow_up (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    opportunity_id BIGINT NOT NULL,
    follow_up_at DATETIME NOT NULL,
    follower VARCHAR(100) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    probability INT NOT NULL,
    next_follow_up VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sales_opportunity_follow_up_opp (opportunity_id),
    INDEX idx_sales_opportunity_follow_up_time (follow_up_at)
);
