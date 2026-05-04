package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mirrors `OTA_DEBUG` and `CHAR_DUMP` Android log lines to a persistent file in
 * the app's external files directory so production failures can be inspected
 * after the fact (Android's logcat ring buffer only keeps a few minutes).
 *
 * Reads its own process's logcat (no extra permission needed). Lifecycle:
 *
 *  - `start()`           — kicks off a daemon thread that streams the app's
 *                          OTA_DEBUG/CHAR_DUMP logcat into `ota_log_current.txt`.
 *  - `markOtaStarted()`  — call when an OTA begins. Truncates the current file
 *                          so each session starts clean.
 *  - `markOtaSuccess()`  — call when the OTA is verified successful. Deletes
 *                          the current session log (we don't keep passing
 *                          runs).
 *  - `markOtaFailed(r)`  — call when an OTA is known to have failed. Renames
 *                          the current session log to
 *                          `ota_log_failed_<yyyyMMdd_HHmmss>.txt` so it's
 *                          preserved across subsequent attempts.
 *
 * Failed-run files accumulate; sysadmin / dev pulls them off the tablet for
 * post-mortem.
 *
 * Files at:
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_log_current.txt
 *   /sdcard/Android/data/com.siliconlabs.bledemo/files/ota_log_failed_*.txt
 */
object OtaFileLogger {
    private const val TAG = "OtaFileLogger"
    private const val CURRENT_NAME = "ota_log_current.txt"
    private const val FAILED_PREFIX = "ota_log_failed_"
    private const val MAX_BYTES = 2_000_000L  // hard cap per session
    private const val MAX_FAILED_FILES = 20    // keep only the most recent N

    private val lock = Any()
    private var dir: File? = null
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var thread: Thread? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (thread?.isAlive == true) return
            val logDir = context.getExternalFilesDir(null) ?: run {
                Log.w(TAG, "No external files dir; cannot start OTA file logger")
                return
            }
            dir = logDir
            currentFile = File(logDir, CURRENT_NAME)
            writer = openWriter(currentFile!!)
            writeBanner("OTA logger started, pid=${Process.myPid()}")
        }

        thread = Thread {
            try {
                val pid = Process.myPid()
                val cmd = arrayOf(
                    "logcat",
                    "--pid=$pid",
                    "-v", "threadtime",
                    "OTA_DEBUG:V", "CHAR_DUMP:V", "*:S"
                )
                val proc = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lock) {
                        val w = writer ?: return@synchronized
                        val cf = currentFile ?: return@synchronized
                        try {
                            if (cf.length() > MAX_BYTES) {
                                // Hard cap: snapshot to a failed-style name and start fresh,
                                // since something is firehosing logs without an outcome.
                                w.flush(); w.close()
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val capped = File(cf.parentFile, "${FAILED_PREFIX}capped_$ts.txt")
                                cf.renameTo(capped)
                                writer = openWriter(cf)
                            }
                            writer!!.write(line)
                            writer!!.write("\n")
                            writer!!.flush()
                        } catch (e: Exception) {
                            Log.w(TAG, "log write failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTA file logger thread crashed", e)
            }
        }.apply {
            isDaemon = true
            name = "OtaFileLogger"
            start()
        }
        Log.d(TAG, "OTA file logger started → ${currentFile?.absolutePath}")
    }

    /** OTA started — clear the current session file. */
    fun markOtaStarted() {
        synchronized(lock) {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            currentFile?.delete()
            currentFile?.let { writer = openWriter(it) }
            writeBanner("OTA STARTED")
        }
    }

    /** OTA succeeded — discard the session file. */
    fun markOtaSuccess() {
        synchronized(lock) {
            writeBanner("OTA SUCCESS — discarding log")
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            val cf = currentFile ?: return
            cf.delete()
            writer = openWriter(cf)
        }
    }

    /**
     * OTA failed — preserve the session file with a timestamped, MAC-tagged
     * name. Caller passes the device address (typically `bluetoothGatt?.device?.address`);
     * pass null/blank if unknown. After writing, prune oldest failed logs so
     * we don't accumulate indefinitely on the tablet.
     */
    fun markOtaFailed(reason: String, deviceAddress: String? = null) {
        synchronized(lock) {
            writeBanner("OTA FAILED — $reason (device=${deviceAddress ?: "?"})")
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            val cf = currentFile ?: return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val macTag = sanitizeMac(deviceAddress)
            val target = File(cf.parentFile, "$FAILED_PREFIX${ts}_$macTag.txt")
            if (cf.exists() && cf.length() > 0) {
                if (!cf.renameTo(target)) {
                    // Rename failed (e.g. cross-volume) — fall back to copy.
                    try {
                        cf.inputStream().use { input ->
                            FileOutputStream(target).use { out -> input.copyTo(out) }
                        }
                        cf.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not preserve failed log: ${e.message}")
                    }
                }
                Log.d(TAG, "Saved failure log → ${target.absolutePath}")
                pruneOldestFailedLogs(cf.parentFile)
            }
            writer = openWriter(cf)
        }
    }

    /**
     * Strip colons, lowercase, replace blanks with "unknown" so the MAC fits
     * cleanly in a filename across all platforms.
     */
    private fun sanitizeMac(addr: String?): String {
        if (addr.isNullOrBlank()) return "unknown"
        return addr.replace(":", "").lowercase(Locale.US)
    }

    private fun pruneOldestFailedLogs(dir: File?) {
        if (dir == null) return
        val failed = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FAILED_PREFIX)
        } ?: return
        if (failed.size <= MAX_FAILED_FILES) return
        // Sort newest first by mtime, then delete the tail past MAX.
        val sorted = failed.sortedByDescending { it.lastModified() }
        for (oldFile in sorted.drop(MAX_FAILED_FILES)) {
            try {
                if (oldFile.delete()) {
                    Log.d(TAG, "Pruned old failed log: ${oldFile.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prune ${oldFile.name}: ${e.message}")
            }
        }
    }

    private fun openWriter(file: File): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, /* append = */ true)))

    private fun writeBanner(text: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            writer?.let {
                it.write("\n==== [$ts] $text ====\n")
                it.flush()
            }
        } catch (_: Exception) {}
    }
}
