-- ====================================
-- V107: 补全 ai_proactive_suggestion.status 的取值说明
-- V105 的列注释只写了 NEW / DONE / DISMISSED，漏了 AUTO_DISMISSED
-- （条件消失后由 dismissStale 写入，统计上不计入用户的「忽略」）。
-- V105 已在生产执行，改其内容会触发 Flyway checksum 校验失败，所以用本迁移补注释。
-- ====================================

ALTER TABLE ai_proactive_suggestion
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW'
        COMMENT 'NEW / DONE / DISMISSED / AUTO_DISMISSED';
