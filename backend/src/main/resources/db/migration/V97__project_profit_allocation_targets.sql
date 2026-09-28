-- V97: 项目利润分配扩展——差额所有项（9 个数值列）均可分配到「项目/销售」子项
-- 1) target_type 区分分配目标：project（项目行/项目集/精准单行）| sales（销售行）| line_other（full 线「业务线」行）
-- 2) project_id=0 表示非项目目标（销售/业务线行、aggregate 项目集、simple 单行），规避 MySQL 唯一键允许多个 NULL 的坑
-- 3) cost_type 在 6 个成本列之外扩展 revenue/hours/cost：差额任意列都可被分配消除，归零后前端自动隐藏差额行
-- 4) amount 精度 2→4 位：工时（人月）差额阈值 0.0001，需要更高精度；金额仍按 2 位录入，4 位为其超集
ALTER TABLE project_profit_allocation
    ADD COLUMN target_type VARCHAR(20) NOT NULL DEFAULT 'project' COMMENT '分配目标：project/sales/line_other' AFTER project_id,
    MODIFY COLUMN project_id BIGINT NOT NULL DEFAULT 0 COMMENT '本系统项目ID（主项目）；0=非项目目标（销售/业务线/项目集/精准单行）',
    MODIFY COLUMN cost_type VARCHAR(20) NOT NULL COMMENT 'revenue/sms/direct/platform_fee/compensation/outsourcing/software_gift/hours/cost',
    MODIFY COLUMN amount DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '分配数量（金额：元；hours：人月；允许负数作调整）',
    DROP INDEX uk_month_project_type,
    ADD UNIQUE KEY uk_month_target_type (`year_month`, target_type, project_id, cost_type);
