-- OTA database schema (MySQL 8 / Azure Database for MySQL Flexible Server).
-- Written to directly by the Android app (OtaDatabaseClient.kt) — keep in sync.
-- OTA history only: per-product settings stay on SFTP (app_settings.ini).
--
-- All DATETIME columns are the tablet's local time (Europe/Paris) to the
-- second — what the operator saw — not UTC.

CREATE TABLE IF NOT EXISTS ota_history (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ota_start                DATETIME        NULL,      -- first upload of this attempt (NULL if unknown)
    ota_end                  DATETIME        NOT NULL,  -- PASS/FAIL verdict given
    result                   VARCHAR(16)     NOT NULL,  -- PASS / FAIL
    batch_number             VARCHAR(64)     NOT NULL,
    product_name             VARCHAR(128)    NOT NULL,
    pn_name                  VARCHAR(128)    NOT NULL,
    card_type                VARCHAR(16)     NULL,      -- ANTENNA / POWER / BOTH
    device_mac               CHAR(17)        NULL,      -- AA:BB:CC:DD:EE:FF
    model_number             VARCHAR(64)     NULL,
    firmware_version_antenna VARCHAR(64)     NULL,
    firmware_version_power   VARCHAR(64)     NULL,
    expected_model           VARCHAR(64)     NULL,
    expected_antenna         VARCHAR(64)     NULL,
    expected_power           VARCHAR(64)     NULL,
    failure_reason           VARCHAR(1024)   NULL,
    tablet_id                VARCHAR(64)     NULL,      -- ANDROID_ID, shown on the tablet's "Historique OTA" screen
    app_version              VARCHAR(16)     NULL,
    uploaded_at              DATETIME        NOT NULL,  -- reached the DB (gap vs ota_end = time in retry queue)
    PRIMARY KEY (id),
    UNIQUE KEY uq_ota_attempt (ota_end, device_mac, product_name),
    KEY ix_ota_batch (batch_number),
    KEY ix_ota_product_time (product_name, ota_end),
    KEY ix_ota_mac (device_mac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The tablet account needs INSERT on this table (currently has DB-wide
-- SELECT, INSERT, UPDATE). Duration of an OTA:
--   SELECT TIMEDIFF(ota_end, ota_start) FROM ota_history;
