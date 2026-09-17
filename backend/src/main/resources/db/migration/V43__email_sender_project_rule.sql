-- ====================================
-- V43: 邮件发件人→项目路由表（分组质量：规则优先于 AI，人工纠偏沉淀为规则）
-- ====================================

CREATE TABLE email_sender_project_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL COMMENT '规则归属用户（邮件按用户隔离）',
    sender_pattern VARCHAR(255) NOT NULL COMMENT '发件人地址或域名（小写）',
    project_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL=人工纠偏 / LEARNED=系统沉淀',
    hit_count INT NOT NULL DEFAULT 0 COMMENT '命中次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_sender (owner_user_id, sender_pattern),
    INDEX idx_rule_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮件发件人项目路由规则';
