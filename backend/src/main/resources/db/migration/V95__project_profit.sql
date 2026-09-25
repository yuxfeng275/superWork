-- V95: 项目利润（项目利润表）：月 × 业务线 × 分类 × 项目 利润视图
-- 手动成本分配表 + 菜单/权限/角色授权（沿用 V74 幂等模式，父菜单按 /bl-profit 兄弟路径反查，避免硬编码 parent_id）
-- 金额单位统一为元；比率为百分数；工时单位为人月。

CREATE TABLE project_profit_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    `year_month` VARCHAR(7) NOT NULL COMMENT '归属月份 YYYY-MM',
    business_line_id BIGINT NOT NULL COMMENT '本系统业务线ID',
    project_id BIGINT NOT NULL COMMENT '本系统项目ID（主项目）',
    cost_type VARCHAR(20) NOT NULL COMMENT 'sms/direct/platform_fee/compensation/outsourcing/software_gift',
    amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '分配金额（元，允许负数作调整）',
    note VARCHAR(500) NULL,
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project_type (`year_month`, project_id, cost_type),
    INDEX idx_month_line (`year_month`, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目利润成本手动分配';

-- 数据分析 → 项目利润（与 /bl-profit 同级，紧随其後；父节点与位次均按兄弟路径反查）
-- 先把同父节点中排在 /bl-profit 之后的菜单位次顺延 +1
UPDATE sys_menu sm
JOIN (SELECT t.parent_id AS pid, t.sort_order AS s
      FROM (SELECT parent_id, sort_order FROM sys_menu WHERE path = '/bl-profit') t) ref
  ON sm.parent_id = ref.pid AND sm.sort_order > ref.s
SET sm.sort_order = sm.sort_order + 1
WHERE sm.path <> '/project-profit';

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT ref.pid, '项目利润', 'DataLine', '/project-profit', 'ProjectProfitView', ref.s + 1, 1, 1
FROM (SELECT t.parent_id AS pid, t.sort_order AS s
      FROM (SELECT parent_id, sort_order FROM sys_menu WHERE path = '/bl-profit') t) ref
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/project-profit');

-- 角色授权：复制「业务线利润」(/bl-profit) 的授权
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.id
FROM sys_role_menu rm
JOIN sys_menu old_menu ON old_menu.id = rm.menu_id AND old_menu.path = '/bl-profit'
JOIN sys_menu m ON m.path = '/project-profit';

-- 接口权限：查看 / 分配与同步
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'project-profit:view', '查看项目利润', '查看项目利润表（月 × 业务线 × 项目）', 'menu', id FROM sys_menu WHERE path = '/project-profit'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'project-profit:manage', '项目利润分配与同步', '项目利润成本手动分配与月度一键同步', 'button', id FROM sys_menu WHERE path = '/project-profit'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('project-profit:view', 'project-profit:manage')
WHERE role.code IN ('DIRECTOR', 'BUSINESS_OWNER', 'DEPUTY_DIRECTOR', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
