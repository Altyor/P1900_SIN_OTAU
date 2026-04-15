package com.siliconlabs.bledemo.features.firmware_browser.domain

data class FirmwareValidation(
    val preModel: String,
    val postModel: String,
    val antennaVersion: String,
    val powerVersion: String
)
