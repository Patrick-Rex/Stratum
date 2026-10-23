CREATE TABLE erp_master_data (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    base_unit VARCHAR(32) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_master_data_kind_code UNIQUE (kind, code),
    CONSTRAINT uk_master_data_id_kind UNIQUE (id, kind),
    CONSTRAINT ck_master_data_kind CHECK (kind IN ('MATERIAL', 'CUSTOMER', 'SUPPLIER', 'WAREHOUSE')),
    CONSTRAINT ck_master_data_unit CHECK (
        (kind = 'MATERIAL' AND base_unit IS NOT NULL AND CHAR_LENGTH(TRIM(base_unit)) > 0)
        OR (kind <> 'MATERIAL' AND base_unit IS NULL)
    ),
    CONSTRAINT ck_master_data_text CHECK (CHAR_LENGTH(TRIM(code)) > 0 AND CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_master_data_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
