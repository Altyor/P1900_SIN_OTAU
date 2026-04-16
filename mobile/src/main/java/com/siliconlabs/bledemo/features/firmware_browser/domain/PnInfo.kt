package com.siliconlabs.bledemo.features.firmware_browser.domain

data class PnInfo(val name: String) {
    /** True when product has no PN subfolders — FW/ is directly under product */
    val isDirect: Boolean get() = name.isEmpty()
}
