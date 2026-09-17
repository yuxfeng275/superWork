-- ====================================
-- V33: 大事儿参与人关系和访问权限
-- ====================================

CREATE TABLE bu_key_matter_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_matter_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id),
    INDEX idx_key_matter_participant_user (user_id),
    CONSTRAINT fk_key_matter_participant_matter
        FOREIGN KEY (key_matter_id) REFERENCES bu_key_matter(id) ON DELETE CASCADE,
    CONSTRAINT fk_key_matter_participant_user
        FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU大事儿参与人';

-- 回填现有事项负责人为参与人
INSERT IGNORE INTO bu_key_matter_participant (key_matter_id, user_id)
SELECT id, owner_id FROM bu_key_matter;

-- 查看权限
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:key-matter:view', '查看BU大事儿', '查看全部大事儿和周会视图', 'menu', id
FROM sys_menu WHERE path = '/key-matters'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

-- 周进度反馈权限
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:key-matter:feedback', '反馈大事儿周进度', '负责人反馈本人负责事项的周进度', 'button', id
FROM sys_menu WHERE path = '/key-matters'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

-- 授予所有启用角色
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission
  ON permission.code IN ('bu:key-matter:view', 'bu:key-matter:feedback')
WHERE role.status = 1;
