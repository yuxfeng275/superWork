-- ====================================
-- V83: 企微连接器「机器人通道」凭据列
-- wecom-cli（官方 CLI）走智能机器人通道（Bot ID + Bot Secret），
-- 与应用通道的应用 Secret（encrypted_token）是两个授权域，分列存储避免语义混淆。
-- Bot ID 非敏感，存 ai_connector.extra_config.botId；Bot Secret 加密存本列。
-- ====================================

SET @col_exists := (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_connector' AND COLUMN_NAME = 'encrypted_bot_secret'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE ai_connector ADD COLUMN encrypted_bot_secret VARCHAR(512) NULL COMMENT ''企微机器人 Bot Secret（AES-GCM）'' AFTER encrypted_token',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
