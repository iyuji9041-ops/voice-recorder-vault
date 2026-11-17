package com.my.seekapplication

import android.content.Context
import android.util.Log
import com.my.seekapplication.crypto.SafeBase64
import com.my.seekapplication.crypto.VaultKeyManager
import com.my.seekapplication.data.VaultMigrationHelper
import com.my.seekapplication.data.VaultRepository
import com.my.seekapplication.data.VaultSession
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * VaultSecurityManager: Central security facade for authentication, session management,
 * and encrypted private vault operations.
 *
 * Security Enhancements:
 * 1. Storage is strictly in private app sandbox (filesDir/vault/real and filesDir/vault/decoy).
 * 2. Public Documents storage and plaintext .vault_config are eliminated.
 * 3. Master Key is dual-wrapped with Android Keystore AES-256 and PBKDF2-HMAC-SHA256 (600,000 iterations).
 * 4. Fake/Decoy credential opens an isolated, independent decoy repository with constant-time verification.
 * 5. Automatic wipe of application cache upon locking.
 */
object VaultSecurityManager {

    private const val TAG = "VaultSecurityManager"
    private const val PREFS_NAME = "VaultSecurityPrefs"

    private const val KEY_LOCK_TYPE = "selected_lock_type"
    private const val KEY_HOLD_DURATION = "hold_duration_seconds"
    private const val KEY_DOOR_HASH = "door_passcode_hash"
    private const val KEY_DOOR_SALT = "door_passcode_salt"
    private const val KEY_SEC_QUESTION = "security_question"
    private const val KEY_SEC_ANSWER_HASH = "security_answer_hash"
    private const val KEY_SEC_ANSWER_SALT = "security_answer_salt"

    private const val PBKDF2_AUTH_ITERATIONS = 600_000
    private const val KEY_LENGTH = 256

    private val secureRandom = SecureRandom()

    enum class LockType {
        PIN,
        PASSWORD,
        PATTERN
    }

    enum class PasswordCheckResult {
        REAL,
        DECOY,
        INVALID
    }

    private fun getKeyManager(context: Context): VaultKeyManager = VaultKeyManager(context)

    // ---------------------------------------------------------------------------------------------
    // HOLD DURATION SETTING (5s, 7s, 10s, 15s)
    // ---------------------------------------------------------------------------------------------

