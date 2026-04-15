package com.siliconlabs.bledemo.features.firmware_browser.domain

data class FirmwareValidation(
    val modelNumbers: List<String>,
    val antennaVersion: String,
    val powerVersion: String
)
