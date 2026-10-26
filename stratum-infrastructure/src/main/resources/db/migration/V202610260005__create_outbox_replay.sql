CREATE TABLE outbox_replay_delivery (
    delivery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    dead_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NOT NULL,
    event_sequence BIGINT NOT NULL CHECK (event_sequence > 0),
    consumer_group VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_retry_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    active_key TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_replay_archive (dead_id),
    UNIQUE KEY uk_replay_active_event (event_id, consumer_group, active_key),
    KEY idx_replay_ready (status, next_retry_at, lease_until),
    CONSTRAINT fk_replay_archive FOREIGN KEY (dead_id) REFERENCES outbox_dead_letter(dead_id),
    CONSTRAINT fk_replay_event FOREIGN KEY (event_id) REFERENCES outbox_event(event_id),
    CONSTRAINT chk_replay_group CHECK (consumer_group IN ('ERP_AUDIT', 'CACHE_INVALIDATION')),
    CONSTRAINT chk_replay_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'RESOLVED')),
    CONSTRAINT chk_replay_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT chk_replay_resolution CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL) OR (status <> 'RESOLVED' AND resolved_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_replay_request (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    dead_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registration_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_failures INT NOT NULL DEFAULT 0 CHECK (previous_failures >= 0),
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    CONSTRAINT fk_replay_request_archive FOREIGN KEY (dead_id) REFERENCES outbox_dead_letter(dead_id),
    CONSTRAINT fk_replay_request_delivery FOREIGN KEY (delivery_id) REFERENCES outbox_replay_delivery(delivery_id),
    CONSTRAINT chk_replay_request_outcome CHECK (
        (outcome IN ('REGISTERING', 'ALREADY_CONSUMED') AND delivery_id IS NULL)
        OR (outcome IN ('QUEUED', 'ALREADY_QUEUED') AND delivery_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
