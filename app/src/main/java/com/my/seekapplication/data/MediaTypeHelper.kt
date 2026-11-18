package com.my.seekapplication.data

import android.webkit.MimeTypeMap

/**
 * MediaTypeHelper: Classifies vault items using their stored MIME type from the encrypted index,
 * falling back to filename extension only when MIME type is missing or generic octet-stream.
 */
object MediaTypeHelper {

    fun isVideo(mimeType: String?, originalName: String? = null): Boolean {
        if (mimeType != null && mimeType.startsWith("video/", ignoreCase = true)) {
            return true
        }
        val lower = (originalName ?: "").lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".3gp") ||
                lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov")
    }

    fun isAudio(mimeType: String?, originalName: String? = null): Boolean {
        if (mimeType != null && mimeType.startsWith("audio/", ignoreCase = true)) {
            return true
        }
        val lower = (originalName ?: "").lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") ||
                lower.endsWith(".opus") || lower.endsWith(".wma")
    }

    fun isImage(mimeType: String?, originalName: String? = null): Boolean {
        if (mimeType != null && mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        val lower = (originalName ?: "").lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    fun getCategory(mimeType: String?, originalName: String? = null): String {
        return when {
            isVideo(mimeType, originalName) -> "Videos"
            isAudio(mimeType, originalName) -> "Audios"
            else -> "Photos"
        }
    }

    fun resolveMimeType(fileName: String, fallback: String = "image/jpeg"): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (!fromMap.isNullOrEmpty()) return fromMap
        }
        return fallback
    }
}
