package com.my.seekapplication.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.my.seekapplication.crypto.VaultCipherStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey

data class VaultItem(
    val id: String,
    val originalName: String,
    val category: String,
    val mimeType: String,
    val dateAdded: Long,
    val fileSize: Long
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("originalName", originalName)
        put("category", category)
        put("mimeType", mimeType)
        put("dateAdded", dateAdded)
        put("fileSize", fileSize)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): VaultItem = VaultItem(
            id = json.getString("id"),
            originalName = json.getString("originalName"),
            category = json.optString("category", "Photos"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            dateAdded = json.optLong("dateAdded", System.currentTimeMillis()),
            fileSize = json.optLong("fileSize", 0L)
        )
    }
}

/**
 * VaultRepository: Isolated vault storage manager for either real or decoy store.
 * Manages random UUID files, encrypted index, and atomic writes in private app storage.
 */
class VaultRepository(
    private val vaultDir: File,
    val masterKey: SecretKey,
    val isDecoy: Boolean,
    private val cipherStream: VaultCipherStream = VaultCipherStream()
) {

    companion object {
        private const val TAG = "VaultRepository"
        private const val INDEX_FILE_NAME = "index.enc"
        private const val INDEX_TMP_FILE = ".tmp_index.enc"
    }

    private val indexFile = File(vaultDir, INDEX_FILE_NAME)
    private val itemsMap = mutableMapOf<String, VaultItem>()
    private val lock = Any()

    init {
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        loadIndex()
    }

    fun getVaultDir(): File = vaultDir

    fun getFileForItem(item: VaultItem): File = File(vaultDir, "${item.id}.enc")

    fun getFileById(id: String): File = File(vaultDir, "$id.enc")

    fun getItemById(id: String): VaultItem? = synchronized(lock) {
        itemsMap[id]
    }

    fun getItems(category: String? = null): List<VaultItem> = synchronized(lock) {
        val list = itemsMap.values.toList()
        if (category == null || category == "All") {
            list.sortedByDescending { it.dateAdded }
        } else {
            list.filter { it.category.equals(category, ignoreCase = true) }
                .sortedByDescending { it.dateAdded }
        }
    }

    fun getAllItems(): List<VaultItem> = synchronized(lock) {
        itemsMap.values.toList()
    }

    @Synchronized
    private fun loadIndex() {
        synchronized(lock) {
            itemsMap.clear()
            if (!indexFile.exists() || indexFile.length() == 0L) {
                return
            }

            try {
                val out = ByteArrayOutputStream()
                cipherStream.getDecryptedStream(indexFile, masterKey).use { input ->
                    input.copyTo(out)
                }
                val jsonStr = out.toString("UTF-8")
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val item = VaultItem.fromJsonObject(obj)
                    // Only include item if file actually exists on disk
                    if (getFileForItem(item).exists()) {
                        itemsMap[item.id] = item
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading encrypted index: ${e.message}", e)
            }
        }
    }

    @Synchronized
    private fun persistIndex() {
        synchronized(lock) {
            val array = JSONArray()
            itemsMap.values.forEach { array.put(it.toJsonObject()) }
            val jsonBytes = array.toString(2).toByteArray(Charsets.UTF_8)

            val tmpFile = File(vaultDir, INDEX_TMP_FILE)
            try {
                ByteArrayInputStream(jsonBytes).use { input ->
                    cipherStream.encryptStreamToFile(input, tmpFile, masterKey)
                }
                if (!tmpFile.renameTo(indexFile)) {
                    tmpFile.copyTo(indexFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting encrypted index: ${e.message}", e)
                tmpFile.delete()
                throw e
            }
        }
    }

    /**
     * Imports an input stream atomically into the vault.
     * Generates a random UUID, encrypts to a temp file, flushes, syncs, then renames.
     */
    fun importStream(
        source: InputStream,
        originalName: String,
        category: String,
        mimeType: String,
        dateAdded: Long = System.currentTimeMillis()
    ): VaultItem {
        val id = UUID.randomUUID().toString()
        val finalFile = File(vaultDir, "$id.enc")
        val tmpFile = File(vaultDir, ".tmp_$id.enc")

        try {
            val plaintextSize = cipherStream.encryptStreamToFile(source, tmpFile, masterKey)
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            val item = VaultItem(
                id = id,
                originalName = originalName,
                category = category,
                mimeType = mimeType,
                dateAdded = dateAdded,
                fileSize = plaintextSize
            )

            synchronized(lock) {
                itemsMap[id] = item
                persistIndex()
            }
            return item
        } catch (e: Exception) {
            tmpFile.delete()
            finalFile.delete()
            throw e
        }
    }

    /**
     * Updates an existing vault item with new content (e.g. after photo rotation/editing) atomically.
     */
    fun updateItemContent(item: VaultItem, source: InputStream): VaultItem = synchronized(lock) {
        val targetFile = getFileForItem(item)
        val tempFile = File(vaultDir, ".tmp_${item.id}.enc")
        try {
            cipherStream.encryptStreamToFile(source, tempFile, masterKey)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            val updated = item.copy(fileSizeBytes = targetFile.length())
            itemsMap[item.id] = updated
            persistIndex()
            return updated
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Deletes an item from the vault and removes its encrypted file.
     */
    fun deleteItem(id: String): Boolean = synchronized(lock) {
        val item = itemsMap.remove(id) ?: return false
        val file = getFileForItem(item)
        val deleted = file.delete()
        persistIndex()
        deleted
    }

    /**
     * Exports/restores an encrypted vault item to a target destination stream.
     */
    fun exportItemToStream(item: VaultItem, target: OutputStream) {
        val file = getFileForItem(item)
        cipherStream.getDecryptedStream(file, masterKey).use { input ->
            input.copyTo(target)
        }
    }

    /**
     * Decrypts a range of bytes for streaming playback with seek support.
     */
    fun readItemBytes(
        item: VaultItem,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        val file = getFileForItem(item)
        return cipherStream.readBytes(file, position, buffer, offset, length, masterKey)
    }

    fun getDecryptedStream(item: VaultItem): InputStream {
        val file = getFileForItem(item)
        return cipherStream.getDecryptedStream(file, masterKey)
    }
}
