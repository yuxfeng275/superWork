-- ====================================
-- V50: AI 助手多模型支持（新增 DeepSeek 配置）
-- 在 ai-agent 配置组下增加 DeepSeek 专属配置项，与 GLM 配置并列；
-- 会话创建时选择 provider，运行时按 provider 读取对应配置。
-- api-key 采用 AES-256-GCM 加密保存。
-- ====================================

UPDATE system_config_item
SET group_description = '管理 AI 助手使用的模型（GLM / DeepSeek）'
WHERE group_code = 'ai-agent';

INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
VALUES
('ai-agent', 'AI 助手', '管理 AI 助手使用的模型（GLM / DeepSeek）', 'aiagent.deepseek.enabled', '启用 DeepSeek', '是否允许 AI 助手使用 DeepSeek 模型', 'BOOLEAN', 'false', 0, 1, 50, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的模型（GLM / DeepSeek）', 'aiagent.deepseek.base-url', 'DeepSeek 服务地址', 'DeepSeek OpenAI 兼容接口根地址', 'URL', 'https://api.deepseek.com', 0, 1, 60, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的模型（GLM / DeepSeek）', 'aiagent.deepseek.model', 'DeepSeek 模型', 'AI 助手 DeepSeek 使用的模型名称', 'STRING', 'deepseek-v4-flash', 0, 1, 70, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的模型（GLM / DeepSeek）', 'aiagent.deepseek.api-key', 'DeepSeek API Key', 'DeepSeek 调用凭据，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 80, 1);
