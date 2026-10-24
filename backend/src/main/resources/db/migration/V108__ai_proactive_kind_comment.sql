-- ====================================
-- V108: 补全 ai_proactive_suggestion.kind 的取值说明
-- V105 的列注释只写了 4 种 kind，漏了 weekly_toc 与 key_matter_overdue
-- （AiProactiveService 的 weeklyTocRule / overdueMatterRule 会写入）。
-- V105 已在生产执行，改其内容会触发 Flyway checksum 校验失败，所以用本迁移补注释。
-- ====================================

ALTER TABLE ai_proactive_suggestion
    MODIFY COLUMN kind VARCHAR(40) NOT NULL
        COMMENT 'weekly_report / key_matter_week / meeting_todos / email_digest / weekly_toc / key_matter_overdue';
