-- ====================================
-- BU Management System - 测试数据初始化
-- Version: 3.0
-- Date: 2026-04-02
-- Description: 初始化测试数据（仅开发环境）
-- ====================================

-- 1. 初始化业务线
INSERT INTO business_line (name, description, status) VALUES
('全渠道云鹿定制', '全渠道云鹿定制业务线', 1),
('全渠道云鹿SAAS', '全渠道云鹿SAAS业务线', 1),
('会员通', '会员通业务线', 1);

-- 2. 初始化用户（密码为 123456）
INSERT INTO user (username, password, real_name, role, email, phone, status) VALUES
('admin', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '系统管理员', 'BU_ADMIN', 'admin@bu.com', '13800000001', 1),
('pm_zhang', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '张项目经理', 'PM', 'pm.zhang@bu.com', '13800000002', 1),
('tech_li', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '李技术经理', 'TECH_MANAGER', 'tech.li@bu.com', '13800000003', 1),
('product_wang', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '王产品经理', 'PRODUCT', 'product.wang@bu.com', '13800000004', 1),
('dev_zhao', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '赵前端开发', 'DEVELOPER', 'dev.zhao@bu.com', '13800000005', 1),
('dev_qian', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '钱后端开发', 'DEVELOPER', 'dev.qian@bu.com', '13800000006', 1),
('test_sun', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '孙测试工程师', 'TESTER', 'test.sun@bu.com', '13800000007', 1),
('ui_zhou', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '周UI设计师', 'UI_DESIGN', 'ui.zhou@bu.com', '13800000008', 1);

-- 3. 初始化项目
INSERT INTO project (business_line_id, parent_id, level, name, full_path, code, manager_id, status) VALUES
-- 全渠道云鹿定制 - 主项目
(1, NULL, 1, '皇家项目', '皇家项目', 'ROYAL', 2, 1),
-- 全渠道云鹿定制 - 子项目
(1, 1, 2, 'PMS', '皇家项目/PMS', 'ROYAL-PMS', 2, 1),
(1, 1, 2, 'RC新体系', '皇家项目/RC新体系', 'ROYAL-RC', 2, 1),
(1, 1, 2, '数据看板', '皇家项目/数据看板', 'ROYAL-DASHBOARD', 2, 1),
-- 全渠道云鹿SAAS - 主项目
(2, NULL, 1, 'SAAS平台', 'SAAS平台', 'SAAS', 2, 1),
-- 会员通 - 主项目
(3, NULL, 1, '会员通系统', '会员通系统', 'MEMBER', 2, 1);

-- 4. 初始化项目成员
INSERT INTO project_member (project_id, user_id, role) VALUES
-- 皇家项目成员
(1, 2, '项目经理'),
(1, 3, '技术经理'),
(1, 4, '产品经理'),
(1, 5, '前端开发'),
(1, 6, '后端开发'),
(1, 7, '测试'),
(1, 8, 'UI设计'),
-- PMS 子项目成员
(2, 3, '技术经理'),
(2, 5, '前端开发'),
(2, 6, '后端开发'),
-- SAAS平台成员
(5, 2, '项目经理'),
(5, 3, '技术经理'),
(5, 4, '产品经理');

-- 5. 初始化客户联系人
INSERT INTO customer_contact (project_id, name, company, position, phone, email, is_active) VALUES
(1, 'Ember', '皇家集团', '产品总监', '13900000001', 'ember@royal.com', 1),
(1, 'Molly', '皇家集团', '项目经理', '13900000002', 'molly@royal.com', 1),
(2, '张三', '皇家集团', '业务负责人', '13900000003', 'zhangsan@royal.com', 1);

-- 6. 初始化示例需求
INSERT INTO requirement (req_no, title, description, type, business_line_id, project_id, customer_contact_id, status, priority, source, expected_online_date, creator_id) VALUES
('REQ-2026-0001', 'PMS系统用户管理模块优化', '优化用户管理模块，增加批量导入功能', '项目需求', 1, 2, 3, '待评估', '高', '客户', '2026-05-01', 2),
('REQ-2026-0002', 'SAAS平台多租户支持', '实现多租户架构，支持数据隔离', '产品需求', 2, 5, NULL, '待评估', '高', '内部', '2026-06-01', 4);

-- 注意：密码哈希值是 BCrypt 加密的 "123456"
-- 实际使用时需要在应用层使用 BCryptPasswordEncoder 加密
