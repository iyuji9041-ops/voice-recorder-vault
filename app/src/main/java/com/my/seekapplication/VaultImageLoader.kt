package com.my.seekapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.data.MediaTypeHelper
import com.my.seekapplication.data.VaultSession
import com.my.seekapplication.player.EncryptedMediaDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * VaultImageLoader: In-memory streaming image & video thumbnail loader with EXIF orientation handling,
 * OOM downsampling protection, and cancellable image jobs.
 *
 * Guarantees:
 * 1. ZERO plaintext files on disk: decodes directly from encrypted chunk streams.
 * 2. NO OutOfMemoryError: calculates inSampleSize with max bounds (2048x2048).
 * 3. EXIF orientation corrected in memory so photos display right-side up.
 * 4. Video frame thumbnails extracted via EncryptedMediaDataSource directly in memory.
 * 5. Memory-only LRU Cache, wiped immediately upon session lock.
 * 6. View-attached job tracking so recycled grid items cancel inflight decoding jobs.
 */
object VaultImageLoader {

    private const val TAG = "VaultImageLoader"
    private const val MAX_DECODE_DIMENSION = 2048

    // LRU Cache: 1/8th of available application memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val loaderScope = CoroutineScope(Dispatchers.Main + Job())
    private val runningJobs = Collections.synchronizedMap(WeakHashMap<ImageView, Job>())
    private val cipherStream = VaultCipherStream()

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    fun cancelLoad(imageView: ImageView) {
        runningJobs.remove(imageView)?.cancel()
    }

    /**
     * Loads an image or video thumbnail into the target ImageView asynchronously.
     */
    fun loadThumbnail(
        context: Context,
        file: File,
        imageView: ImageView,
        targetWidth: Int = 320,
        targetHeight: Int = 320,
        mimeType: String? = null
    ) {
        // Cancel any pending decode job on this view
        cancelLoad(imageView)

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

        val job = loaderScope.launch {
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

        runningJobs[imageView] = job
    }

    private fun decodeThumbnail(
        file: File,
        reqWidth: Int,
        reqHeight: Int,
        mimeType: String?
    ): Bitmap? {
        val isVideo = MediaTypeHelper.isVideo(mimeType, file.name)
        val isAudio = MediaTypeHelper.isAudio(mimeType, file.name)

        return when {
            isVideo -> decodeVideoThumbnail(file)
            isAudio -> null
            else -> decodeImageBitmap(file, reqWidth, reqHeight)
        }
    }

    /**
     * Decodes an encrypted image with downsampling and EXIF orientation normalization.
     */
    fun decodeImageBitmap(
        file: File,
        reqWidth: Int = MAX_DECODE_DIMENSION,
        reqHeight: Int = MAX_DECODE_DIMENSION
    ): Bitmap? {
        val repo = VaultSession.getRepositoryOrNull()
        val effectiveWidth = reqWidth.coerceIn(100, MAX_DECODE_DIMENSION)
        val effectiveHeight = reqHeight.coerceIn(100, MAX_DECODE_DIMENSION)

        if (repo == null) {
            if (!file.name.endsWith(".enc")) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, effectiveWidth, effectiveHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
                return applyExifOrientation(file.absolutePath, decoded)
            }
            return null
        }

        val masterKey = repo.masterKey

        try {
            // First pass: decode bounds to determine inSampleSize without large memory allocation
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cipherStream.getDecryptedStream(file, masterKey).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }

            options.inSampleSize = calculateInSampleSize(options, effectiveWidth, effectiveHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            // Second pass: decode downsampled bitmap directly into memory
            val decodedBitmap = cipherStream.getDecryptedStream(file, masterKey).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Read EXIF orientation from decrypted stream
            val orientation = try {
                cipherStream.getDecryptedStream(file, masterKey).use { stream ->
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            return applyExifRotation(decodedBitmap, orientation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed streaming decode for ${file.name}: ${e.message}")
            return null
        }
    }

    private fun applyExifOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            applyExifRotation(bitmap, orientation)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        return try {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented != bitmap) {
                bitmap.recycle()
            }
            oriented
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying EXIF rotation: ${e.message}")
            bitmap
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
