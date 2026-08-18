ALTER TABLE email_message
    ADD COLUMN project_id BIGINT NULL AFTER account_id,
    ADD COLUMN grouping_status VARCHAR(32) NOT NULL DEFAULT 'NOT_GROUPED' AFTER ai_interpreted_at,
    ADD COLUMN grouping_method VARCHAR(32) NULL AFTER grouping_status,
    ADD COLUMN grouping_confidence DECIMAL(5,4) NULL AFTER grouping_method,
    ADD COLUMN grouping_reason VARCHAR(500) NULL AFTER grouping_confidence,
    ADD COLUMN grouping_model VARCHAR(128) NULL AFTER grouping_reason,
    ADD COLUMN grouped_at DATETIME NULL AFTER grouping_model,
    ADD KEY idx_email_message_owner_project (owner_user_id, project_id, received_at),
    ADD CONSTRAINT fk_email_message_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL;
