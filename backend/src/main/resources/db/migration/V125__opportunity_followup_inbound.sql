-- 商机跟进问句：补企微入站幂等键与会话 ID
-- 用途：企微机器人回调只带发送人 userid + msgid，按 inbound_msg_id 幂等，按 (wecom_userid, sent_at) 反查最近未回复问句
ALTER TABLE opportunity_followup_prompt
    ADD COLUMN inbound_msg_id VARCHAR(64) NULL COMMENT '企微入站消息 ID（幂等键）',
    ADD COLUMN chat_id VARCHAR(64) NULL COMMENT '企微会话 ID',
    ADD UNIQUE KEY uk_opp_prompt_inbound_msg (inbound_msg_id),
    ADD KEY idx_opp_prompt_sender (wecom_userid, sent_at);