    fun getHoldDurationSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HOLD_DURATION, 5)
    }

    fun setHoldDurationSeconds(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_HOLD_DURATION, seconds).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // LOCK TYPE
    // ---------------------------------------------------------------------------------------------

    fun getLockType(context: Context): LockType {
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
    }

    // ---------------------------------------------------------------------------------------------
    // VAULT PASSWORDS & UNLOCKING
    // ---------------------------------------------------------------------------------------------

    fun hasPassword(context: Context): Boolean {
        return getKeyManager(context).isVaultConfigured()
    }

    fun hasDecoyPassword(context: Context): Boolean {
        return getKeyManager(context).hasDecoyConfigured()
    }

    /**
     * Configures real or decoy password, generating or re-wrapping the master key.
     */
    fun setPassword(context: Context, password: String, isReal: Boolean) {
        val clean = password.trim()
        val keyManager = getKeyManager(context)
        val existingMasterKey = if (VaultSession.isUnlocked && VaultSession.isDecoy == !isReal) {
            VaultSession.repository.masterKey
        } else null

        val masterKey = keyManager.setPassword(clean, isReal, existingMasterKey)

        // Open session for the configured vault
        val vaultSubDir = if (isReal) "vault/real" else "vault/decoy"
        val vaultDir = File(context.filesDir, vaultSubDir)
        val repo = VaultRepository(vaultDir, masterKey, isDecoy = !isReal)
        VaultSession.open(repo)
    }

    /**
     * Authenticates user entered passphrase.
     * Evaluates real and decoy through symmetric operations and opens VaultSession on success.
     */
    fun checkPassword(context: Context, entered: String): PasswordCheckResult {
        val clean = entered.trim()
        val keyManager = getKeyManager(context)

        return when (val result = keyManager.unlock(clean)) {
            is VaultKeyManager.UnlockResult.Success -> {
                val vaultSubDir = if (result.isDecoy) "vault/decoy" else "vault/real"
                val vaultDir = File(context.filesDir, vaultSubDir)
                val repo = VaultRepository(vaultDir, result.masterKey, isDecoy = result.isDecoy)
                VaultSession.open(repo)

                // Run legacy migration if this is real vault
                if (!result.isDecoy && VaultMigrationHelper.hasLegacyData(context)) {
                    VaultMigrationHelper.migrateAllLegacyFiles(context, repo)
                }

                if (result.isDecoy) PasswordCheckResult.DECOY else PasswordCheckResult.REAL
            }
            is VaultKeyManager.UnlockResult.InvalidCredential -> PasswordCheckResult.INVALID
            is VaultKeyManager.UnlockResult.Error -> {
                Log.e(TAG, "Unlock error: ${result.message}")
                PasswordCheckResult.INVALID
            }
        }
    }

    /**
     * Locks vault, clearing active session master key from RAM and wiping cache.
     */
    fun lockVault(context: Context) {
        VaultSession.lock(context)
    }

    // ---------------------------------------------------------------------------------------------
    // DOOR ENTRANCE PASSCODE (Layer 1 of Triple Protection)
    // ---------------------------------------------------------------------------------------------

    private fun hashWithSalt(input: String, salt: ByteArray): String {
        val spec = PBEKeySpec(input.toCharArray(), salt, PBKDF2_AUTH_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return SafeBase64.encode(hash)
    }

    fun hasDoorPasscode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_DOOR_HASH, null).isNullOrEmpty()
    }

    fun setDoorPasscode(context: Context, passcode: String) {
        val clean = passcode.trim()
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val hash = hashWithSalt(clean, salt)
        val saltBase64 = SafeBase64.encode(salt)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DOOR_HASH, hash)
            .putString(KEY_DOOR_SALT, saltBase64)
            .apply()
    }

    fun checkDoorPasscode(context: Context, entered: String): Boolean {
        val clean = entered.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_DOOR_HASH, null) ?: return true
        val saltStr = prefs.getString(KEY_DOOR_SALT, null) ?: return true
        val salt = SafeBase64.decode(saltStr)
        return hashWithSalt(clean, salt) == hash
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD RECOVERY / RESET (Security Question & Answer)
    // ---------------------------------------------------------------------------------------------

    fun hasSecurityQuestion(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_SEC_QUESTION, null).isNullOrEmpty() &&
                !prefs.getString(KEY_SEC_ANSWER_HASH, null).isNullOrEmpty()
    }

    fun getSecurityQuestion(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SEC_QUESTION, "What is your secret recovery keyword?")
            ?: "What is your secret recovery keyword?"
    }

    fun setSecurityRecovery(context: Context, question: String, answer: String) {
        val cleanQuestion = question.trim()
        val cleanAnswer = answer.trim().lowercase()
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val hash = hashWithSalt(cleanAnswer, salt)
        val saltBase64 = SafeBase64.encode(salt)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SEC_QUESTION, cleanQuestion)
            .putString(KEY_SEC_ANSWER_HASH, hash)
            .putString(KEY_SEC_ANSWER_SALT, saltBase64)
            .apply()
    }

    fun verifySecurityAnswer(context: Context, enteredAnswer: String): Boolean {
        val clean = enteredAnswer.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_SEC_ANSWER_HASH, null) ?: return false
        val saltStr = prefs.getString(KEY_SEC_ANSWER_SALT, null) ?: return false
        val salt = SafeBase64.decode(saltStr)
        return hashWithSalt(clean, salt) == hash
    }

    // ---------------------------------------------------------------------------------------------
    // COMPATIBILITY HELPERS FOR UI & ACTIVITIES
    // ---------------------------------------------------------------------------------------------

    fun getActiveRepository(): VaultRepository? = VaultSession.getRepositoryOrNull()

    fun migrateLegacyDataIfNeeded(context: Context) {
        // Will be triggered when user unlocks or logs in
    }

    fun restoreConfigIfCleared(context: Context) {
        // Obsolete: public .vault_config is removed. Private config in filesDir is canonical.
    }
}
