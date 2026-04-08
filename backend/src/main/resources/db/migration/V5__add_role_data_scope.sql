-- ====================================
-- BU Management System - 角色数据范围
-- Version: 5.0
-- Date: 2026-04-06
-- Description: 为角色添加数据范围控制字段
-- ====================================

ALTER TABLE sys_role
    ADD COLUMN data_scope VARCHAR(20) DEFAULT 'SELF' COMMENT '数据范围：ALL=全部数据，BU_LINE=按业务线，PROJECT=按项目，DEPT=按部门，SELF=仅本人',
    ADD COLUMN data_scope_value VARCHAR(500) DEFAULT NULL COMMENT '数据范围值（业务线ID/项目ID/部门ID，逗号分隔）';
