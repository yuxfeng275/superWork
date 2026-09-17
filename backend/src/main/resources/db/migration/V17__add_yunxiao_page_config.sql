CREATE TABLE yunxiao_integration_config (
    id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    edition VARCHAR(20) NOT NULL DEFAULT 'center',
    base_url VARCHAR(500) NOT NULL,
    organization_id VARCHAR(100),
    encrypted_token TEXT,
    updated_by BIGINT,
    last_tested_at TIMESTAMP NULL,
    last_test_status VARCHAR(20),
    last_test_message VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_yunxiao_config_updater FOREIGN KEY (updated_by) REFERENCES user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云效页面连接配置';
