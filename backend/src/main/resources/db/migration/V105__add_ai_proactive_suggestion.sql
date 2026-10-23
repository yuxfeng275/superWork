-- ====================================
-- V105: AI 助手主动建议
-- 打开助手/工作台时按规则扫描数据源生成建议条，用户「去做 / 忽略」后落终态
-- ====================================

CREATE TABLE IF NOT EXISTS ai_proactive_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '建议归属用户',
    kind VARCHAR(40) NOT NULL COMMENT 'weekly_report / key_matter_week / meeting_todos / email_digest',
    dedupe_key VARCHAR(120) NOT NULL COMMENT '用户+规则+周期去重键',
    title VARCHAR(200) NOT NULL COMMENT '一句话建议标题',
    body VARCHAR(500) NULL COMMENT '补充说明（为什么现在提这件事）',
    prompt VARCHAR(1000) NOT NULL COMMENT '点「去做」时填入对话的提示词',
    target_path VARCHAR(200) NULL COMMENT '跳转页面路径',
    score INT NOT NULL DEFAULT 0 COMMENT '排序权重（规则基础分 + 数量/紧急度）',
    status VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT 'NEW / DONE / DISMISSED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_proactive_user_key (user_id, dedupe_key),
    INDEX idx_ai_proactive_user_status (user_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI 助手主动建议';
