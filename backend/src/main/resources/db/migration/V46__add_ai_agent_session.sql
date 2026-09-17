-- ====================================
-- V46: AI 助手会话（Pi Agent）
-- 会话归属当前用户，消息以 AgentMessage JSON 数组（旧→新）持久化，
-- 模型固定走 GLM（智谱），provider/model 由侧车实际调用。
-- ====================================

CREATE TABLE ai_agent_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL COMMENT '归属用户',
    title VARCHAR(200) NOT NULL DEFAULT '新的对话' COMMENT '会话标题',
    provider VARCHAR(50) NOT NULL DEFAULT 'zhipu' COMMENT '模型提供方，固定 zhipu',
    model VARCHAR(100) NOT NULL DEFAULT 'glm-5.3' COMMENT '模型名称，固定 glm-5.3',
    messages_json MEDIUMTEXT NULL COMMENT 'AgentMessage JSON 数组,旧→新',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_agent_session_owner (owner_user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 助手会话';
