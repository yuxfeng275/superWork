-- ====================================
-- V53: 站内通知已读状态
-- 通知内容实时聚合（工时缺填/OA待办/邮箱未绑定），本表仅记录
-- "用户在某天已读某类通知"，避免重复提醒。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_notice_read (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '本地用户 ID',
    notice_kind VARCHAR(32) NOT NULL COMMENT '通知类型：WORKLOG_MISSING/OA_PENDING/EMAIL_UNBOUND',
    notice_date DATE NOT NULL COMMENT '通知归属日期（幂等键的一部分）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_kind_date (user_id, notice_kind, notice_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知已读记录';
