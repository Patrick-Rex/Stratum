CREATE TABLE auth_user (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash CHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL,
    bootstrap_slot TINYINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_user_username UNIQUE (username),
    CONSTRAINT uk_auth_user_bootstrap UNIQUE (bootstrap_slot),
    CONSTRAINT ck_auth_user_id CHECK (id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_auth_user_username CHECK (username REGEXP '^[-A-Za-z0-9._]{1,64}$'),
    CONSTRAINT ck_auth_user_hash CHECK (password_hash REGEXP '^[$]2[aby][$](0[4-9]|1[0-2])[$][./A-Za-z0-9]{53}$'),
    CONSTRAINT ck_auth_user_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_auth_user_bootstrap CHECK (bootstrap_slot IS NULL OR bootstrap_slot = 1),
    CONSTRAINT ck_auth_user_version CHECK (version >= 0)
) ENGINE=InnoDB;
