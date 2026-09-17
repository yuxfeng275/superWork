-- 电商BU内部管理系统 - 数据库初始化脚本
-- 创建时间: 2026-04-03
-- 数据库: MySQL 8.0+

-- 创建数据库
CREATE DATABASE IF NOT EXISTS bu_management DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE bu_management;

-- 1. 业务线表
CREATE TABLE IF NOT EXISTS business_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '业务线名称',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '业务线编码',
    description TEXT COMMENT '业务线描述',
    leader_id BIGINT COMMENT '负责人ID',
    status VARCHAR(20) DEFAULT '启用' COMMENT '状态：启用/停用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_code (code),
    INDEX idx_leader_id (leader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务线表';

-- 2. 项目表
CREATE TABLE IF NOT EXISTS project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    business_line_id BIGINT NOT NULL COMMENT '业务线ID',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '项目编码',
    description TEXT COMMENT '项目描述',
    parent_id BIGINT COMMENT '父项目ID',
    manager_id BIGINT COMMENT '项目经理ID',
    status VARCHAR(20) DEFAULT '进行中' COMMENT '状态：进行中/已完成/已暂停',
    start_date DATE COMMENT '开始日期',
    end_date DATE COMMENT '结束日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_business_line_id (business_line_id),
    INDEX idx_parent_id (parent_id),
    INDEX idx_manager_id (manager_id),
    INDEX idx_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';

-- 3. 用户表
CREATE TABLE IF NOT EXISTS user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码（加密）',
    real_name VARCHAR(50) NOT NULL COMMENT '真实姓名',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    role VARCHAR(50) NOT NULL COMMENT '角色：BU负责人/项目经理/技术经理/产品经理/研发/测试/UI设计',
    status VARCHAR(20) DEFAULT '启用' COMMENT '状态：启用/停用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 4. 项目成员表
CREATE TABLE IF NOT EXISTS project_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(50) NOT NULL COMMENT '项目角色',
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_project_user (project_id, user_id),
    INDEX idx_project_id (project_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员表';

-- 5. 客户联系人表
CREATE TABLE IF NOT EXISTS customer_contact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    name VARCHAR(50) NOT NULL COMMENT '联系人姓名',
    title VARCHAR(50) COMMENT '职位',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    company VARCHAR(100) COMMENT '公司名称',
    notes TEXT COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户联系人表';

-- 6. 需求表
CREATE TABLE IF NOT EXISTS requirement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    req_no VARCHAR(50) NOT NULL UNIQUE COMMENT '需求编号',
    business_line_id BIGINT NOT NULL COMMENT '业务线ID',
    project_id BIGINT COMMENT '项目ID（产品需求为空）',
    customer_contact_id BIGINT COMMENT '客户联系人ID（产品需求为空）',
    title VARCHAR(200) NOT NULL COMMENT '需求标题',
    description TEXT COMMENT '需求描述',
    type VARCHAR(20) NOT NULL COMMENT '需求类型：项目需求/产品需求',
    status VARCHAR(20) DEFAULT '待评估' COMMENT '状态',
    priority VARCHAR(20) DEFAULT '中' COMMENT '优先级：低/中/高',
    created_by BIGINT NOT NULL COMMENT '创建人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_req_no (req_no),
    INDEX idx_business_line_id (business_line_id),
    INDEX idx_project_id (project_id),
    INDEX idx_status (status),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求表';

-- 7. 需求评估表
CREATE TABLE IF NOT EXISTS requirement_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL UNIQUE COMMENT '需求ID',
    technical_feasibility TEXT COMMENT '技术可行性分析',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时',
    estimated_cost DECIMAL(10,2) COMMENT '预估报价',
    work_breakdown TEXT COMMENT '工作拆解',
    evaluator_id BIGINT NOT NULL COMMENT '评估人ID',
    evaluated_at DATETIME COMMENT '评估时间',
    decision VARCHAR(20) COMMENT 'BU决策：通过/拒绝/转产品需求',
    decision_notes TEXT COMMENT '决策备注',
    decision_at DATETIME COMMENT '决策时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求评估表';

-- 8. 需求设计汇总表
CREATE TABLE IF NOT EXISTS requirement_design (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL UNIQUE COMMENT '需求ID',
    prototype_status VARCHAR(20) DEFAULT '未开始' COMMENT '原型设计状态：未开始/进行中/已完成',
    ui_status VARCHAR(20) DEFAULT '未开始' COMMENT 'UI设计状态：未开始/进行中/已完成',
    tech_solution_status VARCHAR(20) DEFAULT '未开始' COMMENT '技术方案状态：未开始/进行中/已完成',
    all_completed_at DATETIME COMMENT '全部完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求设计汇总表';

-- 9. 设计工作记录表
CREATE TABLE IF NOT EXISTS design_work_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    work_type VARCHAR(50) NOT NULL COMMENT '工作类型：原型设计/UI设计/技术方案设计',
    designer_id BIGINT NOT NULL COMMENT '设计人ID',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时',
    actual_hours DECIMAL(10,2) COMMENT '实际工时',
    result_url VARCHAR(500) COMMENT '成果物链接或文件ID',
    work_content TEXT COMMENT '工作内容描述',
    planned_completed_at DATETIME COMMENT '计划完成时间',
    status VARCHAR(20) DEFAULT '待开始' COMMENT '状态：待开始/进行中/已完成',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id),
    INDEX idx_work_type (work_type),
    INDEX idx_designer_id (designer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计工作记录表';

-- 10. 需求确认表
CREATE TABLE IF NOT EXISTS requirement_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL UNIQUE COMMENT '需求ID',
    confirmation_type VARCHAR(20) NOT NULL COMMENT '确认类型：客户确认/内部确认',
    confirmed_by BIGINT NOT NULL COMMENT '确认人ID',
    confirmed_at DATETIME NOT NULL COMMENT '确认时间',
    confirmation_notes TEXT COMMENT '确认备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求确认表';

-- 11. 需求交付表
CREATE TABLE IF NOT EXISTS requirement_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL UNIQUE COMMENT '需求ID',
    delivered_at DATETIME NOT NULL COMMENT '交付时间',
    delivered_by BIGINT NOT NULL COMMENT '交付人ID',
    delivery_notes TEXT COMMENT '交付说明',
    accepted_by BIGINT COMMENT '验收人ID',
    accepted_at DATETIME COMMENT '验收时间',
    acceptance_notes TEXT COMMENT '验收备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求交付表';

-- 12. 任务表
CREATE TABLE IF NOT EXISTS task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    title VARCHAR(200) NOT NULL COMMENT '任务标题',
    description TEXT COMMENT '任务描述',
    task_type VARCHAR(20) NOT NULL DEFAULT '开发任务' COMMENT '任务类型：前端开发/后端开发/测试/UI设计/开发任务',
    assignee_id BIGINT COMMENT '负责人ID',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时',
    actual_hours DECIMAL(10,2) COMMENT '实际工时',
    status VARCHAR(20) DEFAULT '待开始' COMMENT '状态：待开始/进行中/已完成',
    priority VARCHAR(20) DEFAULT '中' COMMENT '优先级：低/中/高',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_requirement_id (requirement_id),
    INDEX idx_assignee_id (assignee_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- 13. 工时记录表
CREATE TABLE IF NOT EXISTS work_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    work_date DATE NOT NULL COMMENT '工作日期',
    hours DECIMAL(10,2) NOT NULL COMMENT '工时（小时）',
    work_content TEXT COMMENT '工作内容描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_work_date (work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工时记录表';

-- 14. 事项表
CREATE TABLE IF NOT EXISTS issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    related_type VARCHAR(20) NOT NULL COMMENT '关联类型：需求/任务',
    related_id BIGINT NOT NULL COMMENT '关联ID',
    title VARCHAR(200) NOT NULL COMMENT '事项标题',
    description TEXT COMMENT '事项描述',
    issue_type VARCHAR(20) NOT NULL COMMENT '事项类型：问题/风险/变更',
    severity VARCHAR(20) DEFAULT '中' COMMENT '严重程度：低/中/高/紧急',
    status VARCHAR(20) DEFAULT '待处理' COMMENT '状态：待处理/处理中/已解决/已关闭',
    assignee_id BIGINT COMMENT '负责人ID',
    created_by BIGINT NOT NULL COMMENT '创建人ID',
    resolution TEXT COMMENT '解决方案',
    resolved_at DATETIME COMMENT '解决时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_related (related_type, related_id),
    INDEX idx_status (status),
    INDEX idx_assignee_id (assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事项表';

-- 15. 附件表
CREATE TABLE IF NOT EXISTS attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    related_type VARCHAR(50) NOT NULL COMMENT '关联类型：需求/任务/事项/设计工作记录',
    related_id BIGINT NOT NULL COMMENT '关联ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '文件路径',
    file_size BIGINT COMMENT '文件大小（字节）',
    file_type VARCHAR(50) COMMENT '文件类型',
    uploaded_by BIGINT NOT NULL COMMENT '上传人ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_related (related_type, related_id),
    INDEX idx_uploaded_by (uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='附件表';

-- 插入初始数据

-- 插入默认管理员用户（密码：admin123，已加密）
INSERT INTO user (username, password, real_name, email, role, status) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 'admin@example.com', 'BU负责人', '启用');

-- 插入示例业务线
INSERT INTO business_line (name, code, description, leader_id, status) VALUES
('电商业务线', 'ECOMMERCE', '电商相关业务', 1, '启用');
