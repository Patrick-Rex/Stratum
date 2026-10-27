CREATE TABLE outbox_original_recovery_request (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registration_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_failures INT NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    KEY idx_original_recovery_event (event_id, created_at, request_id),
    CONSTRAINT ck_original_recovery_request_id CHECK (request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_original_recovery_event_id CHECK (event_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_original_recovery_operator_id CHECK (operator_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_original_recovery_token CHECK (registration_token REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_original_recovery_trace CHECK (CHAR_LENGTH(trace_id) BETWEEN 1 AND 64 AND BINARY trace_id NOT REGEXP '[^A-Za-z0-9_.-]'),
    CONSTRAINT ck_original_recovery_outcome CHECK (
        (BINARY outcome IN (BINARY 'REGISTERING', BINARY 'ALREADY_CONSUMED') AND previous_failures IS NULL)
        OR (BINARY outcome = BINARY 'RECOVERED' AND previous_failures IS NOT NULL AND previous_failures >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
