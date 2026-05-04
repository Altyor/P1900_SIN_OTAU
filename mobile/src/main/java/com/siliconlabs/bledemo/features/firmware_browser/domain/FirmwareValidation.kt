package com.siliconlabs.bledemo.features.firmware_browser.domain

data class FirmwareValidation(
    val preModel: String,
    val postModel: String,
    val antennaVersion: String,
    val powerVersion: String,
    val afterAntenna: Set<String>? = null,
    val afterPower: Set<String>? = null,
    val afterBoth: Set<String>? = null,
    val scanFilter: ScanFilterConfig? = null,
) {
    fun fieldsToCheck(cardType: CardType?): Set<String> = when (cardType) {
        CardType.ANTENNA -> afterAntenna ?: ALL_FIELDS
        CardType.POWER   -> afterPower   ?: ALL_FIELDS
        CardType.BOTH    -> afterBoth    ?: ALL_FIELDS
        null             -> emptySet()
    }

    companion object {
        val ALL_FIELDS = setOf("post_model", "antenna_version", "power_version")
    }
}
