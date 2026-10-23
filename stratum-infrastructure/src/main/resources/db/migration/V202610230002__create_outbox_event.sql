CREATE TABLE outbox_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC',
    payload JSON NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL COMMENT 'UTC',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbox_retry CHECK (retry_count >= 0),
    CONSTRAINT ck_outbox_payload_size CHECK (OCTET_LENGTH(payload) <= 262144),
    INDEX idx_outbox_pending (status, next_retry_at, created_at),
    INDEX idx_outbox_aggregate (aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
