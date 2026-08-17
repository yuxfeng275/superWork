-- 云效预计工时按 owner 记录。人员负荷必须按明细负责人汇总，
-- 不能把整条工作项预估全部归到工作项 assignedTo。
CREATE TABLE yunxiao_estimated_effort (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    yunxiao_record_id VARCHAR(100) NOT NULL,
    yunxiao_workitem_id VARCHAR(100) NOT NULL,
    project_id BIGINT NOT NULL,
    yunxiao_user_id VARCHAR(100) NOT NULL,
    user_name VARCHAR(100),
    estimated_hours DECIMAL(10,2) NOT NULL,
    work_type VARCHAR(100),
    description VARCHAR(1000),
    last_synced_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_yunxiao_estimated_effort_external (yunxiao_record_id),
    INDEX idx_yunxiao_estimated_user (yunxiao_user_id),
    INDEX idx_yunxiao_estimated_workitem (yunxiao_workitem_id),
    CONSTRAINT fk_yunxiao_estimated_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云效预计工时负责人明细';
