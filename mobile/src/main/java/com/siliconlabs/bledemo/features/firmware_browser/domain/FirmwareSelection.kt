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

    // Entered by the operator at the start of every firmware-selection flow
    // (see FirmwareBrowserViewModel.BatchEntry). Never remembered/pre-filled
    // across selections — must be typed fresh each time to avoid an old batch
    // being silently reused against a new one.
    var batchNumber: String = ""

    fun isSelected(): Boolean = productName.isNotEmpty() && fileName.isNotEmpty()

    fun clear() {
        productName = ""
        pnName = ""
        cardType = null
        fileName = ""
        secondFilePath = ""
        secondFileName = ""
        pendingSecondOta = false
        batchNumber = ""
    }
}
