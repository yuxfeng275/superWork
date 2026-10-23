-- ====================================
-- V106: AI 助手主动建议的站外推送记录
-- 用户不登录系统时，每天最多一条企微消息（每日简报），点链接直达助手并预填提示词
-- ====================================

CREATE TABLE IF NOT EXISTS ai_proactive_push (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '接收用户',
    channel VARCHAR(20) NOT NULL COMMENT 'WECOM / MAIL',
    suggestion_ids VARCHAR(200) NULL COMMENT '本次涉及的建 id，逗号分隔',
    content VARCHAR(2000) NULL COMMENT '实际推送文本（排障用）',
    status VARCHAR(20) NOT NULL COMMENT 'SENT / FAILED / SKIPPED',
    reason VARCHAR(300) NULL COMMENT 'SENT 时为空；SKIPPED/FAILED 写原因',
    pushed_on DATE NOT NULL COMMENT '推送业务日期（每天一条）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_proactive_push_day (user_id, pushed_on),
    INDEX idx_ai_proactive_push_status (status, pushed_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI 主动建议站外推送记录';
