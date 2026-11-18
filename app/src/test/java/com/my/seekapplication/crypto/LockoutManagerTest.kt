package com.my.seekapplication.crypto

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LockoutManager:
 * 1. Exponential backoff progression (30s, 1m, 5m, 15m).
 * 2. Monotonic clock countdown.
 * 3. Reboot persistence across boot-count increment and elapsed clock reset.
 * 4. Success attempt reset.
 * 5. Isolation between DOOR, VAULT, and RECOVERY targets.
 */
class LockoutManagerTest {

    private class TestClock(
        var elapsed: Long = 100_000L,
        var bootCount: Int = 1
    ) : LockoutManager.Clock {
        override fun elapsedRealtime(): Long = elapsed
        override fun getBootCount(): Int = bootCount
    }

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
    private lateinit var testClock: TestClock
    private lateinit var lockoutManager: LockoutManager

    @Before
    fun setup() {
        testPrefs = TestSharedPreferences()
        testClock = TestClock()
        lockoutManager = LockoutManager(prefs = testPrefs, clock = testClock)
    }

    @Test
    fun testExponentialBackoffProgression() {
        val target = LockoutManager.Target.VAULT

        // Attempts 1 to 4: no delay
        for (i in 1..4) {
            val status = lockoutManager.recordFailedAttempt(target)
            assertFalse("Attempt $i should not lock out", status.isLockedOut)
            assertEquals(0L, status.remainingMs)
            assertEquals(i, status.failedAttempts)
        }

        // 5th attempt: 30 seconds
        val status5 = lockoutManager.recordFailedAttempt(target)
        assertTrue("Attempt 5 should trigger lockout", status5.isLockedOut)
        assertEquals(30_000L, status5.remainingMs)

        // 6th attempt: 1 minute
        val status6 = lockoutManager.recordFailedAttempt(target)
        assertTrue(status6.isLockedOut)
        assertEquals(60_000L, status6.remainingMs)

        // 7th attempt: 5 minutes
        val status7 = lockoutManager.recordFailedAttempt(target)
        assertTrue(status7.isLockedOut)
        assertEquals(300_000L, status7.remainingMs)

        // 8th attempt: 15 minutes
        val status8 = lockoutManager.recordFailedAttempt(target)
        assertTrue(status8.isLockedOut)
        assertEquals(900_000L, status8.remainingMs)
    }

    @Test
    fun testElapsedCountdownAndExpiration() {
        val target = LockoutManager.Target.DOOR

        // Trigger 5 failures -> 30s lockout
        for (i in 1..5) {
            lockoutManager.recordFailedAttempt(target)
        }
        assertTrue(lockoutManager.isLockedOut(target))
        assertEquals(30_000L, lockoutManager.getRemainingLockoutMs(target))

        // Advance clock by 10 seconds
        testClock.elapsed += 10_000L
        assertTrue(lockoutManager.isLockedOut(target))
        assertEquals(20_000L, lockoutManager.getRemainingLockoutMs(target))

        // Advance clock by another 20.001 seconds (past 30s)
        testClock.elapsed += 20_001L
        assertFalse(lockoutManager.isLockedOut(target))
        assertEquals(0L, lockoutManager.getRemainingLockoutMs(target))
    }

    @Test
    fun testRebootPreservesRemainingLockoutPenalty() {
        val target = LockoutManager.Target.RECOVERY

        // Trigger 5 failures -> 30s lockout
        for (i in 1..5) {
            lockoutManager.recordFailedAttempt(target)
        }

        // Advance clock by 10s (20s remaining)
        testClock.elapsed += 10_000L
        assertEquals(20_000L, lockoutManager.getRemainingLockoutMs(target))

        // Simulate reboot: elapsedRealtime resets to near 0, boot count increments
        testClock.bootCount += 1
        testClock.elapsed = 500L

        // Lockout must not be bypassed by rebooting!
        assertTrue(lockoutManager.isLockedOut(target))
        val remainingAfterReboot = lockoutManager.getRemainingLockoutMs(target)
        assertEquals(20_000L, remainingAfterReboot)
    }

    @Test
    fun testSuccessfulAttemptResetsFailuresAndDelay() {
        val target = LockoutManager.Target.VAULT

        for (i in 1..5) {
            lockoutManager.recordFailedAttempt(target)
        }
        assertTrue(lockoutManager.isLockedOut(target))

        lockoutManager.recordSuccessfulAttempt(target)
        assertFalse(lockoutManager.isLockedOut(target))
        assertEquals(0L, lockoutManager.getRemainingLockoutMs(target))
        assertEquals(0, lockoutManager.getFailedAttempts(target))
    }

    @Test
    fun testTargetIsolation() {
        for (i in 1..5) {
            lockoutManager.recordFailedAttempt(LockoutManager.Target.DOOR)
        }

        assertTrue(lockoutManager.isLockedOut(LockoutManager.Target.DOOR))
        assertFalse(lockoutManager.isLockedOut(LockoutManager.Target.VAULT))
        assertFalse(lockoutManager.isLockedOut(LockoutManager.Target.RECOVERY))
    }
}
