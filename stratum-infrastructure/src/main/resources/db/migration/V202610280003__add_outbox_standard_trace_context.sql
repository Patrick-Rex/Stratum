-- 历史事件不推测标准父链；新列保持可空以兼容旧版本写入。
ALTER TABLE outbox_event
    ADD COLUMN standard_traceparent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN standard_tracestate VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_outbox_standard_trace_parent CHECK (
        standard_traceparent IS NOT NULL OR standard_tracestate IS NULL
    );
