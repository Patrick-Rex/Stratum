CREATE TABLE erp_stock_balance (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MATERIAL',
    warehouse_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAREHOUSE',
    quantity DECIMAL(38,12) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_balance_dimension UNIQUE (material_id, warehouse_id),
    CONSTRAINT fk_stock_balance_material FOREIGN KEY (material_id, material_kind) REFERENCES erp_master_data (id, kind),
    CONSTRAINT fk_stock_balance_warehouse FOREIGN KEY (warehouse_id, warehouse_kind) REFERENCES erp_master_data (id, kind),
    CONSTRAINT ck_stock_balance_kinds CHECK (material_kind = 'MATERIAL' AND warehouse_kind = 'WAREHOUSE'),
    CONSTRAINT ck_stock_balance_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_stock_balance_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
