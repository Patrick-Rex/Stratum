CREATE TABLE erp_stock_movement (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    balance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    posting_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_line_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    direction VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity DECIMAL(38,12) NOT NULL,
    balance_after DECIMAL(38,12) NOT NULL,
    balance_version BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_stock_movement_posting_line UNIQUE (posting_id, order_line_id),
    CONSTRAINT uk_stock_movement_balance_version UNIQUE (balance_id, balance_version),
    CONSTRAINT fk_stock_movement_balance FOREIGN KEY (balance_id) REFERENCES erp_stock_balance (id),
    CONSTRAINT ck_stock_movement_direction CHECK (direction IN ('RECEIPT', 'ISSUE')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0 AND balance_after >= 0),
    CONSTRAINT ck_stock_movement_version CHECK (balance_version >= 0),
    CONSTRAINT ck_stock_movement_before CHECK (
        (direction = 'RECEIPT' AND balance_after >= quantity)
        OR (direction = 'ISSUE' AND balance_after <= 99999999999999999999999999.999999999999 - quantity)
    ),
    INDEX ix_stock_movement_order (order_id, order_line_id),
    INDEX ix_stock_movement_time (occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
