package com.my.seekapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying the Door Passcode and Vault Unlock flow on Android context.
 */
@RunWith(AndroidJUnit4::class)
class UnlockFlowInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("VaultPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testDoorPasscodeVerificationFlow() {
        // Initially default door passcode is set or tested
        VaultSecurityManager.setDoorPasscode(context, "1234")

        assertTrue(VaultSecurityManager.checkDoorPasscode(context, "1234"))
        assertFalse(VaultSecurityManager.checkDoorPasscode(context, "9999"))
        assertFalse(VaultSecurityManager.checkDoorPasscode(context, ""))
    }

    @Test
    fun testDoorLockoutState() {
        // Fresh state: not locked out
        assertFalse(VaultSecurityManager.isDoorLockedOut(context))
    }
}
