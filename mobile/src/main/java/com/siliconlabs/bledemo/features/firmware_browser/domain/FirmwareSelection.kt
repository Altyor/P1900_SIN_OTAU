package com.siliconlabs.bledemo.features.firmware_browser.domain

object FirmwareSelection {
    var productName: String = ""
    var pnName: String = ""
    var cardType: CardType? = null
    var fileName: String = ""

    fun isSelected(): Boolean = productName.isNotEmpty() && fileName.isNotEmpty()

    fun clear() {
        productName = ""
        pnName = ""
        cardType = null
        fileName = ""
    }
}
