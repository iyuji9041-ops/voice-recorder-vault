package com.my.seekapplication.crypto

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests for IntruderPhotoCrypto:
 * 1. RSA-2048 keypair generation and asymmetric encryption.
 * 2. Asymmetric photo encryption while vault is locked (without Master Key).
 * 3. Photo decryption upon unlocking with Real Master Key.
 * 4. Wrong master key prevents private key loading.
 * 5. Tamper detection on modified ciphertext.
 * 6. Hard storage cap enforcement (max 50 photos).
 */
class IntruderPhotoCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var realMasterKey: SecretKey
    private lateinit var wrongMasterKey: SecretKey

    @Before
    fun setup() {
        IntruderPhotoCrypto.customBaseDir = tempFolder.root

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        realMasterKey = keyGen.generateKey()
        wrongMasterKey = keyGen.generateKey()
    }

    @After
    fun tearDown() {
        IntruderPhotoCrypto.customBaseDir = null
    }

    @Test
    fun testKeypairInitializationAndLoading() {
        assertNull(IntruderPhotoCrypto.loadPublicKey())
        assertNull(IntruderPhotoCrypto.loadPrivateKey(masterKey = realMasterKey))

        IntruderPhotoCrypto.initializeKeypair(masterKey = realMasterKey)
        assertTrue(IntruderPhotoCrypto.hasKeys())

        val pubKey = IntruderPhotoCrypto.loadPublicKey()
        assertNotNull("Public key must load", pubKey)

        val privKey = IntruderPhotoCrypto.loadPrivateKey(masterKey = realMasterKey)
        assertNotNull("Private key must load with correct real master key", privKey)

        val invalidPrivKey = IntruderPhotoCrypto.loadPrivateKey(masterKey = wrongMasterKey)
        assertNull("Private key must not load with wrong master key", invalidPrivKey)
    }

    @Test
    fun testEncryptPhotoWhileLockedAndDecryptWithMasterKey() {
        // Setup keypair
        IntruderPhotoCrypto.initializeKeypair(masterKey = realMasterKey)

        // Simulate intruder photo captured while vault is locked (no master key needed!)
        val mockJpeg = ByteArray(4096) { (it % 255).toByte() }
        val encryptedFile = IntruderPhotoCrypto.encryptAndSavePhoto(jpegBytes = mockJpeg)

        assertNotNull("Encrypted file must be created", encryptedFile)
        assertTrue("Encrypted file must exist on disk", encryptedFile!!.exists())
        assertTrue("Encrypted file must have .enc extension", encryptedFile.name.endsWith(".enc"))

        // Unlock vault: load private key with real master key and decrypt photo
        val privKey = IntruderPhotoCrypto.loadPrivateKey(masterKey = realMasterKey)
        assertNotNull(privKey)

        val decryptedBytes = IntruderPhotoCrypto.decryptPhoto(encryptedFile, privKey!!)
        assertNotNull("Decrypted bytes must not be null", decryptedBytes)
        assertArrayEquals("Decrypted photo must match original mock JPEG exactly", mockJpeg, decryptedBytes)
    }

    @Test
    fun testEncryptedPhotoTamperDetection() {
        IntruderPhotoCrypto.initializeKeypair(masterKey = realMasterKey)

        val mockJpeg = "Test intrusion image payload".toByteArray()
        val encryptedFile = IntruderPhotoCrypto.encryptAndSavePhoto(jpegBytes = mockJpeg)
        assertNotNull(encryptedFile)

        // Tamper with the last byte of ciphertext (auth tag corruption)
        val fileBytes = encryptedFile!!.readBytes()
        fileBytes[fileBytes.size - 1] = (fileBytes[fileBytes.size - 1] xor 0xFF.toByte())
        encryptedFile.writeBytes(fileBytes)

        val privKey = IntruderPhotoCrypto.loadPrivateKey(masterKey = realMasterKey)!!
        val decrypted = IntruderPhotoCrypto.decryptPhoto(encryptedFile, privKey)
        assertNull("Tampered file must fail authentication tag check and return null", decrypted)
    }

    @Test
    fun testStorageCapEnforcesMaximum50Photos() {
        IntruderPhotoCrypto.initializeKeypair(masterKey = realMasterKey)

        val mockJpeg = byteArrayOf(1, 2, 3)
        // Create 55 photos
        for (i in 1..55) {
            IntruderPhotoCrypto.encryptAndSavePhoto(jpegBytes = mockJpeg)
        }

        val allSelfies = IntruderPhotoCrypto.getAllEncryptedSelfies()
        assertTrue("Stored selfies count (${allSelfies.size}) must not exceed 50", allSelfies.size <= 50)
    }
}
