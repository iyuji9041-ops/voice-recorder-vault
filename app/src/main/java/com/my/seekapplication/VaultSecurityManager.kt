package com.my.seekapplication

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject

/**
 * VaultSecurityManager: Handles security, encryption, and data protection.
 *
 * Enhancements:
 * 1. Persistent Storage that survives "Clear Data": Vault files and encrypted config are stored
 *    in Documents/.secure_vault_system with a .nomedia file, preventing data loss when app cache/data is cleared.
 * 2. Password Reset / Recovery: Security question & answer verification allowing real and fake password resets.
 * 3. Triple Protection: Layer 1 Door Passcode, Layer 2 Slider, Layer 3 Real/Decoy Vault Key.
 * 4. Configurable Secret Hold Duration (default 5s, adjustable in settings).
 */
object VaultSecurityManager {

    private const val TAG = "VaultSecurityManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "MySecureVaultMasterKey"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val PREFS_NAME = "VaultSecurityPrefs"

    // Keys in private SharedPreferences
    private const val KEY_REAL_HASH = "real_hash"
    private const val KEY_REAL_SALT = "real_salt"
    private const val KEY_FAKE_HASH = "fake_hash"
    private const val KEY_FAKE_SALT = "fake_salt"
    private const val KEY_ENCRYPTED_TRIGGER = "enc_trigger"
    private const val KEY_TRIGGER_IV = "trigger_iv"
    private const val KEY_MIGRATED = "is_legacy_migrated"
    private const val KEY_LOCK_TYPE = "selected_lock_type"
    private const val KEY_DOOR_HASH = "door_passcode_hash"
    private const val KEY_DOOR_SALT = "door_passcode_salt"
    private const val KEY_SEC_QUESTION = "security_question"
    private const val KEY_SEC_ANSWER_HASH = "security_answer_hash"
    private const val KEY_SEC_ANSWER_SALT = "security_answer_salt"
    private const val KEY_HOLD_DURATION = "hold_duration_seconds"

    enum class LockType {
        PIN,
        PASSWORD,
        PATTERN
    }

    init {
        initKeyStoreKey()
    }

