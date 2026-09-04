-- ====================================
-- V52: AI 连接器通用化注册表
-- 一行 = 一个外部系统连接实例（BASIC 账号密码 / TOKEN / MCP）。
-- 凭据复用 EmailCredentialCipher（AES-256-GCM，EMAIL_CREDENTIAL_ENCRYPTION_KEY）。
-- V51 的 ai-connector 配置组废弃（行保留，代码不再读取）；
-- ai_connector_identity 表继续复用。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_connector (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT '唯一编码（slug），作为工具名前缀',
    name VARCHAR(64) NOT NULL COMMENT '显示名',
    auth_type VARCHAR(16) NOT NULL COMMENT 'BASIC | TOKEN | MCP',
    base_url VARCHAR(512) NOT NULL COMMENT '服务根地址',
    mcp_url VARCHAR(512) NULL COMMENT 'MCP 端点（auth_type=MCP）',
    test_path VARCHAR(256) NULL COMMENT '连接测试路径（BASIC，默认 /api/auth/login）',
    query_path VARCHAR(256) NULL COMMENT '查询接口路径（通用工具）',
    read_path VARCHAR(256) NULL COMMENT '读取接口路径（通用工具）',
    encrypted_username VARCHAR(256) NULL COMMENT 'BASIC 用户名，AES-256-GCM 加密',
    encrypted_password VARCHAR(512) NULL COMMENT 'BASIC 密码，AES-256-GCM 加密',
    encrypted_token VARCHAR(512) NULL COMMENT 'TOKEN/MCP Bearer，AES-256-GCM 加密',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用',
    last_test_status VARCHAR(16) NULL COMMENT 'SUCCESS | FAILED',
    last_test_message VARCHAR(500) NULL,
    last_tested_at DATETIME NULL,
    built_in TINYINT NOT NULL DEFAULT 0 COMMENT '内置连接器不可删除',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 连接器注册表';

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, encrypted_username, encrypted_password, encrypted_token,
 enabled, built_in, sort_order)
VALUES
('worktime', '工时系统', 'BASIC', 'https://worktime.lucidata.cn', NULL, '/api/v1/auth/login', NULL, NULL, NULL, 0, 1, 10),
('yuque', '语雀', 'MCP', 'https://lucidata.yuque.com', 'https://mcp.yuque.com/mcp', NULL, NULL, NULL, NULL, 0, 1, 20);
