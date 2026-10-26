-- 已有事件不能据时间或UUID推断因果，任何DDL之前先拒绝非空历史表。
SET @stratum_outbox_guard_sql = IF(EXISTS(SELECT 1 FROM outbox_event),
    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''非空Outbox需要人工确认有序迁移策略''',
    'DO 0');
PREPARE stratum_outbox_guard FROM @stratum_outbox_guard_sql;
EXECUTE stratum_outbox_guard;
DEALLOCATE PREPARE stratum_outbox_guard;
SET @stratum_outbox_guard_sql = NULL;

CREATE TABLE outbox_aggregate_progress (
    aggregate_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    consumed_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_progress_allocated CHECK (last_sequence >= 0),
    CONSTRAINT ck_outbox_progress_consumed CHECK (consumed_sequence >= 0 AND consumed_sequence <= last_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE outbox_event
    MODIFY aggregate_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NOT NULL,
    ADD event_sequence BIGINT NOT NULL,
    ADD CONSTRAINT ck_outbox_event_sequence CHECK (event_sequence > 0),
    ADD CONSTRAINT uq_outbox_event_sequence UNIQUE (aggregate_id, event_sequence),
    ADD CONSTRAINT fk_outbox_event_progress FOREIGN KEY (aggregate_id)
        REFERENCES outbox_aggregate_progress (aggregate_id);
