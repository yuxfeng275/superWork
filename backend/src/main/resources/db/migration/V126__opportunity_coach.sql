-- 商机 AI 教练建议快照：一次生成一行，只读建议，不参与商机字段计算。
-- 触发链路：商机问句回复入站（INBOUND） / 人工点「重新生成」（MANUAL） / 定时任务（SCHEDULED）。
CREATE TABLE opportunity_coach (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    opportunity_id BIGINT NOT NULL COMMENT '商机 ID',
    prompt_id BIGINT NULL COMMENT '触发的问句 ID（手动生成时为空）',
    trigger_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'INBOUND/MANUAL/SCHEDULED',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/SUCCESS/FAILED',
    content_json TEXT NULL COMMENT 'AI 教练包 JSON（readiness/diagnosis/risks/questions/solutions/nextActions/probabilitySuggestion）',
    provider VARCHAR(32) NULL,
    model VARCHAR(128) NULL,
    error VARCHAR(500) NULL,
    requested_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    generated_at DATETIME NULL,
    KEY idx_coach_opp (opportunity_id, created_at),
    KEY idx_coach_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机 AI 教练建议快照（只读建议，不参与商机字段计算）';
