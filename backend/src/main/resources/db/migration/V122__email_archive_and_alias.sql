-- ====================================
-- V122: 归档邮箱模式 + 项目邮箱别名路由
-- 1) email_account 增 mode / shared_scope：
--    mode=PERSONAL（缺省）沿用现有个人的邮箱行为；ARCHIVE 为归档/共享邮箱，
--    一封邮件按收件人（to/cc）归属到真实 owner。
--    shared_scope 预留共享范围标记（如部门/全员归档），当前仅落库不做路由。
-- 2) email_message 增 grouping_alias：记录命中的项目邮箱别名（形如 +XM）。
-- 别名不建表：别名即 project.code，投递地址 = 任意账号地址 +code@域名。
-- ALTER 带存在性判断，保证可重复执行。
-- 3) email_message 增 idx_email_message_internet_id（排查用普通索引，非唯一）。
--    注意 internet_message_id 为 VARCHAR(998)，utf8mb4 下整列索引 3992 字节，
--    超过 InnoDB 上限 3072 字节（MySQL 1071），故取前缀 191（191*4=764 字节）。
--    该索引仅用于等值查询与排查，前缀足够（真实 Message-ID 远短于 191 字符）。
--
-- 【部分执行说明 / 2026-11 生产事故修复】
-- MySQL DDL 非事务，本文件在 241 上第一次执行时前 3 段 ALTER（email_account.mode、
-- email_account.shared_scope、email_message.grouping_alias）已生效，仅第 4 段索引因
-- 超长报 1071 失败。上述 3 段均带 information_schema 存在性判断，已生效环境下重跑
-- 自动降级为 SELECT 1，不会报 duplicate column，故保留原样以兼容全新库。
-- 修复后需执行 `flyway repair` 清掉 success=0 的那条历史记录，再重启即可重新执行本文件。
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

-- 别名/归档归属排查用：按互联网邮件 ID 等值查询（VARCHAR(998) 过长，取前缀 191）
SET @has_msg_id_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_message'
      AND INDEX_NAME = 'idx_email_message_internet_id');
SET @add_msg_id_index := IF(@has_msg_id_index = 0,
    'ALTER TABLE email_message ADD KEY idx_email_message_internet_id (internet_message_id(191))',
    'SELECT 1');
PREPARE stmt FROM @add_msg_id_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
