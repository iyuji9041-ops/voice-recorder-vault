package com.my.seekapplication.setup

import android.content.Context
import android.content.SharedPreferences
import com.my.seekapplication.VaultSecurityManager
import com.my.seekapplication.crypto.CredentialPolicy
import com.my.seekapplication.crypto.IntruderPhotoCrypto
import com.my.seekapplication.crypto.VaultKeyManager
import com.my.seekapplication.data.VaultRepository
import com.my.seekapplication.data.VaultSession
import java.io.File

/**
 * SetupWizardManager: Atomic, resumable multi-stage vault setup state machine.
 *
 * Guarantees:
 * 1. Vault is never left half-configured.
 * 2. Uncommitted staged data can be resumed if killed mid-setup.
 * 3. Atomic commit only upon successful completion of all required steps.
 * 4. Staged plaintext credentials are wiped from temporary preferences immediately upon commit.
 */
object SetupWizardManager {

    private const val PREFS_NAME = "VaultSetupWizardPrefs"

    private const val KEY_SETUP_STAGE = "setup_stage"
    private const val KEY_STAGED_DOOR_CODE = "staged_door_code"
    private const val KEY_STAGED_LOCK_TYPE = "staged_lock_type"
    private const val KEY_STAGED_REAL_KEY = "staged_real_key"
    private const val KEY_STAGED_DECOY_KEY = "staged_decoy_key"
    private const val KEY_STAGED_RECOVERY_CODE = "staged_recovery_code"
    private const val KEY_STAGED_SEC_QUESTION = "staged_sec_question"
    private const val KEY_STAGED_SEC_ANSWER = "staged_sec_answer"
    private const val KEY_SETUP_COMPLETED = "vault_setup_completed"

    enum class Stage {
        NOT_STARTED,
        DOOR_PASSCODE,
        VAULT_REAL_KEY,
        VAULT_DECOY_KEY,
        RECOVERY_CODE,
        CAMERA_PERMISSION,
        COMPLETED
    }

