package com.siliconlabs.bledemo.features.firmware_browser.data

import com.siliconlabs.bledemo.features.firmware_browser.domain.CardType
import com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareValidation
import com.siliconlabs.bledemo.features.firmware_browser.domain.PnInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.ProductInfo
import java.io.File

interface SftpRepository {
    suspend fun listProducts(cacheDir: File): Result<List<ProductInfo>>
    suspend fun listPns(product: ProductInfo): Result<List<PnInfo>>
    suspend fun fetchValidation(product: ProductInfo, pn: PnInfo): Result<FirmwareValidation>
    suspend fun downloadFirmware(
        product: ProductInfo,
        pn: PnInfo,
        cardType: CardType,
        cacheDir: File
    ): Result<File>
}
