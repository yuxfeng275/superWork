CREATE TABLE email_integration_config (
    id BIGINT PRIMARY KEY,
    deep_seek_enabled TINYINT NOT NULL DEFAULT 0,
    deep_seek_base_url VARCHAR(512) NOT NULL DEFAULT 'https://api.deepseek.com',
    deep_seek_model VARCHAR(128) NOT NULL DEFAULT 'deepseek-chat',
    encrypted_deep_seek_api_key VARCHAR(2048) NULL,
    deep_seek_test_status VARCHAR(32) NULL,
    deep_seek_test_message VARCHAR(500) NULL,
    deep_seek_tested_at DATETIME NULL,
    we_com_enabled TINYINT NOT NULL DEFAULT 0,
    we_com_base_url VARCHAR(512) NOT NULL DEFAULT 'https://qyapi.weixin.qq.com',
    we_com_corp_id VARCHAR(128) NULL,
    we_com_agent_id VARCHAR(128) NULL,
    encrypted_we_com_secret VARCHAR(2048) NULL,
    public_base_url VARCHAR(512) NULL,
    we_com_test_status VARCHAR(32) NULL,
    we_com_test_message VARCHAR(500) NULL,
    we_com_tested_at DATETIME NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_integration_updater FOREIGN KEY (updated_by) REFERENCES user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件摘要与推送页面配置';

INSERT INTO sys_permission (code, name, description, type, menu_id, status)
SELECT 'email:config', '配置邮件集成', '在页面维护 DeepSeek 与企业微信内部应用配置',
       'button', menu.id, 1
FROM sys_menu menu
WHERE menu.path = '/emails'
  AND NOT EXISTS (SELECT 1 FROM sys_permission permission WHERE permission.code = 'email:config');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'email:config'
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission mapping
      WHERE mapping.role_id = role.id AND mapping.permission_id = permission.id
  );
