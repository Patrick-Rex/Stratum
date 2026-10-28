-- 请求与任务在同一工作单元绑定；不使用外键阻止归档或账号的未来保留策略。
CREATE TABLE dead_letter_replay_task (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    archive_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registration_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    UNIQUE KEY uk_dead_letter_replay_task (task_id),
    CONSTRAINT fk_dead_letter_replay_task_execution FOREIGN KEY (task_id) REFERENCES job_execution(execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
