package com.my.seekapplication.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.crypto.VaultKeyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * VaultBackupManager: Encrypted backup export and import.
 *
 * Creates a standalone, password-protected archive (.svb) that users can safely save
 * to cloud drive, USB, or local storage. Requires the user-specified backup password to decrypt.
 */
object VaultBackupManager {

    private const val TAG = "VaultBackupManager"
    private const val BACKUP_MAGIC = 0x5356424B // "SVBK"
    private const val BACKUP_VERSION: Byte = 1
    private const val SALT_SIZE = 32
    private const val HEADER_SIZE = 4 + 1 + SALT_SIZE // 37 bytes

    private val secureRandom = SecureRandom()
    private val cipherStream = VaultCipherStream()

    /**
     * Exports all items in the repository to target destination stream.
     * The archive is encrypted using AES-256-GCM chunked streaming with a key derived from backupPassword.
     */
    fun exportBackup(
        context: Context,
        repository: VaultRepository,
        backupPassword: String,
        targetOutputStream: OutputStream,
        progressListener: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        val items = repository.getAllItems()
        val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }

        // Derive 256-bit AES key with PBKDF2 (600,000 iterations)
        val keyManager = VaultKeyManager(context)
        val backupKey = keyManager.deriveKeyFromPassword(backupPassword, salt)

        // Create temporary zip archive in private storage
        val tempZip = File(context.filesDir, ".tmp_backup.zip")
        val tempEnc = File(context.filesDir, ".tmp_backup.svb")

        try {
            // 1. Pack items into unencrypted ZIP archive in private sandbox
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                // Write manifest
                val manifestArray = JSONArray()
                items.forEach { manifestArray.put(it.toJsonObject()) }
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestArray.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Write file payloads
                items.forEachIndexed { index, item ->
                    val entry = ZipEntry("files/${item.id}.dat")
                    zos.putNextEntry(entry)
                    repository.getDecryptedStream(item).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                    progressListener?.invoke(index + 1, items.size)
                }
            }

            // 2. Encrypt temporary zip into target backup format
            val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            headerBuf.putInt(BACKUP_MAGIC)
            headerBuf.put(BACKUP_VERSION)
            headerBuf.put(salt)

            targetOutputStream.write(headerBuf.array())

            FileInputStream(tempZip).use { fis ->
                cipherStream.encryptStreamToFile(fis, tempEnc, backupKey)
            }

            FileInputStream(tempEnc).use { fis ->
                fis.copyTo(targetOutputStream)
            }
            targetOutputStream.flush()

            return items.size
        } finally {
            tempZip.delete()
            tempEnc.delete()
        }
    }

    /**
     * Imports and restores items from an encrypted backup stream.
     */
    fun importBackup(
        context: Context,
        repository: VaultRepository,
        backupPassword: String,
        backupInputStream: InputStream,
        progressListener: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        val headerBytes = ByteArray(HEADER_SIZE)
        var readHeader = 0
        while (readHeader < HEADER_SIZE) {
            val r = backupInputStream.read(headerBytes, readHeader, HEADER_SIZE - readHeader)
            if (r == -1) throw IllegalArgumentException("Invalid backup file: truncated header")
            readHeader += r
        }

        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.int
        if (magic != BACKUP_MAGIC) {
            throw IllegalArgumentException("Invalid backup file: wrong magic identifier")
        }
        val version = buf.get()
        if (version != BACKUP_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }
        val salt = ByteArray(SALT_SIZE)
        buf.get(salt)

        // Derive key
        val keyManager = VaultKeyManager(context)
        val backupKey = keyManager.deriveKeyFromPassword(backupPassword, salt)

        // Copy encrypted stream to temp file
        val tempEnc = File(context.filesDir, ".tmp_import.svb")
        val tempZip = File(context.filesDir, ".tmp_import.zip")

        try {
            FileOutputStream(tempEnc).use { fos ->
                backupInputStream.copyTo(fos)
            }

            // Decrypt backup
            FileOutputStream(tempZip).use { fos ->
                cipherStream.getDecryptedStream(tempEnc, backupKey).use { input ->
                    input.copyTo(fos)
                }
            }

            // Unpack and import items
            var restoredCount = 0
            val manifestItems = mutableListOf<VaultItem>()

            // 1. Read manifest from zip
            ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        val out = ByteArrayOutputStream()
                        zis.copyTo(out)
                        val array = JSONArray(out.toString("UTF-8"))
                        for (i in 0 until array.length()) {
                            manifestItems.add(VaultItem.fromJsonObject(array.getJSONObject(i)))
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            val manifestMap = manifestItems.associateBy { "files/${it.id}.dat" }

            // 2. Extract and import files into repository
            ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val item = manifestMap[entry.name]
                    if (item != null) {
                        repository.importStream(
                            source = zis,
                            originalName = item.originalName,
                            category = item.category,
                            mimeType = item.mimeType,
                            dateAdded = item.dateAdded
                        )
                        restoredCount++
                        progressListener?.invoke(restoredCount, manifestItems.size)
                    }
                    entry = zis.nextEntry
                }
            }

            return restoredCount
        } finally {
            tempEnc.delete()
            tempZip.delete()
        }
    }
}
