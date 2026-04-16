package com.siliconlabs.bledemo.features.firmware_browser.domain

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets manager for Android — equivalent to TB45's secret_loader.py
 *
 * Flow:
 * 1. Place Secret_OTAU.ini on tablet storage (e.g., /sdcard/Secret_OTAU.ini)
 * 2. App detects it on launch, imports credentials
 * 3. Encrypts with Android Keystore (hardware-backed)
 * 4. Stores encrypted data in app-private storage
 * 5. Securely deletes the plaintext INI
 *
 * INI format:
 * [SFTP]
 * host=sftp.altyor.solutions
 * port=22
 * username=P1900-production-manager
 * private_key=-----BEGIN OPENSSH PRIVATE KEY-----
 * ...base64...
 * -----END OPENSSH PRIVATE KEY-----
 * key_passphrase=your_passphrase
 * root_dir=/production
 */
object SecretsManager {

    private const val TAG = "SecretsManager"
    private const val KEYSTORE_ALIAS = "OTAU_SECRETS_KEY"
    private const val ENCRYPTED_FILE = "secrets.enc"
    private const val INI_FILENAME = "Secret_OTAU.ini"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private var secrets: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    /**
     * Load secrets. Checks for INI file first (imports + deletes), then loads encrypted store.
     */
    fun load(context: Context): Boolean {
        // Check for INI file on external storage
        val iniFile = findIniFile(context)
        if (iniFile != null) {
            Log.d(TAG, "Found secrets INI at: ${iniFile.absolutePath}")
            importIniFile(iniFile, context)
        }

        // Load from encrypted storage
        val encFile = File(context.filesDir, ENCRYPTED_FILE)
        if (encFile.exists()) {
            try {
                val encrypted = encFile.readBytes()
                val decrypted = decrypt(encrypted)
                secrets = parseJson(String(decrypted))
                loaded = true
                Log.d(TAG, "Loaded ${secrets.size} secret sections from encrypted store")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load encrypted secrets", e)
                loaded = false
            }
        }
        return loaded
    }

    fun get(section: String, key: String, default: String? = null): String? {
        return secrets[section]?.get(key) ?: default
    }

    /**
     * Check if SFTP credentials are available
     */
    fun hasSftpCredentials(): Boolean {
        return loaded && secrets.containsKey("SFTP") &&
            get("SFTP", "host") != null &&
            get("SFTP", "username") != null &&
            get("SFTP", "private_key") != null
    }

    private fun findIniFile(context: Context): File? {
        // App-specific external storage first (no permissions needed on any Android version)
        // Operators drop the file at: /sdcard/Android/data/com.siliconlabs.bledemo/files/
        val locations = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let {
            locations.add(File(it, INI_FILENAME))
        }
        // Legacy paths (work on older Android or with MANAGE_EXTERNAL_STORAGE)
        locations.add(File(Environment.getExternalStorageDirectory(), INI_FILENAME))
        locations.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), INI_FILENAME))

        for (f in locations) {
            Log.d(TAG, "Checking: ${f.absolutePath} exists=${f.exists()} canRead=${f.canRead()}")
        }
        return locations.firstOrNull { it.exists() && it.canRead() }
    }

    private fun importIniFile(iniFile: File, context: Context) {
        try {
            val lines = iniFile.readLines()
            val parsed = parseIni(lines)

            // Merge with existing secrets
            for ((section, entries) in parsed) {
                secrets.getOrPut(section) { mutableMapOf() }.putAll(entries)
            }

            // Encrypt and save
            val json = toJson(secrets)
            val encrypted = encrypt(json.toByteArray())
            File(context.filesDir, ENCRYPTED_FILE).writeBytes(encrypted)

            // Securely delete INI file
            secureDelete(iniFile)
            Log.d(TAG, "Imported and encrypted ${parsed.size} sections, INI deleted")
            loaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import INI file", e)
        }
    }

    private fun parseIni(lines: List<String>): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = ""
        var multiLineKey = ""
        var multiLineValue = StringBuilder()
        var inMultiLine = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inMultiLine) {
                    result.getOrPut(currentSection) { mutableMapOf() }[multiLineKey] =
                        multiLineValue.toString().trim()
                    inMultiLine = false
                }
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                continue
            }

            if (inMultiLine) {
                if (trimmed.contains("-----END")) {
                    multiLineValue.appendLine(line)
                    result.getOrPut(currentSection) { mutableMapOf() }[multiLineKey] =
                        multiLineValue.toString().trim()
                    inMultiLine = false
                } else {
                    multiLineValue.appendLine(line)
                }
                continue
            }

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                if (value.contains("-----BEGIN")) {
                    multiLineKey = key
                    multiLineValue = StringBuilder(value).appendLine()
                    inMultiLine = true
                } else {
                    result.getOrPut(currentSection) { mutableMapOf() }[key] =
                        value.removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }

        if (inMultiLine) {
            result.getOrPut(currentSection) { mutableMapOf() }[multiLineKey] =
                multiLineValue.toString().trim()
        }

        return result
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        // Prepend IV to encrypted data
        return iv + encrypted
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val iv = data.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = data.sliceArray(GCM_IV_LENGTH until data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }

    private fun secureDelete(file: File) {
        try {
            val length = file.length()
            file.outputStream().use { stream ->
                val zeros = ByteArray(4096)
                var remaining = length
                while (remaining > 0) {
                    val toWrite = minOf(remaining, zeros.size.toLong()).toInt()
                    stream.write(zeros, 0, toWrite)
                    remaining -= toWrite
                }
                stream.flush()
            }
            file.delete()
            Log.d(TAG, "Securely deleted ${file.name}")
        } catch (e: Exception) {
            // Best effort — at least try regular delete
            file.delete()
            Log.w(TAG, "Could not securely delete, used regular delete: ${e.message}")
        }
    }

    // Simple JSON serialization (no external dependency)
    private fun toJson(map: Map<String, Map<String, String>>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((section, entries) in map) {
            if (!first) sb.append(",")
            sb.append("\"${escapeJson(section)}\":{")
            var firstEntry = true
            for ((key, value) in entries) {
                if (!firstEntry) sb.append(",")
                sb.append("\"${escapeJson(key)}\":\"${escapeJson(value)}\"")
                firstEntry = false
            }
            sb.append("}")
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    private fun parseJson(json: String): MutableMap<String, MutableMap<String, String>> {
        // Use Gson which is already a project dependency
        val type = object : com.google.gson.reflect.TypeToken<
            MutableMap<String, MutableMap<String, String>>>() {}.type
        return com.google.gson.Gson().fromJson(json, type)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