    private fun initKeyStoreKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_LENGTH)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
                Log.d(TAG, "Initialized new AES-256 KeyStore key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Android KeyStore key: ${e.message}", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    // ---------------------------------------------------------------------------------------------
    // HOLD DURATION SETTING (5s, 7s, 10s, 15s)
    // ---------------------------------------------------------------------------------------------

    fun getHoldDurationSeconds(context: Context): Int {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HOLD_DURATION, 5)
    }

    fun setHoldDurationSeconds(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_HOLD_DURATION, seconds).apply()
        syncConfigToPersistentStorage(context)
    }

    // ---------------------------------------------------------------------------------------------
    // LOCK TYPE
    // ---------------------------------------------------------------------------------------------

    fun getLockType(context: Context): LockType {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_LOCK_TYPE, LockType.PIN.name) ?: LockType.PIN.name
        return try {
            LockType.valueOf(name)
        } catch (e: Exception) {
            LockType.PIN
        }
    }

    fun setLockType(context: Context, lockType: LockType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCK_TYPE, lockType.name).apply()
        syncConfigToPersistentStorage(context)
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD HASHING (PBKDF2WithHmacSHA256 with Salt)
    // ---------------------------------------------------------------------------------------------

    private fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }

    private fun hashWithSalt(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun setPassword(context: Context, password: String, isReal: Boolean) {
        val cleanPassword = password.trim()
        val salt = generateSalt()
        val hash = hashWithSalt(cleanPassword, salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (isReal) {
                putString(KEY_REAL_HASH, hash)
                putString(KEY_REAL_SALT, saltBase64)
            } else {
                putString(KEY_FAKE_HASH, hash)
                putString(KEY_FAKE_SALT, saltBase64)
            }
            apply()
        }
        syncConfigToPersistentStorage(context)
    }

    fun hasPassword(context: Context): Boolean {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_REAL_HASH, null).isNullOrEmpty()
    }

    enum class PasswordCheckResult {
        REAL,
        DECOY,
        INVALID
    }

    fun checkPassword(context: Context, entered: String): PasswordCheckResult {
        restoreConfigIfCleared(context)
        val clean = entered.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val realHash = prefs.getString(KEY_REAL_HASH, null)
        val realSaltStr = prefs.getString(KEY_REAL_SALT, null)
        if (realHash != null && realSaltStr != null) {
            val salt = Base64.decode(realSaltStr, Base64.NO_WRAP)
            if (hashWithSalt(clean, salt) == realHash) {
                return PasswordCheckResult.REAL
            }
        }

        val fakeHash = prefs.getString(KEY_FAKE_HASH, null)
        val fakeSaltStr = prefs.getString(KEY_FAKE_SALT, null)
        if (fakeHash != null && fakeSaltStr != null) {
            val salt = Base64.decode(fakeSaltStr, Base64.NO_WRAP)
            if (hashWithSalt(clean, salt) == fakeHash) {
                return PasswordCheckResult.DECOY
            }
        }

        return PasswordCheckResult.INVALID
    }

    // ---------------------------------------------------------------------------------------------
    // DOOR ENTRANCE PASSCODE (Layer 1 of Triple Protection)
    // ---------------------------------------------------------------------------------------------

    fun hasDoorPasscode(context: Context): Boolean {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_DOOR_HASH, null).isNullOrEmpty()
    }

    fun setDoorPasscode(context: Context, passcode: String) {
        val clean = passcode.trim()
        val salt = generateSalt()
        val hash = hashWithSalt(clean, salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DOOR_HASH, hash)
            .putString(KEY_DOOR_SALT, saltBase64)
            .apply()
        syncConfigToPersistentStorage(context)
    }

    fun checkDoorPasscode(context: Context, entered: String): Boolean {
        restoreConfigIfCleared(context)
        val clean = entered.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_DOOR_HASH, null) ?: return true // Default pass if not set
        val saltStr = prefs.getString(KEY_DOOR_SALT, null) ?: return true
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        return hashWithSalt(clean, salt) == hash
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD RECOVERY / RESET (Security Question & Answer)
    // ---------------------------------------------------------------------------------------------

    fun hasSecurityQuestion(context: Context): Boolean {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_SEC_QUESTION, null).isNullOrEmpty() &&
                !prefs.getString(KEY_SEC_ANSWER_HASH, null).isNullOrEmpty()
    }

    fun getSecurityQuestion(context: Context): String {
        restoreConfigIfCleared(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SEC_QUESTION, "What is your secret recovery keyword?")
            ?: "What is your secret recovery keyword?"
    }

    fun setSecurityRecovery(context: Context, question: String, answer: String) {
        val cleanQuestion = question.trim()
        val cleanAnswer = answer.trim().lowercase()
        val salt = generateSalt()
        val hash = hashWithSalt(cleanAnswer, salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SEC_QUESTION, cleanQuestion)
            .putString(KEY_SEC_ANSWER_HASH, hash)
            .putString(KEY_SEC_ANSWER_SALT, saltBase64)
            .apply()
        syncConfigToPersistentStorage(context)
    }

    fun verifySecurityAnswer(context: Context, enteredAnswer: String): Boolean {
        restoreConfigIfCleared(context)
        val clean = enteredAnswer.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_SEC_ANSWER_HASH, null) ?: return false
        val saltStr = prefs.getString(KEY_SEC_ANSWER_SALT, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        return hashWithSalt(clean, salt) == hash
    }

    // ---------------------------------------------------------------------------------------------
    // PERSISTENT STORAGE (Survives "Clear Data")
    // ---------------------------------------------------------------------------------------------

    /**
     * Primary persistent vault folder in external Documents directory so that
     * clearing app data in Android Settings NEVER wipes hidden user photos.
     */
    fun getPersistentVaultDir(context: Context): File {
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val persistentDir = File(publicDocs, ".secure_vault_system")
        if (!persistentDir.exists()) persistentDir.mkdirs()

        // Place .nomedia file so Android Gallery / Photos doesn't index encrypted vault files
        val noMedia = File(persistentDir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (ignored: Exception) {}
        }
        return persistentDir
    }

    fun getCategoryDir(context: Context, category: String): File {
        val catDir = File(getPersistentVaultDir(context), category)
        if (!catDir.exists()) catDir.mkdirs()

        val noMedia = File(catDir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (ignored: Exception) {}
        }
        return catDir
    }

    /**
     * Synchronizes hashes, lock type, and security question to a hidden persistent config file.
     */
    private fun syncConfigToPersistentStorage(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put(KEY_REAL_HASH, prefs.getString(KEY_REAL_HASH, ""))
                put(KEY_REAL_SALT, prefs.getString(KEY_REAL_SALT, ""))
                put(KEY_FAKE_HASH, prefs.getString(KEY_FAKE_HASH, ""))
                put(KEY_FAKE_SALT, prefs.getString(KEY_FAKE_SALT, ""))
                put(KEY_DOOR_HASH, prefs.getString(KEY_DOOR_HASH, ""))
                put(KEY_DOOR_SALT, prefs.getString(KEY_DOOR_SALT, ""))
                put(KEY_LOCK_TYPE, prefs.getString(KEY_LOCK_TYPE, LockType.PIN.name))
                put(KEY_SEC_QUESTION, prefs.getString(KEY_SEC_QUESTION, ""))
                put(KEY_SEC_ANSWER_HASH, prefs.getString(KEY_SEC_ANSWER_HASH, ""))
                put(KEY_SEC_ANSWER_SALT, prefs.getString(KEY_SEC_ANSWER_SALT, ""))
                put(KEY_HOLD_DURATION, prefs.getInt(KEY_HOLD_DURATION, 5))
            }

            val configFile = File(getPersistentVaultDir(context), ".vault_config")
            configFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing config to persistent storage: ${e.message}")
        }
    }

    /**
     * Restores hashes, lock types, and passwords from persistent storage if app data was cleared.
     */
    fun restoreConfigIfCleared(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getString(KEY_REAL_HASH, null).isNullOrEmpty()) {
            return // Config is already intact
        }

        try {
            val configFile = File(getPersistentVaultDir(context), ".vault_config")
            if (configFile.exists()) {
                val json = JSONObject(configFile.readText())
                prefs.edit().apply {
                    putString(KEY_REAL_HASH, json.optString(KEY_REAL_HASH, ""))
                    putString(KEY_REAL_SALT, json.optString(KEY_REAL_SALT, ""))
                    putString(KEY_FAKE_HASH, json.optString(KEY_FAKE_HASH, ""))
                    putString(KEY_FAKE_SALT, json.optString(KEY_FAKE_SALT, ""))
                    putString(KEY_DOOR_HASH, json.optString(KEY_DOOR_HASH, ""))
                    putString(KEY_DOOR_SALT, json.optString(KEY_DOOR_SALT, ""))
                    putString(KEY_LOCK_TYPE, json.optString(KEY_LOCK_TYPE, LockType.PIN.name))
                    putString(KEY_SEC_QUESTION, json.optString(KEY_SEC_QUESTION, ""))
                    putString(KEY_SEC_ANSWER_HASH, json.optString(KEY_SEC_ANSWER_HASH, ""))
                    putString(KEY_SEC_ANSWER_SALT, json.optString(KEY_SEC_ANSWER_SALT, ""))
                    putInt(KEY_HOLD_DURATION, json.optInt(KEY_HOLD_DURATION, 5))
                    apply()
                }
                Log.d(TAG, "Vault config successfully restored from persistent storage after Clear Data.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed restoring config from persistent storage: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AES-256-GCM FILE ENCRYPTION / DECRYPTION
    // ---------------------------------------------------------------------------------------------

    fun encryptStreamToFile(inputStream: InputStream, targetFile: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv

        FileOutputStream(targetFile).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    cos.write(buffer, 0, bytesRead)
                }
                cos.flush()
            }
        }
    }

    fun decryptFileToStream(encryptedFile: File, targetOutputStream: OutputStream) {
        FileInputStream(encryptedFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val readIv = fis.read(iv)
            if (readIv != GCM_IV_LENGTH) {
                throw IllegalStateException("Invalid encrypted file format: missing IV header")
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            CipherInputStream(fis, cipher).use { cis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (cis.read(buffer).also { bytesRead = it } != -1) {
                    targetOutputStream.write(buffer, 0, bytesRead)
                }
                targetOutputStream.flush()
            }
        }
    }

    fun decryptFileToBytes(encryptedFile: File): ByteArray {
        val tempBytes = java.io.ByteArrayOutputStream()
        decryptFileToStream(encryptedFile, tempBytes)
        return tempBytes.toByteArray()
    }

    // ---------------------------------------------------------------------------------------------
    // LEGACY MIGRATION
    // ---------------------------------------------------------------------------------------------

    fun migrateLegacyDataIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        try {
            // Check if older internal files exist and copy them to persistent folder
            val internalVault = File(context.filesDir, "vault_encrypted")
            if (internalVault.exists()) {
                val categories = listOf("Photos", "Videos", "Audios")
                for (cat in categories) {
                    val oldCat = File(internalVault, cat)
                    if (oldCat.exists()) {
                        val persistentCat = getCategoryDir(context, cat)
                        oldCat.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith(".")) {
                                val dest = File(persistentCat, file.name)
                                file.copyTo(dest, overwrite = true)
                                file.delete()
                            }
                        }
                    }
                }
            }

            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            syncConfigToPersistentStorage(context)
            Log.d(TAG, "Legacy migration completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Migration encountered an issue: ${e.message}", e)
        }
    }
}
