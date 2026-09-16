-- ====================================
-- V79: 连接器收口（Connector Hub）
-- 1) ai_connector 扩展 extra_config（系统专属参数，JSON）
-- 2) 补齐内置连接器：云效 / 邮件 / DeepSeek / 智谱 GLM / 企业微信
--    （worktime / yuque / oa 已由 V52 预置）
-- 3) 收口方向：云效/工时/OA 配置表、system_config 组（ai-connector、email-integration、
--    ai-agent 的模型凭据、weekly-report 的语雀参数、oa-vreport）里的连接参数，
--    由启动迁移器 ConnectorLegacyConfigMigrator 一次性搬入 ai_connector（值迁移无法在
--    SQL 完成：旧密文由各自 cipher/key 加密），搬迁完成后源配置组置 status=0。
-- 设计文档见 docs/connector-hub-design.md
-- ====================================

ALTER TABLE ai_connector
    ADD COLUMN extra_config TEXT NULL COMMENT '系统专属扩展参数(JSON)';

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order)
SELECT 'yunxiao', '云效', 'TOKEN', 'https://openapi-rdc.aliyuncs.com', NULL, NULL, 0, 1, 5
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'yunxiao');

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order)
SELECT 'mail', '邮件', 'MAIL', '', NULL, NULL, 1, 1, 15
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'mail');

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order, extra_config)
SELECT 'deepseek', 'DeepSeek', 'TOKEN', 'https://api.deepseek.com', NULL, NULL, 0, 1, 40,
       '{"model":"deepseek-v4-flash","digestModel":"deepseek-chat","digestEnabled":false}'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'deepseek');

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order, extra_config)
SELECT 'glm', '智谱 GLM', 'TOKEN', 'https://open.bigmodel.cn/api/paas/v4', NULL, NULL, 0, 1, 45,
       '{"model":"glm-5.3"}'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'glm');

INSERT INTO ai_connector
(code, name, auth_type, base_url, mcp_url, test_path, enabled, built_in, sort_order)
SELECT 'wecom', '企业微信', 'WECOM', 'https://qyapi.weixin.qq.com', NULL, NULL, 0, 1, 50
FROM dual WHERE NOT EXISTS (SELECT 1 FROM ai_connector WHERE code = 'wecom');

-- 内置连接器补齐默认 extra（仅当为空时）
UPDATE ai_connector SET extra_config = '{"edition":"center"}'
WHERE code = 'yunxiao' AND (extra_config IS NULL OR extra_config = '');
UPDATE ai_connector SET extra_config = '{"searchDays":90}'
WHERE code = 'mail' AND (extra_config IS NULL OR extra_config = '');
UPDATE ai_connector SET extra_config = '{"repo":"vuntcs/cf_records"}'
WHERE code = 'yuque' AND (extra_config IS NULL OR extra_config = '');

-- 内置连接器认证类型归一（历史上被页面改成 BASIC，导致致远 REST 探活用错请求体）
UPDATE ai_connector SET auth_type = 'BASIC' WHERE code = 'worktime' AND auth_type <> 'BASIC';
UPDATE ai_connector SET auth_type = 'MCP' WHERE code = 'yuque' AND auth_type <> 'MCP';
UPDATE ai_connector SET auth_type = 'SEEYON' WHERE code = 'oa' AND auth_type <> 'SEEYON';
UPDATE ai_connector SET test_path = '/seeyon/rest/token' WHERE code = 'oa' AND (test_path IS NULL OR test_path = '/api/v1/auth/login');
UPDATE ai_connector SET test_path = '/api/v1/auth/login' WHERE code = 'worktime' AND test_path IS NULL;

-- 菜单收口：原「AI连接器」改为「连接器管理」，挂到系统管理分组（/system）
UPDATE sys_menu
SET name = '连接器管理',
    icon = 'Connection',
    path = '/system/connectors',
    component = 'ConnectorManageView',
    sort_order = 106,
    updated_at = NOW()
WHERE path = '/ai-connectors';

-- 搬迁标记（隐藏配置组：status=0 不出现在配置管理；值由 ConnectorLegacyConfigMigrator 写入）
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'yunxiao.migrated', '云效配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 10, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'yunxiao.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'worktime.migrated', '工时配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 20, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'worktime.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'oa.migrated', 'OA 配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 30, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'oa.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'yuque.migrated', '语雀配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 40, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'yuque.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'mail.migrated', '邮件配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 50, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'mail.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'deepseek.migrated', 'DeepSeek 配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 60, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'deepseek.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'glm.migrated', 'GLM 配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 70, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'glm.migrated') t);
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'connector-hub', '连接器收口', '历史连接配置搬迁标记（内部使用，不展示）', 'wecom.migrated', '企业微信配置搬迁', 'done=已从旧配置搬迁到连接器', 'STRING', NULL, 0, 0, 80, 0
FROM dual WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM system_config_item WHERE group_code = 'connector-hub' AND config_key = 'wecom.migrated') t);
