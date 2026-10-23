-- 仅集成测试使用的映射表，不属于应用业务数据库结构。
CREATE TABLE IF NOT EXISTS stratum_it_repository_probe (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL
) ENGINE=InnoDB;
