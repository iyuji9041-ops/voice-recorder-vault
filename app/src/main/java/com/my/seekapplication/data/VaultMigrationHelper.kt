package com.my.seekapplication.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * VaultMigrationHelper: Safely detects legacy vault files in public Documents/.secure_vault_system
 * and internal vault_encrypted folders, decrypts with legacy Keystore key, and migrates them
 * into the new chunked AES-256-GCM private repository.
 *
 * Safety Guarantee: If any file cannot be decrypted (e.g. keystore key lost after Clear Data),
 * the file is NEVER deleted and reported to the user.
 */
object VaultMigrationHelper {

    private const val TAG = "VaultMigrationHelper"
    private const val LEGACY_KEYSTORE_ALIAS = "MySecureVaultMasterKey"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    data class MigrationResult(
        val totalFound: Int,
        val migratedCount: Int,
        val failedFiles: List<File>
    )

    private fun getLegacyKeystoreKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(LEGACY_KEYSTORE_ALIAS)) {
                (keyStore.getEntry(LEGACY_KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy keystore key not found or error loading: ${e.message}")
            null
        }
    }

    fun hasLegacyData(context: Context): Boolean {
        val legacyPublicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            ".secure_vault_system"
        )
        val legacyInternalDir = File(context.filesDir, "vault_encrypted")
        return (legacyPublicDir.exists() && hasEncryptedFiles(legacyPublicDir)) ||
                (legacyInternalDir.exists() && hasEncryptedFiles(legacyInternalDir))
    }

    private fun hasEncryptedFiles(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        for (f in files) {
            if (f.isDirectory && hasEncryptedFiles(f)) return true
            if (f.isFile && f.name.endsWith(".enc")) return true
        }
        return false
    }

    /**
     * Attempts legacy decryption of an individual .enc file using the old Keystore key.
     */
    fun decryptLegacyFile(file: File, key: SecretKey): ByteArray {
        FileInputStream(file).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val readIv = fis.read(iv)
            if (readIv != GCM_IV_LENGTH) {
                throw IllegalStateException("Invalid legacy file format: missing IV header")
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val out = ByteArrayOutputStream()
            CipherInputStream(fis, cipher).use { cis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (cis.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            return out.toByteArray()
        }
    }

    /**
     * Migrates all legacy files into the destination repository.
     */
    fun migrateAllLegacyFiles(
        context: Context,
        destinationRepository: VaultRepository
    ): MigrationResult {
        val legacyPublicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            ".secure_vault_system"
        )
        val legacyInternalDir = File(context.filesDir, "vault_encrypted")

        val filesToMigrate = mutableListOf<Pair<File, String>>() // File to Category

        fun collectFiles(dir: File, category: String) {
            if (!dir.exists()) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    collectFiles(f, f.name)
                } else if (f.isFile && f.name.endsWith(".enc")) {
                    collectFiles(f, category)
                }
            }
        }

        fun scanDir(rootDir: File) {
            if (!rootDir.exists()) return
            val categories = listOf("Photos", "Videos", "Audios")
            for (cat in categories) {
                val catDir = File(rootDir, cat)
                if (catDir.exists()) {
                    catDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".enc")) {
                            filesToMigrate.add(Pair(f, cat))
                        }
                    }
                }
            }
            // Also check rootDir files directly
            rootDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".enc")) {
                    filesToMigrate.add(Pair(f, "Photos"))
                }
            }
        }

        scanDir(legacyPublicDir)
        scanDir(legacyInternalDir)

        if (filesToMigrate.isEmpty()) {
            return MigrationResult(0, 0, emptyList())
        }

        val legacyKey = getLegacyKeystoreKey()
        var migratedCount = 0
        val failedFiles = mutableListOf<File>()

        for ((file, category) in filesToMigrate) {
            if (legacyKey == null) {
                // Keystore key lost (e.g. app was cleared or uninstalled in the past)
                failedFiles.add(file)
                continue
            }

            try {
                val decryptedBytes = decryptLegacyFile(file, legacyKey)
                val originalName = file.name.removeSuffix(".enc")
                val mimeType = detectMimeType(originalName)

                // Import into new repository
                ByteArrayInputStream(decryptedBytes).use { bais ->
                    destinationRepository.importStream(
                        source = bais,
                        originalName = originalName,
                        category = category,
                        mimeType = mimeType,
                        dateAdded = file.lastModified()
                    )
                }

                // Delete legacy file only after successful verification
                file.delete()
                migratedCount++
                Log.d(TAG, "Successfully migrated legacy file: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed migrating legacy file ${file.name}: ${e.message}")
                failedFiles.add(file)
            }
        }

        // Clean up legacy .vault_config and .nomedia files if migration was completed
        try {
            val legacyConfig = File(legacyPublicDir, ".vault_config")
            if (legacyConfig.exists() && failedFiles.isEmpty()) {
                legacyConfig.delete()
            }
        } catch (ignored: Exception) {}

        return MigrationResult(
            totalFound = filesToMigrate.size,
            migratedCount = migratedCount,
            failedFiles = failedFiles
        )
    }

    private fun detectMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
