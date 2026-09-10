-- ====================================
-- V56: KPI 经营周报 + 工时系统数据自动同步
-- 1) 工时系统集成配置（单行，模式同 seeyon_oa_integration_config）
-- 2) 同步日志 + 业务线映射
-- 3) KPI 目标 / 周快照 / 偏差备注 / 预警规则
-- 4) revenue_contract_entry 扩行级直接成本 + 人工映射锁
-- 金额单位统一为元。
-- ====================================

-- 1. 工时系统集成配置
CREATE TABLE worktime_integration_config (
    id BIGINT PRIMARY KEY,
    enabled TINYINT DEFAULT 0 COMMENT '是否启用',
    base_url VARCHAR(512) DEFAULT 'https://worktime.lucidata.cn' COMMENT '工时系统地址',
    encrypted_employee_no VARCHAR(512) COMMENT '加密的登录工号',
    encrypted_password VARCHAR(512) COMMENT '加密的登录密码',
    updated_by BIGINT COMMENT '最后更新人',
    last_tested_at DATETIME COMMENT '最后测试时间',
    last_test_status VARCHAR(50) COMMENT '最后测试状态',
    last_test_message VARCHAR(500) COMMENT '最后测试消息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时系统集成配置';

-- 2. 工时系统同步日志
CREATE TABLE worktime_sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sync_type VARCHAR(20) NOT NULL COMMENT 'contract=合同明细/worklog=工时明细/cost=成本分析',
    scope VARCHAR(200) DEFAULT NULL COMMENT '同步范围，如 2026 / 2026-08',
    status VARCHAR(20) NOT NULL DEFAULT 'running' COMMENT 'running/success/failed',
    total_count INT NOT NULL DEFAULT 0 COMMENT '远端读取行数',
    upsert_count INT NOT NULL DEFAULT 0 COMMENT '落库行数',
    pending_count INT NOT NULL DEFAULT 0 COMMENT '待映射行数',
    message VARCHAR(1000) DEFAULT NULL,
    triggered_by VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'schedule/manual',
    started_at DATETIME DEFAULT NULL,
    finished_at DATETIME DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type_created (sync_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时系统同步日志';

-- 3. 工时系统业务线 ↔ 本系统业务线映射（名称匹配兜底）
CREATE TABLE worktime_business_line_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    worktime_business_line_id BIGINT NOT NULL COMMENT '工时系统业务线ID',
    worktime_business_line_name VARCHAR(200) DEFAULT NULL COMMENT '工时系统业务线名',
    business_line_id BIGINT NOT NULL COMMENT '本系统业务线ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wt_bl (worktime_business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时系统业务线映射';

-- 4. KPI 报表行分组：云鹿=定制+SAAS 合并行；NULL=不参与 KPI 周报
ALTER TABLE business_line
    ADD COLUMN kpi_report_group VARCHAR(50) DEFAULT NULL COMMENT 'KPI报表行分组（会员通/全渠道云鹿/全渠道精准），NULL=不参与';

UPDATE business_line SET kpi_report_group = '会员通' WHERE name LIKE '%会员通%';
UPDATE business_line SET kpi_report_group = '全渠道云鹿'
WHERE name LIKE '%定制%' OR name LIKE '%saas%';
UPDATE business_line SET kpi_report_group = '全渠道精准' WHERE name LIKE '%精准%';

-- 5. KPI 年度目标（按报表行分组）
CREATE TABLE kpi_target (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    `year` INT NOT NULL COMMENT '年度',
    report_group VARCHAR(50) NOT NULL COMMENT '报表行分组（会员通/全渠道云鹿/全渠道精准）',
    revenue_target DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '年度营收目标（元）',
    profit_target DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '年度毛利目标（元）',
    remark VARCHAR(500) DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_year_group (`year`, report_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='KPI年度目标';

-- 2026 目标预置（来源 2026KPI.xlsx，单位换算 万→元）
INSERT INTO kpi_target (`year`, report_group, revenue_target, profit_target, remark) VALUES
(2026, '会员通', 2010000, 1410000, '2026KPI.xlsx 预置'),
(2026, '全渠道云鹿', 5000000, 100000, '2026KPI.xlsx 预置'),
(2026, '全渠道精准', 920000, 280000, '2026KPI.xlsx 预置');

-- 6. KPI 周快照（每周日定时生成，冻结历史）
CREATE TABLE kpi_weekly_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    week_end_date DATE NOT NULL COMMENT '周截止日（周日）',
    report_group VARCHAR(50) NOT NULL COMMENT '报表行分组',
    ytd_revenue DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT 'YTD 交付营收（元）',
    ytd_direct_cost DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT 'YTD 直接成本（短信/直接/三方采购）',
    ytd_labor_cost DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT 'YTD 人工成本',
    ytd_other_cost DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT 'YTD 其他成本（协力/服务器/其他）',
    ytd_profit DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT 'YTD 毛利',
    week_delta_revenue DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '周环比增量-营收',
    week_delta_profit DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '周环比增量-毛利',
    estimated TINYINT NOT NULL DEFAULT 0 COMMENT '1=含当月估算（未月结）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_week_group (week_end_date, report_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='KPI周快照';

-- 7. 偏差备注（一个异常单元格一条：快照 × 指标）
CREATE TABLE kpi_deviation_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL COMMENT '关联 kpi_weekly_snapshot',
    metric VARCHAR(20) NOT NULL COMMENT 'revenue/profit',
    alert_level VARCHAR(10) NOT NULL COMMENT 'red=环比<=0/yellow=明显偏小',
    deviation_reason VARCHAR(500) DEFAULT NULL COMMENT '偏差原因',
    is_abnormal TINYINT DEFAULT NULL COMMENT '是否异常：1是/0否',
    countermeasure VARCHAR(500) DEFAULT NULL COMMENT '对策（正常免填）',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/done',
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_snapshot_metric (snapshot_id, metric),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='KPI偏差备注';

-- 8. 预警规则（report_group NULL=全局默认）
CREATE TABLE kpi_alert_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_group VARCHAR(50) DEFAULT NULL COMMENT '报表行分组；NULL=全局默认',
    weekly_divisor DECIMAL(5,2) NOT NULL DEFAULT 4.33 COMMENT '周基准=月均目标÷该值',
    yellow_ratio DECIMAL(4,2) NOT NULL DEFAULT 0.50 COMMENT '环比增量<周基准×该比例判偏小',
    enabled TINYINT NOT NULL DEFAULT 1,
    updated_by BIGINT DEFAULT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group (report_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='KPI预警规则';

INSERT INTO kpi_alert_rule (report_group, weekly_divisor, yellow_ratio) VALUES (NULL, 4.33, 0.50);

-- 9. 合同明细扩展：行级直接成本（工时系统同步）+ 人工映射锁
ALTER TABLE revenue_contract_entry
    ADD COLUMN sms_cost DECIMAL(16,2) DEFAULT NULL COMMENT '短信成本（元）',
    ADD COLUMN direct_cost DECIMAL(16,2) DEFAULT NULL COMMENT '直接成本（元）',
    ADD COLUMN third_party_cost DECIMAL(16,2) DEFAULT NULL COMMENT '三方采购成本（元）',
    ADD COLUMN received_amount DECIMAL(16,2) DEFAULT NULL COMMENT '实收金额（元）',
    ADD COLUMN payment_status VARCHAR(50) DEFAULT NULL COMMENT '收款状态',
    ADD COLUMN mapping_locked TINYINT NOT NULL DEFAULT 0 COMMENT '1=人工调整过归属，自动同步不覆盖映射';

-- 10. 权限与菜单
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, 'KPI周报', 'DataLine', '/kpi-report', 'KpiReportView', 65, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/kpi-report');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'kpi:view', '查看KPI周报', '查看KPI经营周报', 'menu', id FROM sys_menu WHERE path = '/kpi-report'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'kpi:manage', '管理KPI周报', '维护KPI目标/预警规则/偏差备注/工时系统集成', 'button', id FROM sys_menu WHERE path = '/kpi-report'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('kpi:view', 'kpi:manage')
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER', 'DEPUTY_DIRECTOR', 'EFFECTIVENESS_OWNER');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/kpi-report'
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER', 'DEPUTY_DIRECTOR', 'EFFECTIVENESS_OWNER');
