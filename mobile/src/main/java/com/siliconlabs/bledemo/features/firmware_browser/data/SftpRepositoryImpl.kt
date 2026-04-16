package com.siliconlabs.bledemo.features.firmware_browser.data

import android.util.Log
import com.siliconlabs.bledemo.features.firmware_browser.domain.CardType
import com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareValidation
import com.siliconlabs.bledemo.features.firmware_browser.domain.PnInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.ProductInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.SftpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.Security

class SftpRepositoryImpl : SftpRepository {

    companion object {
        private const val TAG = "SftpRepository"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CONFIG_FILENAME = "config.ini"
        private const val PRODUCT_IMAGE_FILENAME = "product.png"
        private val FIRMWARE_EXTENSIONS = setOf("gbl", "zigbee")

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    private fun getOrCreateSftp(): SFTPClient {
        val existingSftp = sftpClient
        if (existingSftp != null && sshClient?.isConnected == true) {
            return existingSftp
        }
        // Clean up stale connection
        disconnect()

        val ssh = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = CONNECT_TIMEOUT_MS
            connect(SftpConfig.HOST, SftpConfig.PORT)

            val keyFile = File.createTempFile("sshkey", ".pem")
            try {
                keyFile.writeText(SftpConfig.PRIVATE_KEY)
                val keyProvider = loadKeys(keyFile.absolutePath, SftpConfig.KEY_PASSPHRASE)
                authPublickey(SftpConfig.USERNAME, keyProvider)
            } finally {
                keyFile.delete()
            }
        }
        sshClient = ssh
        val sftp = ssh.newSFTPClient()
        sftpClient = sftp
        return sftp
    }

    fun disconnect() {
        try { sftpClient?.close() } catch (_: Exception) {}
        try { sshClient?.disconnect() } catch (_: Exception) {}
        sftpClient = null
        sshClient = null
    }

    override suspend fun listProducts(cacheDir: File): Result<List<ProductInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val sftp = getOrCreateSftp()
            val productDirs = sftp.ls(SftpConfig.ROOT_DIR)
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name }

            productDirs.map { dir ->
                val imagePath = try {
                    val remotePath = "${SftpConfig.ROOT_DIR}/${dir.name}/$PRODUCT_IMAGE_FILENAME"
                    val localFile = File(cacheDir, "product_${dir.name}.png")
                    if (!localFile.exists() || localFile.length() == 0L) {
                        val remoteFile = sftp.open(remotePath)
                        val inputStream = remoteFile.RemoteFileInputStream()
                        localFile.outputStream().use { out ->
                            inputStream.copyTo(out)
                        }
                        remoteFile.close()
                        Log.d(TAG, "Downloaded image for ${dir.name}: ${localFile.length()} bytes")
                    }
                    localFile.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "No product image for ${dir.name}", e)
                    null
                }
                ProductInfo(name = dir.name, imagePath = imagePath)
            }
        }.onFailure {
            Log.e(TAG, "Failed to list products", it)
            disconnect()
        }
    }

    override suspend fun listPns(product: ProductInfo): Result<List<PnInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sftp = getOrCreateSftp()
                val path = "${SftpConfig.ROOT_DIR}/${product.name}"
                val dirs = sftp.ls(path)
                    .filter { it.isDirectory && !it.name.startsWith(".") }

                // If FW/ exists directly under product, no PN subfolders
                if (dirs.any { it.name.equals("FW", ignoreCase = true) }) {
                    Log.d(TAG, "Product ${product.name} has direct FW/ folder (no PN)")
                    listOf(PnInfo(""))
                } else {
                    dirs.map { PnInfo(it.name) }.sortedBy { it.name }
                }
            }.onFailure {
                Log.e(TAG, "Failed to list PNs for ${product.name}", it)
                disconnect()
            }
        }

    override suspend fun listAvailableCards(
        product: ProductInfo,
        pn: PnInfo
    ): Result<Pair<Boolean, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val sftp = getOrCreateSftp()
            val fwP = fwPath(product, pn)
            val dirs = sftp.ls(fwP)
                .filter { it.isDirectory }
                .map { it.name }
            val hasAntenna = dirs.any { it.equals("Antenna", ignoreCase = true) }
            val hasPower = dirs.any { it.equals("Power", ignoreCase = true) }
            Pair(hasAntenna, hasPower)
        }.onFailure {
            Log.e(TAG, "Failed to list available cards", it)
            disconnect()
        }
    }

    override suspend fun fetchValidation(
        product: ProductInfo,
        pn: PnInfo
    ): Result<FirmwareValidation> = withContext(Dispatchers.IO) {
        runCatching {
            val sftp = getOrCreateSftp()
            val configPath = "${fwPath(product, pn)}/$CONFIG_FILENAME"
            val inputStream = sftp.open(configPath).RemoteFileInputStream()
            val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
            parseConfigIni(lines)
        }.onFailure {
            Log.e(TAG, "Failed to fetch validation config", it)
            disconnect()
        }
    }

    override suspend fun downloadFirmware(
        product: ProductInfo,
        pn: PnInfo,
        cardType: CardType,
        cacheDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val sftp = getOrCreateSftp()
            val fwDir = "${fwPath(product, pn)}/${cardType.dirName}"
            val firmwareFile = sftp.ls(fwDir)
                .filter { !it.isDirectory }
                .firstOrNull { entry ->
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    ext in FIRMWARE_EXTENSIONS
                }
                ?: throw IllegalStateException(
                    "No firmware file (.gbl/.zigbee) found in $fwDir"
                )

            val localFile = File(cacheDir, firmwareFile.name)
            sftp.get(firmwareFile.path, localFile.absolutePath)
            Log.d(TAG, "Downloaded ${firmwareFile.name} to ${localFile.absolutePath}")
            localFile
        }.onFailure {
            Log.e(TAG, "Failed to download firmware", it)
            disconnect()
        }
    }

    /** Builds the FW path: with PN → {root}/{product}/{pn}/FW, without PN → {root}/{product}/FW */
    private fun fwPath(product: ProductInfo, pn: PnInfo): String {
        return if (pn.isDirect) {
            "${SftpConfig.ROOT_DIR}/${product.name}/FW"
        } else {
            "${SftpConfig.ROOT_DIR}/${product.name}/${pn.name}/FW"
        }
    }

    private fun parseConfigIni(lines: List<String>): FirmwareValidation {
        val props = mutableMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("#")) continue
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                props[key] = value
            }
        }
        return FirmwareValidation(
            preModel = props["pre_model"] ?: "",
            postModel = props["post_model"] ?: "",
            antennaVersion = props["antenna_version"] ?: "",
            powerVersion = props["power_version"] ?: ""
        )
    }
}
