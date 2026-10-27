-- 消费成功但发布确认未持久化时保留原状态及重试次数，不改写已有SENT历史。
ALTER TABLE outbox_event
    DROP CONSTRAINT ck_outbox_status,
    ADD COLUMN consumed_from_status VARCHAR(16) NULL,
    ADD CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CONSUMED')),
    ADD CONSTRAINT ck_outbox_consumed_origin CHECK (
        (status = 'CONSUMED' AND consumed_from_status IS NOT NULL
            AND consumed_from_status IN ('PENDING', 'PROCESSING', 'FAILED'))
        OR (status <> 'CONSUMED' AND consumed_from_status IS NULL)
    );
