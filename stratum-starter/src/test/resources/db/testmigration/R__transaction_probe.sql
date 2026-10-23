-- 仅集成测试加载，使用独立数据库，不向应用交付业务表。
CREATE TABLE IF NOT EXISTS stratum_it_transaction_probe (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
