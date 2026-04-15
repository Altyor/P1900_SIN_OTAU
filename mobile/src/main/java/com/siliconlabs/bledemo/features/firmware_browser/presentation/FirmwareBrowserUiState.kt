package com.siliconlabs.bledemo.features.firmware_browser.presentation

import com.siliconlabs.bledemo.features.firmware_browser.domain.CardType
import com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareValidation
import com.siliconlabs.bledemo.features.firmware_browser.domain.PnInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.ProductInfo

sealed class FirmwareBrowserUiState {
    data object Loading : FirmwareBrowserUiState()
    data class ProductList(val products: List<ProductInfo>) : FirmwareBrowserUiState()
    data class PnSelection(
        val product: ProductInfo,
        val pns: List<PnInfo>
    ) : FirmwareBrowserUiState()
    data class CardSelection(
        val product: ProductInfo,
        val pn: PnInfo,
        val hasAntenna: Boolean = true,
        val hasPower: Boolean = true
    ) : FirmwareBrowserUiState()
    data class Downloading(val fileName: String) : FirmwareBrowserUiState()
    data class Ready(
        val filePath: String,
        val fileName: String,
        val validation: FirmwareValidation
    ) : FirmwareBrowserUiState()
    data class Error(val message: String) : FirmwareBrowserUiState()
}
