CREATE TABLE outbox_dead_letter (
    dead_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    consumer_group VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body MEDIUMBLOB NOT NULL,
    metadata_json LONGTEXT NOT NULL CHECK (JSON_VALID(metadata_json) AND OCTET_LENGTH(metadata_json) <= 131072),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    aggregate_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NULL,
    event_sequence BIGINT NULL,
    event_type VARCHAR(200) NULL,
    occurred_at DATETIME(6) NULL,
    payload LONGTEXT NULL CHECK (payload IS NULL OR JSON_VALID(payload)),
    trace_id VARCHAR(64) NULL,
    category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_count BIGINT NOT NULL,
    seen_count BIGINT NOT NULL DEFAULT 1,
    first_seen_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    last_seen_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE KEY uk_dead_letter_evidence (consumer_group, fingerprint),
    KEY idx_dead_letter_event (event_id, consumer_group, status),
    CONSTRAINT chk_dead_letter_body CHECK (OCTET_LENGTH(body) <= 2097152),
    CONSTRAINT chk_dead_letter_count CHECK (failure_count >= 0 AND seen_count >= 1),
    CONSTRAINT chk_dead_letter_group CHECK (consumer_group IN ('ERP_AUDIT', 'CACHE_INVALIDATION')),
    CONSTRAINT chk_dead_letter_status CHECK (status IN ('DEAD', 'QUARANTINED', 'REPLAY_REQUESTED', 'RESOLVED')),
    CONSTRAINT chk_dead_letter_identity CHECK (
        (category = 'RETRIES_EXHAUSTED' AND event_id IS NOT NULL AND aggregate_id IS NOT NULL
            AND event_sequence IS NOT NULL AND event_sequence > 0 AND event_type IS NOT NULL AND occurred_at IS NOT NULL
            AND payload IS NOT NULL AND trace_id IS NOT NULL)
        OR (category = 'INVALID_ENVELOPE' AND event_id IS NULL AND aggregate_id IS NULL
            AND event_sequence IS NULL AND event_type IS NULL AND occurred_at IS NULL
            AND payload IS NULL AND trace_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
