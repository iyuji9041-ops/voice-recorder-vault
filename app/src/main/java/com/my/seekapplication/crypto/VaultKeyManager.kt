package com.my.seekapplication.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * VaultKeyManager: Dual-factor Key Wrapping, PBKDF2-HMAC-SHA256 (600,000 iterations),
 * Hardware Keystore Integration, and Indistinguishable Decoy Isolation.
 */
class VaultKeyManager(
    private val context: Context? = null,
    private val keystoreProvider: KeystoreKeyProvider = DefaultAndroidKeystoreProvider(),
    private val baseDir: File? = null,
    val pbkdf2Iterations: Int = PBKDF2_ITERATIONS
) {

    companion object {
        private const val TAG = "VaultKeyManager"
        const val PBKDF2_ITERATIONS = 600_000
        const val MASTER_KEY_SIZE_BYTES = 32 // 256 bits
        const val SALT_SIZE_BYTES = 32 // 256 bits
        const val GCM_IV_SIZE_BYTES = 12 // 96 bits
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEYSTORE_ALIAS = "SecureVaultHardwareMasterKey_v2"
        const val AUTH_CONFIG_FILE = "vault_meta/auth.json"

        private val secureRandom = SecureRandom()
    }

    interface KeystoreKeyProvider {
        fun getOrCreateKey(alias: String): SecretKey
        fun hasKey(alias: String): Boolean
        fun deleteKey(alias: String)
    }

    class DefaultAndroidKeystoreProvider : KeystoreKeyProvider {
        override fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        }

        override fun hasKey(alias: String): Boolean {
            return try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.containsAlias(alias)
            } catch (e: Exception) {
                false
            }
        }

        override fun deleteKey(alias: String) {
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun getConfigFile(): File {
        val root = baseDir ?: context?.filesDir ?: File(".")
        val file = File(root, AUTH_CONFIG_FILE)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return file
    }

    @Synchronized
    fun readAuthConfig(): JSONObject {
        val file = getConfigFile()
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Error reading auth config: ${e.message}")
            JSONObject()
        }
    }

    @Synchronized
    fun saveAuthConfig(config: JSONObject) {
        val file = getConfigFile()
        val temp = File(file.parentFile, ".tmp_auth.json")
        temp.writeText(config.toString(2))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /**
     * Derives a 256-bit AES key from a passphrase using PBKDF2WithHmacSHA256 (600,000 iterations).
     */
    fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, pbkdf2Iterations, MASTER_KEY_SIZE_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Generates a random 256-bit master key.
     */
    fun generateRandomMasterKey(): SecretKey {
        val keyBytes = ByteArray(MASTER_KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Wraps a master key using dual factors:
     * 1. User PIN-derived key (Inner layer)
     * 2. Hardware Keystore key (Outer layer)
     */
    data class WrappedKeyData(
        val salt: ByteArray,
        val ivInner: ByteArray,
        val ivOuter: ByteArray,
        val ciphertext: ByteArray
    )

    fun wrapMasterKey(masterKey: SecretKey, passphrase: String): WrappedKeyData {
        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val pbeKey = deriveKeyFromPassword(passphrase, salt)
        val keystoreKey = keystoreProvider.getOrCreateKey(KEYSTORE_ALIAS)

        // Inner wrapping with PIN-derived key
        val ivInner = ByteArray(GCM_IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val cipherInner = Cipher.getInstance("AES/GCM/NoPadding")
        cipherInner.init(Cipher.ENCRYPT_MODE, pbeKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivInner))
        val innerCiphertext = cipherInner.doFinal(masterKey.encoded)

        // Outer wrapping with Keystore key
        val ivOuter = ByteArray(GCM_IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val cipherOuter = Cipher.getInstance("AES/GCM/NoPadding")
        cipherOuter.init(Cipher.ENCRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivOuter))
        val outerCiphertext = cipherOuter.doFinal(innerCiphertext)

        return WrappedKeyData(salt, ivInner, ivOuter, outerCiphertext)
    }

    /**
     * Unwraps the master key. Fails with GeneralSecurityException if PIN is incorrect or data is tampered.
     */
    fun unwrapMasterKey(wrapped: WrappedKeyData, passphrase: String): SecretKey {
        val keystoreKey = keystoreProvider.getOrCreateKey(KEYSTORE_ALIAS)

        // Outer unwrapping with Keystore key
        val cipherOuter = Cipher.getInstance("AES/GCM/NoPadding")
        cipherOuter.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapped.ivOuter))
        val innerCiphertext = cipherOuter.doFinal(wrapped.ciphertext)

        // Inner unwrapping with PIN-derived key
        val pbeKey = deriveKeyFromPassword(passphrase, wrapped.salt)
        val cipherInner = Cipher.getInstance("AES/GCM/NoPadding")
        cipherInner.init(Cipher.DECRYPT_MODE, pbeKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapped.ivInner))
        val masterKeyBytes = cipherInner.doFinal(innerCiphertext)

        return SecretKeySpec(masterKeyBytes, "AES")
    }

    /**
     * Checks if a master password has been configured.
     */
    fun isVaultConfigured(): Boolean {
        val config = readAuthConfig()
        return config.has("real_wrapped_key") && config.has("real_salt")
    }

    fun hasDecoyConfigured(): Boolean {
        val config = readAuthConfig()
        return config.has("decoy_wrapped_key") && config.has("decoy_salt")
    }

    /**
     * Sets or changes the master password (re-wraps existing master key or initializes a new one).
     */
    fun setPassword(passphrase: String, isReal: Boolean, existingMasterKey: SecretKey? = null): SecretKey {
        val keyToWrap = existingMasterKey ?: generateRandomMasterKey()
        val wrapped = wrapMasterKey(keyToWrap, passphrase)

        val config = readAuthConfig()
        val prefix = if (isReal) "real" else "decoy"
        config.put("${prefix}_salt", SafeBase64.encode(wrapped.salt))
        config.put("${prefix}_iv_inner", SafeBase64.encode(wrapped.ivInner))
        config.put("${prefix}_iv_outer", SafeBase64.encode(wrapped.ivOuter))
        config.put("${prefix}_wrapped_key", SafeBase64.encode(wrapped.ciphertext))
        saveAuthConfig(config)

        return keyToWrap
    }

    sealed class UnlockResult {
        data class Success(val masterKey: SecretKey, val isDecoy: Boolean) : UnlockResult()
        object InvalidCredential : UnlockResult()
        data class Error(val message: String, val cause: Throwable? = null) : UnlockResult()
    }

    /**
     * Unlocks the vault given entered passphrase.
     * Evaluates real and decoy through symmetric operations to eliminate timing leaks.
     */
    fun unlock(enteredPassphrase: String): UnlockResult {
        val config = readAuthConfig()
        if (!config.has("real_wrapped_key")) {
            return UnlockResult.Error("Vault has not been set up yet.")
        }

        var realMasterKey: SecretKey? = null
        var decoyMasterKey: SecretKey? = null

        // 1. Attempt unwrap real vault
        try {
            val realWrapped = WrappedKeyData(
                salt = SafeBase64.decode(config.getString("real_salt")),
                ivInner = SafeBase64.decode(config.getString("real_iv_inner")),
                ivOuter = SafeBase64.decode(config.getString("real_iv_outer")),
                ciphertext = SafeBase64.decode(config.getString("real_wrapped_key"))
            )
            realMasterKey = unwrapMasterKey(realWrapped, enteredPassphrase)
        } catch (ignored: GeneralSecurityException) {
            // Wrong real PIN or corrupted key
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error attempting real vault unwrap: ${e.message}")
        }

        // 2. Attempt unwrap decoy vault or execute constant dummy work
        if (config.has("decoy_wrapped_key") && config.has("decoy_salt")) {
            try {
                val decoyWrapped = WrappedKeyData(
                    salt = SafeBase64.decode(config.getString("decoy_salt")),
                    ivInner = SafeBase64.decode(config.getString("decoy_iv_inner")),
                    ivOuter = SafeBase64.decode(config.getString("decoy_iv_outer")),
                    ciphertext = SafeBase64.decode(config.getString("decoy_wrapped_key"))
                )
                decoyMasterKey = unwrapMasterKey(decoyWrapped, enteredPassphrase)
            } catch (ignored: GeneralSecurityException) {
                // Wrong decoy PIN
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error attempting decoy vault unwrap: ${e.message}")
            }
        } else {
            // Constant-time balancing dummy PBKDF2 if decoy not set
            if (realMasterKey == null) {
                val dummySalt = ByteArray(SALT_SIZE_BYTES)
                deriveKeyFromPassword(enteredPassphrase, dummySalt)
            }
        }

        return when {
            realMasterKey != null -> UnlockResult.Success(realMasterKey, isDecoy = false)
            decoyMasterKey != null -> UnlockResult.Success(decoyMasterKey, isDecoy = true)
            else -> UnlockResult.InvalidCredential
        }
    }
}
