package com.siliconlabs.bledemo.features.firmware_browser.domain

/**
 * Central MySQL database (Azure) for OTA history. Per-product app settings
 * stay on SFTP (app_settings.ini). Credentials arrive the TB45 way, like SFTP's: a `[MYSQL]` section
 * in the operator-dropped `Secret_OTAU.ini`, imported and Android
 * Keystore-encrypted by `SecretsManager` — never stored or shipped in
 * plaintext, and never embedded in the APK.
 *
 * The DB account should be least-privilege (INSERT on ota_history only —
 * see docs/ota_database_schema.sql), since every
 * tablet holds a copy of it.
 *
 * There is no separate on/off flag — the database is used automatically once
 * the credentials are present; until then records wait in OtaHistoryStore's
 * pending queue.
 */
object DatabaseConfig {
    val HOST: String get() = SecretsManager.get("MYSQL", "host") ?: ""
    val PORT: Int get() = SecretsManager.get("MYSQL", "port")?.toIntOrNull() ?: 3306
    val DATABASE: String get() = SecretsManager.get("MYSQL", "database") ?: ""
    val USERNAME: String get() = SecretsManager.get("MYSQL", "username") ?: ""
    val PASSWORD: String get() = SecretsManager.get("MYSQL", "password") ?: ""

    fun isConfigured(): Boolean =
        HOST.isNotBlank() && DATABASE.isNotBlank() && USERNAME.isNotBlank() && PASSWORD.isNotBlank()
}
