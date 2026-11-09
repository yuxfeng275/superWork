-- ====================================
-- V120: 归档邮箱模式 + 项目邮箱别名路由
-- 1) email_account 增 mode / shared_scope：
--    mode=PERSONAL（缺省）沿用现有个人的邮箱行为；ARCHIVE 为归档/共享邮箱，
--    一封邮件按收件人（to/cc）归属到真实 owner。
--    shared_scope 预留共享范围标记（如部门/全员归档），当前仅落库不做路由。
-- 2) email_message 增 grouping_alias：记录命中的项目邮箱别名（形如 +XM）。
-- 别名不建表：别名即 project.code，投递地址 = 任意账号地址 +code@域名。
-- ALTER 带存在性判断，保证可重复执行。
-- ====================================

SET @has_account_mode := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_account'
      AND COLUMN_NAME = 'mode');
SET @add_account_mode := IF(@has_account_mode = 0,
    'ALTER TABLE email_account ADD COLUMN `mode` VARCHAR(16) NOT NULL DEFAULT ''PERSONAL'' COMMENT ''PERSONAL=个人邮箱 / ARCHIVE=归档共享邮箱'' AFTER enabled',
    'SELECT 1');
PREPARE stmt FROM @add_account_mode;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_shared_scope := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_account'
      AND COLUMN_NAME = 'shared_scope');
SET @add_shared_scope := IF(@has_shared_scope = 0,
    'ALTER TABLE email_account ADD COLUMN `shared_scope` VARCHAR(32) NULL COMMENT ''共享范围标记（归档模式预留）'' AFTER `mode`',
    'SELECT 1');
PREPARE stmt FROM @add_shared_scope;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_grouping_alias := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_message'
      AND COLUMN_NAME = 'grouping_alias');
SET @add_grouping_alias := IF(@has_grouping_alias = 0,
    'ALTER TABLE email_message ADD COLUMN `grouping_alias` VARCHAR(64) NULL COMMENT ''命中的项目邮箱别名（+CODE）'' AFTER grouping_model',
    'SELECT 1');
PREPARE stmt FROM @add_grouping_alias;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 别名/归档归属排查用：按互联网邮件 ID 去重查询
SET @has_msg_id_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_message'
      AND INDEX_NAME = 'idx_email_message_internet_id');
SET @add_msg_id_index := IF(@has_msg_id_index = 0,
    'ALTER TABLE email_message ADD KEY idx_email_message_internet_id (internet_message_id)',
    'SELECT 1');
PREPARE stmt FROM @add_msg_id_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