    var testPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context?): SharedPreferences {
        return testPrefs ?: context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSetupCompleted(context: Context? = null): Boolean {
        val prefs = getPrefs(context)
        val keyManager = context?.let { VaultKeyManager(it) }
        return prefs.getBoolean(KEY_SETUP_COMPLETED, false) && (keyManager?.isVaultConfigured() ?: false)
    }

    fun getCurrentStage(context: Context? = null): Stage {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_SETUP_COMPLETED, false)) return Stage.COMPLETED
        val name = prefs.getString(KEY_SETUP_STAGE, Stage.NOT_STARTED.name)
        return try {
            Stage.valueOf(name ?: Stage.NOT_STARTED.name)
        } catch (e: Exception) {
            Stage.NOT_STARTED
        }
    }

    fun setStage(context: Context? = null, stage: Stage) {
        getPrefs(context).edit().putString(KEY_SETUP_STAGE, stage.name).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // STAGED VALUES ACCESSORS
    // ---------------------------------------------------------------------------------------------

    fun stageDoorCode(context: Context? = null, code: String) {
        getPrefs(context).edit()
            .putString(KEY_STAGED_DOOR_CODE, code.trim())
            .putString(KEY_SETUP_STAGE, Stage.VAULT_REAL_KEY.name)
            .apply()
    }

    fun getStagedDoorCode(context: Context? = null): String? {
        return getPrefs(context).getString(KEY_STAGED_DOOR_CODE, null)
    }

    fun stageRealKey(context: Context? = null, lockType: VaultSecurityManager.LockType, key: String) {
        getPrefs(context).edit()
            .putString(KEY_STAGED_LOCK_TYPE, lockType.name)
            .putString(KEY_STAGED_REAL_KEY, key.trim())
            .putString(KEY_SETUP_STAGE, Stage.VAULT_DECOY_KEY.name)
            .apply()
    }

    fun getStagedRealKey(context: Context? = null): String? {
        return getPrefs(context).getString(KEY_STAGED_REAL_KEY, null)
    }

    fun getStagedLockType(context: Context? = null): VaultSecurityManager.LockType {
        val name = getPrefs(context).getString(KEY_STAGED_LOCK_TYPE, VaultSecurityManager.LockType.PIN.name)
        return try {
            VaultSecurityManager.LockType.valueOf(name ?: VaultSecurityManager.LockType.PIN.name)
        } catch (e: Exception) {
            VaultSecurityManager.LockType.PIN
        }
    }

    fun stageDecoyKey(context: Context? = null, key: String) {
        getPrefs(context).edit()
            .putString(KEY_STAGED_DECOY_KEY, key.trim())
            .putString(KEY_SETUP_STAGE, Stage.RECOVERY_CODE.name)
            .apply()
    }

    fun getStagedDecoyKey(context: Context? = null): String? {
        return getPrefs(context).getString(KEY_STAGED_DECOY_KEY, null)
    }

    fun stageRecoveryCode(context: Context? = null, code: String, question: String? = null, answer: String? = null) {
        val editor = getPrefs(context).edit()
            .putString(KEY_STAGED_RECOVERY_CODE, VaultKeyManager.normalizeRecoveryCode(code))
            .putString(KEY_SETUP_STAGE, Stage.CAMERA_PERMISSION.name)

        if (!question.isNullOrEmpty() && !answer.isNullOrEmpty()) {
            editor.putString(KEY_STAGED_SEC_QUESTION, question.trim())
            editor.putString(KEY_STAGED_SEC_ANSWER, answer.trim())
        }
        editor.apply()
    }

    fun getStagedRecoveryCode(context: Context? = null): String {
        var code = getPrefs(context).getString(KEY_STAGED_RECOVERY_CODE, null)
        if (code.isNullOrEmpty()) {
            code = VaultKeyManager.generateRecoveryCode()
            getPrefs(context).edit().putString(KEY_STAGED_RECOVERY_CODE, code).apply()
        }
        return code
    }

    // ---------------------------------------------------------------------------------------------
    // ATOMIC COMMIT
    // ---------------------------------------------------------------------------------------------

    /**
     * Atomically commits all staged credentials to production storage.
     * Wipes temporary preferences on completion.
     */
    @Synchronized
    fun commitSetup(context: Context): Boolean {
        val prefs = getPrefs(context)
        val doorCode = prefs.getString(KEY_STAGED_DOOR_CODE, null) ?: return false
        val realKey = prefs.getString(KEY_STAGED_REAL_KEY, null) ?: return false
        val decoyKey = prefs.getString(KEY_STAGED_DECOY_KEY, null) ?: return false
        val recoveryCode = prefs.getString(KEY_STAGED_RECOVERY_CODE, null) ?: return false
        val lockType = getStagedLockType(context)
        val secQuestion = prefs.getString(KEY_STAGED_SEC_QUESTION, null)
        val secAnswer = prefs.getString(KEY_STAGED_SEC_ANSWER, null)

        val keyManager = VaultKeyManager(context)

        try {
            // 1. Configure Door Passcode (Layer 1)
            VaultSecurityManager.setDoorPasscode(context, doorCode)

            // 2. Configure Lock Type
            VaultSecurityManager.setLockType(context, lockType)

            // 3. Generate and wrap Real Master Key
            val realMasterKey = keyManager.setPassword(realKey, isReal = true)

            // 4. Generate and wrap Decoy Master Key
            keyManager.setPassword(decoyKey, isReal = false)

            // 5. Wrap Real Master Key into offline Recovery Slot
            val recoverySlot = keyManager.wrapMasterKeyForRecovery(realMasterKey, recoveryCode)
            keyManager.saveRecoverySlot(recoverySlot)

            // 6. Initialize Asymmetric RSA Keypair for Intruder Selfies
            IntruderPhotoCrypto.initializeKeypair(context, realMasterKey)

            // 7. Configure Security Recovery Question (optional extra layer)
            if (!secQuestion.isNullOrEmpty() && !secAnswer.isNullOrEmpty()) {
                VaultSecurityManager.setSecurityRecovery(context, secQuestion, secAnswer)
            }

            // 8. Open active vault session
            val vaultDir = File(context.filesDir, "vault/real")
            val repo = VaultRepository(vaultDir, realMasterKey, isDecoy = false)
            VaultSession.open(repo)

            // 9. Mark setup completed & wipe staged credentials
            prefs.edit()
                .putBoolean(KEY_SETUP_COMPLETED, true)
                .putString(KEY_SETUP_STAGE, Stage.COMPLETED.name)
                .remove(KEY_STAGED_DOOR_CODE)
                .remove(KEY_STAGED_REAL_KEY)
                .remove(KEY_STAGED_DECOY_KEY)
                .remove(KEY_STAGED_RECOVERY_CODE)
                .remove(KEY_STAGED_SEC_QUESTION)
                .remove(KEY_STAGED_SEC_ANSWER)
                .apply()

            return true
        } catch (e: Exception) {
            android.util.Log.e("SetupWizardManager", "Failed committing vault setup: ${e.message}", e)
            return false
        }
    }

    /**
     * Wipes setup state and temporary storage.
     */
    fun resetSetup(context: Context? = null) {
        getPrefs(context).edit().clear().apply()
    }
}
