-- ====================================
-- BU Management System - 数据库初始化脚本
-- Version: 1.0
-- Date: 2026-04-02
-- Description: 创建所有数据表（16张表）
-- ====================================

-- ====================================
-- 1. 组织架构模块（5张表）
-- ====================================

-- 1.1 业务线表
CREATE TABLE business_line (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '业务线名称',
    description TEXT COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务线表';

-- 1.2 项目表
CREATE TABLE project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    business_line_id BIGINT NOT NULL COMMENT '所属业务线',
    parent_id BIGINT COMMENT '父项目ID，NULL表示主项目',
    level TINYINT NOT NULL COMMENT '层级：1=主项目，2=子项目',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    full_path VARCHAR(500) COMMENT '完整路径，如"皇家/PMS"',
    code VARCHAR(50) UNIQUE COMMENT '项目编码',
    manager_id BIGINT COMMENT '项目经理ID',
    status TINYINT DEFAULT 1 COMMENT '状态：1=进行中，0=已结束',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (business_line_id) REFERENCES business_line(id),
    FOREIGN KEY (parent_id) REFERENCES project(id),
    INDEX idx_business_line (business_line_id),
    INDEX idx_parent (parent_id),
    INDEX idx_manager (manager_id),
    INDEX idx_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';

-- 1.3 用户表
CREATE TABLE user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码（加密）',
    real_name VARCHAR(50) NOT NULL COMMENT '真实姓名',
    role VARCHAR(50) NOT NULL COMMENT '角色：BU负责人/项目经理/产品经理/技术经理/前端研发/后端研发/测试/UI设计',
    email VARCHAR(100) UNIQUE COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    status TINYINT DEFAULT 1 COMMENT '状态：1=启用，0=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 1.4 项目成员表
CREATE TABLE project_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(50) COMMENT '在项目中的角色',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    UNIQUE KEY uk_project_user (project_id, user_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员表';

-- 1.5 客户联系人表
CREATE TABLE customer_contact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    project_id BIGINT NOT NULL COMMENT '所属项目',
    name VARCHAR(50) NOT NULL COMMENT '联系人姓名',
    company VARCHAR(100) COMMENT '公司名称',
    position VARCHAR(50) COMMENT '职位',
    phone VARCHAR(20) COMMENT '电话',
    email VARCHAR(100) COMMENT '邮箱',
    is_active TINYINT DEFAULT 1 COMMENT '是否有效：1=是，0=否',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (project_id) REFERENCES project(id),
    INDEX idx_project (project_id),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户联系人表';

-- ====================================
-- 2. 需求管理模块（6张表）
-- ====================================

-- 2.1 需求表
CREATE TABLE requirement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    req_no VARCHAR(50) UNIQUE NOT NULL COMMENT '需求编号，自动生成',
    title VARCHAR(200) NOT NULL COMMENT '需求标题',
    description TEXT COMMENT '需求描述',
    type VARCHAR(20) NOT NULL COMMENT '类型：项目需求/产品需求',
    business_line_id BIGINT NOT NULL COMMENT '所属业务线',
    project_id BIGINT COMMENT '所属项目，产品需求可为空',
    customer_contact_id BIGINT COMMENT '客户联系人ID，项目需求必填',
    status VARCHAR(20) NOT NULL DEFAULT '待评估' COMMENT '状态',
    priority VARCHAR(20) DEFAULT '中' COMMENT '优先级：高/中/低',
    source VARCHAR(20) COMMENT '来源：客户/商务/内部',
    expected_online_date DATE COMMENT '客户期望上线时间',
    estimated_online_date DATE COMMENT '评估后的预估上线时间',
    actual_online_date DATE COMMENT '实际上线时间',
    creator_id BIGINT NOT NULL COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (business_line_id) REFERENCES business_line(id),
    FOREIGN KEY (project_id) REFERENCES project(id),
    FOREIGN KEY (customer_contact_id) REFERENCES customer_contact(id),
    FOREIGN KEY (creator_id) REFERENCES user(id),
    INDEX idx_req_no (req_no),
    INDEX idx_status (status),
    INDEX idx_type (type),
    INDEX idx_business_line (business_line_id),
    INDEX idx_project (project_id),
    INDEX idx_creator (creator_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求表';

-- 2.2 需求评估表
CREATE TABLE requirement_evaluation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    is_feasible TINYINT COMMENT '技术可行性：1=是，0=否',
    feasibility_desc TEXT COMMENT '可行性说明',
    estimated_workload DECIMAL(10,2) COMMENT '预估工作量（人天）',
    estimated_cost DECIMAL(15,2) COMMENT '预估报价（元），项目需求必填',
    work_breakdown JSON COMMENT '工作内容拆解',
    suggest_product TINYINT DEFAULT 0 COMMENT '是否建议转产品需求：1=是，0=否',
    evaluator_id BIGINT COMMENT '评估人',
    evaluated_at TIMESTAMP COMMENT '评估时间',
    decision VARCHAR(20) COMMENT 'BU决策：通过/拒绝/转产品需求',
    decision_by BIGINT COMMENT '决策人',
    decision_at TIMESTAMP COMMENT '决策时间',
    decision_reason TEXT COMMENT '决策理由',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (evaluator_id) REFERENCES user(id),
    FOREIGN KEY (decision_by) REFERENCES user(id),
    INDEX idx_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求评估表';

-- 2.3 需求设计汇总表
CREATE TABLE requirement_design (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    prototype_status VARCHAR(20) DEFAULT '未开始' COMMENT '原型设计状态：未开始/进行中/已完成',
    ui_status VARCHAR(20) DEFAULT '未开始' COMMENT 'UI设计状态',
    tech_solution_status VARCHAR(20) DEFAULT '未开始' COMMENT '技术方案状态',
    all_completed_at TIMESTAMP COMMENT '全部完成时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    UNIQUE KEY uk_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求设计汇总表';

-- 2.4 设计工作记录表
CREATE TABLE design_work_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    work_type VARCHAR(20) NOT NULL COMMENT '工作类型：原型设计/UI设计/技术方案设计',
    designer_id BIGINT NOT NULL COMMENT '设计人ID',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时（小时）',
    actual_hours DECIMAL(10,2) COMMENT '实际工时（小时）',
    result_url VARCHAR(500) COMMENT '成果物链接或文件ID',
    work_content TEXT COMMENT '工作内容描述',
    status VARCHAR(20) DEFAULT '进行中' COMMENT '状态：进行中/已完成',
    started_at TIMESTAMP COMMENT '开始时间',
    completed_at TIMESTAMP COMMENT '完成时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (designer_id) REFERENCES user(id),
    INDEX idx_requirement (requirement_id),
    INDEX idx_designer (designer_id),
    INDEX idx_work_type (work_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计工作记录表';

-- 2.5 需求确认表
CREATE TABLE requirement_confirmation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    confirmation_type VARCHAR(20) NOT NULL COMMENT '确认类型：客户确认/内部确认',
    confirmed_by BIGINT NOT NULL COMMENT '确认人',
    confirmed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
    confirmation_notes TEXT COMMENT '确认备注',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (confirmed_by) REFERENCES user(id),
    INDEX idx_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求确认表';

-- 2.6 需求交付表
CREATE TABLE requirement_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '需求ID',
    delivered_at TIMESTAMP COMMENT '交付时间',
    accepted_at TIMESTAMP COMMENT '验收时间',
    delivery_notes TEXT COMMENT '交付说明',
    acceptance_notes TEXT COMMENT '验收说明',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    UNIQUE KEY uk_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求交付表';

-- ====================================
-- 3. 任务工时模块（2张表）
-- ====================================

-- 3.1 任务表
CREATE TABLE task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_id BIGINT NOT NULL COMMENT '关联需求',
    title VARCHAR(200) NOT NULL COMMENT '任务标题',
    description TEXT COMMENT '任务描述',
    task_type VARCHAR(20) NOT NULL COMMENT '任务类型：前端开发/后端开发/测试/UI设计',
    assignee_id BIGINT COMMENT '分配给谁',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时（小时）',
    actual_hours DECIMAL(10,2) COMMENT '实际工时（小时）',
    status VARCHAR(20) DEFAULT '待开始' COMMENT '状态：待开始/进行中/已完成/已测试',
    start_date DATE COMMENT '开始日期',
    end_date DATE COMMENT '结束日期',
    created_by BIGINT NOT NULL COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (assignee_id) REFERENCES user(id),
    FOREIGN KEY (created_by) REFERENCES user(id),
    INDEX idx_requirement (requirement_id),
    INDEX idx_assignee (assignee_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- 3.2 工时记录表
CREATE TABLE work_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id BIGINT COMMENT '任务ID',
    requirement_id BIGINT COMMENT '需求ID（可选）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    work_date DATE NOT NULL COMMENT '工作日期',
    hours DECIMAL(10,2) NOT NULL COMMENT '工时（小时）',
    description TEXT COMMENT '工作内容描述',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(id),
    INDEX idx_task (task_id),
    INDEX idx_user_date (user_id, work_date),
    INDEX idx_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工时记录表';

-- ====================================
-- 4. 事项管理模块（1张表）
-- ====================================

-- 4.1 事项表
CREATE TABLE issue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    issue_no VARCHAR(50) UNIQUE NOT NULL COMMENT '事项编号',
    title VARCHAR(200) NOT NULL COMMENT '事项标题',
    description TEXT COMMENT '事项描述',
    type VARCHAR(20) NOT NULL COMMENT '类型：Bug修复/运维/临时需求/优化',
    business_line_id BIGINT COMMENT '所属业务线（可选）',
    project_id BIGINT COMMENT '所属项目（可选）',
    requirement_id BIGINT COMMENT '关联需求（可选）',
    assignee_id BIGINT COMMENT '分配给谁',
    creator_id BIGINT NOT NULL COMMENT '创建人',
    estimated_hours DECIMAL(10,2) COMMENT '预估工时（小时）',
    actual_hours DECIMAL(10,2) COMMENT '实际工时（小时）',
    status VARCHAR(20) DEFAULT '待处理' COMMENT '状态：待处理/处理中/已完成/已验证',
    priority VARCHAR(20) DEFAULT '中' COMMENT '优先级：高/中/低',
    completed_at TIMESTAMP COMMENT '完成时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (business_line_id) REFERENCES business_line(id),
    FOREIGN KEY (project_id) REFERENCES project(id),
    FOREIGN KEY (requirement_id) REFERENCES requirement(id),
    FOREIGN KEY (assignee_id) REFERENCES user(id),
    FOREIGN KEY (creator_id) REFERENCES user(id),
    INDEX idx_issue_no (issue_no),
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_assignee (assignee_id),
    INDEX idx_creator (creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事项表';

-- ====================================
-- 5. 工作流配置模块（1张表）
-- ====================================

-- 5.1 工作流配置表
CREATE TABLE workflow_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    requirement_type VARCHAR(20) NOT NULL COMMENT '需求类型：项目需求/产品需求',
    from_status VARCHAR(20) NOT NULL COMMENT '源状态',
    to_status VARCHAR(20) NOT NULL COMMENT '目标状态',
    allowed_roles JSON NOT NULL COMMENT '允许的角色（JSON数组）',
    condition_type JSON COMMENT '流转条件（JSON）',
    is_active TINYINT DEFAULT 1 COMMENT '是否启用：1=是，0=否',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_type_from (requirement_type, from_status),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流配置表';

-- ====================================
-- 6. 附件模块（1张表）
-- ====================================

-- 6.1 附件表
CREATE TABLE attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_path VARCHAR(500) NOT NULL COMMENT 'MinIO路径',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    file_type VARCHAR(100) COMMENT '文件类型',
    related_type VARCHAR(20) NOT NULL COMMENT '关联类型：需求/任务/事项',
    related_id BIGINT NOT NULL COMMENT '关联ID',
    uploaded_by BIGINT NOT NULL COMMENT '上传人',
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    FOREIGN KEY (uploaded_by) REFERENCES user(id),
    INDEX idx_related (related_type, related_id),
    INDEX idx_uploaded_by (uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='附件表';
