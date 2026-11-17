package com.my.seekapplication.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for VaultCipherStream:
 * 1. Small payload encrypt/decrypt round trip (< 64KB).
 * 2. Multi-chunk payload encrypt/decrypt round trip (> 64KB across multiple chunks).
 * 3. Random access seek verification within and across chunk boundaries.
 * 4. Tamper detection on ciphertext payload (GCM tag verification).
 * 5. Tamper detection on header.
 * 6. Wrong key failure.
 */
class VaultCipherStreamTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cipherStream = VaultCipherStream(defaultChunkSize = 4096) // 4 KB chunks for fast testing
    private val secureRandom = SecureRandom()

    private fun generateRandomKey(): SecretKeySpec {
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    @Test
    fun testSmallPayloadRoundTrip() {
        val key = generateRandomKey()
        val originalData = "Hello, Secure Vault World! This is a test string for encrypted storage.".toByteArray(Charsets.UTF_8)
        val encryptedFile = tempFolder.newFile("test_small.enc")

        // Encrypt
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, key)
        }

        assertTrue(encryptedFile.exists())
        assertTrue(encryptedFile.length() > originalData.size)

        // Decrypt
        val decryptedOutput = ByteArrayOutputStream()
        cipherStream.getDecryptedStream(encryptedFile, key).use { decryptedInput ->
            decryptedInput.copyTo(decryptedOutput)
        }

        assertArrayEquals(originalData, decryptedOutput.toByteArray())
    }

    @Test
    fun testMultiChunkPayloadRoundTrip() {
        val key = generateRandomKey()
        // 4 KB chunk size * 3.5 chunks = 14,336 bytes
        val originalData = ByteArray(14336)
        secureRandom.nextBytes(originalData)

        val encryptedFile = tempFolder.newFile("test_multichunk.enc")

        // Encrypt
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, key)
        }

        // Verify header
        val header = cipherStream.readHeader(encryptedFile)
        assertEquals(originalData.size.toLong(), header.plaintextSize)
        assertEquals(4096, header.chunkSize)

        // Decrypt full stream
        val decryptedOutput = ByteArrayOutputStream()
        cipherStream.getDecryptedStream(encryptedFile, key).use { decryptedInput ->
            decryptedInput.copyTo(decryptedOutput)
        }

        assertArrayEquals(originalData, decryptedOutput.toByteArray())
    }

    @Test
    fun testSeekingWithinAndAcrossChunks() {
        val key = generateRandomKey()
        val totalSize = 16384 // Exactly 4 chunks of 4096 bytes
        val originalData = ByteArray(totalSize)
        for (i in 0 until totalSize) {
            originalData[i] = (i % 256).toByte()
        }

        val encryptedFile = tempFolder.newFile("test_seek.enc")
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, key)
        }

        // Test seeking to various offsets
        val seekOffsets = longArrayOf(0, 100, 4095, 4096, 4097, 8192, 12000, 16383)

        for (offset in seekOffsets) {
            val decryptedStream = cipherStream.getDecryptedStream(encryptedFile, key)
            decryptedStream.seek(offset)
            assertEquals(offset, decryptedStream.currentPosition())

            val readBytes = ByteArray(128)
            val bytesRead = decryptedStream.read(readBytes)
            val expectedLength = (totalSize - offset).toInt().coerceAtMost(128)
            assertEquals(expectedLength, bytesRead)

            for (j in 0 until expectedLength) {
                assertEquals(
                    "Byte mismatch at offset ${offset + j}",
                    originalData[(offset + j).toInt()],
                    readBytes[j]
                )
            }
            decryptedStream.close()
        }
    }

    @Test
    fun testTamperDetectionInCiphertextThrowsException() {
        val key = generateRandomKey()
        val originalData = ByteArray(8192)
        secureRandom.nextBytes(originalData)

        val encryptedFile = tempFolder.newFile("test_tamper_payload.enc")
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, key)
        }

        // Tamper with a byte in the first chunk's ciphertext (after 29-byte header)
        RandomAccessFile(encryptedFile, "rw").use { raf ->
            raf.seek(35)
            val b = raf.readByte()
            raf.seek(35)
            raf.writeByte(b.toInt() xor 0xFF) // Flip bits
        }

        // Attempting to read decrypted stream must fail with security exception
        try {
            cipherStream.getDecryptedStream(encryptedFile, key).use { input ->
                val buf = ByteArray(1024)
                while (input.read(buf) != -1) {
                    // reading
                }
            }
            fail("Expected GeneralSecurityException or SecurityException when ciphertext was tampered")
        } catch (e: Exception) {
            assertTrue(
                "Exception should indicate cryptographic tamper failure: ${e.javaClass.name}",
                e is GeneralSecurityException || e is SecurityException || e.cause is GeneralSecurityException
            )
        }
    }

    @Test
    fun testTamperDetectionInHeaderThrowsException() {
        val key = generateRandomKey()
        val originalData = "Some secret data to be protected".toByteArray()

        val encryptedFile = tempFolder.newFile("test_tamper_header.enc")
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, key)
        }

        // Tamper with magic bytes
        RandomAccessFile(encryptedFile, "rw").use { raf ->
            raf.seek(0)
            raf.writeByte(0x00) // Corrupt magic
        }

        try {
            cipherStream.getDecryptedStream(encryptedFile, key).use { input ->
                input.read()
            }
            fail("Expected SecurityException on corrupted header magic")
        } catch (e: Exception) {
            assertTrue(e is SecurityException || e is GeneralSecurityException)
        }
    }

    @Test
    fun testWrongKeyFailsDecryption() {
        val keyA = generateRandomKey()
        val keyB = generateRandomKey()
        val originalData = "Super secret data encrypted under key A".toByteArray()

        val encryptedFile = tempFolder.newFile("test_wrong_key.enc")
        ByteArrayInputStream(originalData).use { input ->
            cipherStream.encryptStreamToFile(input, encryptedFile, keyA)
        }

        // Attempt decrypt with key B
        try {
            cipherStream.getDecryptedStream(encryptedFile, keyB).use { input ->
                val buf = ByteArray(1024)
                while (input.read(buf) != -1) {
                    // read
                }
            }
            fail("Decryption with wrong key must throw an authentication exception")
        } catch (e: Exception) {
            assertTrue(
                "Exception must be security related: ${e.javaClass.name}",
                e is GeneralSecurityException || e is SecurityException || e.cause is GeneralSecurityException
            )
        }
    }
}
