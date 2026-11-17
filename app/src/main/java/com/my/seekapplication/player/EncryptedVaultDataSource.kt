package com.my.seekapplication.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.my.seekapplication.crypto.VaultCipherStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.crypto.SecretKey

/**
 * EncryptedVaultDataSource: Streaming Media3 DataSource that decrypts AES-256-GCM chunks on the fly.
 * Allows ExoPlayer to seek to any arbitrary timestamp without creating plaintext files on disk.
 */
class EncryptedVaultDataSource(
    private val cipherStream: VaultCipherStream,
    private val masterKey: SecretKey
) : DataSource {

    private var targetFile: File? = null
    private var uri: Uri? = null
    private var plaintextSize: Long = 0L
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var isOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        // No-op for local in-memory streaming
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = uri?.path ?: throw IOException("Invalid null URI path in DataSpec: $uri")
        val file = File(path)
        if (!file.exists()) {
            throw FileNotFoundException("Encrypted media file not found: $path")
        }
        targetFile = file
        plaintextSize = cipherStream.getPlaintextSize(file)
        currentPosition = dataSpec.position

        if (currentPosition > plaintextSize) {
            throw IOException("Requested position $currentPosition exceeds media plaintextSize $plaintextSize")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            plaintextSize - currentPosition
        }

        isOpen = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = targetFile ?: return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()

        val bytesRead = cipherStream.readBytes(
            file = file,
            position = currentPosition,
            target = buffer,
            offset = offset,
            length = bytesToRead,
            key = masterKey
        )

        if (bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        currentPosition += bytesRead
        bytesRemaining -= bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        isOpen = false
        targetFile = null
        uri = null
    }

    class Factory(
        private val cipherStream: VaultCipherStream,
        private val masterKey: SecretKey
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return EncryptedVaultDataSource(cipherStream, masterKey)
        }
    }
}
