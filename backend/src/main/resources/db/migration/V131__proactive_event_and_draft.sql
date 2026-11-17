-- 主动性事件触发与 L2 草稿（2026-11-17）
-- 1) 建议条新增 draft：点「去做」时把已生成的草稿预填进输入框，而不是只给一句提示词；
-- 2) 事件表：邮件同步 / 会议纪要 / 周报失败三类事件各留一行，证明「是谁、什么时候、因为什么」触发了扫描。

ALTER TABLE ai_proactive_suggestion
    ADD COLUMN draft TEXT NULL COMMENT '点「去做」时预填的草稿正文（确定性生成，不再调模型）';

CREATE TABLE IF NOT EXISTS ai_proactive_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '事件归属人：只扫这个人的建议',
    kind VARCHAR(40) NOT NULL COMMENT 'EMAIL_SYNCED / MEETING_MINUTES / WEEKLY_REPORT_FAILED',
    ref_id VARCHAR(80) NULL COMMENT '来源对象 ID：邮箱账号 / 会议 / 周报',
    created_at DATETIME NOT NULL COMMENT '事件发生时间',
    KEY idx_proactive_event_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = '主动性事件触发日志';
