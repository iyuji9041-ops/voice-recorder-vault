package com.my.seekapplication.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * VaultRestoreManager: Secure export and restoration of vault items into public device storage.
 *
 * Guarantees:
 * 1. Restores through appropriate MediaStore collections (Images, Video, Audio, Downloads).
 * 2. Uses IS_PENDING = 1 on Android 10+ during streaming to prevent partial/corrupt file reads.
 * 3. Preserves original filename, MIME type, and timestamps.
 * 4. Rigorous verification: verifies byte length and SHA-256 checksum after writing.
 * 5. Rollback on failure: automatically deletes the MediaStore row if export or verification fails.
 * 6. Vault copy is deleted ONLY after the restored file has been verified on disk.
 */
object VaultRestoreManager {

    private const val TAG = "VaultRestoreManager"
    const val RESTORE_SUBDIR = "Vault_Restored"

    data class RestoreResult(
        val item: VaultItem,
        val restoredUri: Uri,
        val bytesExported: Long,
        val success: Boolean,
        val error: String? = null
    )

    /**
     * Restores a single VaultItem to public storage through MediaStore.
     */
    fun restoreItem(
        context: Context,
        repository: VaultRepository,
        item: VaultItem
    ): RestoreResult {
        val resolver = context.contentResolver
        val mimeType = item.mimeType
        val originalName = item.originalName
        val dateSeconds = if (item.dateAdded > 0) item.dateAdded / 1000 else System.currentTimeMillis() / 1000

        var destUri: Uri? = null

        try {
            val (collectionUri, relativeDir) = getCollectionAndDirectory(mimeType, originalName)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.DATE_ADDED, dateSeconds)
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateSeconds)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            destUri = resolver.insert(collectionUri, values)
                ?: throw IOException("Failed to create MediaStore entry in $collectionUri")

            // 1. Export item decrypted into target stream while calculating SHA-256
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytesWritten = 0L

            resolver.openOutputStream(destUri, "w")?.use { rawOut ->
                DigestOutputStream(rawOut, digest).use { digestOut ->
                    repository.exportItemToStream(item, digestOut)
                    digestOut.flush()
                }
            } ?: throw IOException("Could not open destination output stream for $destUri")

            val writtenHash = digest.digest()

            // 2. Verification: check length and stream integrity of exported public file
            var exportedLength = 0L
            val verifyDigest = MessageDigest.getInstance("SHA-256")

            resolver.openInputStream(destUri)?.use { verifyIn ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (verifyIn.read(buffer).also { read = it } != -1) {
                    verifyDigest.update(buffer, 0, read)
                    exportedLength += read
                }
            } ?: throw IOException("Cannot read back exported file for verification from $destUri")

            if (exportedLength <= 0L) {
                throw IOException("Exported file is 0 bytes: verification failed.")
            }

            if (item.fileSize > 0 && exportedLength != item.fileSize) {
                throw IOException("Exported file size ($exportedLength) does not match expected (${item.fileSize}).")
            }

            if (!MessageDigest.isEqual(writtenHash, verifyDigest.digest())) {
                throw IOException("Exported file checksum mismatch: verification failed.")
            }

            // 3. Clear IS_PENDING on Android 10+ so the file becomes available to gallery
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(destUri, completeValues, null, null)
            }

            // 4. Delete the vault copy ONLY after verified on disk
            repository.deleteItem(item.id)

            return RestoreResult(
                item = item,
                restoredUri = destUri,
                bytesExported = exportedLength,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restoration failed for ${item.originalName}: ${e.message}", e)
            // Roll back inserted MediaStore entry on failure
            if (destUri != null) {
                try {
                    resolver.delete(destUri, null, null)
                } catch (cleanupEx: Exception) {
                    Log.w(TAG, "Failed rolling back MediaStore row $destUri: ${cleanupEx.message}")
                }
            }
            return RestoreResult(
                item = item,
                restoredUri = destUri ?: Uri.EMPTY,
                bytesExported = 0L,
                success = false,
                error = e.message ?: "Unknown restoration error"
            )
        }
    }

    private fun getCollectionAndDirectory(mimeType: String, originalName: String): Pair<Uri, String> {
        return when {
            MediaTypeHelper.isImage(mimeType, originalName) -> {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                collection to "${Environment.DIRECTORY_PICTURES}/$RESTORE_SUBDIR"
            }
            MediaTypeHelper.isVideo(mimeType, originalName) -> {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                collection to "${Environment.DIRECTORY_MOVIES}/$RESTORE_SUBDIR"
            }
            MediaTypeHelper.isAudio(mimeType, originalName) -> {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                collection to "${Environment.DIRECTORY_MUSIC}/$RESTORE_SUBDIR"
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_DOWNLOADS}/$RESTORE_SUBDIR"
                } else {
                    MediaStore.Files.getContentUri("external") to
                            "${Environment.DIRECTORY_DOWNLOADS}/$RESTORE_SUBDIR"
                }
            }
        }
    }
}
