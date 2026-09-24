package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Stable per-tablet identifier for OTA history records (`tablet_id` column).
 *
 * True hardware IDs (serial, IMEI, real Wi-Fi/BT MAC) are unreadable by
 * normal apps since Android 10, so this is ANDROID_ID: 16 hex digits, unique
 * per device + app signing key, stable across app updates/reinstalls, reset
 * only by a factory reset. Not visible from outside the app (adb shows a
 * different per-app value), so it is displayed on the "Historique OTA" screen
 * for prod to label each tablet.
 */
object TabletId {
    @SuppressLint("HardwareIds")
    fun get(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
}
