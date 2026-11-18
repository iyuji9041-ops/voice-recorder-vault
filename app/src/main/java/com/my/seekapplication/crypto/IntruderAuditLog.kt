package com.my.seekapplication.crypto

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * IntruderAuditLog: Persistent security audit trail for unauthorized access attempts.
 * Logs target layer, timestamp, failure attempt number, and camera capture status.
 */
object IntruderAuditLog {

    private const val TAG = "IntruderAuditLog"
    private const val LOG_FILE_NAME = "intruder_audit.json"
    private const val MAX_ENTRIES = 100

    data class Entry(
        val id: String,
        val timestamp: Long,
        val layer: String, // DOOR, VAULT, RECOVERY
        val attemptNumber: Int,
        val status: String, // CAPTURED, PERMISSION_DENIED, CAMERA_UNAVAILABLE, ERROR
        val photoFileName: String? = null
    ) {
        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("layer", layer)
            put("attemptNumber", attemptNumber)
            put("status", status)
            put("photoFileName", photoFileName ?: JSONObject.NULL)
        }

        companion object {
            fun fromJsonObject(json: JSONObject): Entry = Entry(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                layer = json.optString("layer", "VAULT"),
                attemptNumber = json.optInt("attemptNumber", 1),
                status = json.optString("status", "UNKNOWN"),
                photoFileName = if (json.isNull("photoFileName")) null else json.optString("photoFileName")
            )
        }
    }

    private fun getLogFile(context: Context): File {
        val metaDir = File(context.filesDir, "vault_meta")
        if (!metaDir.exists()) metaDir.mkdirs()
        return File(metaDir, LOG_FILE_NAME)
    }

    @Synchronized
    fun getEntries(context: Context): List<Entry> {
        val file = getLogFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val array = JSONArray(file.readText())
            val list = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                list.add(Entry.fromJsonObject(array.getJSONObject(i)))
            }
            list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading audit log: ${e.message}", e)
            emptyList()
        }
    }

    @Synchronized
    fun logAttempt(
        context: Context,
        layer: String,
        attemptNumber: Int,
        status: String,
        photoFileName: String? = null
    ) {
        val entries = getEntries(context).toMutableList()
        val newEntry = Entry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            layer = layer,
            attemptNumber = attemptNumber,
            status = status,
            photoFileName = photoFileName
        )
        entries.add(0, newEntry)

        // Cap entries at MAX_ENTRIES
        val capped = entries.take(MAX_ENTRIES)

        try {
            val array = JSONArray()
            capped.forEach { array.put(it.toJsonObject()) }
            val file = getLogFile(context)
            val tmp = File(file.parentFile, ".tmp_audit.json")
            tmp.writeText(array.toString(2))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving audit log: ${e.message}", e)
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        val file = getLogFile(context)
        if (file.exists()) file.delete()
    }
}
