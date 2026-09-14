-- V74: 业务线月度利润报表（数据源自工时系统 /finance/report/business-line/{month}，整月覆盖）
-- 金额单位统一为元；比率为百分数（如 11.1 表示 11.1%）；工时单位为人月。

CREATE TABLE biz_line_profit_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    year_month VARCHAR(7) NOT NULL COMMENT '归属月份 YYYY-MM',
    worktime_business_line_id BIGINT DEFAULT NULL COMMENT '工时系统业务线ID',
    worktime_business_line_name VARCHAR(200) NOT NULL COMMENT '工时系统业务线名称',
    business_line_id BIGINT DEFAULT NULL COMMENT '本系统业务线ID（映射成功时填充）',
    group_name VARCHAR(200) DEFAULT NULL COMMENT '工时系统分组名',
    revenue DECIMAL(18,2) DEFAULT NULL COMMENT '营业收入',
    sms_cost DECIMAL(18,2) DEFAULT NULL COMMENT '短信成本',
    direct_cost DECIMAL(18,2) DEFAULT NULL COMMENT '直接成本',
    platform_fee DECIMAL(18,2) DEFAULT NULL COMMENT '平台佣金&手续费',
    compensation DECIMAL(18,2) DEFAULT NULL COMMENT '赔付',
    outsourcing DECIMAL(18,2) DEFAULT NULL COMMENT '协力&外包',
    software_gift DECIMAL(18,2) DEFAULT NULL COMMENT '软件赠送',
    total_hours DECIMAL(12,2) DEFAULT NULL COMMENT '工时（人月）',
    hours_ratio DECIMAL(8,2) DEFAULT NULL COMMENT '工时占比(%)',
    expense_1 DECIMAL(18,2) DEFAULT NULL COMMENT '费用1',
    labor_cost_1 DECIMAL(18,2) DEFAULT NULL COMMENT '人工成本1',
    gross_profit DECIMAL(18,2) DEFAULT NULL COMMENT '考核毛利',
    gross_profit_rate DECIMAL(8,2) DEFAULT NULL COMMENT '考核毛利率(%)',
    marketing_cost DECIMAL(18,2) DEFAULT NULL COMMENT '营销成本',
    labor_cost_2_sales DECIMAL(18,2) DEFAULT NULL COMMENT '人工成本2-销售均摊',
    labor_cost_3_backend DECIMAL(18,2) DEFAULT NULL COMMENT '人工成本3-后台成本',
    labor_cost_3_tech DECIMAL(18,2) DEFAULT NULL COMMENT '人工成本3-技术人员成本',
    labor_cost_3_rd DECIMAL(18,2) DEFAULT NULL COMMENT '人工成本3-研发投入',
    expense_2 DECIMAL(18,2) DEFAULT NULL COMMENT '费用2',
    net_profit DECIMAL(18,2) DEFAULT NULL COMMENT '净利润',
    net_profit_rate DECIMAL(8,2) DEFAULT NULL COMMENT '净利率(%)',
    synced_at DATETIME DEFAULT NULL COMMENT '最近同步时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_wtbl (year_month, worktime_business_line_id),
    INDEX idx_month (year_month),
    INDEX idx_bl (business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务线月度利润报表（工时系统同步）';

-- 数据分析 → 业务线利润（二级菜单，配置分组之前）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t),
       '业务线利润', 'DataAnalysis', '/bl-profit', 'BusinessLineProfitView', 5, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/bl-profit');

-- 配置分组顺延到 6
UPDATE sys_menu SET sort_order = 6
WHERE name = '配置'
  AND parent_id = (SELECT id FROM (SELECT id FROM sys_menu WHERE path = '/sec-analytics') t);

-- 角色授权：复制「工时 & 成本」(/revenue/worktime) 的授权
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.id
FROM sys_role_menu rm
JOIN sys_menu old_menu ON old_menu.id = rm.menu_id AND old_menu.path = '/revenue/worktime'
JOIN sys_menu m ON m.path = '/bl-profit';

-- 接口权限：查看 / 同步
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bl-profit:view', '查看业务线利润', '查看业务线月度利润报表', 'menu', id FROM sys_menu WHERE path = '/bl-profit'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bl-profit:manage', '同步业务线利润', '从工时系统同步业务线利润报表', 'button', id FROM sys_menu WHERE path = '/bl-profit'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('bl-profit:view', 'bl-profit:manage')
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER', 'DEPUTY_DIRECTOR', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
