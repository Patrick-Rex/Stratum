-- 仅允许从未开始的任务取消；保留原定义、身份和执行历史。
ALTER TABLE job_execution
    DROP CONSTRAINT ck_job_failure_state,
    DROP CONSTRAINT ck_job_finished,
    ADD CONSTRAINT ck_job_failure_state CHECK (
        (BINARY state='WAITING' AND failure_count=0)
        OR (BINARY state='CANCELLED' AND failure_count=0 AND attempt_no=0 AND cycle_no=0)
        OR (BINARY state IN ('RUNNING','SUCCEEDED') AND failure_count>=0 AND failure_count<max_attempts)
        OR (BINARY state='RETRY_WAIT' AND failure_count>0 AND failure_count<max_attempts)
        OR (BINARY state='DEAD' AND failure_count=max_attempts)
    ),
    ADD CONSTRAINT ck_job_finished CHECK (
        (BINARY state IN ('SUCCEEDED','DEAD','CANCELLED') AND finished_at IS NOT NULL)
        OR (BINARY state NOT IN ('SUCCEEDED','DEAD','CANCELLED') AND finished_at IS NULL)
    );
