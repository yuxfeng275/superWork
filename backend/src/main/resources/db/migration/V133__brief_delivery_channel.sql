-- 晨报送达通道（2026-11-18）
-- 07:30 定时任务此前只在企微/Bark 成功时写 delivered_at；两个通道都未配置时晨报已经在工作台可见，
-- 但 delivered_at 永远为空，看起来像「没送达」。这里把站内送达也记为一次真实送达（IN_APP），
-- 外部通道成功后分别记 WECOM / BARK；不是外发替代，只是把「送达」口径说清楚。
ALTER TABLE ai_brief
    ADD COLUMN delivery_channel VARCHAR(20) NULL COMMENT '送达通道：IN_APP / WECOM / BARK';
