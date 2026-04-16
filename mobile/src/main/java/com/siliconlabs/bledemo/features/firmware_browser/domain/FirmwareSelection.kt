package com.siliconlabs.bledemo.features.firmware_browser.domain

object FirmwareSelection {
    var productName: String = ""
    var pnName: String = ""
    var cardType: CardType? = null
    var fileName: String = ""

    // For BOTH mode: second firmware file (Power) to flash after Antenna
    var secondFilePath: String = ""
    var secondFileName: String = ""
    var pendingSecondOta: Boolean = false

    fun isSelected(): Boolean = productName.isNotEmpty() && fileName.isNotEmpty()

    fun clear() {
        productName = ""
        pnName = ""
        cardType = null
        fileName = ""
        secondFilePath = ""
        secondFileName = ""
        pendingSecondOta = false
    }
}
