-- Extend the read-only Yunxiao projection for unified requirement/task/defect queries.
ALTER TABLE yunxiao_workitem_cache
    ADD COLUMN yunxiao_project_id VARCHAR(100) NULL AFTER yunxiao_workitem_id,
    ADD COLUMN normalized_status VARCHAR(20) NOT NULL DEFAULT 'OTHER' AFTER status,
    ADD COLUMN source_created_at DATETIME NULL AFTER raw_json,
    ADD COLUMN source_updated_at DATETIME NULL AFTER source_created_at,
    ADD COLUMN active TINYINT NOT NULL DEFAULT 1 AFTER source_updated_at,
    ADD INDEX idx_yunxiao_workitem_space_category (yunxiao_project_id, category, active),
    ADD INDEX idx_yunxiao_workitem_normalized_status (normalized_status);

UPDATE yunxiao_workitem_cache cache
JOIN yunxiao_project_mapping mapping ON mapping.project_id = cache.project_id
SET cache.yunxiao_project_id = mapping.yunxiao_project_id
WHERE cache.yunxiao_project_id IS NULL;

ALTER TABLE yunxiao_project_mapping
    ADD COLUMN full_synced_at DATETIME NULL AFTER last_synced_at;

-- Reuse the existing issue permission and menu slot for the cloud-only defect module.
UPDATE sys_menu
SET name = '缺陷管理', icon = 'CircleCloseFilled', path = '/defects', component = 'DefectsView'
WHERE path = '/issues';
