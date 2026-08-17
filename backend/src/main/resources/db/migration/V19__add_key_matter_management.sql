CREATE TABLE bu_key_matter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    project_id BIGINT,
    owner_id BIGINT NOT NULL,
    priority VARCHAR(2) NOT NULL DEFAULT 'P1',
    status VARCHAR(20) NOT NULL DEFAULT '未开始',
    progress INT NOT NULL DEFAULT 0,
    start_date DATE NOT NULL,
    planned_completion_date DATE NOT NULL,
    completed_at TIMESTAMP NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_bu_key_matter_status_priority (status, priority),
    INDEX idx_bu_key_matter_owner (owner_id),
    INDEX idx_bu_key_matter_project (project_id),
    INDEX idx_bu_key_matter_plan_date (planned_completion_date),
    CONSTRAINT fk_bu_key_matter_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE SET NULL,
    CONSTRAINT fk_bu_key_matter_owner FOREIGN KEY (owner_id) REFERENCES user(id),
    CONSTRAINT fk_bu_key_matter_creator FOREIGN KEY (created_by) REFERENCES user(id),
    CONSTRAINT chk_bu_key_matter_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU大事儿台账';

CREATE TABLE bu_key_matter_weekly_update (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_matter_id BIGINT NOT NULL,
    week_start_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    progress INT NOT NULL,
    progress_summary TEXT NOT NULL,
    issues TEXT,
    next_week_plan TEXT,
    support_needed TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bu_key_matter_week (key_matter_id, week_start_date),
    INDEX idx_bu_key_matter_week_date (week_start_date),
    CONSTRAINT fk_bu_key_matter_weekly_matter FOREIGN KEY (key_matter_id)
        REFERENCES bu_key_matter(id) ON DELETE CASCADE,
    CONSTRAINT fk_bu_key_matter_weekly_creator FOREIGN KEY (created_by) REFERENCES user(id),
    CONSTRAINT chk_bu_key_matter_weekly_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BU大事儿每周进展';

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '大事儿管理', 'Flag', '/key-matters', 'KeyMattersView', 5, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/key-matters');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:key-matter:manage', '管理BU大事儿', '维护BU重点事项和每周进展', 'menu', id
FROM sys_menu WHERE path = '/key-matters'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'bu:key-matter:manage'
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/key-matters'
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER');
