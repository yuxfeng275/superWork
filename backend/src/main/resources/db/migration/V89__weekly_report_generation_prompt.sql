-- ====================================
-- V89: 周报生成提示词 + 生成模型提供方
-- generation_prompt：用户填写的生成指引，生成时拼入 AI 输入，引导周报侧重点。
-- generation_provider：记录生成时使用的模型提供方编码，配合 generation_model 在页面展示模型接入。
-- ====================================

ALTER TABLE weekly_report
    ADD COLUMN generation_prompt TEXT NULL
        COMMENT '生成提示词：引导 AI 生成周报的侧重点'
        AFTER manual_notes,
    ADD COLUMN generation_provider VARCHAR(64) NULL
        COMMENT '生成使用的模型提供方编码（如 glm / deepseek）'
        AFTER generation_model;
