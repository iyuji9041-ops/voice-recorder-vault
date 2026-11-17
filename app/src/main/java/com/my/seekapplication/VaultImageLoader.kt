package com.my.seekapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.data.VaultSession
import com.my.seekapplication.player.EncryptedMediaDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * VaultImageLoader: In-memory streaming image & video thumbnail loader.
 *
 * Guarantees:
 * 1. ZERO plaintext files on disk: decodes directly from encrypted chunk streams.
 * 2. NO OutOfMemoryError: uses streaming downsampling with BitmapFactory.Options.inSampleSize.
 * 3. Video frame thumbnails extracted via EncryptedMediaDataSource directly in memory.
 * 4. Memory-only LRU Cache, wiped immediately upon session lock.
 */
object VaultImageLoader {

    private const val TAG = "VaultImageLoader"

    // LRU Cache: 1/8th of available application memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val loaderScope = CoroutineScope(Dispatchers.Main + Job())
    private val cipherStream = VaultCipherStream()

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * Loads an image or video thumbnail into the target ImageView asynchronously.
     */
    fun loadThumbnail(
        context: Context,
        file: File,
        imageView: ImageView,
        targetWidth: Int = 300,
        targetHeight: Int = 300,
        mimeType: String? = null
    ) {
        val cacheKey = "${file.absolutePath}_${targetWidth}x${targetHeight}"

        // 1. Check memory cache first
        val cachedBitmap = memoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            return
        }

        // Set placeholder color while loading
        imageView.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        imageView.setImageDrawable(null)

        val tagKey = file.absolutePath
        imageView.tag = tagKey

        loaderScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    decodeThumbnail(file, targetWidth, targetHeight, mimeType)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading thumbnail for ${file.name}: ${e.message}", e)
                    null
                }
            }

            // Verify the ImageView hasn't been recycled for a different item
            if (imageView.tag == tagKey) {
                if (bitmap != null) {
                    memoryCache.put(cacheKey, bitmap)
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }
        }
    }

    private fun decodeThumbnail(
        file: File,
        reqWidth: Int,
        reqHeight: Int,
        mimeType: String?
    ): Bitmap? {
        val isVideo = mimeType?.startsWith("video/") == true ||
                file.name.endsWith(".mp4", ignoreCase = true)
        val isAudio = mimeType?.startsWith("audio/") == true ||
                file.name.endsWith(".m4a", ignoreCase = true) ||
                file.name.endsWith(".mp3", ignoreCase = true)

        return when {
            isVideo -> decodeVideoThumbnail(file)
            isAudio -> null
            else -> decodeImageBitmap(file, reqWidth, reqHeight)
        }
    }

    fun decodeImageBitmap(
        file: File,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val repo = VaultSession.getRepositoryOrNull()
        if (repo == null) {
            // If unencrypted fallback (e.g. legacy or intruder file)
            if (!file.name.endsWith(".enc")) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                return BitmapFactory.decodeFile(file.absolutePath, options)
            }
            return null
        }

        val masterKey = repo.masterKey

        try {
            // First pass: decode bounds to determine inSampleSize without memory allocation
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cipherStream.getDecryptedStream(file, masterKey).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            // Second pass: decode downsampled bitmap directly into memory
            return cipherStream.getDecryptedStream(file, masterKey).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed streaming decode for ${file.name}: ${e.message}")
            return null
        }
    }

    private fun decodeVideoThumbnail(file: File): Bitmap? {
        val repo = VaultSession.getRepositoryOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            val dataSource = EncryptedMediaDataSource(file, cipherStream, repo.masterKey)
            retriever.setDataSource(dataSource)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed video thumbnail extraction: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
