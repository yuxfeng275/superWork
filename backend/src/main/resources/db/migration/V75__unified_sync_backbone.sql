-- V75: 统一数据同步骨架
-- 目标：销售/工时/合同等外部数据拉齐 —— 所有来源（OA、工时系统、Excel兜底）的
-- 定时与手动同步统一由 sync_task 编排、统一写入 data_sync_log。
-- 设计文档见 docs/data-flow-unification-design.md

CREATE TABLE sync_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL COMMENT '任务编码，如 worktime-contract',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    source_system VARCHAR(32) NOT NULL COMMENT '来源系统：OA/WORKTIME/EXCEL',
    domain VARCHAR(32) NOT NULL COMMENT '数据域：contract/worklog/cost/org/member',
    cron VARCHAR(64) DEFAULT NULL COMMENT '定时表达式（NULL=仅手动触发）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用（含定时与手动）',
    last_status VARCHAR(16) DEFAULT NULL COMMENT '最近一次执行状态 running/success/failed',
    last_run_at DATETIME DEFAULT NULL COMMENT '最近一次执行时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_code (task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据同步任务（统一编排）';

CREATE TABLE data_sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL COMMENT '关联 sync_task.task_code',
    source_system VARCHAR(32) NOT NULL COMMENT '来源系统：OA/WORKTIME/EXCEL',
    domain VARCHAR(32) NOT NULL COMMENT '数据域：contract/worklog/cost/org/member',
    scope VARCHAR(32) NOT NULL DEFAULT '' COMMENT '同步范围，如 2026 / 2026-08 / all',
    status VARCHAR(16) NOT NULL COMMENT 'running/success/failed',
    total_count INT DEFAULT NULL COMMENT '拉取总数',
    upsert_count INT DEFAULT NULL COMMENT '写入/更新数',
    pending_count INT DEFAULT NULL COMMENT '待映射数',
    message TEXT COMMENT '摘要或错误信息',
    triggered_by VARCHAR(32) NOT NULL COMMENT 'schedule/manual/fallback-import',
    operator_id BIGINT DEFAULT NULL COMMENT '手动触发人（定时为NULL）',
    started_at DATETIME DEFAULT NULL,
    finished_at DATETIME DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task (task_code, id),
    INDEX idx_domain (domain, status, id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一数据同步日志（全域）';

-- 内置任务：工时链路沿用现有 cron；OA 任务先禁用，待 P1 采集器落地后开启
INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'worktime-contract', '工时系统-合同明细同步', 'WORKTIME', 'contract', '0 30 6 * * *', 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'worktime-contract');

INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'worktime-monthly', '工时系统-工时/成本月度同步', 'WORKTIME', 'worklog', '0 0 7 * * *', 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'worktime-monthly');

INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'oa-org', 'OA-组织与人员同步', 'OA', 'org', '0 20 6 * * *', 0 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'oa-org');

INSERT INTO sync_task (task_code, task_name, source_system, domain, cron, enabled)
SELECT 'oa-contract', 'OA-销售合同(vReport)同步', 'OA', 'contract', '0 40 6 * * *', 0 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sync_task WHERE task_code = 'oa-contract');

-- 统一同步管理接口权限，挂在「配置管理」菜单下，授权给与 OA 集成一致的管理角色
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'sync:manage', '管理数据同步', '查看/配置/手动触发各数据域同步任务', 'button', id
FROM sys_menu WHERE path = '/system/configs'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code = 'sync:manage'
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
