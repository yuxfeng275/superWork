ALTER TABLE requirement_delivery
    ADD COLUMN delivered_by BIGINT NULL COMMENT '交付人ID' AFTER delivered_at,
    ADD COLUMN accepted_by BIGINT NULL COMMENT '验收人ID' AFTER delivery_notes;
