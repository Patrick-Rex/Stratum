CREATE TABLE idempotency_receipt (
    scope VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (scope, request_key),
    CONSTRAINT ck_idempotency_result_size CHECK (OCTET_LENGTH(result_json) <= 262144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
