package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.siliconlabs.bledemo.base.activities.BaseActivity
import com.siliconlabs.bledemo.databinding.ActivityOtaHistoryBinding
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryRecord
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryStore
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistorySyncWorker
import com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
import com.siliconlabs.bledemo.utils.CustomToastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtaHistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityOtaHistoryBinding
    private lateinit var adapter: OtaHistoryAdapter

    private var allRecords: List<OtaHistoryRecord> = emptyList()
    private var visibleRecords: List<OtaHistoryRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtaHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = UiStrings.otaHistoryTitle
        binding.toolbar.subtitle = String.format(
            UiStrings.otaHistoryTabletId,
            com.siliconlabs.bledemo.features.firmware_browser.domain.TabletId.get(this) ?: "?"
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etFilterBatch.hint = UiStrings.otaHistoryFilterHint
        binding.btnExportCsv.text = UiStrings.otaHistoryExportCsv

        adapter = OtaHistoryAdapter()
        binding.rvOtaHistory.layoutManager = LinearLayoutManager(this)
        binding.rvOtaHistory.adapter = adapter

        allRecords = OtaHistoryStore.loadAll()
        applyFilter("")

        binding.etFilterBatch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        binding.btnRetrySync.text = UiStrings.otaHistoryRetrySync
        binding.btnRetrySync.setOnClickListener {
            OtaHistorySyncWorker.syncNow(this)
            CustomToastManager.showSuccess(this, UiStrings.otaHistoryRetryStarted)
        }
        // Re-count after every sync pass so the warning clears on its own.
        OtaHistorySyncWorker.observe(this).observe(this) { refreshPendingWarning() }

        binding.btnExportCsv.setOnClickListener {
            val file = OtaHistoryStore.exportCsv(visibleRecords)
            if (file != null) {
                CustomToastManager.showSuccess(
                    this, String.format(UiStrings.otaHistoryExportSuccess, file.absolutePath)
                )
            } else {
                CustomToastManager.showError(this, UiStrings.otaHistoryExportFailed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPendingWarning()
    }

    /** Records still only on this tablet (not yet in the database). */
    private fun refreshPendingWarning() {
        val pending = OtaHistoryStore.pendingFiles().size
        val unreadable = OtaHistoryStore.unreadableCount()
        val lines = mutableListOf<String>()
        if (pending > 0) {
            val oldest = OtaHistoryStore.oldestPendingMillis()?.let {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(it))
            } ?: "?"
            lines += String.format(UiStrings.otaHistoryPendingWarning, pending, oldest)
        }
        if (unreadable > 0) {
            lines += String.format(UiStrings.otaHistoryUnreadableWarning, unreadable)
        }
        binding.layoutPendingWarning.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.tvPendingWarning.text = lines.joinToString("\n\n")
        // Retrying only helps the queue; parked files need a person.
        binding.btnRetrySync.visibility = if (pending > 0) View.VISIBLE else View.GONE
    }

    private fun applyFilter(query: String) {
        visibleRecords = if (query.isBlank()) {
            allRecords
        } else {
            allRecords.filter { it.batchNumber.contains(query, ignoreCase = true) }
        }
        adapter.submitList(visibleRecords)

        if (visibleRecords.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = if (allRecords.isEmpty()) {
                UiStrings.otaHistoryEmpty
            } else {
                UiStrings.otaHistoryNoMatch
            }
        } else {
            binding.tvEmptyState.visibility = View.GONE
        }
    }
}
