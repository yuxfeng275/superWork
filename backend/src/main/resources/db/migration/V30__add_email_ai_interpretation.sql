ALTER TABLE email_message
    ADD COLUMN ai_interpretation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_GENERATED' AFTER attachments_json,
    ADD COLUMN ai_interpretation_json JSON NULL AFTER ai_interpretation_status,
    ADD COLUMN ai_interpretation_model VARCHAR(128) NULL AFTER ai_interpretation_json,
    ADD COLUMN ai_interpretation_error VARCHAR(500) NULL AFTER ai_interpretation_model,
    ADD COLUMN ai_interpreted_at DATETIME NULL AFTER ai_interpretation_error;

ALTER TABLE email_daily_digest
    ADD COLUMN generated_model VARCHAR(128) NULL AFTER generation_mode;
