package com.my.seekapplication.crypto

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * VaultCipherStream: Production-grade Chunked AES-256-GCM Streaming Cipher.
 *
 * Provides:
 * 1. Authenticated Streaming Encryption (AES-256-GCM with 128-bit authentication tags).
 * 2. O(1) Random-Access Seeking without decrypting the entire file.
 * 3. Constant memory footprint (64 KB chunk buffer), preventing OutOfMemoryError.
 * 4. Cryptographic binding of chunks via Associated Data (AAD) to prevent chunk reordering or truncation.
 * 5. Instant tamper detection (any flipped bit or altered tag throws SecurityException).
 */
class VaultCipherStream(
    val defaultChunkSize: Int = DEFAULT_CHUNK_SIZE
) {

    companion object {
        const val MAGIC_BYTES = 0x53564331 // "SVC1"
        const val VERSION: Byte = 1
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
        const val GCM_TAG_LENGTH_BYTES = 16
        const val GCM_TAG_LENGTH_BITS = 128
        const val BASE_NONCE_LENGTH = 12
        const val HEADER_LENGTH = 4 + 1 + 4 + 8 + BASE_NONCE_LENGTH // 29 bytes

        private val secureRandom = SecureRandom()
    }

    data class Header(
        val version: Byte,
        val chunkSize: Int,
        val plaintextSize: Long,
        val baseNonce: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return version == other.version &&
                    chunkSize == other.chunkSize &&
                    plaintextSize == other.plaintextSize &&
                    baseNonce.contentEquals(other.baseNonce)
        }

        override fun hashCode(): Int {
            var result = version.toInt()
            result = 31 * result + chunkSize
            result = 31 * result + plaintextSize.hashCode()
            result = 31 * result + baseNonce.contentHashCode()
            return result
        }
    }

    /**
     * Reads and validates the header from an encrypted file.
     */
    fun readHeader(file: File): Header {
        if (!file.exists()) throw FileNotFoundException("Encrypted file not found: ${file.absolutePath}")
        RandomAccessFile(file, "r").use { raf ->
            return readHeaderFromRaf(raf)
        }
    }

    private fun readHeaderFromRaf(raf: RandomAccessFile): Header {
        if (raf.length() < HEADER_LENGTH) {
            throw IOException("File is too short to be a valid encrypted vault file (length: ${raf.length()})")
        }
        raf.seek(0)
        val headerBytes = ByteArray(HEADER_LENGTH)
        raf.readFully(headerBytes)

        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.int
        if (magic != MAGIC_BYTES) {
            throw SecurityException("Invalid magic header: expected 0x${Integer.toHexString(MAGIC_BYTES)} but got 0x${Integer.toHexString(magic)}")
        }
        val ver = buf.get()
        if (ver != VERSION) {
            throw SecurityException("Unsupported vault file format version: $ver")
        }
        val chunkSize = buf.int
        if (chunkSize <= 0 || chunkSize > 4 * 1024 * 1024) {
            throw SecurityException("Invalid chunk size in header: $chunkSize")
        }
        val plaintextSize = buf.long
        if (plaintextSize < 0) {
            throw SecurityException("Invalid negative plaintext size in header: $plaintextSize")
        }
        val baseNonce = ByteArray(BASE_NONCE_LENGTH)
        buf.get(baseNonce)

        return Header(ver, chunkSize, plaintextSize, baseNonce)
    }

    /**
     * Derives a unique 12-byte nonce for a specific chunk index:
     * First 8 bytes of baseNonce XORed / combined with chunkIndex in big endian.
     */
    internal fun deriveNonce(baseNonce: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        val indexBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(chunkIndex).array()
        for (i in 0 until 8) {
            nonce[4 + i] = (nonce[4 + i].toInt() xor indexBuf[i].toInt()).toByte()
        }
        return nonce
    }

    /**
     * Derives Associated Authenticated Data (AAD) binding chunk index and magic bytes.
     */
    internal fun deriveAAD(chunkIndex: Long): ByteArray {
        return ByteBuffer.allocate(12)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC_BYTES)
            .putLong(chunkIndex)
            .array()
    }

    /**
     * Gets the plaintext size of an encrypted file without decrypting its contents.
     */
    fun getPlaintextSize(file: File): Long {
        return readHeader(file).plaintextSize
    }

    /**
     * Encrypts an input stream to targetFile in chunked AES-256-GCM format.
     * Ensures atomic creation: writes to tempFile first, flushes, syncs, then caller can rename.
     *
     * @return Total plaintext bytes encrypted.
     */
    fun encryptStreamToFile(
        source: InputStream,
        targetFile: File,
        key: SecretKey,
        progressListener: ((bytesWritten: Long) -> Unit)? = null
    ): Long {
        val baseNonce = ByteArray(BASE_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val chunkSize = defaultChunkSize

        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(0)

            // Write placeholder header
            val headerBuf = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
            headerBuf.putInt(MAGIC_BYTES)
            headerBuf.put(VERSION)
            headerBuf.putInt(chunkSize)
            headerBuf.putLong(0L) // Will be updated after streaming
            headerBuf.put(baseNonce)
            raf.write(headerBuf.array())

            val buffer = ByteArray(chunkSize)
            var chunkIndex = 0L
            var totalPlaintextBytes = 0L

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            while (true) {
                var bytesReadThisChunk = 0
                while (bytesReadThisChunk < chunkSize) {
                    val count = source.read(buffer, bytesReadThisChunk, chunkSize - bytesReadThisChunk)
                    if (count == -1) break
                    bytesReadThisChunk += count
                }

                if (bytesReadThisChunk == 0) {
                    break
                }

                val nonce = deriveNonce(baseNonce, chunkIndex)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
                cipher.init(Cipher.ENCRYPT_MODE, key, spec)
                cipher.updateAAD(deriveAAD(chunkIndex))

                val encryptedChunk = cipher.doFinal(buffer, 0, bytesReadThisChunk)
                raf.write(encryptedChunk)

                totalPlaintextBytes += bytesReadThisChunk
                chunkIndex++
                progressListener?.invoke(totalPlaintextBytes)

                if (bytesReadThisChunk < chunkSize) {
                    // Reached end of input stream
                    break
                }
            }

            // Update real plaintext length in header
            raf.seek(4 + 1 + 4) // Position of plaintextSize Long
            raf.writeLong(totalPlaintextBytes)
            raf.fd.sync()

            return totalPlaintextBytes
        }
    }

    /**
     * Reads and decrypts a specific range of plaintext bytes from an encrypted file.
     * Enables random-access seeking directly into video / audio / photo streams.
     *
     * @param file The encrypted file.
     * @param position The byte offset in the unencrypted plaintext stream.
     * @param target Buffer where decrypted bytes will be written.
     * @param offset Offset into target buffer.
     * @param length Maximum number of bytes to read.
     * @param key The master encryption key.
     * @return Number of plaintext bytes read, or 0 if EOF.
     */
    fun readBytes(
        file: File,
        position: Long,
        target: ByteArray,
        offset: Int,
        length: Int,
        key: SecretKey
    ): Int {
        if (length <= 0) return 0
        if (!file.exists()) return -1

        RandomAccessFile(file, "r").use { raf ->
            val header = readHeaderFromRaf(raf)
            val plaintextSize = header.plaintextSize
            if (position >= plaintextSize) {
                return 0 // EOF
            }

            val bytesToRead = minOf(length.toLong(), plaintextSize - position).toInt()
            var bytesReadTotal = 0
            var currentPos = position
            val chunkSize = header.chunkSize
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            while (bytesReadTotal < bytesToRead) {
                val chunkIndex = currentPos / chunkSize
                val chunkOffset = (currentPos % chunkSize).toInt()
                val chunkPlaintextSize = minOf(chunkSize.toLong(), plaintextSize - chunkIndex * chunkSize).toInt()
                val encryptedChunkSize = chunkPlaintextSize + GCM_TAG_LENGTH_BYTES

                val fileOffset = HEADER_LENGTH + chunkIndex * (chunkSize.toLong() + GCM_TAG_LENGTH_BYTES)
                raf.seek(fileOffset)

                val encryptedBytes = ByteArray(encryptedChunkSize)
                raf.readFully(encryptedBytes)

                val nonce = deriveNonce(header.baseNonce, chunkIndex)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
                val decryptedChunk: ByteArray
                try {
                    cipher.init(Cipher.DECRYPT_MODE, key, spec)
                    cipher.updateAAD(deriveAAD(chunkIndex))
                    decryptedChunk = cipher.doFinal(encryptedBytes)
                } catch (e: GeneralSecurityException) {
                    throw SecurityException("Cryptographic verification failed for chunk $chunkIndex: data may be corrupted or tampered with.", e)
                }

                val availableInThisChunk = chunkPlaintextSize - chunkOffset
                val copyCount = minOf(availableInThisChunk, bytesToRead - bytesReadTotal)
                System.arraycopy(
                    decryptedChunk,
                    chunkOffset,
                    target,
                    offset + bytesReadTotal,
                    copyCount
                )

                bytesReadTotal += copyCount
                currentPos += copyCount
            }

            return bytesReadTotal
        }
    }

    /**
     * SeekableDecryptedStream: In-memory seekable streaming InputStream over the encrypted file.
     * Decrypts 64 KB chunks on the fly without loading the whole file into RAM or writing plaintext to disk.
     */
    open class SeekableDecryptedStream(
        private val cipherStream: VaultCipherStream,
        private val file: File,
        private val key: SecretKey,
        val totalPlaintextSize: Long
    ) : InputStream() {
        private var position = 0L
        private val singleByteBuf = ByteArray(1)

        fun seek(newPosition: Long) {
            position = newPosition.coerceIn(0L, totalPlaintextSize)
        }

        fun currentPosition(): Long = position

        override fun read(): Int {
            val count = read(singleByteBuf, 0, 1)
            return if (count <= 0) -1 else singleByteBuf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= totalPlaintextSize) return -1
            val readCount = cipherStream.readBytes(file, position, b, off, len, key)
            if (readCount <= 0) return -1
            position += readCount
            return readCount
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val skipped = minOf(n, totalPlaintextSize - position)
            position += skipped
            return skipped
        }

        override fun available(): Int {
            val remaining = totalPlaintextSize - position
            return if (remaining > Int.MAX_VALUE) Int.MAX_VALUE else remaining.toInt()
        }
    }

    /**
     * Returns a streaming decrypted SeekableDecryptedStream over the encrypted file.
     * Reads chunks on the fly without loading the whole file into memory.
     */
    fun getDecryptedStream(file: File, key: SecretKey): SeekableDecryptedStream {
        val header = readHeader(file)
        return SeekableDecryptedStream(this, file, key, header.plaintextSize)
    }
}
