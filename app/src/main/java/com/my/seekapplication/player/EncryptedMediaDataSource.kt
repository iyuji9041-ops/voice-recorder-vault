package com.my.seekapplication.player

import android.media.MediaDataSource
import com.my.seekapplication.crypto.VaultCipherStream
import java.io.File
import javax.crypto.SecretKey

/**
 * EncryptedMediaDataSource: Custom Android MediaDataSource for MediaMetadataRetriever.
 * Extracts video frame thumbnails directly from AES-256-GCM chunks without temp files.
 */
class EncryptedMediaDataSource(
    private val file: File,
    private val cipherStream: VaultCipherStream,
    private val masterKey: SecretKey
) : MediaDataSource() {

    private val plaintextSize: Long = try {
        cipherStream.getPlaintextSize(file)
    } catch (e: Exception) {
        -1L
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= plaintextSize || plaintextSize <= 0) {
            return -1
        }
        val bytesToRead = minOf(size.toLong(), plaintextSize - position).toInt()
        val read = cipherStream.readBytes(
            file = file,
            position = position,
            target = buffer,
            offset = offset,
            length = bytesToRead,
            key = masterKey
        )
        return if (read <= 0) -1 else read
    }

    override fun getSize(): Long = plaintextSize

    override fun close() {
        // In-memory stream
    }
}
