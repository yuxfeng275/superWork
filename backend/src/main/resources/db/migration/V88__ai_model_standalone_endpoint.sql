-- ====================================
-- V88: 模型独立接入（协议 / 地址 / API Key）
-- 模型不再必须挂连接器：可直接填 OpenAI 兼容地址（官方、中转站、自建）。
-- 未填时仍回落同名连接器的地址与凭据，兼容现有 deepseek / glm / typesafe。
-- ====================================

ALTER TABLE ai_model
    ADD COLUMN api_protocol VARCHAR(32) NOT NULL DEFAULT 'openai-compat'
        COMMENT '接入协议：openai-compat（对话/摘要）/ typesafe（Jev 决策）'
        AFTER provider_code,
    ADD COLUMN base_url VARCHAR(512) NULL
        COMMENT '模型自己的接口地址；空则回落同名连接器'
        AFTER display_name,
    ADD COLUMN encrypted_api_key VARCHAR(512) NULL
        COMMENT '模型自己的 API Key（AES）；空则回落同名连接器'
        AFTER base_url;

UPDATE ai_model
SET api_protocol = 'typesafe'
WHERE provider_code = 'typesafe';
