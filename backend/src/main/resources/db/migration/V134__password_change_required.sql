-- 管理员重置密码后的账号：下次登录必须改密（2026-11-18）
-- 只对 reset-password 的账号置 1，不批量标记历史初始密码账号，避免无通知锁死。
ALTER TABLE user
    ADD COLUMN password_change_required TINYINT NOT NULL DEFAULT 0
        COMMENT '1=下次登录必须修改密码（管理员重置后置 1，改密后清 0）';
