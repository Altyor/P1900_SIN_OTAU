package com.siliconlabs.bledemo.features.firmware_browser.domain

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One completed OTA attempt (pass or fail), captured locally on the device.
 * Structured so it can be pushed to a central store later without changing
 * its shape — see OtaHistoryStore.
 */
data class OtaHistoryRecord(
    /** End of OTA (the moment the PASS/FAIL verdict was given). */
    val timestampMillis: Long,
    val timestampIso: String,
    /** Start of OTA (first upload of this attempt). Null if unknown, e.g.
     *  records written before this field existed. */
    val otaStartMillis: Long? = null,
    val batchNumber: String,
    val deviceMac: String?,
    val productName: String,
    val pnName: String,
    val cardType: String?,
    val modelNumber: String?,
    val firmwareVersionAntenna: String?,
    val firmwareVersionPower: String?,
    val expectedModel: String?,
    val expectedAntenna: String?,
    val expectedPower: String?,
    val result: String,
    val failureReason: String?,
    /** Which tablet did the OTA — see TabletId. */
    val tabletId: String? = null,
    val appVersion: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("timestampIso", timestampIso)
        put("otaStartMillis", otaStartMillis ?: JSONObject.NULL)
        put("batchNumber", batchNumber)
        put("deviceMac", deviceMac ?: JSONObject.NULL)
        put("productName", productName)
        put("pnName", pnName)
        put("cardType", cardType ?: JSONObject.NULL)
        put("modelNumber", modelNumber ?: JSONObject.NULL)
        put("firmwareVersionAntenna", firmwareVersionAntenna ?: JSONObject.NULL)
        put("firmwareVersionPower", firmwareVersionPower ?: JSONObject.NULL)
        put("expectedModel", expectedModel ?: JSONObject.NULL)
        put("expectedAntenna", expectedAntenna ?: JSONObject.NULL)
        put("expectedPower", expectedPower ?: JSONObject.NULL)
        put("result", result)
        put("failureReason", failureReason ?: JSONObject.NULL)
        put("tabletId", tabletId ?: JSONObject.NULL)
        put("appVersion", appVersion ?: JSONObject.NULL)
    }

    companion object {
        private fun JSONObject.optStringOrNull(name: String): String? =
            if (!has(name) || isNull(name)) null else optString(name)

        fun nowIso(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())

        fun fromJson(obj: JSONObject): OtaHistoryRecord = OtaHistoryRecord(
            timestampMillis = obj.optLong("timestampMillis"),
            timestampIso = obj.optString("timestampIso"),
            otaStartMillis = if (!obj.has("otaStartMillis") || obj.isNull("otaStartMillis")) null
                else obj.optLong("otaStartMillis"),
            batchNumber = obj.optString("batchNumber"),
            deviceMac = obj.optStringOrNull("deviceMac"),
            productName = obj.optString("productName"),
            pnName = obj.optString("pnName"),
            cardType = obj.optStringOrNull("cardType"),
            modelNumber = obj.optStringOrNull("modelNumber"),
            firmwareVersionAntenna = obj.optStringOrNull("firmwareVersionAntenna"),
            firmwareVersionPower = obj.optStringOrNull("firmwareVersionPower"),
            expectedModel = obj.optStringOrNull("expectedModel"),
            expectedAntenna = obj.optStringOrNull("expectedAntenna"),
            expectedPower = obj.optStringOrNull("expectedPower"),
            result = obj.optString("result"),
            failureReason = obj.optStringOrNull("failureReason"),
            tabletId = obj.optStringOrNull("tabletId"),
            appVersion = obj.optStringOrNull("appVersion")
        )
    }
}
