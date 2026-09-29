-- V98: 跨业务线分配支持（云鹿Saas ↔ 云鹿定制，两条 full 线互为目标域）
-- 分配记录归属于「目标所在业务线」；不同业务线的非项目目标键相同（sales:0 / line_other:0 / project:0），
-- 唯一键必须含 business_line_id 才能区分两条线的同名目标。
ALTER TABLE project_profit_allocation
    DROP INDEX uk_month_target_type,
    ADD UNIQUE KEY uk_month_line_target_type (`year_month`, business_line_id, target_type, project_id, cost_type);
