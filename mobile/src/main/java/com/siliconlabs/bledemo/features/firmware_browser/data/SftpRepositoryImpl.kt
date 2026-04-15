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
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.security.Security

class SftpRepositoryImpl : SftpRepository {

    companion object {
        private const val TAG = "SftpRepository"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CONFIG_FILENAME = "config.ini"
        private val FIRMWARE_EXTENSIONS = setOf("gbl", "zigbee")

        init {
            // Android's built-in BouncyCastle provider is stripped and conflicts with sshj.
            // Remove it and register the full one bundled by sshj.
            Security.removeProvider("BC")
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }

    private fun createClient(): SSHClient {
        return SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = CONNECT_TIMEOUT_MS
            connect(SftpConfig.HOST, SftpConfig.PORT)

            // Write key to temp file — sshj handles file-based keys more reliably
            val keyFile = java.io.File.createTempFile("sshkey", ".pem")
            try {
                keyFile.writeText(SftpConfig.PRIVATE_KEY)
                val keyProvider = loadKeys(
                    keyFile.absolutePath,
                    SftpConfig.KEY_PASSPHRASE
                )
                authPublickey(SftpConfig.USERNAME, keyProvider)
            } finally {
                keyFile.delete()
            }
        }
    }

    private inline fun <T> withSftp(block: (SFTPClient) -> T): T {
        val ssh = createClient()
        try {
            val sftp = ssh.newSFTPClient()
            try {
                return block(sftp)
            } finally {
                sftp.close()
            }
        } finally {
            ssh.disconnect()
        }
    }

    override suspend fun listProducts(): Result<List<ProductInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp { sftp ->
                sftp.ls(SftpConfig.ROOT_DIR)
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .map { ProductInfo(it.name) }
                    .sortedBy { it.name }
            }
        }.onFailure { Log.e(TAG, "Failed to list products", it) }
    }

    override suspend fun listPns(product: ProductInfo): Result<List<PnInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = "${SftpConfig.ROOT_DIR}/${product.name}"
                withSftp { sftp ->
                    sftp.ls(path)
                        .filter { it.isDirectory && !it.name.startsWith(".") }
                        .map { PnInfo(it.name) }
                        .sortedBy { it.name }
                }
            }.onFailure { Log.e(TAG, "Failed to list PNs for ${product.name}", it) }
        }

    override suspend fun fetchValidation(
        product: ProductInfo,
        pn: PnInfo
    ): Result<FirmwareValidation> = withContext(Dispatchers.IO) {
        runCatching {
            val configPath = "${SftpConfig.ROOT_DIR}/${product.name}/${pn.name}/FW/$CONFIG_FILENAME"
            withSftp { sftp ->
                val inputStream = sftp.open(configPath).RemoteFileInputStream()
                val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
                parseConfigIni(lines)
            }
        }.onFailure { Log.e(TAG, "Failed to fetch validation config", it) }
    }

    override suspend fun downloadFirmware(
        product: ProductInfo,
        pn: PnInfo,
        cardType: CardType,
        cacheDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val fwDir = "${SftpConfig.ROOT_DIR}/${product.name}/${pn.name}/FW/${cardType.dirName}"
            withSftp { sftp ->
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
            }
        }.onFailure { Log.e(TAG, "Failed to download firmware", it) }
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
            modelNumbers = props["model_numbers"]?.split(",")?.map { it.trim() } ?: emptyList(),
            antennaVersion = props["antenna_version"] ?: "",
            powerVersion = props["power_version"] ?: ""
        )
    }
}
