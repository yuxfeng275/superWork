-- ====================================
-- V58: 周报自动化
-- 1) weekly_report 表（每周一唯一）
-- 2) weekly-report 配置组（语雀/汇总表/企微提醒）
-- 3) 菜单 /weekly-report（周报中心，挂在 /sec-work 下）
-- 4) 权限 weekly:view / weekly:manage 及管理角色授权
-- ====================================

-- 1. 周报主表
CREATE TABLE IF NOT EXISTS weekly_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    week_start_date DATE NOT NULL COMMENT '本周周一',
    period_end_date DATE NOT NULL COMMENT '本周周五',
    wecom_summary MEDIUMTEXT NULL COMMENT '企微智能总结（粘贴）',
    manual_notes MEDIUMTEXT NULL COMMENT '人为补充信息',
    auto_facts_json MEDIUMTEXT NULL COMMENT '自动采集事实（大事儿+财务+上周闭环）',
    core_work TEXT NULL COMMENT '本周核心工作完成情况',
    kpi_section TEXT NULL COMMENT 'KPI相关情况（财务/业务/提效/品质）',
    risks TEXT NULL COMMENT '问题/风险与解决办法',
    next_week_plan TEXT NULL COMMENT '下周工作计划',
    minutes_markdown MEDIUMTEXT NULL COMMENT '周会纪要（Markdown）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|GENERATING|DRAFT|CONFIRMED|PUBLISHED|GENERATION_FAILED',
    generation_model VARCHAR(64) NULL,
    generation_mode VARCHAR(20) NULL COMMENT 'AI|RULES|NONE',
    generation_error VARCHAR(500) NULL,
    yuque_doc_id INT NULL,
    yuque_doc_slug VARCHAR(256) NULL,
    yuque_doc_url VARCHAR(512) NULL,
    yuque_toc_status VARCHAR(20) NULL COMMENT 'VERIFIED|MOVED|NOT_FOUND',
    sheet_sync_status VARCHAR(20) NULL COMMENT 'PENDING|MANUAL_DONE|API_FAILED',
    sheet_synced_at DATETIME NULL,
    wecom_push_status VARCHAR(20) NULL,
    wecom_pushed_at DATETIME NULL,
    created_by BIGINT NULL,
    published_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_weekly_report_week (week_start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BG周报与周会纪要';

-- 2. 配置组 weekly-report（周报集成）
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT * FROM (
    SELECT 'weekly-report' AS group_code, '周报集成' AS group_name, '周报/周会纪要发布相关集成配置' AS group_description,
           'yuque.repo' AS config_key, '语雀知识库' AS config_name, '周会纪要发布的语雀知识库（login/repo）' AS config_description,
           'STRING' AS value_type, 'vuntcs/cf_records' AS config_value, 0 AS is_sensitive, 1 AS is_required, 10 AS sort_order, 1 AS status
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'yuque.parent-dir', '语雀父目录', '纪要挂载的父目录名（按年建子目录）',
           'STRING', '部门会议', 0, 1, 20, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'yuque.base-url', '语雀站点地址', '语雀站点根地址，用于拼接文档链接',
           'URL', 'https://lucidata.yuque.com', 0, 1, 30, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.mode', '汇总表回填模式', 'MANUAL=人工回填（系统给目标行信息）；API=接口自动回填',
           'STRING', 'MANUAL', 0, 1, 40, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.api-base-url', '汇总表API地址', 'sheet.mode=API 时使用的接口根地址',
           'URL', NULL, 0, 0, 50, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.api-token', '汇总表API令牌', 'sheet.mode=API 时使用的调用凭据，加密保存',
           'PASSWORD', NULL, 1, 0, 60, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.doc-slug', '汇总表文档标识', '语雀汇总表文档 slug（路径片段）',
           'STRING', 'staff-qvc012/mghdgg/tyavbayo9ir7tyrk', 0, 1, 70, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.sheet-name', '汇总表工作表名', '汇总表内目标工作表名称',
           'STRING', '电商业务', 0, 1, 80, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'sheet.team-name', '汇总表团队名', '汇总表目标行所属团队名称',
           'STRING', '电商业务BU', 0, 1, 90, 1
    UNION ALL
    SELECT 'weekly-report', '周报集成', '周报/周会纪要发布相关集成配置',
           'report.notify-wecom-user', '企微提醒用户', '周报草稿生成/发布后企微提醒目标 userid，为空不提醒',
           'STRING', NULL, 0, 0, 100, 1
) seed
WHERE NOT EXISTS (
    SELECT 1 FROM system_config_item i
    WHERE i.group_code = 'weekly-report' AND i.config_key = seed.config_key
);

-- 3. 菜单：周报中心（/sec-work 下，大事儿 sort=7 之后）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-work') t),
       '周报中心', 'DataLine', '/weekly-report', 'WeeklyReportView', 8, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/weekly-report');

-- 4. 权限
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'weekly:view', '查看周报', '查看周报与周会纪要及自动采集事实', 'menu', menu.id
FROM sys_menu menu WHERE menu.path = '/weekly-report'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'weekly:view');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'weekly:manage', '管理周报', '生成、编辑、确认、发布周报与周会纪要', 'button', menu.id
FROM sys_menu menu WHERE menu.path = '/weekly-report'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'weekly:manage');

-- 5. 管理角色授权（菜单 + 权限）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/weekly-report'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
JOIN sys_permission p ON p.code IN ('weekly:view', 'weekly:manage')
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
