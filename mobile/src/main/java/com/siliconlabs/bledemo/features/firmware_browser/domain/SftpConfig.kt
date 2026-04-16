package com.siliconlabs.bledemo.features.firmware_browser.domain

object SftpConfig {
    val HOST: String get() = SecretsManager.get("SFTP", "host") ?: ""
    val PORT: Int get() = SecretsManager.get("SFTP", "port")?.toIntOrNull() ?: 22
    val USERNAME: String get() = SecretsManager.get("SFTP", "username") ?: ""
    val KEY_PASSPHRASE: String get() = SecretsManager.get("SFTP", "key_passphrase") ?: ""
    val ROOT_DIR: String get() = SecretsManager.get("SFTP", "root_dir") ?: "/"
    val PRIVATE_KEY: String get() = SecretsManager.get("SFTP", "private_key") ?: ""

    fun isConfigured(): Boolean = HOST.isNotEmpty() && USERNAME.isNotEmpty() && PRIVATE_KEY.isNotEmpty()
}
