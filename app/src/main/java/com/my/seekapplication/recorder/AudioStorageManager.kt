package com.my.seekapplication.recorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AudioRecordingItem: Unified model for public recordings across MediaStore and legacy file storage.
 */
data class AudioRecordingItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val durationMs: Long = 0L,
    val legacyFile: File? = null
)

/**
 * AudioStorageManager: Manages public audio recordings.
 *
 * Guarantees:
 * 1. On Android 10+ (API 29+): Scoped MediaStore storage with RELATIVE_PATH 'Recordings/MyVoiceRecordings' and IS_PENDING = 1 during recording.
 * 2. On Android 9 & below: App-specific external storage as clean legacy path without folder-switching drift.
 * 3. Unified MediaStore querying, renaming, and batch deletion.
 */
object AudioStorageManager {

    private const val TAG = "AudioStorageManager"
    const val RELATIVE_RECORDINGS_PATH = "Recordings/MyVoiceRecordings"

    fun sanitizeRecordingName(rawName: String): String {
        var clean = rawName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (clean.isBlank()) clean = "recording"
        if (!clean.endsWith(".m4a", ignoreCase = true)) {
            clean = "$clean.m4a"
        }
        return clean
    }

    fun generateRecordingDisplayName(date: Date = Date()): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
        return "REC_${timestamp}.m4a"
    }

    fun formatDurationMs(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun createPendingRecording(context: Context): Pair<Uri, ParcelFileDescriptor> {
        val displayName = generateRecordingDisplayName()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_RECORDINGS_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }

            val uri = resolver.insert(collection, values)
                ?: throw IOException("Failed to create MediaStore entry for audio recording.")

            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Failed to open file descriptor for recording URI: $uri")

            return uri to pfd
        } else {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Recordings")
            val recordingsDir = File(baseDir, "MyVoiceRecordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val file = File(recordingsDir, displayName)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
            return Uri.fromFile(file) to pfd
        }
    }

    fun finishPendingRecording(context: Context, uri: Uri, save: Boolean) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (save) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    resolver.update(uri, values, null, null)
                } else {
                    resolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing MediaStore recording: ${e.message}", e)
            }
        } else {
            if (!save) {
                try {
                    val path = uri.path
                    if (path != null) {
                        File(path).delete()
                    }
                } catch (ignored: Exception) {}
            }
        }
    }

    fun queryAllRecordings(context: Context): List<AudioRecordingItem> {
        val items = mutableListOf<AudioRecordingItem>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION
            )

            val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$RELATIVE_RECORDINGS_PATH%", "REC_%.m4a")
            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Recording_$id.m4a"
                        val size = cursor.getLong(sizeCol)
                        val dateSec = cursor.getLong(dateCol)
                        val duration = cursor.getLong(durCol)
                        val itemUri = ContentUris.withAppendedId(collection, id)

                        items.add(
                            AudioRecordingItem(
                                id = id,
                                uri = itemUri,
                                name = name,
                                sizeBytes = size,
                                dateModifiedMs = if (dateSec > 0) dateSec * 1000 else System.currentTimeMillis(),
                                durationMs = duration
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore recordings: ${e.message}", e)
            }
        } else {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Recordings")
            val recordingsDir = File(baseDir, "MyVoiceRecordings")
            val files = recordingsDir.listFiles { f -> f.isFile && f.name.endsWith(".m4a", true) }
            if (files != null) {
                files.sortedByDescending { it.lastModified() }.forEachIndexed { idx, file ->
                    items.add(
                        AudioRecordingItem(
                            id = idx.toLong(),
                            uri = Uri.fromFile(file),
                            name = file.name,
                            sizeBytes = file.length(),
                            dateModifiedMs = file.lastModified(),
                            legacyFile = file
                        )
                    )
                }
            }
        }

        return items
    }

    fun deleteRecording(context: Context, item: AudioRecordingItem): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(item.uri, null, null) > 0
            } else {
                item.legacyFile?.delete() ?: (context.contentResolver.delete(item.uri, null, null) > 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording ${item.name}: ${e.message}", e)
            false
        }
    }

    fun renameRecording(context: Context, item: AudioRecordingItem, newNameRaw: String): Boolean {
        val cleanName = sanitizeRecordingName(newNameRaw)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, cleanName)
                }
                context.contentResolver.update(item.uri, values, null, null) > 0
            } else {
                val legacy = item.legacyFile
                if (legacy != null && legacy.exists()) {
                    val dest = File(legacy.parentFile, cleanName)
                    legacy.renameTo(dest)
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename recording ${item.name}: ${e.message}", e)
            false
        }
    }
}
