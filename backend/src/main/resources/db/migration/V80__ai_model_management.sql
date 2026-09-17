-- ====================================
-- V80: 模型管理（AI 模型从连接器中抽离）
-- 连接器只管「连接」（地址 + 凭据 + 启停 + 测试）；模型（模型名 + 用途 + 默认）独立成表，
-- 页面入口：系统管理 → 系统配置 → 模型管理（/system/models）。
-- 种子从连接器 extra_config 的 model/digestModel/digestEnabled 迁入，迁完从连接器里移除这些键。
-- 设计文档见 docs/connector-hub-design.md
-- ====================================

CREATE TABLE IF NOT EXISTS ai_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_code VARCHAR(64) NOT NULL COMMENT '提供方连接器编码（deepseek/glm/...）',
    model VARCHAR(128) NOT NULL COMMENT 'API 模型名（传给模型的 model 参数）',
    display_name VARCHAR(128) NOT NULL COMMENT '管理页与助手下拉的展示名',
    assistant_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '可用于 AI 助手',
    digest_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '用于邮件摘要 / 周报纪要',
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT 'AI 助手默认模型（全局唯一）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_model (provider_code, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型注册表';

-- 1) 助手模型：从连接器 extra_config.model 搬入（缺失时回落历史默认模型名）
INSERT INTO ai_model (provider_code, model, display_name, assistant_enabled, is_default, enabled, sort_order)
SELECT c.code,
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.model')),
                CASE c.code WHEN 'deepseek' THEN 'deepseek-v4-flash' ELSE 'glm-5.3' END),
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.model')),
                CASE c.code WHEN 'deepseek' THEN 'deepseek-v4-flash' ELSE 'glm-5.3' END),
       1,
       CASE c.code WHEN 'glm' THEN 1 ELSE 0 END,
       1,
       CASE c.code WHEN 'deepseek' THEN 10 ELSE 40 END
FROM ai_connector c
WHERE c.code IN ('deepseek', 'glm')
  AND NOT EXISTS (SELECT 1 FROM ai_model m WHERE m.provider_code = c.code);

-- 2) 摘要模型：连接器里配过 digestModel 时单独成行（与助手模型同名则复用同一行）
INSERT IGNORE INTO ai_model (provider_code, model, display_name, assistant_enabled, digest_enabled, enabled, sort_order)
SELECT 'deepseek',
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.digestModel')),
                JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.model')),
                'deepseek-chat'),
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.digestModel')),
                JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.model')),
                'deepseek-chat'),
       0, 1, 1, 20
FROM ai_connector c
WHERE c.code = 'deepseek'
  AND JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.digestEnabled')) = 'true';

-- 摘要用途打在对应模型行上（含与助手模型同名的情况）
UPDATE ai_model m
JOIN ai_connector c ON c.code = 'deepseek'
SET m.digest_enabled = 1
WHERE m.provider_code = 'deepseek'
  AND m.model = COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.digestModel')),
                         JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.model')))
  AND JSON_UNQUOTE(JSON_EXTRACT(c.extra_config, '$.digestEnabled')) = 'true';

-- 3) 备选模型（默认不勾选用途，管理员可在模型管理里一键启用）
INSERT IGNORE INTO ai_model (provider_code, model, display_name, assistant_enabled, digest_enabled, enabled, sort_order)
VALUES ('deepseek', 'deepseek-chat', 'deepseek-chat', 0, 0, 1, 30),
       ('glm', 'glm-5.3', 'glm-5.3', 0, 0, 1, 50);

-- 4) 连接器不再是模型的存放处：移除模型相关扩展参数
UPDATE ai_connector
SET extra_config = JSON_REMOVE(extra_config, '$.model', '$.digestModel', '$.digestEnabled')
WHERE code IN ('deepseek', 'glm') AND extra_config IS NOT NULL;

-- 5) 菜单：系统配置 → 模型管理（与配置管理/连接器管理同组）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT parent_id AS id FROM sys_menu WHERE path = '/system/connectors' LIMIT 1) t),
       '模型管理', 'ChatDotRound', '/system/models', 'ModelManageView', 108, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/models');

-- 6) 授权：跟随「连接器管理 / 配置管理」已有授权
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, (SELECT id FROM sys_menu WHERE path = '/system/models')
FROM sys_role_menu rm
WHERE rm.menu_id IN (SELECT id FROM sys_menu WHERE path IN ('/system/connectors', '/system/configs'));
