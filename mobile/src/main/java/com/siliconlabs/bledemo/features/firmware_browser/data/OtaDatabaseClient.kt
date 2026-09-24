package com.siliconlabs.bledemo.features.firmware_browser.data

import android.util.Log
import com.siliconlabs.bledemo.features.firmware_browser.domain.DatabaseConfig
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Types
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Direct JDBC access to the central MySQL database (see DatabaseConfig), for
 * OTA history only — product settings stay on SFTP (app_settings.ini). Called
 * by OtaHistorySyncWorker, which drains OtaHistoryStore's pending queue.
 *
 * Driver: MariaDB Connector/J 1.8 (Java 7 line) — 2.x+ uses JDBC 4.2 classes
 * Android doesn't have. One short-lived connection per call: traffic is one
 * insert per OTA, and a pooled connection would just go stale on flaky
 * factory Wi-Fi. Table layout: docs/ota_database_schema.sql.
 */
object OtaDatabaseClient {
    private const val TAG = "OtaDatabaseClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val SOCKET_TIMEOUT_MS = 15_000

    private fun openConnection(): Connection {
        // TLS is mandatory on Azure MySQL. The certificate chain is verified
        // against Android's system CA store, but not the hostname: the DB is
        // reached through the company alias (prod.france.db.altyor.com) while
        // Azure's certificate only covers *.mysql.database.azure.com.
        val url = "jdbc:mysql://${DatabaseConfig.HOST}:${DatabaseConfig.PORT}/${DatabaseConfig.DATABASE}" +
            "?useSsl=true&trustServerCertificate=false&disableSslHostnameVerification=true" +
            "&connectTimeout=$CONNECT_TIMEOUT_MS&socketTimeout=$SOCKET_TIMEOUT_MS"
        val props = Properties().apply {
            setProperty("user", DatabaseConfig.USERNAME)
            setProperty("password", DatabaseConfig.PASSWORD)
        }
        // Instantiate the driver directly rather than via DriverManager's
        // ServiceLoader lookup, which is unreliable on Android.
        return org.mariadb.jdbc.Driver().connect(url, props)
            ?: throw IllegalStateException("Driver rejected JDBC URL")
    }

    /** Tablet-local wall-clock time to the second, e.g. "2026-09-24 11:32:05".
     *  Sent as text so the driver can't shift it through a time-zone
     *  conversion — the DB stores exactly what the operator saw. */
    private fun localDateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    suspend fun pushOtaHistory(r: OtaHistoryRecord): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            openConnection().use { conn ->
                // IGNORE: a re-sent attempt hits uq_ota_attempt and is skipped
                // instead of failing, and an over-long value is truncated
                // rather than rejected.
                conn.prepareStatement(
                    """
                    INSERT IGNORE INTO ota_history (
                        ota_start, ota_end, result, batch_number,
                        product_name, pn_name, card_type, device_mac,
                        model_number, firmware_version_antenna, firmware_version_power,
                        expected_model, expected_antenna, expected_power,
                        failure_reason, tablet_id, app_version, uploaded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { st ->
                    val values = listOf(
                        r.otaStartMillis?.let(::localDateTime),
                        localDateTime(r.timestampMillis),
                        r.result, r.batchNumber,
                        r.productName, r.pnName, r.cardType, r.deviceMac,
                        r.modelNumber, r.firmwareVersionAntenna, r.firmwareVersionPower,
                        r.expectedModel, r.expectedAntenna, r.expectedPower,
                        r.failureReason, r.tabletId, r.appVersion,
                        localDateTime(System.currentTimeMillis())
                    )
                    values.forEachIndexed { i, v ->
                        if (v == null) st.setNull(i + 1, Types.VARCHAR) else st.setString(i + 1, v)
                    }
                    st.executeUpdate()
                }
            }
            Unit
        }.onFailure {
            Log.w(TAG, "Failed to push OTA history record: ${it.message}")
        }
    }
}
