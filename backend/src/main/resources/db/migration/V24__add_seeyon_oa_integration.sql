-- 致远互联 OA 集成配置表
CREATE TABLE IF NOT EXISTS seeyon_oa_integration_config (
    id BIGINT PRIMARY KEY,
    enabled TINYINT DEFAULT 0 COMMENT '是否启用',
    base_url VARCHAR(512) DEFAULT 'https://oa.lucidata.cn' COMMENT 'OA 服务地址',
    encrypted_username VARCHAR(512) COMMENT '加密的用户名',
    encrypted_password VARCHAR(512) COMMENT '加密的密码',
    encrypted_token VARCHAR(2048) COMMENT '加密的访问令牌',
    updated_by BIGINT COMMENT '最后更新人',
    last_tested_at DATETIME COMMENT '最后测试时间',
    last_test_status VARCHAR(50) COMMENT '最后测试状态',
    last_test_message VARCHAR(500) COMMENT '最后测试消息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 集成配置';

-- OA 用户映射表：将 OA 人员映射到本地系统用户
CREATE TABLE IF NOT EXISTS seeyon_oa_user_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '本地系统用户 ID',
    oa_member_id VARCHAR(128) NOT NULL COMMENT 'OA 人员 ID',
    oa_login_name VARCHAR(128) COMMENT 'OA 登录名',
    sync_enabled TINYINT DEFAULT 1 COMMENT '是否启用同步',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_oa_member_id (oa_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 用户映射';

-- OA 部门映射表：将 OA 部门映射到本地系统业务线/项目
CREATE TABLE IF NOT EXISTS seeyon_oa_department_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_line_id BIGINT COMMENT '本地业务线 ID',
    project_id BIGINT COMMENT '本地项目 ID',
    oa_department_id VARCHAR(128) NOT NULL COMMENT 'OA 部门 ID',
    oa_department_name VARCHAR(256) COMMENT 'OA 部门名称',
    sync_enabled TINYINT DEFAULT 1 COMMENT '是否启用同步',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oa_department_id (oa_department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 部门映射';

-- 添加 OA 集成权限
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'seeyon-oa:manage', '管理OA集成', '维护OA连接配置、查询OA数据、执行同步', 'button', id
FROM sys_menu WHERE path = '/statistics'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

-- 将 OA 集成权限分配给管理角色
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'seeyon-oa:manage'
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER');