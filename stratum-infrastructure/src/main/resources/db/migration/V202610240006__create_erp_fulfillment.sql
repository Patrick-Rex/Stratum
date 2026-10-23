CREATE TABLE erp_fulfillment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_fulfillment_id_order UNIQUE (id, order_id),
    CONSTRAINT uk_fulfillment_order_version UNIQUE (order_id, order_version),
    CONSTRAINT fk_fulfillment_order FOREIGN KEY (order_id, kind) REFERENCES erp_trade_order (id, kind),
    CONSTRAINT ck_fulfillment_version CHECK (order_version > 0),
    INDEX ix_fulfillment_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE erp_fulfillment_line (
    posting_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_line_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity DECIMAL(38,12) NOT NULL,
    PRIMARY KEY (posting_id, order_line_id),
    CONSTRAINT uk_fulfillment_line_source UNIQUE (posting_id, order_line_id, order_id),
    CONSTRAINT fk_fulfillment_line_posting FOREIGN KEY (posting_id, order_id) REFERENCES erp_fulfillment (id, order_id),
    CONSTRAINT fk_fulfillment_line_order FOREIGN KEY (order_line_id, order_id) REFERENCES erp_order_line (id, order_id),
    CONSTRAINT ck_fulfillment_line_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
