ALTER TABLE email_daily_digest
    ADD COLUMN topic_items JSON NULL AFTER overview,
    ADD COLUMN progress_items JSON NULL AFTER topic_items;

UPDATE email_daily_digest
SET topic_items = JSON_ARRAY(), progress_items = JSON_ARRAY()
WHERE topic_items IS NULL OR progress_items IS NULL;

ALTER TABLE email_daily_digest
    MODIFY COLUMN topic_items JSON NOT NULL,
    MODIFY COLUMN progress_items JSON NOT NULL;
