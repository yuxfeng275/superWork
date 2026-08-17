-- ====================================
-- V15: BU 方向驾驶舱与云效只读集成
-- ====================================

CREATE TABLE bu_direction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(120) NOT NULL,
    objective VARCHAR(1000),
    owner_id BIGINT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT '未开始',
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bu_direction_code (code),
    INDEX idx_bu_direction_period (start_date, end_date),
    INDEX idx_bu_direction_owner (owner_id),
    CONSTRAINT fk_bu_direction_owner FOREIGN KEY (owner_id) REFERENCES user(id),
    CONSTRAINT fk_bu_direction_creator FOREIGN KEY (created_by) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU重点方向';

CREATE TABLE bu_direction_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    direction_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bu_direction_project (direction_id, project_id),
    INDEX idx_bu_direction_project_project (project_id),
    CONSTRAINT fk_bu_direction_project_direction FOREIGN KEY (direction_id) REFERENCES bu_direction(id) ON DELETE CASCADE,
    CONSTRAINT fk_bu_direction_project_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU方向关联项目';

CREATE TABLE bu_direction_milestone (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    direction_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT '未开始',
    completed_at TIMESTAMP NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_bu_milestone_direction (direction_id),
    INDEX idx_bu_milestone_due_date (due_date),
    CONSTRAINT fk_bu_milestone_direction FOREIGN KEY (direction_id) REFERENCES bu_direction(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU方向里程碑';

CREATE TABLE yunxiao_project_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    yunxiao_project_id VARCHAR(100) NOT NULL,
    workitem_type_id VARCHAR(100),
    category VARCHAR(20) NOT NULL DEFAULT 'Req',
    sync_enabled TINYINT NOT NULL DEFAULT 1,
    last_synced_at TIMESTAMP NULL,
    last_sync_status VARCHAR(20),
    last_sync_error VARCHAR(1000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_project_mapping_project (project_id),
    UNIQUE KEY uk_yunxiao_project_mapping_external (yunxiao_project_id),
    CONSTRAINT fk_yunxiao_project_mapping_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地项目与云效项目映射';

CREATE TABLE yunxiao_user_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    yunxiao_user_id VARCHAR(100) NOT NULL,
    sync_enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_user_mapping_user (user_id),
    UNIQUE KEY uk_yunxiao_user_mapping_external (yunxiao_user_id),
    CONSTRAINT fk_yunxiao_user_mapping_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地用户与云效用户映射';

CREATE TABLE yunxiao_workitem_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    yunxiao_workitem_id VARCHAR(100) NOT NULL,
    project_id BIGINT NOT NULL,
    serial_number VARCHAR(100),
    category VARCHAR(20),
    title VARCHAR(500) NOT NULL,
    status VARCHAR(100),
    yunxiao_assignee_id VARCHAR(100),
    assignee_name VARCHAR(100),
    estimated_hours DECIMAL(10,2),
    actual_hours DECIMAL(10,2),
    raw_json JSON,
    last_synced_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_workitem_cache_external (yunxiao_workitem_id),
    INDEX idx_yunxiao_workitem_project (project_id),
    INDEX idx_yunxiao_workitem_assignee (yunxiao_assignee_id),
    CONSTRAINT fk_yunxiao_workitem_cache_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云效工作项只读缓存';

CREATE TABLE yunxiao_effort_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    yunxiao_record_id VARCHAR(100) NOT NULL,
    yunxiao_workitem_id VARCHAR(100) NOT NULL,
    project_id BIGINT NOT NULL,
    yunxiao_user_id VARCHAR(100) NOT NULL,
    user_name VARCHAR(100),
    work_date DATE NOT NULL,
    actual_hours DECIMAL(10,2) NOT NULL,
    description VARCHAR(1000),
    last_synced_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_effort_record_external (yunxiao_record_id),
    INDEX idx_yunxiao_effort_user_date (yunxiao_user_id, work_date),
    INDEX idx_yunxiao_effort_workitem (yunxiao_workitem_id),
    CONSTRAINT fk_yunxiao_effort_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云效实际工时只读缓存';

CREATE TABLE yunxiao_workday_calendar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_date DATE NOT NULL,
    is_workday TINYINT NOT NULL,
    expected_hours DECIMAL(5,2) NOT NULL DEFAULT 8.00,
    source VARCHAR(20) NOT NULL DEFAULT 'DEFAULT',
    UNIQUE KEY uk_yunxiao_workday_calendar_date (work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工时核查工作日历';

CREATE TABLE yunxiao_effort_exemption (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_effort_exemption (user_id, work_date),
    CONSTRAINT fk_yunxiao_effort_exemption_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT fk_yunxiao_effort_exemption_creator FOREIGN KEY (created_by) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工时核查豁免';

CREATE TABLE yunxiao_worklog_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    expected_hours DECIMAL(5,2) NOT NULL,
    actual_hours DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    is_final TINYINT NOT NULL DEFAULT 0,
    source VARCHAR(20) NOT NULL DEFAULT 'YUNXIAO',
    computed_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_yunxiao_worklog_snapshot (user_id, work_date),
    INDEX idx_yunxiao_worklog_status (work_date, status),
    CONSTRAINT fk_yunxiao_worklog_snapshot_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人员每日工时核查快照';

CREATE TABLE yunxiao_workitem_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id BIGINT NOT NULL,
    project_id BIGINT,
    yunxiao_workitem_id VARCHAR(100),
    serial_number VARCHAR(100),
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_error VARCHAR(1000),
    last_synced_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_workitem_link_requirement (requirement_id),
    INDEX idx_yunxiao_workitem_link_external (yunxiao_workitem_id),
    CONSTRAINT fk_yunxiao_workitem_link_requirement FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    CONSTRAINT fk_yunxiao_workitem_link_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地需求与云效工作项关联';

CREATE TABLE yunxiao_handoff_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NULL,
    last_error VARCHAR(1000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_handoff_event_requirement (requirement_id),
    INDEX idx_yunxiao_handoff_retry (status, next_retry_at),
    CONSTRAINT fk_yunxiao_handoff_event_requirement FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求进入研发阶段的云效交接事件';

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:dashboard:view', '查看BU驾驶舱', '查看方向、人员负荷和工时核查数据', 'menu', id
FROM sys_menu WHERE path = '/statistics'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:direction:manage', '管理BU方向', '维护重点方向、关联项目和里程碑', 'button', id
FROM sys_menu WHERE path = '/statistics'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'yunxiao:manage', '管理云效集成', '维护云效映射、执行同步和重试', 'button', id
FROM sys_menu WHERE path = '/statistics'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN (
    'bu:dashboard:view', 'bu:direction:manage', 'yunxiao:manage'
)
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER');
