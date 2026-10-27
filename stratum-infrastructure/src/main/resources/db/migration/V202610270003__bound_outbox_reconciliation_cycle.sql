-- 保留每轮数据库UTC上界，持续新消费不能无限延长同一轮扫描。
ALTER TABLE outbox_reconciliation_cursor
    ADD COLUMN cycle_upper_bound DATETIME(6) NULL COMMENT 'UTC',
    ADD CONSTRAINT ck_outbox_reconcile_cycle CHECK (
        (consumed_at IS NULL AND cycle_upper_bound IS NULL)
        OR (consumed_at IS NOT NULL AND cycle_upper_bound IS NOT NULL AND consumed_at <= cycle_upper_bound)
    );
