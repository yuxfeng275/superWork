-- ====================================
-- BU Management System - 工作流配置初始化
-- Version: 2.0
-- Date: 2026-04-02
-- Description: 初始化默认工作流配置
-- ====================================

-- 项目需求工作流配置
INSERT INTO workflow_config (requirement_type, from_status, to_status, allowed_roles, condition_type, is_active, sort_order) VALUES
-- 待评估 → 评估中
('项目需求', '待评估', '评估中', '["技术经理", "产品经理"]', NULL, 1, 1),
-- 评估中 → 已拒绝
('项目需求', '评估中', '已拒绝', '["BU负责人"]', '{"type": "evaluation_completed"}', 1, 2),
-- 评估中 → 待设计
('项目需求', '评估中', '待设计', '["BU负责人"]', '{"type": "evaluation_completed", "decision": "approved"}', 1, 3),
-- 评估中 → 转产品需求
('项目需求', '评估中', '转产品需求', '["BU负责人"]', '{"type": "evaluation_suggest_product"}', 1, 4),
-- 待设计 → 设计中
('项目需求', '待设计', '设计中', '["产品经理", "UI设计", "技术经理"]', NULL, 1, 5),
-- 设计中 → 待确认
('项目需求', '设计中', '待确认', '["系统自动"]', '{"type": "all_design_completed"}', 1, 6),
-- 待确认 → 开发中
('项目需求', '待确认', '开发中', '["项目经理"]', '{"type": "confirmation_completed"}', 1, 7),
-- 开发中 → 测试中
('项目需求', '开发中', '测试中', '["技术经理"]', '{"type": "all_tasks_completed"}', 1, 8),
-- 测试中 → 待上线
('项目需求', '测试中', '待上线', '["测试"]', '{"type": "test_passed"}', 1, 9),
-- 待上线 → 已上线
('项目需求', '待上线', '已上线', '["项目经理"]', NULL, 1, 10),
-- 已上线 → 已交付
('项目需求', '已上线', '已交付', '["项目经理"]', NULL, 1, 11),
-- 已交付 → 已验收
('项目需求', '已交付', '已验收', '["项目经理"]', NULL, 1, 12);

-- 产品需求工作流配置
INSERT INTO workflow_config (requirement_type, from_status, to_status, allowed_roles, condition_type, is_active, sort_order) VALUES
-- 待评估 → 评估中
('产品需求', '待评估', '评估中', '["技术经理", "产品经理"]', NULL, 1, 1),
-- 评估中 → 已拒绝
('产品需求', '评估中', '已拒绝', '["BU负责人"]', '{"type": "evaluation_completed"}', 1, 2),
-- 评估中 → 待设计
('产品需求', '评估中', '待设计', '["BU负责人"]', '{"type": "evaluation_completed", "decision": "approved"}', 1, 3),
-- 待设计 → 设计中
('产品需求', '待设计', '设计中', '["产品经理", "UI设计", "技术经理"]', NULL, 1, 4),
-- 设计中 → 待确认
('产品需求', '设计中', '待确认', '["系统自动"]', '{"type": "all_design_completed"}', 1, 5),
-- 待确认 → 开发中
('产品需求', '待确认', '开发中', '["产品经理"]', '{"type": "confirmation_completed"}', 1, 6),
-- 开发中 → 测试中
('产品需求', '开发中', '测试中', '["技术经理"]', '{"type": "all_tasks_completed"}', 1, 7),
-- 测试中 → 待上线
('产品需求', '测试中', '待上线', '["测试"]', '{"type": "test_passed"}', 1, 8),
-- 待上线 → 已上线
('产品需求', '待上线', '已上线', '["产品经理"]', NULL, 1, 9);
