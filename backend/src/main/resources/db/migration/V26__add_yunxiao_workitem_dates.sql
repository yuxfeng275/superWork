-- Add source lifecycle dates used by unified work-item analysis.
ALTER TABLE yunxiao_workitem_cache
    ADD COLUMN due_date DATE NULL AFTER source_updated_at,
    ADD INDEX idx_yunxiao_workitem_due_status (due_date, normalized_status, active);

-- Existing Yunxiao timestamps are millisecond epochs in raw_json.
UPDATE yunxiao_workitem_cache
SET source_created_at = FROM_UNIXTIME(
        CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.gmtCreate')) AS UNSIGNED) / 1000
    )
WHERE source_created_at IS NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.gmtCreate')) REGEXP '^[0-9]+$';

UPDATE yunxiao_workitem_cache
SET source_updated_at = FROM_UNIXTIME(
        CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.gmtModified')) AS UNSIGNED) / 1000
    )
WHERE source_updated_at IS NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.gmtModified')) REGEXP '^[0-9]+$';

-- Load planned completion first, then let expected completion override it.
UPDATE yunxiao_workitem_cache cache
JOIN JSON_TABLE(
    cache.raw_json,
    '$.customFieldValues[*]' COLUMNS (
        field_id VARCHAR(100) PATH '$.fieldId',
        field_name VARCHAR(100) PATH '$.fieldName',
        due_value VARCHAR(100) PATH '$.values[0].identifier'
    )
) field ON TRUE
SET cache.due_date = STR_TO_DATE(LEFT(field.due_value, 10), '%Y-%m-%d')
WHERE (field.field_id = '80' OR field.field_name = '计划完成时间')
  AND field.due_value REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}';

UPDATE yunxiao_workitem_cache cache
JOIN JSON_TABLE(
    cache.raw_json,
    '$.customFieldValues[*]' COLUMNS (
        field_id VARCHAR(100) PATH '$.fieldId',
        field_name VARCHAR(100) PATH '$.fieldName',
        due_value VARCHAR(100) PATH '$.values[0].identifier'
    )
) field ON TRUE
SET cache.due_date = STR_TO_DATE(LEFT(field.due_value, 10), '%Y-%m-%d')
WHERE (field.field_id = 'ExpCompletionTime' OR field.field_name = '期望完成时间')
  AND field.due_value REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}';
