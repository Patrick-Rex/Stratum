CREATE TABLE auth_role (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_role_code UNIQUE (code),
    CONSTRAINT ck_auth_role_id CHECK (id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_auth_role_code CHECK (code REGEXP '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CONSTRAINT ck_auth_role_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_auth_role_version CHECK (version >= 0)
) ENGINE=InnoDB;

CREATE TABLE auth_permission (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_permission_type_code UNIQUE (type, code),
    CONSTRAINT ck_auth_permission_id CHECK (id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_auth_permission_code CHECK (code REGEXP '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CONSTRAINT ck_auth_permission_type CHECK (type IN ('API', 'FEAT')),
    CONSTRAINT ck_auth_permission_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;

CREATE TABLE auth_user_role (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_auth_user_role_role (role_id),
    CONSTRAINT fk_auth_user_role_user FOREIGN KEY (user_id) REFERENCES auth_user (id),
    CONSTRAINT fk_auth_user_role_role FOREIGN KEY (role_id) REFERENCES auth_role (id)
) ENGINE=InnoDB;

CREATE TABLE auth_role_permission (
    role_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_auth_role_permission_permission (permission_id),
    CONSTRAINT fk_auth_role_permission_role FOREIGN KEY (role_id) REFERENCES auth_role (id),
    CONSTRAINT fk_auth_role_permission_permission FOREIGN KEY (permission_id) REFERENCES auth_permission (id)
) ENGINE=InnoDB;
