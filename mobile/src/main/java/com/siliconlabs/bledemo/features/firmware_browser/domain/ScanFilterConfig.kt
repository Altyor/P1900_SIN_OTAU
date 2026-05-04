package com.siliconlabs.bledemo.features.firmware_browser.domain

import com.siliconlabs.bledemo.bluetooth.beacon_utils.BleFormat
import com.siliconlabs.bledemo.utils.FilterDeviceParams

data class ScanFilterConfig(
    val name: String?,
    val rssiMin: Float,
    val rssiMax: Float,
    val bleFormats: List<BleFormat>,
    val onlyConnectable: Boolean,
    val onlyBonded: Boolean,
    val onlyFavourite: Boolean,
) {
    fun toFilterDeviceParams(): FilterDeviceParams = FilterDeviceParams(
        name = name,
        rssiValue = rssiMin to rssiMax,
        isRssiFlag = true,
        bleFormats = bleFormats,
        isOnlyFavourite = onlyFavourite,
        isOnlyConnectable = onlyConnectable,
        isOnlyBonded = onlyBonded,
    )

    companion object {
        val FACTORY = ScanFilterConfig(
            name = "SIN",
            rssiMin = -40f,
            rssiMax = 0f,
            bleFormats = emptyList(),
            onlyConnectable = false,
            onlyBonded = false,
            onlyFavourite = false,
        )
    }
}
