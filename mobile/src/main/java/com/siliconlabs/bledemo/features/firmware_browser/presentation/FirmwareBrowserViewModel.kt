package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.siliconlabs.bledemo.features.firmware_browser.data.SftpRepository
import com.siliconlabs.bledemo.features.firmware_browser.domain.CardType
import com.siliconlabs.bledemo.features.firmware_browser.domain.PnInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.ProductInfo
import com.siliconlabs.bledemo.features.scan.browser.activities.DeviceServicesActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FirmwareBrowserViewModel @Inject constructor(
    private val app: Application,
    private val sftpRepository: SftpRepository
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<FirmwareBrowserUiState>(FirmwareBrowserUiState.Loading)
    val uiState: StateFlow<FirmwareBrowserUiState> = _uiState

    private var selectedProduct: ProductInfo? = null
    private var selectedPn: PnInfo? = null
    private var lastPnList: List<PnInfo>? = null

    fun loadProducts() {
        _uiState.value = FirmwareBrowserUiState.Loading
        viewModelScope.launch {
            sftpRepository.listProducts(app.cacheDir)
                .onSuccess { products ->
                    if (products.isEmpty()) {
                        _uiState.value = FirmwareBrowserUiState.Error(
                            "No products found on the firmware server."
                        )
                    } else {
                        _uiState.value = FirmwareBrowserUiState.ProductList(products)
                    }
                }
                .onFailure { e ->
                    _uiState.value = FirmwareBrowserUiState.Error(
                        "Failed to connect to firmware server: ${e.message}"
                    )
                }
        }
    }

    fun selectProduct(product: ProductInfo) {
        selectedProduct = product
        _uiState.value = FirmwareBrowserUiState.Loading
        viewModelScope.launch {
            sftpRepository.listPns(product)
                .onSuccess { pns ->
                    lastPnList = pns
                    when {
                        pns.isEmpty() -> _uiState.value = FirmwareBrowserUiState.Error(
                            "No part numbers found for ${product.name}."
                        )
                        pns.size == 1 -> {
                            selectedPn = pns.first()
                            _uiState.value = FirmwareBrowserUiState.CardSelection(
                                product = product,
                                pn = pns.first()
                            )
                        }
                        else -> _uiState.value = FirmwareBrowserUiState.PnSelection(
                            product = product,
                            pns = pns
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.value = FirmwareBrowserUiState.Error(
                        "Failed to list part numbers: ${e.message}"
                    )
                }
        }
    }

    fun selectPn(pn: PnInfo) {
        val product = selectedProduct ?: return
        selectedPn = pn
        _uiState.value = FirmwareBrowserUiState.CardSelection(product = product, pn = pn)
    }

    fun selectCard(cardType: CardType) {
        val product = selectedProduct ?: return
        val pn = selectedPn ?: return

        _uiState.value = FirmwareBrowserUiState.Downloading("Loading firmware...")
        viewModelScope.launch {
            // Fetch validation config
            val validationResult = sftpRepository.fetchValidation(product, pn)
            val validation = validationResult.getOrElse { e ->
                _uiState.value = FirmwareBrowserUiState.Error(
                    "Failed to read config: ${e.message}"
                )
                return@launch
            }

            // Download firmware file
            sftpRepository.downloadFirmware(product, pn, cardType, app.cacheDir)
                .onSuccess { file ->
                    // Set global OTA file for DeviceServicesActivity
                    DeviceServicesActivity.globalOtaFilePath = file.absolutePath
                    DeviceServicesActivity.globalOtaFileName = file.name
                    DeviceServicesActivity.selectedValidation = validation

                    _uiState.value = FirmwareBrowserUiState.Ready(
                        filePath = file.absolutePath,
                        fileName = file.name,
                        validation = validation
                    )
                }
                .onFailure { e ->
                    _uiState.value = FirmwareBrowserUiState.Error(
                        "Failed to download firmware: ${e.message}"
                    )
                }
        }
    }

    fun retry() {
        loadProducts()
    }

    fun goBack() {
        val currentState = _uiState.value
        when (currentState) {
            is FirmwareBrowserUiState.PnSelection -> loadProducts()
            is FirmwareBrowserUiState.CardSelection -> {
                // If there was a PN selection step, go back to it; otherwise go to products
                val product = selectedProduct
                val lastPns = lastPnList
                if (product != null && lastPns != null && lastPns.size > 1) {
                    _uiState.value = FirmwareBrowserUiState.PnSelection(product, lastPns)
                } else {
                    loadProducts()
                }
            }
            is FirmwareBrowserUiState.Error -> loadProducts()
            else -> { /* no-op for Loading, Downloading, Ready, ProductList */ }
        }
    }
}
