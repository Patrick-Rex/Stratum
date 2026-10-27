-- 稳定凭据顺序支持有界扫描；游标单例只保存技术进度，不更改既有事件。
ALTER TABLE outbox_consumption
    ADD INDEX idx_outbox_consumption_reconcile (consumed_at, event_id, consumer_group);

CREATE TABLE outbox_reconciliation_cursor (
    cursor_id TINYINT NOT NULL PRIMARY KEY,
    consumed_at DATETIME(6) NULL COMMENT 'UTC',
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    consumer_group VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT ck_outbox_reconcile_singleton CHECK (cursor_id = 1),
    CONSTRAINT ck_outbox_reconcile_position CHECK (
        (consumed_at IS NULL AND event_id IS NULL AND consumer_group IS NULL)
        OR (consumed_at IS NOT NULL AND event_id IS NOT NULL AND consumer_group IS NOT NULL
            AND consumer_group IN ('ERP_AUDIT', 'CACHE_INVALIDATION'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO outbox_reconciliation_cursor (cursor_id) VALUES (1);
