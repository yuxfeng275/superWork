-- ====================================
-- V100: 周报中心加固
-- 1) 四段正文 / 生成指引扩为 MEDIUMTEXT：TEXT 只有 64KB，粘贴长稿会直接
--    「Data too long for column」，前端校验却看不出来（2026-11-01 复现）。
-- 2) 新增 yuque_toc_message：目录挂载失败原因独立存放，不再复用
--    generation_error（否则发布成功但目录失败时，页面显示成「生成失败」）。
-- ====================================

ALTER TABLE weekly_report
    MODIFY COLUMN core_work MEDIUMTEXT NULL COMMENT '本周核心工作完成情况',
    MODIFY COLUMN kpi_section MEDIUMTEXT NULL COMMENT 'KPI相关情况（财务/业务/提效/品质）',
    MODIFY COLUMN risks MEDIUMTEXT NULL COMMENT '问题/风险与解决办法',
    MODIFY COLUMN next_week_plan MEDIUMTEXT NULL COMMENT '下周工作计划',
    MODIFY COLUMN generation_prompt MEDIUMTEXT NULL COMMENT '生成提示词：引导 AI 生成周报的侧重点';

ALTER TABLE weekly_report
    ADD COLUMN yuque_toc_message VARCHAR(500) NULL COMMENT '最近一次目录挂载失败原因' AFTER yuque_toc_status;
