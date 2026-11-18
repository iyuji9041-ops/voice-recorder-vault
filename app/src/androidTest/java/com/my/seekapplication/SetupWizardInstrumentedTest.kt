package com.my.seekapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.my.seekapplication.setup.SetupWizardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying the SetupWizardManager workflow on a live Android target context.
 */
@RunWith(AndroidJUnit4::class)
class SetupWizardInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("SetupWizardPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testSetupCompletionFlow() {
        assertFalse(SetupWizardManager.isSetupCompleted(context))

        SetupWizardManager.setSetupCompleted(context, true)
        assertTrue(SetupWizardManager.isSetupCompleted(context))

        SetupWizardManager.setSetupCompleted(context, false)
        assertFalse(SetupWizardManager.isSetupCompleted(context))
    }

    @Test
    fun testRecoveryCodeGenerationAndPersistence() {
        val code = SetupWizardManager.generateRecoveryCode()
        assertEquals(19, code.length) // "XXXX-XXXX-XXXX-XXXX"

        val parts = code.split("-")
        assertEquals(4, parts.size)
        for (part in parts) {
            assertEquals(4, part.length)
        }

        SetupWizardManager.saveRecoveryCode(context, code)
        val saved = SetupWizardManager.getSavedRecoveryCode(context)
        assertEquals(code, saved)
    }

    @Test
    fun testNormalizeRecoveryCode() {
        val input = " a1b2 - c3d4 - e5f6 - 7890 "
        val normalized = SetupWizardManager.normalizeRecoveryCode(input)
        assertEquals("A1B2-C3D4-E5F6-7890", normalized)
    }
}
