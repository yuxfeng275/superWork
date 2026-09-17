-- ====================================
-- V47: AI 助手 GLM（智谱）模型配置项
-- 挂在通用系统配置机制（V29 system_config_item）下，新组 ai-agent。
-- 模型固定 glm-5.3；api-key 采用 AES-256-GCM 加密保存。
-- ====================================

INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
VALUES
('ai-agent', 'AI 助手', '管理 AI 助手使用的 GLM（智谱）模型', 'aiagent.enabled', '启用 AI 助手', '是否允许使用 AI 助手', 'BOOLEAN', 'false', 0, 1, 10, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的 GLM（智谱）模型', 'aiagent.base-url', 'GLM 服务地址', '智谱开放平台 API 根地址', 'URL', 'https://open.bigmodel.cn/api/paas/v4', 0, 1, 20, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的 GLM（智谱）模型', 'aiagent.model', 'GLM 模型', 'AI 助手使用的模型名称', 'STRING', 'glm-5.3', 0, 1, 30, 1),
('ai-agent', 'AI 助手', '管理 AI 助手使用的 GLM（智谱）模型', 'aiagent.api-key', 'GLM API Key', '智谱开放平台调用凭据，采用 AES-256-GCM 加密保存', 'PASSWORD', NULL, 1, 0, 40, 1);
