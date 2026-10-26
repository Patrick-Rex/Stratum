CREATE TABLE outbox_consumption (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_group VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    payload LONGTEXT NOT NULL CHECK (JSON_VALID(payload)),
    trace_id VARCHAR(64) NOT NULL,
    consumed_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (event_id, consumer_group),
    UNIQUE KEY uk_consumption_aggregate_sequence (aggregate_id, event_sequence, consumer_group),
    CONSTRAINT fk_consumption_event FOREIGN KEY (event_id) REFERENCES outbox_event(event_id),
    CONSTRAINT chk_consumption_sequence CHECK (event_sequence > 0),
    CONSTRAINT chk_consumption_group CHECK (consumer_group IN ('ERP_AUDIT', 'CACHE_INVALIDATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
