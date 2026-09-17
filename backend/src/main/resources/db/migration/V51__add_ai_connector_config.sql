-- ====================================
-- V51: AI 助手连接器（工时系统 / 语雀）配置项 + 用户身份映射表
-- 挂在通用系统配置机制（V29 system_config_item）下，新组 ai-connector。
-- OA / 云效 / 邮箱凭据沿用各自既有配置（seeyon_oa_integration_config、
-- 云效配置表、email_account），此处不重复建项。
-- PASSWORD 项采用 AES-256-GCM 加密保存。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_connector_identity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '本地系统用户 ID',
    connector_code VARCHAR(32) NOT NULL COMMENT '连接器编码（oa/worktime/yunxiao）',
    external_id VARCHAR(128) NOT NULL COMMENT '外部系统身份 ID',
    display_name VARCHAR(128) NULL COMMENT '外部系统显示名（缓存）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_connector (user_id, connector_code),
    KEY idx_connector_external (connector_code, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 连接器用户身份映射';

INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
VALUES
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'worktime.enabled', '启用工时系统连接器', '是否允许 AI 助手查询工时系统', 'BOOLEAN', 'false', 0, 1, 10, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'worktime.base-url', '工时系统服务地址', '工时系统 API 根地址', 'URL', 'https://worktime.lucidata.cn', 0, 1, 20, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'worktime.username', '工时系统服务账号', '用于 AI 助手查询的服务账号（员工号）', 'STRING', NULL, 0, 1, 30, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'worktime.password', '工时系统服务账号密码', '服务账号密码，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 40, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'worktime.timeout-seconds', '工时系统请求超时（秒）', '调用工时系统接口的超时时间', 'NUMBER', '30', 0, 1, 50, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'yuque.enabled', '启用语雀连接器', '是否允许 AI 助手通过 MCP 查询语雀', 'BOOLEAN', 'false', 0, 1, 60, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'yuque.mcp-url', '语雀 MCP 服务地址', '语雀 MCP 端点（streamable HTTP，SSE 自动回退）', 'URL', 'https://mcp.yuque.com/mcp', 0, 1, 70, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'yuque.token', '语雀访问 Token', '语雀个人 OAuth Token，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 80, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'yuque.timeout-seconds', '语雀请求超时（秒）', '调用语雀 MCP 接口的超时时间', 'NUMBER', '30', 0, 1, 90, 1),
('ai-connector', 'AI 连接器', '管理 AI 助手连接器（工时系统 / 语雀）的连接配置', 'mail.search-days', '邮件搜索回溯天数', 'AI 助手搜索已同步邮件的默认回溯天数上限', 'NUMBER', '90', 0, 1, 100, 1);
