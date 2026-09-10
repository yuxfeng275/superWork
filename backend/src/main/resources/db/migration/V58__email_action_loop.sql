-- ====================================
-- V54: 邮件行动闭环（整改方案 P0-1/P0-2/P1-4/P2-5）
-- email_action：摘要待办/风险 → 任务/大事儿/事项 的转化记录与闭环状态
-- email_sent_reply：SMTP 发送记录（挂在原邮件会话下）
-- ====================================

CREATE TABLE email_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL COMMENT '归属用户（与邮件同隔离）',
    message_id BIGINT NOT NULL COMMENT '来源邮件',
    item_kind VARCHAR(16) NOT NULL COMMENT 'TODO/RISK/IMPORTANT/REPLY',
    item_title VARCHAR(500) NOT NULL COMMENT '摘要条目标题',
    action_type VARCHAR(16) NOT NULL COMMENT 'TASK/ISSUE/KEY_MATTER',
    target_id BIGINT NOT NULL COMMENT '任务/事项/大事儿 ID',
    target_title VARCHAR(200) NULL COMMENT '冗余标题，便于展示',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    closed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_action_owner FOREIGN KEY (owner_user_id) REFERENCES user(id),
    CONSTRAINT fk_email_action_message FOREIGN KEY (message_id) REFERENCES email_message(id) ON DELETE CASCADE,
    INDEX idx_email_action_owner (owner_user_id, status),
    INDEX idx_email_action_message (message_id),
    INDEX idx_email_action_target (action_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮件待办/风险转化闭环';

CREATE TABLE email_sent_reply (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL COMMENT '发信人（与邮件同隔离）',
    message_id BIGINT NOT NULL COMMENT '被回复的邮件',
    to_address VARCHAR(320) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    body_text TEXT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'SENT/FAILED',
    error_message VARCHAR(500) NULL,
    sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_reply_owner FOREIGN KEY (owner_user_id) REFERENCES user(id),
    CONSTRAINT fk_email_reply_message FOREIGN KEY (message_id) REFERENCES email_message(id) ON DELETE CASCADE,
    INDEX idx_email_reply_owner_message (owner_user_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮件 SMTP 回复发送记录';

ALTER TABLE email_daily_digest
    ADD COLUMN feedback VARCHAR(8) NULL COMMENT 'USEFUL/USELESS 用户反馈' AFTER push_error,
    ADD COLUMN closed_total INT NOT NULL DEFAULT 0 COMMENT '快照：本次摘要待办+风险数' AFTER feedback,
    ADD COLUMN closed_done INT NOT NULL DEFAULT 0 COMMENT '快照：生成时已闭环数' AFTER closed_total;
