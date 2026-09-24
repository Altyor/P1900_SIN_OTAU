package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.databinding.ItemOtaHistoryBinding
import com.siliconlabs.bledemo.features.firmware_browser.domain.OtaHistoryRecord
import com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtaHistoryAdapter : RecyclerView.Adapter<OtaHistoryAdapter.ViewHolder>() {

    private var items: List<OtaHistoryRecord> = emptyList()

    private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun submitList(newItems: List<OtaHistoryRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOtaHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemOtaHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: OtaHistoryRecord) {
            val context = binding.root.context
            binding.tvItemTimestamp.text = displayFormat.format(Date(record.timestampMillis))

            val product = if (record.pnName.isNotBlank()) {
                "${record.productName} — ${record.pnName}"
            } else {
                record.productName
            }
            binding.tvItemProduct.text = product.ifBlank { "—" }

            binding.tvItemBatch.text = String.format(UiStrings.otaHistoryItemBatch, record.batchNumber.ifBlank { "—" })

            binding.tvItemVersions.text = "Modèle : ${record.modelNumber ?: "—"} · " +
                "Antenne : ${record.firmwareVersionAntenna ?: "—"} · " +
                "Puissance : ${record.firmwareVersionPower ?: "—"} · " +
                "MAC : ${record.deviceMac ?: "—"}"

            val isPass = record.result == "PASS"
            binding.tvItemResult.text = if (isPass) UiStrings.otaHistoryResultPass else UiStrings.otaHistoryResultFail
            binding.tvItemResult.setTextColor(
                ContextCompat.getColor(context, if (isPass) R.color.silabs_green else R.color.silabs_red)
            )
            binding.ivItemResult.setImageResource(
                if (isPass) R.drawable.ic_circle_green else R.drawable.ic_circle_red
            )

            if (!isPass && !record.failureReason.isNullOrBlank()) {
                binding.tvItemFailureReason.visibility = View.VISIBLE
                binding.tvItemFailureReason.text = record.failureReason
            } else {
                binding.tvItemFailureReason.visibility = View.GONE
            }
        }
    }
}
