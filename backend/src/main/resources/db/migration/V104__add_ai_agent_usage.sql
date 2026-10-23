-- ====================================
-- V104: AI 助手单轮用量（token / 成本）
-- 侧车 run_end 返回的 assistant 消息里带 usage，落库用于用量与成本统计
-- ====================================

CREATE TABLE IF NOT EXISTS ai_agent_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL COMMENT '侧车运行 ID（幂等键，重复 run_end 不重复计）',
    session_id BIGINT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '发起用户',
    provider VARCHAR(50) NULL COMMENT '模型提供方（连接器编码）',
    model VARCHAR(100) NULL COMMENT '模型名',
    input_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '输入 token',
    output_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '输出 token（含思考）',
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd DECIMAL(14, 6) NOT NULL DEFAULT 0 COMMENT '侧车按模型定价估算的成本（美元）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_agent_usage_run (run_id),
    INDEX idx_ai_agent_usage_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI 助手单轮用量';
