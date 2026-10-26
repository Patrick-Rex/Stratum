-- 任务技术状态与历史；业务事务由Application控制，Quartz触发与此状态分别持久化。
CREATE TABLE job_execution (
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_type VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cron_expression VARCHAR(120) NULL,
    trigger_at DATETIME(6) NULL,
    timeout_sec SMALLINT NOT NULL,
    max_attempts SMALLINT NOT NULL,
    initial_delay_ms INT NOT NULL,
    max_delay_ms INT NOT NULL,
    idempotency_rule VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_time DATETIME(6) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    arguments MEDIUMTEXT NOT NULL,
    initial_trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING',
    attempt_no BIGINT NOT NULL DEFAULT 0,
    failure_count SMALLINT NOT NULL DEFAULT 0,
    cycle_no BIGINT NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    start_time DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (execution_id),
    UNIQUE KEY uk_job_execution_key (job_code, idempotency_key),
    KEY idx_job_execution_due (job_code, state, next_attempt_at, execution_id),
    KEY idx_job_execution_lease (job_code, state, lease_until, execution_id),
    CONSTRAINT ck_job_definition CHECK (
        CHAR_LENGTH(job_code) BETWEEN 1 AND 64 AND job_code REGEXP '^[a-z]'
        AND job_code NOT REGEXP '[^a-z0-9.-]' AND timeout_sec BETWEEN 1 AND 300
        AND max_attempts BETWEEN 1 AND 10 AND initial_delay_ms BETWEEN 100 AND 60000
        AND max_delay_ms BETWEEN initial_delay_ms AND 3600000
        AND BINARY idempotency_rule IN ('EXPLICIT_KEY','SCHEDULE_TIME')
        AND CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 128 AND idempotency_key NOT REGEXP '[^!-~]'
        AND ((BINARY job_type='CRON' AND cron_expression IS NOT NULL AND CHAR_LENGTH(TRIM(cron_expression))>0
              AND cron_expression NOT REGEXP '[[:cntrl:]]' AND trigger_at IS NULL)
          OR (BINARY job_type='ONCE' AND cron_expression IS NULL AND trigger_at IS NOT NULL AND trigger_at=schedule_time))
    ),
    CONSTRAINT ck_job_arguments CHECK (JSON_VALID(arguments) AND OCTET_LENGTH(arguments) BETWEEN 1 AND 16384),
    CONSTRAINT ck_job_trace CHECK (
        CHAR_LENGTH(initial_trace_id) BETWEEN 1 AND 64 AND initial_trace_id NOT REGEXP '[^A-Za-z0-9_.-]'
        AND CHAR_LENGTH(trace_id) BETWEEN 1 AND 64 AND trace_id NOT REGEXP '[^A-Za-z0-9_.-]'
    ),
    CONSTRAINT ck_job_counters CHECK (attempt_no>=failure_count AND cycle_no>=0 AND cycle_no<=attempt_no AND version>=0),
    CONSTRAINT ck_job_failure_state CHECK (
        (BINARY state='WAITING' AND failure_count=0)
        OR (BINARY state IN ('RUNNING','SUCCEEDED') AND failure_count>=0 AND failure_count<max_attempts)
        OR (BINARY state='RETRY_WAIT' AND failure_count>0 AND failure_count<max_attempts)
        OR (BINARY state='DEAD' AND failure_count=max_attempts)
    ),
    CONSTRAINT ck_job_lease CHECK (
        (BINARY state='RUNNING' AND lease_token IS NOT NULL AND start_time IS NOT NULL AND lease_until IS NOT NULL
         AND lease_until>start_time AND lease_until<=start_time+INTERVAL 330 SECOND AND attempt_no>0)
        OR (BINARY state<>'RUNNING' AND lease_token IS NULL AND start_time IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_job_finished CHECK (
        (BINARY state IN ('SUCCEEDED','DEAD') AND finished_at IS NOT NULL)
        OR (BINARY state NOT IN ('SUCCEEDED','DEAD') AND finished_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;

CREATE TABLE job_attempt (
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no BIGINT NOT NULL,
    cycle_no BIGINT NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_time DATETIME(6) NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (execution_id, attempt_no),
    UNIQUE KEY uk_job_attempt_token (lease_token),
    UNIQUE KEY uk_job_attempt_cycle_result (execution_id,attempt_no,cycle_no,result),
    CONSTRAINT fk_job_attempt_execution FOREIGN KEY (execution_id) REFERENCES job_execution(execution_id),
    CONSTRAINT ck_job_attempt_counter CHECK (attempt_no>0 AND cycle_no>=0 AND cycle_no<attempt_no),
    CONSTRAINT ck_job_attempt_trace CHECK (
        CHAR_LENGTH(trace_id) BETWEEN 1 AND 64 AND trace_id NOT REGEXP '[^A-Za-z0-9_.-]'
    ),
    CONSTRAINT ck_job_attempt_result CHECK (
        (BINARY result='RUNNING' AND end_time IS NULL AND duration_ms IS NULL)
        OR (BINARY result IN ('SUCCEEDED','FAILED','TIMED_OUT','INTERRUPTED','LEASE_EXPIRED')
            AND end_time IS NOT NULL AND end_time>=start_time AND duration_ms IS NOT NULL AND duration_ms>=0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;

CREATE TABLE dead_job (
    dead_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cycle_no BIGINT NOT NULL,
    last_attempt BIGINT NOT NULL,
    failure_count SMALLINT NOT NULL,
    reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DEAD',
    failed_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (dead_id),
    UNIQUE KEY uk_dead_job_cycle (execution_id, cycle_no),
    UNIQUE KEY uk_dead_job_execution (dead_id,execution_id),
    KEY idx_dead_job_state (state, failed_at),
    CONSTRAINT fk_dead_job_attempt FOREIGN KEY (execution_id,last_attempt,cycle_no,reason) REFERENCES job_attempt(execution_id,attempt_no,cycle_no,result),
    CONSTRAINT ck_dead_job_counter CHECK (cycle_no>=0 AND last_attempt>cycle_no AND failure_count BETWEEN 1 AND 10 AND failure_count<=last_attempt),
    CONSTRAINT ck_dead_job_reason CHECK (BINARY reason IN ('FAILED','TIMED_OUT','INTERRUPTED','LEASE_EXPIRED')),
    CONSTRAINT ck_dead_job_state CHECK (
        (BINARY state IN ('DEAD','REPLAY_REQUESTED') AND resolved_at IS NULL)
        OR (BINARY state='RESOLVED' AND resolved_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;

CREATE TABLE job_replay_request (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dead_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resumed_cycle BIGINT NOT NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_job_replay_dead (dead_id),
    CONSTRAINT fk_job_replay_dead FOREIGN KEY (dead_id,execution_id) REFERENCES dead_job(dead_id,execution_id),
    CONSTRAINT ck_job_replay_cycle CHECK (resumed_cycle>0),
    CONSTRAINT ck_job_replay_trace CHECK (
        CHAR_LENGTH(trace_id) BETWEEN 1 AND 64 AND trace_id NOT REGEXP '[^A-Za-z0-9_.-]'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_nopad_bin;
