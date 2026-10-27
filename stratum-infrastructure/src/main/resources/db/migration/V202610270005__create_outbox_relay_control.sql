CREATE TABLE outbox_relay_control (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    paused BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT ck_relay_control_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_relay_control_paused CHECK (paused IN (0, 1)),
    CONSTRAINT ck_relay_control_revision CHECK (revision >= 0)
) ENGINE=InnoDB;

INSERT INTO outbox_relay_control (singleton_id, paused, revision) VALUES (1, 1, 0);
