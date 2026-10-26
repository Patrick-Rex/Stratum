ALTER TABLE outbox_event
    ADD lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD lease_until DATETIME(6) NULL COMMENT 'UTC',
    ADD CONSTRAINT ck_outbox_processing_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    ),
    ADD INDEX idx_outbox_processing_lease (status, lease_until, event_id);
