CREATE TABLE system_config_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_code VARCHAR(100) NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    group_description VARCHAR(500) NULL,
    config_key VARCHAR(150) NOT NULL,
    config_name VARCHAR(100) NOT NULL,
    config_description VARCHAR(500) NULL,
    value_type VARCHAR(30) NOT NULL DEFAULT 'STRING',
    config_value MEDIUMTEXT NULL,
    is_sensitive TINYINT NOT NULL DEFAULT 0,
    is_required TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_system_config_group_key (group_code, config_key),
    KEY idx_system_config_group (group_code, status, sort_order),
    CONSTRAINT fk_system_config_updater FOREIGN KEY (updated_by) REFERENCES user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用系统配置项';

INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
VALUES
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'deepseek.enabled', '启用 DeepSeek', '是否使用 DeepSeek 生成每日邮件摘要', 'BOOLEAN', 'false', 0, 1, 10, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'deepseek.base-url', 'DeepSeek 服务地址', 'OpenAI 兼容接口根地址', 'URL', 'https://api.deepseek.com', 0, 1, 20, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'deepseek.model', 'DeepSeek 模型', '每日摘要使用的模型名称', 'STRING', 'deepseek-chat', 0, 1, 30, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'deepseek.api-key', 'DeepSeek API Key', '服务端调用凭据，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 40, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'wecom.enabled', '启用企业微信推送', '是否向员工点对点推送摘要概览', 'BOOLEAN', 'false', 0, 1, 50, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'wecom.base-url', '企业微信服务地址', '企业微信 API 根地址', 'URL', 'https://qyapi.weixin.qq.com', 0, 1, 60, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'wecom.corp-id', '企业微信 CorpId', '企业 ID', 'STRING', NULL, 0, 0, 70, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'wecom.agent-id', '企业微信 AgentId', '内部应用 AgentId', 'STRING', NULL, 0, 0, 80, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'wecom.secret', '企业微信 Secret', '内部应用 Secret，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 90, 1),
('email-integration', '邮件摘要与推送', '管理邮件摘要模型、企业微信内部应用与系统链接', 'app.public-base-url', '系统访问地址', '推送消息中用于返回系统的根地址', 'URL', NULL, 0, 0, 100, 1);

-- Migrate any V28 values without decrypting or exposing sensitive ciphertext.
UPDATE system_config_item item
JOIN email_integration_config legacy ON legacy.id = 1
SET item.config_value = CASE item.config_key
    WHEN 'deepseek.enabled' THEN IF(legacy.deep_seek_enabled = 1, 'true', 'false')
    WHEN 'deepseek.base-url' THEN legacy.deep_seek_base_url
    WHEN 'deepseek.model' THEN legacy.deep_seek_model
    WHEN 'deepseek.api-key' THEN legacy.encrypted_deep_seek_api_key
    WHEN 'wecom.enabled' THEN IF(legacy.we_com_enabled = 1, 'true', 'false')
    WHEN 'wecom.base-url' THEN legacy.we_com_base_url
    WHEN 'wecom.corp-id' THEN legacy.we_com_corp_id
    WHEN 'wecom.agent-id' THEN legacy.we_com_agent_id
    WHEN 'wecom.secret' THEN legacy.encrypted_we_com_secret
    WHEN 'app.public-base-url' THEN legacy.public_base_url
    ELSE item.config_value
END,
item.updated_by = legacy.updated_by,
item.updated_at = legacy.updated_at
WHERE legacy.id = 1;

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT system_menu.id, '配置管理', 'Setting', '/system/configs', 'SystemConfigView', 106, 1, 1
FROM sys_menu system_menu
WHERE system_menu.path = '/system'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path = '/system/configs');

INSERT INTO sys_permission (code, name, description, type, menu_id, status)
SELECT permission_data.code, permission_data.name, permission_data.description,
       permission_data.type, menu.id, 1
FROM (
    SELECT 'system:config:list' code, '查看配置管理' name, '查看所有系统配置组和非敏感配置值' description, 'menu' type
    UNION ALL
    SELECT 'system:config:edit', '编辑配置管理', '修改系统配置并替换敏感凭据', 'button'
) permission_data
JOIN sys_menu menu ON menu.path = '/system/configs'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission existing WHERE existing.code = permission_data.code
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/system/configs'
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu mapping
      WHERE mapping.role_id = role.id AND mapping.menu_id = menu.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('system:config:list', 'system:config:edit')
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission mapping
      WHERE mapping.role_id = role.id AND mapping.permission_id = permission.id
  );

UPDATE sys_permission SET status = 0 WHERE code = 'email:config';
