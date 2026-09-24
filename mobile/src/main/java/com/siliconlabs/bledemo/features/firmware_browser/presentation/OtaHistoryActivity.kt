package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.siliconlabs.bledemo.base.activities.BaseActivity
import com.siliconlabs.bledemo.databinding.ActivityOtaHistoryBinding
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryRecord
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryStore
import com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
import com.siliconlabs.bledemo.utils.CustomToastManager

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
