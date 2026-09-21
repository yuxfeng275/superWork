-- ====================================
-- V87: TypeSafe Jev（System One）连接器 + 决策用途模型
-- Jev 不是对话模型：不进 AI 助手下拉，只做意图路由 / 写操作门禁。
-- ====================================

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order)
SELECT 'typesafe', 'TypeSafe Jev', 'TOKEN', 'https://api.typesafe.ai', NULL, '/v1/systemone', 0, 1, 42
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'typesafe');

ALTER TABLE ai_model
    ADD COLUMN decision_enabled TINYINT NOT NULL DEFAULT 0
        COMMENT '用于 AI 助手意图路由与写操作门禁（System One / Jev）'
        AFTER digest_enabled;

INSERT IGNORE INTO ai_model
(provider_code, model, display_name, assistant_enabled, digest_enabled, decision_enabled, is_default, enabled, sort_order)
VALUES ('typesafe', 'jev-latest', 'Jev（System One）', 0, 0, 1, 0, 1, 5);
