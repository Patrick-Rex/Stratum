CREATE TABLE auth_role_warehouse_scope (
    role_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    scope_type VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CUSTOM',
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_role_warehouse_scope_type UNIQUE (role_id, scope_type),
    CONSTRAINT fk_role_warehouse_scope_role FOREIGN KEY (role_id) REFERENCES auth_role (id) ON DELETE CASCADE,
    CONSTRAINT ck_role_warehouse_scope_type CHECK (BINARY scope_type IN (BINARY 'ALL', BINARY 'CUSTOM')),
    CONSTRAINT ck_role_warehouse_scope_version CHECK (version >= 0)
) ENGINE=InnoDB;

CREATE TABLE auth_role_warehouse (
    role_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CUSTOM',
    warehouse_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAREHOUSE',
    PRIMARY KEY (role_id, warehouse_id),
    KEY ix_role_warehouse_target (warehouse_id, warehouse_kind),
    CONSTRAINT fk_role_warehouse_configuration FOREIGN KEY (role_id, scope_type)
        REFERENCES auth_role_warehouse_scope (role_id, scope_type) ON DELETE CASCADE,
    CONSTRAINT fk_role_warehouse_target FOREIGN KEY (warehouse_id, warehouse_kind) REFERENCES erp_master_data (id, kind),
    CONSTRAINT ck_role_warehouse_custom CHECK (BINARY scope_type = BINARY 'CUSTOM'),
    CONSTRAINT ck_role_warehouse_kind CHECK (BINARY warehouse_kind = BINARY 'WAREHOUSE')
) ENGINE=InnoDB;

INSERT INTO auth_role_warehouse_scope (role_id, scope_type, version)
SELECT id, CASE WHEN code = 'administrator' THEN 'ALL' ELSE 'CUSTOM' END, 0 FROM auth_role;
