package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.content.Context
import android.util.Log
import com.opencsv.CSVWriter
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local, on-device record of every completed OTA attempt (pass or fail).
 *
 * One JSON file per attempt (not a single appended/shared file) so multiple
 * devices/sessions never race on a shared file. Central sync goes to the
 * MySQL database (see DatabaseConfig): each record is also queued in
 * `pending/` and OtaHistorySyncWorker drains that queue in the background,
 * retrying until the database accepts it — never blocking or failing the OTA
 * flow, and never losing a record to a Wi-Fi drop. Records queued before the
 * [MYSQL] credentials are imported are sent once they are. The main files
 * stay on the device regardless (history screen, CSV export).
 *
 * Files at:
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_history/ (one JSON file per record)
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_history/pending/ (not yet in the database)
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_history/pending/unreadable/ (parked, needs a look)
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_history/export/ (CSV exports)
 */
object OtaHistoryStore {
    private const val TAG = "OtaHistoryStore"
    private const val DIR_NAME = "ota_history"
    private const val EXPORT_DIR_NAME = "export"
    private const val PENDING_DIR_NAME = "pending"
    private const val UNREADABLE_DIR_NAME = "unreadable"

    private val lock = Any()
    private var dir: File? = null
    private var pendingDir: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        synchronized(lock) {
            val base = context.getExternalFilesDir(null) ?: run {
                Log.w(TAG, "No external files dir; OTA history disabled")
                return
            }
            dir = File(base, DIR_NAME).apply { mkdirs() }
            pendingDir = File(dir, PENDING_DIR_NAME).apply { mkdirs() }
            appContext = context.applicationContext
        }
        // Flush anything left from a previous run (offline, killed app, or
        // records made before the [MYSQL] credentials were imported).
        if (pendingFiles().isNotEmpty()) OtaHistorySyncWorker.enqueue(context.applicationContext)
    }

    fun record(r: OtaHistoryRecord) {
        val d = dir ?: run {
            Log.w(TAG, "record() called before init()")
            return
        }
        try {
            val macTag = sanitizeMac(r.deviceMac)
            val name = "${r.timestampMillis}_${macTag}_${r.result}.json"
            val json = r.toJson().toString()
            File(d, name).writeText(json)
            pendingDir?.let { enqueueForSync(File(it, name), json) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write OTA history record: ${e.message}")
        }
    }

    /** Write-then-rename so the worker never reads a half-written file. */
    private fun enqueueForSync(target: File, json: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            Log.w(TAG, "Could not queue ${target.name} for database sync")
            return
        }
        appContext?.let { OtaHistorySyncWorker.enqueue(it) }
    }

    /** Records not yet accepted by the database, oldest first. */
    fun pendingFiles(): List<File> =
        pendingDir?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Moves an unparseable pending file aside so it can't block the queue. */
    fun parkPending(file: File) {
        val parked = File(file.parentFile, UNREADABLE_DIR_NAME).apply { mkdirs() }
        if (!file.renameTo(File(parked, file.name))) file.delete()
    }

    fun loadAll(): List<OtaHistoryRecord> {
        val d = dir ?: return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                OtaHistoryRecord.fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable history file ${f.name}: ${e.message}")
                null
            }
        }.sortedByDescending { it.timestampMillis }
    }

    fun exportCsv(records: List<OtaHistoryRecord>): File? {
        val d = dir ?: run {
            Log.w(TAG, "exportCsv() called before init()")
            return null
        }
        return try {
            val exportDir = File(d, EXPORT_DIR_NAME).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(exportDir, "ota_history_$ts.csv")
            val csvTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            CSVWriter(FileWriter(file)).use { writer ->
                writer.writeNext(arrayOf(
                    "Début OTA", "Fin OTA", "N° de production", "Adresse MAC", "Produit", "Référence", "Carte",
                    "Modèle", "Version Antenne", "Version Puissance",
                    "Modèle attendu", "Antenne attendue", "Puissance attendue",
                    "Résultat", "Motif d'échec", "Tablette", "Version app"
                ))
                records.forEach { r ->
                    writer.writeNext(arrayOf(
                        r.otaStartMillis?.let { csvTime.format(Date(it)) } ?: "",
                        csvTime.format(Date(r.timestampMillis)),
                        r.batchNumber,
                        r.deviceMac ?: "",
                        r.productName,
                        r.pnName,
                        r.cardType ?: "",
                        r.modelNumber ?: "",
                        r.firmwareVersionAntenna ?: "",
                        r.firmwareVersionPower ?: "",
                        r.expectedModel ?: "",
                        r.expectedAntenna ?: "",
                        r.expectedPower ?: "",
                        r.result,
                        r.failureReason ?: "",
                        r.tabletId ?: "",
                        r.appVersion ?: ""
                    ))
                }
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export OTA history CSV: ${e.message}")
            null
        }
    }

    /** Strip colons, lowercase, replace blanks with "unknown" so the MAC fits
     *  cleanly in a filename across all platforms. Mirrors OtaFileLogger. */
    private fun sanitizeMac(addr: String?): String {
        if (addr.isNullOrBlank()) return "unknown"
        return addr.replace(":", "").lowercase(Locale.US)
    }
}
