package com.my.seekapplication.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for VaultKeyManager:
 * 1. PBKDF2 Key Derivation & Dual-factor key wrapping.
 * 2. Unlocking with correct Real PIN.
 * 3. Decoy vault isolation and distinct master key generation.
 * 4. Invalid credential rejection.
 * 5. Re-wrapping master key on PIN change.
 */
class VaultKeyManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * In-memory mock keystore provider for unit testing without Android OS KeyStore daemon.
     */
    class TestKeystoreProvider : VaultKeyManager.KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun getOrCreateKey(alias: String): SecretKey {
            return keys.getOrPut(alias) {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                keyGen.generateKey()
            }
        }

        override fun hasKey(alias: String): Boolean = keys.containsKey(alias)

        override fun deleteKey(alias: String) {
            keys.remove(alias)
        }
    }

    private fun createKeyManager(iterations: Int = 5000): VaultKeyManager {
        return VaultKeyManager(
            keystoreProvider = TestKeystoreProvider(),
            baseDir = tempFolder.root,
            pbkdf2Iterations = iterations
        )
    }

    @Test
    fun testSetAndUnlockRealPassword() {
        val manager = createKeyManager()
        assertFalse(manager.isVaultConfigured())

        val realMasterKey = manager.setPassword("my_secret_pin_1234", isReal = true)
        assertNotNull(realMasterKey)
        assertEquals(32, realMasterKey.encoded.size)
        assertTrue(manager.isVaultConfigured())

        // Unlock with correct PIN
        val result = manager.unlock("my_secret_pin_1234")
        assertTrue(result is VaultKeyManager.UnlockResult.Success)
        val success = result as VaultKeyManager.UnlockResult.Success
        assertFalse(success.isDecoy)
        assertArrayEquals(realMasterKey.encoded, success.masterKey.encoded)
    }

    @Test
    fun testRealAndDecoyVaultIsolation() {
        val manager = createKeyManager()

        val realKey = manager.setPassword("real_password_1111", isReal = true)
        val decoyKey = manager.setPassword("decoy_password_2222", isReal = false)

        assertTrue(manager.hasDecoyConfigured())
        // Decoy key and Real key must be completely independent
        assertFalse(realKey.encoded.contentEquals(decoyKey.encoded))

        // Unlock real
        val realResult = manager.unlock("real_password_1111")
        assertTrue(realResult is VaultKeyManager.UnlockResult.Success)
        val realSuccess = realResult as VaultKeyManager.UnlockResult.Success
        assertFalse(realSuccess.isDecoy)
        assertArrayEquals(realKey.encoded, realSuccess.masterKey.encoded)

        // Unlock decoy
        val decoyResult = manager.unlock("decoy_password_2222")
        assertTrue(decoyResult is VaultKeyManager.UnlockResult.Success)
        val decoySuccess = decoyResult as VaultKeyManager.UnlockResult.Success
        assertTrue(decoySuccess.isDecoy)
        assertArrayEquals(decoyKey.encoded, decoySuccess.masterKey.encoded)
    }

    @Test
    fun testWrongPasswordReturnsInvalidCredential() {
        val manager = createKeyManager()
        manager.setPassword("correct_pin_9876", isReal = true)

        val result = manager.unlock("wrong_pin_0000")
        assertTrue("Expected InvalidCredential on wrong PIN", result is VaultKeyManager.UnlockResult.InvalidCredential)
    }

    @Test
    fun testReWrapPreservesExistingMasterKeyOnPasswordChange() {
        val manager = createKeyManager()
        val originalMasterKey = manager.setPassword("initial_pin_1234", isReal = true)

        // Change password while preserving existing master key
        val rewrappedKey = manager.setPassword("new_pin_5678", isReal = true, existingMasterKey = originalMasterKey)
        assertArrayEquals(originalMasterKey.encoded, rewrappedKey.encoded)

        // Verify old PIN no longer unlocks
        val oldUnlock = manager.unlock("initial_pin_1234")
        assertTrue(oldUnlock is VaultKeyManager.UnlockResult.InvalidCredential)

        // Verify new PIN unlocks same master key
        val newUnlock = manager.unlock("new_pin_5678")
        assertTrue(newUnlock is VaultKeyManager.UnlockResult.Success)
        val success = newUnlock as VaultKeyManager.UnlockResult.Success
        assertArrayEquals(originalMasterKey.encoded, success.masterKey.encoded)
    }
}
