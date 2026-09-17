CREATE TABLE email_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    email_address VARCHAR(320) NOT NULL,
    encrypted_credential VARCHAR(1024) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    connection_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
    connection_message VARCHAR(500) NULL,
    last_tested_at DATETIME NULL,
    sync_status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    sync_error VARCHAR(500) NULL,
    last_sync_count INT NOT NULL DEFAULT 0,
    uid_validity BIGINT NULL,
    last_uid BIGINT NULL,
    initial_sync_from DATETIME NOT NULL,
    last_sync_started_at DATETIME NULL,
    last_sync_completed_at DATETIME NULL,
    lock_until DATETIME NULL,
    lock_token VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_account_owner UNIQUE (owner_user_id),
    CONSTRAINT fk_email_account_owner FOREIGN KEY (owner_user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人企业邮箱账户';

CREATE TABLE email_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    folder VARCHAR(128) NOT NULL DEFAULT 'INBOX',
    uid_validity BIGINT NOT NULL,
    uid BIGINT NOT NULL,
    internet_message_id VARCHAR(998) NULL,
    subject VARCHAR(1000) NOT NULL,
    sender_name VARCHAR(500) NULL,
    sender_address VARCHAR(320) NULL,
    to_addresses_json JSON NOT NULL,
    cc_addresses_json JSON NOT NULL,
    received_at DATETIME NOT NULL,
    body_preview VARCHAR(1000) NOT NULL,
    body_text MEDIUMTEXT NOT NULL,
    attachments_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_message_imap UNIQUE (account_id, folder, uid_validity, uid),
    KEY idx_email_message_owner_received (owner_user_id, received_at),
    CONSTRAINT fk_email_message_owner FOREIGN KEY (owner_user_id) REFERENCES user(id),
    CONSTRAINT fk_email_message_account FOREIGN KEY (account_id) REFERENCES email_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收件箱邮件';

CREATE TABLE email_daily_digest (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    digest_date DATE NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    generation_mode VARCHAR(32) NOT NULL,
    overview TEXT NOT NULL,
    important_items JSON NOT NULL,
    todo_items JSON NOT NULL,
    risk_items JSON NOT NULL,
    reply_items JSON NOT NULL,
    error_message VARCHAR(500) NULL,
    push_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED',
    push_attempts INT NOT NULL DEFAULT 0,
    push_error VARCHAR(500) NULL,
    pushed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_digest_owner_date UNIQUE (owner_user_id, digest_date),
    KEY idx_email_digest_owner_date (owner_user_id, digest_date),
    CONSTRAINT fk_email_digest_owner FOREIGN KEY (owner_user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日邮件摘要';

CREATE TABLE email_wecom_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    wecom_user_id VARCHAR(128) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_wecom_mapping_owner UNIQUE (owner_user_id),
    CONSTRAINT uk_email_wecom_user UNIQUE (wecom_user_id),
    CONSTRAINT fk_email_wecom_mapping_owner FOREIGN KEY (owner_user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业微信点对点用户映射';

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '邮件管理', 'Message', '/emails', 'EmailManagementView', 70, 1, 1
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/emails');

INSERT INTO sys_permission (code, name, description, type, menu_id, status)
SELECT permissions.code, permissions.name, permissions.description, 'menu', menu.id, 1
FROM (
    SELECT 'email:view' code, '查看个人邮件' name, '查看本人的邮件与摘要' description
    UNION ALL SELECT 'email:manage', '管理个人邮箱', '绑定、测试和删除本人的邮箱'
    UNION ALL SELECT 'email:sync', '同步个人邮件', '同步与生成本人的邮件摘要'
) permissions
JOIN sys_menu menu ON menu.path = '/emails'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission existing WHERE existing.code = permissions.code);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id FROM sys_role role JOIN sys_menu menu ON menu.path = '/emails'
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = role.id AND rm.menu_id = menu.id);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('email:view', 'email:manage', 'email:sync')
WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = role.id AND rp.permission_id = permission.id);
