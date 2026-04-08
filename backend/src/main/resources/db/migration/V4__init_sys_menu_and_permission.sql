-- ====================================
-- BU Management System - 系统菜单和权限
-- Version: 4.0
-- Date: 2026-04-04
-- Description: 创建系统菜单、权限、角色关联表
-- ====================================

-- ====================================
-- 1. 菜单表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID，0表示根菜单',
    name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    icon VARCHAR(100) COMMENT '菜单图标',
    path VARCHAR(200) COMMENT '路由路径',
    component VARCHAR(200) COMMENT '组件路径',
    sort_order INT DEFAULT 0 COMMENT '排序',
    visible TINYINT DEFAULT 1 COMMENT '是否显示：1=显示，0=隐藏',
    status TINYINT DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_parent (parent_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单表';

-- ====================================
-- 2. 权限表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    code VARCHAR(100) NOT NULL COMMENT '权限编码，如: system:user:list',
    name VARCHAR(100) NOT NULL COMMENT '权限名称',
    description VARCHAR(500) COMMENT '权限描述',
    type VARCHAR(20) NOT NULL COMMENT '权限类型：menu=菜单权限，button=按钮权限，api=接口权限',
    menu_id BIGINT COMMENT '关联菜单ID',
    status TINYINT DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_code (code),
    INDEX idx_menu (menu_id),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

-- ====================================
-- 3. 角色表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    code VARCHAR(50) NOT NULL COMMENT '角色编码',
    name VARCHAR(50) NOT NULL COMMENT '角色名称',
    description VARCHAR(200) COMMENT '角色描述',
    status TINYINT DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- ====================================
-- 4. 角色-菜单关联表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role (role_id),
    INDEX idx_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单关联表';

-- ====================================
-- 5. 角色-权限关联表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    INDEX idx_role (role_id),
    INDEX idx_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ====================================
-- 6. 用户-角色关联表
-- ====================================
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user (user_id),
    INDEX idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- ====================================
-- 初始化数据
-- ====================================

-- 插入角色
INSERT INTO sys_role (code, name, description) VALUES
('BU_ADMIN', 'BU负责人', 'BU负责人，拥有系统所有权限'),
('PM', '项目经理', '项目经理，管理项目需求和任务'),
('TECH_MANAGER', '技术经理', '技术经理，负责技术方案和任务分配'),
('PRODUCT_MANAGER', '产品经理', '产品经理，负责需求管理和设计'),
('DEVELOPER', '研发', '研发人员，负责开发和测试'),
('TESTER', '测试', '测试人员，负责测试工作'),
('UI_DESIGNER', 'UI设计', 'UI设计人员，负责原型和UI设计');

-- 插入菜单（前端路由）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order) VALUES
(0, '首页', 'Home', '/home', 'HomeView', 1),
(0, '需求管理', 'Document', '/requirements', 'RequirementsView', 2),
(0, '事项管理', 'Bug', '/issues', 'IssuesView', 3),
(0, '任务管理', 'Task', '/tasks', 'TasksView', 4),
(0, '数据统计', 'Chart', '/statistics', 'StatisticsView', 5),
(0, '组织架构', 'Office', '/organization', 'OrganizationView', 6),
(0, '系统管理', 'Setting', '/system', NULL, 100),
(7, '用户管理', 'User', '/system/users', 'SystemUserView', 101),
(7, '角色管理', 'Role', '/system/roles', 'SystemRoleView', 102),
(7, '菜单管理', 'Menu', '/system/menus', 'SystemMenuView', 103),
(7, '权限管理', 'Lock', '/system/permissions', 'SystemPermissionView', 104),
(7, '工作流配置', 'Workflow', '/system/workflow', 'SystemWorkflowView', 105);

-- 插入权限
INSERT INTO sys_permission (code, name, description, type, menu_id) VALUES
-- 需求权限
('requirement:list', '查看需求列表', '查看需求列表', 'button', 2),
('requirement:create', '创建需求', '创建新需求', 'button', 2),
('requirement:edit', '编辑需求', '编辑需求信息', 'button', 2),
('requirement:delete', '删除需求', '删除需求', 'button', 2),
('requirement:export', '导出需求', '导出需求数据', 'button', 2),
-- 事项权限
('issue:list', '查看事项列表', '查看事项列表', 'button', 3),
('issue:create', '创建事项', '创建新事项', 'button', 3),
('issue:edit', '编辑事项', '编辑事项信息', 'button', 3),
('issue:delete', '删除事项', '删除事项', 'button', 3),
-- 任务权限
('task:list', '查看任务列表', '查看任务列表', 'button', 4),
('task:create', '创建任务', '创建新任务', 'button', 4),
('task:edit', '编辑任务', '编辑任务信息', 'button', 4),
('task:assign', '分配任务', '分配任务给成员', 'button', 4),
-- 统计权限
('statistics:view', '查看统计数据', '查看统计数据', 'menu', 5),
-- 组织架构权限
('org:view', '查看组织架构', '查看组织架构', 'menu', 6),
('org:edit', '编辑组织架构', '编辑组织架构', 'button', 6),
-- 用户管理权限
('system:user:list', '查看用户列表', '查看用户列表', 'menu', 8),
('system:user:create', '创建用户', '创建新用户', 'button', 8),
('system:user:edit', '编辑用户', '编辑用户信息', 'button', 8),
('system:user:delete', '删除用户', '删除用户', 'button', 8),
-- 角色管理权限
('system:role:list', '查看角色列表', '查看角色列表', 'menu', 9),
('system:role:create', '创建角色', '创建新角色', 'button', 9),
('system:role:edit', '编辑角色', '编辑角色信息', 'button', 9),
('system:role:delete', '删除角色', '删除角色', 'button', 9),
-- 菜单管理权限
('system:menu:list', '查看菜单列表', '查看菜单列表', 'menu', 10),
('system:menu:create', '创建菜单', '创建新菜单', 'button', 10),
('system:menu:edit', '编辑菜单', '编辑菜单信息', 'button', 10),
('system:menu:delete', '删除菜单', '删除菜单', 'button', 10),
-- 权限管理权限
('system:permission:list', '查看权限列表', '查看权限列表', 'menu', 11),
('system:permission:assign', '分配权限', '为角色分配权限', 'button', 11),
-- 工作流配置权限
('system:workflow:list', '查看工作流配置', '查看工作流配置', 'menu', 12),
('system:workflow:edit', '编辑工作流配置', '编辑工作流配置', 'button', 12);

-- 为BU负责人角色分配所有菜单权限
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

-- 为BU负责人角色分配所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission;

-- 为admin用户分配BU负责人角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
