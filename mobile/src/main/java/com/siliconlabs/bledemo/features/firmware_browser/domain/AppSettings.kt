package com.siliconlabs.bledemo.features.firmware_browser.domain

/** Snapshot of a product's `app_settings.ini` as fetched from the SFTP server. */
data class AppSettingsData(
    val batchNumberRegex: String? = null
)

/**
 * Cached settings for the currently-selected product, pulled from that
 * product's own `{product}/app_settings.ini` on the SFTP server (see
 * SftpRepository.fetchAppSettings). Refreshed every time a product is
 * selected in the firmware browser — lets an admin set a per-product
 * batch-number format without shipping a new APK.
 *
 * This is only ever populated with an already-validated, non-blank, compiling
 * regex: `SftpRepositoryImpl.fetchAppSettings` fails the whole fetch (and the
 * caller blocks the operator) on anything wrong with the file — missing,
 * unreadable, missing `[batch_number]`/`regex`, blank, or a regex that
 * doesn't compile. `batchNumberRegex` stays null only before any product has
 * been successfully selected in this session; `validateBatchNumber`'s
 * "accept anything" branch below is unreachable in normal use and exists only
 * as a last-resort guard against a programming error, not as a real fallback.
 */
object AppSettings {
    var batchNumberRegex: String? = null
        private set

    fun update(data: AppSettingsData) {
        batchNumberRegex = data.batchNumberRegex?.takeIf { it.isNotBlank() }
    }

    fun validateBatchNumber(value: String): Boolean {
        val pattern = batchNumberRegex ?: return true
        return try {
            Regex(pattern).matches(value)
        } catch (e: Exception) {
            true
        }
    }
}
