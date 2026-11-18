package com.my.seekapplication.setup

import android.content.SharedPreferences
import com.my.seekapplication.VaultSecurityManager
import com.my.seekapplication.crypto.VaultKeyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SetupWizardManager state machine:
 * 1. Resumable stages progression.
 * 2. Staged values retention across process kill / stage resume.
 * 3. Recovery code generation and normalization.
 * 4. State reset.
 */
class SetupWizardTest {

    private class TestSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = TestEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class TestEditor(private val target: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any>()
            private val removes = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null && value != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) target.clear()
                removes.forEach { target.remove(it) }
                target.putAll(pending)
            }
        }
    }

    private lateinit var testPrefs: TestSharedPreferences

    @Before
    fun setup() {
        testPrefs = TestSharedPreferences()
        SetupWizardManager.testPrefs = testPrefs
    }

    @After
    fun tearDown() {
        SetupWizardManager.testPrefs = null
    }

    @Test
    fun testInitialStageIsNotStarted() {
        val stage = SetupWizardManager.getCurrentStage(null)
        assertEquals(SetupWizardManager.Stage.NOT_STARTED, stage)
    }

    @Test
    fun testStageDoorCodeAdvancesToRealKey() {
        SetupWizardManager.stageDoorCode(null, "123456")

        assertEquals(SetupWizardManager.Stage.VAULT_REAL_KEY, SetupWizardManager.getCurrentStage(null))
        assertEquals("123456", SetupWizardManager.getStagedDoorCode(null))
    }

    @Test
    fun testStageRealKeyAdvancesToDecoyKey() {
        SetupWizardManager.stageDoorCode(null, "123456")
        SetupWizardManager.stageRealKey(null, VaultSecurityManager.LockType.PASSWORD, "SecretRealKey!2026")

        assertEquals(SetupWizardManager.Stage.VAULT_DECOY_KEY, SetupWizardManager.getCurrentStage(null))
        assertEquals("SecretRealKey!2026", SetupWizardManager.getStagedRealKey(null))
        assertEquals(VaultSecurityManager.LockType.PASSWORD, SetupWizardManager.getStagedLockType(null))
    }

    @Test
    fun testStageDecoyKeyAdvancesToRecoveryCode() {
        SetupWizardManager.stageDoorCode(null, "123456")
        SetupWizardManager.stageRealKey(null, VaultSecurityManager.LockType.PIN, "112233")
        SetupWizardManager.stageDecoyKey(null, "998877")

        assertEquals(SetupWizardManager.Stage.RECOVERY_CODE, SetupWizardManager.getCurrentStage(null))
        assertEquals("998877", SetupWizardManager.getStagedDecoyKey(null))
    }

    @Test
    fun testRecoveryCodeGenerationAndNormalization() {
        val code = VaultKeyManager.generateRecoveryCode()
        assertNotNull(code)
        // Format XXXX-XXXX-XXXX-XXXX (19 chars with dashes)
        assertEquals(19, code.length)
        assertEquals('-', code[4])
        assertEquals('-', code[9])
        assertEquals('-', code[14])

        // Normalization strips spaces and dashes and uppercases
        val normalized = VaultKeyManager.normalizeRecoveryCode("abcd-efgh 1234-5678")
        assertEquals("ABCDEFGH12345678", normalized)
    }

    @Test
    fun testResetSetupWipesAllStagedValues() {
        SetupWizardManager.stageDoorCode(null, "123456")
        SetupWizardManager.stageRealKey(null, VaultSecurityManager.LockType.PIN, "654321")

        SetupWizardManager.resetSetup(null)
        assertEquals(SetupWizardManager.Stage.NOT_STARTED, SetupWizardManager.getCurrentStage(null))
        assertNull(SetupWizardManager.getStagedDoorCode(null))
        assertNull(SetupWizardManager.getStagedRealKey(null))
    }
}
