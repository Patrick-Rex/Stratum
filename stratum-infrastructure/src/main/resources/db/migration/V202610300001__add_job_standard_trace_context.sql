-- 与既有业务关联字段并列保存初始及当前标准上下文；历史任务和旧写入保持空值。
ALTER TABLE job_execution
    ADD COLUMN initial_standard_traceparent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN initial_standard_tracestate VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN standard_traceparent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN standard_tracestate VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_job_initial_standard_trace_parent CHECK (
        initial_standard_traceparent IS NOT NULL OR initial_standard_tracestate IS NULL
    ),
    ADD CONSTRAINT ck_job_standard_trace_parent CHECK (
        standard_traceparent IS NOT NULL OR standard_tracestate IS NULL
    );
