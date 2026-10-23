-- 仅测试主数据被业务引用时的外键保护，实际库存/订单引用由后续迁移建立。
CREATE TABLE IF NOT EXISTS stratum_it_master_data_reference (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    master_data_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_probe_master_data FOREIGN KEY (master_data_id) REFERENCES erp_master_data(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
