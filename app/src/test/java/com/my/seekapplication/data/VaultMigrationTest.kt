package com.my.seekapplication.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Unit tests for legacy vault migration logic.
 */
class VaultMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val secureRandom = SecureRandom()

    private fun generateLegacyKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun createLegacyEncryptedFile(file: File, plaintext: ByteArray, key: SecretKey) {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        FileOutputStream(file).use { fos ->
            fos.write(iv)
            fos.write(ciphertext)
        }
    }

    @Test
    fun testLegacyFileDecryptionRoundTrip() {
        val legacyKey = generateLegacyKey()
        val originalData = "Legacy vault secret photo/recording byte content".toByteArray(Charsets.UTF_8)
        val legacyFile = tempFolder.newFile("photo_sample.jpg.enc")

        createLegacyEncryptedFile(legacyFile, originalData, legacyKey)

        val decrypted = VaultMigrationHelper.decryptLegacyFile(legacyFile, legacyKey)
        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun testLegacyFileTamperThrowsException() {
        val legacyKey = generateLegacyKey()
        val originalData = "Sensitive legacy document".toByteArray()
        val legacyFile = tempFolder.newFile("doc.enc")

        createLegacyEncryptedFile(legacyFile, originalData, legacyKey)

        // Corrupt a byte in the legacy ciphertext
        val fileBytes = legacyFile.readBytes()
        fileBytes[fileBytes.size - 1] = (fileBytes[fileBytes.size - 1].toInt() xor 0xFF).toByte()
        legacyFile.writeBytes(fileBytes)

        try {
            VaultMigrationHelper.decryptLegacyFile(legacyFile, legacyKey)
            fail("Expected exception when decrypting corrupted legacy file")
        } catch (e: Exception) {
            assertTrue(e is java.security.GeneralSecurityException || e.cause is java.security.GeneralSecurityException || e is java.io.IOException)
        }
    }
}
