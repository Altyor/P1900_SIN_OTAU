package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Publishes the current OTA state to `ota_status.json` in the app's external
 * files dir so the PC console (BLE_Bridge_OTA) can read it over adb
 * (`adb exec-out cat .../files/ota_status.json`) and drive the workflow.
 *
 * Contract: see BLE_Bridge_OTA/PROTOCOL.md. This is the "status out" half; the
 * command receiver in DeviceServicesActivity is the "commands in" half.
 *
 * Every setter rewrites the file, so the PC always reads a consistent snapshot.
 * All access is synchronized; safe to call from any thread.
 */
object OtaStatusReporter {
    private const val TAG = "OTA_STATUS"
    private const val FILE_NAME = "ota_status.json"

    private val lock = Any()
    private var file: File? = null

    // --- current state (guarded by lock) ---
    private var phase: String = "IDLE"
    private var deviceMac: String? = null
    private var deviceName: String? = null
    private var bonded: Boolean = false
    private var product: String? = null
    private var pn: String? = null
    private var card: String? = null
    private var progress: Double = 0.0
    private var readModel: String? = null
    private var readAntenna: String? = null
    private var readPower: String? = null
    private var expModel: String? = null
    private var expAntenna: String? = null
    private var expPower: String? = null
    private var result: String? = null
    private var needsCacheClear: Boolean = false
    private var message: String = ""

    fun init(context: Context) {
        synchronized(lock) {
            val d = context.getExternalFilesDir(null) ?: run {
                Log.w(TAG, "No external files dir; status reporter disabled")
                return
            }
            file = File(d, FILE_NAME)
            write()
        }
    }

    /** Phase transition, e.g. SCANNING / PRE_OTA / UPLOADING / VERIFYING / DONE / ERROR. */
    fun setPhase(newPhase: String, message: String? = null) = update {
        phase = newPhase
        if (message != null) this@OtaStatusReporter.message = message
    }

    fun setDevice(mac: String?, name: String?, bonded: Boolean) = update {
        deviceMac = mac; deviceName = name; this@OtaStatusReporter.bonded = bonded
    }

    fun setSelection(product: String?, pn: String?, card: String?) = update {
        this@OtaStatusReporter.product = product
        this@OtaStatusReporter.pn = pn
        this@OtaStatusReporter.card = card
    }

    fun setExpected(model: String?, antenna: String?, power: String?) = update {
        expModel = model; expAntenna = antenna; expPower = power
    }

    fun setReads(model: String?, antenna: String?, power: String?) = update {
        readModel = model; readAntenna = antenna; readPower = power
    }

    fun setProgress(value: Double) = update { progress = value.coerceIn(0.0, 1.0) }

    /** true → new post-OTA characteristic not visible; PC should BT-toggle + RECONNECT. */
    fun setNeedsCacheClear(value: Boolean) = update { needsCacheClear = value }

    fun setResult(value: String?, message: String? = null) = update {
        result = value
        if (message != null) this@OtaStatusReporter.message = message
    }

    fun setMessage(value: String) = update { message = value }

    /** Force a rewrite (e.g. GET_STATUS command). File is always current, but this
     *  guarantees it exists and refreshes the timestamp. */
    fun touch() = update { }

    /** Reset per-session fields at the start of a new device/OTA session. */
    fun reset() = update {
        phase = "IDLE"; progress = 0.0
        readModel = null; readAntenna = null; readPower = null
        result = null; needsCacheClear = false; message = ""
    }

    private inline fun update(block: OtaStatusReporter.() -> Unit) {
        synchronized(lock) {
            block()
            write()
        }
    }

    private fun write() {
        val f = file ?: return
        try {
            val json = JSONObject()
            json.put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date()))
            json.put("phase", phase)
            json.put("device", JSONObject().apply {
                put("mac", deviceMac ?: JSONObject.NULL)
                put("name", deviceName ?: JSONObject.NULL)
                put("bonded", bonded)
            })
            json.put("selection", JSONObject().apply {
                put("product", product ?: JSONObject.NULL)
                put("pn", pn ?: JSONObject.NULL)
                put("card", card ?: JSONObject.NULL)
            })
            json.put("progress", progress)
            json.put("reads", JSONObject().apply {
                put("model", readModel ?: JSONObject.NULL)
                put("antenna", readAntenna ?: JSONObject.NULL)
                put("power", readPower ?: JSONObject.NULL)
            })
            json.put("expected", JSONObject().apply {
                put("model", expModel ?: JSONObject.NULL)
                put("antenna", expAntenna ?: JSONObject.NULL)
                put("power", expPower ?: JSONObject.NULL)
            })
            json.put("result", result ?: JSONObject.NULL)
            json.put("needs_cache_clear", needsCacheClear)
            json.put("message", message)
            // Write via temp + rename so the PC never reads a half-written file.
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "status write failed: ${e.message}")
        }
    }
}
