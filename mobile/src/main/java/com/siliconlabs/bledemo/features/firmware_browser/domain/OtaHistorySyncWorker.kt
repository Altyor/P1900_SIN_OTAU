package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.siliconlabs.bledemo.features.firmware_browser.data.OtaDatabaseClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Drains OtaHistoryStore's pending queue into the MySQL database. Runs only
 * with network, and WorkManager persists it across app restarts and reboots,
 * retrying with exponential backoff until every pending record is stored —
 * a record leaves the queue only after the database accepted it.
 */
class OtaHistorySyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Not configured yet: keep the queue; enqueue() at next app start
        // (after [MYSQL] is imported) picks it up.
        if (!DatabaseConfig.isConfigured()) return Result.success()

        var failed = false
        for (file in OtaHistoryStore.pendingFiles()) {
            val record = try {
                OtaHistoryRecord.fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                // Unparseable: park it rather than retrying forever. The
                // main history copy is untouched.
                Log.w(TAG, "Unreadable pending record ${file.name}, parking: ${e.message}")
                OtaHistoryStore.parkPending(file)
                continue
            }
            OtaDatabaseClient.pushOtaHistory(record)
                .onSuccess { file.delete() }
                .onFailure { failed = true }
            // One failure usually means the DB is unreachable — stop and back off.
            if (failed) break
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "OtaHistorySyncWorker"
        private const val WORK_NAME = "ota_history_sync"

        fun enqueue(context: Context) {
            // APPEND_OR_REPLACE: a record queued while a sync is already
            // running still gets its own pass afterwards (KEEP would drop it).
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, buildRequest())
        }

        /** Operator-requested retry: replaces a sync waiting out its backoff
         *  (which can grow to hours) so it runs as soon as there is network.
         *  Cancelling one mid-insert is harmless: INSERT IGNORE skips re-sends. */
        fun syncNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, buildRequest())
        }

        /** Emits whenever the sync job changes state, e.g. to refresh a
         *  pending-records warning once a pass has finished. */
        fun observe(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)

        private fun buildRequest() = OneTimeWorkRequestBuilder<OtaHistorySyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
