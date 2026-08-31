-- ====================================
-- V38: 导入批次月份支持多月范围（如 2026-01~2026-06）
-- ====================================

ALTER TABLE revenue_import_batch MODIFY COLUMN `year_month` VARCHAR(16) NOT NULL COMMENT '归属月份 YYYY-MM，多月文件为 YYYY-MM~YYYY-MM';
