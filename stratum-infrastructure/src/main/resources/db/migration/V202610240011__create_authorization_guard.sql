-- 固定单行用于管理操作的事务协调，不是业务实体或自增业务ID。
CREATE TABLE auth_administration_guard (
    singleton_id TINYINT NOT NULL,
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_auth_administration_guard_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_auth_administration_guard_initialized CHECK (initialized IN (0, 1))
) ENGINE=InnoDB;

INSERT INTO auth_administration_guard(singleton_id, initialized) VALUES (1, FALSE);
