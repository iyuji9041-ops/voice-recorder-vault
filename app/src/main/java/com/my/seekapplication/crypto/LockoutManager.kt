package com.my.seekapplication.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/**
 * LockoutManager: Persistent brute-force protection with exponential backoff.
 *
 * Protection Guarantees:
 * 1. Independent tracking for Door Passcode, Vault Key, and Recovery Answer/Code.
 * 2. Monotonic hardware clock (SystemClock.elapsedRealtime) prevents wall-clock tampering.
 * 3. Boot-count and backward clock shift tracking prevents reboot bypass attacks.
 * 4. Counters and lockout states survive app kills, restarts, and device reboots.
 */
class LockoutManager(
    context: Context? = null,
    prefs: SharedPreferences? = null,
    private val clock: Clock = context?.let { SystemClockImpl(it) } ?: DefaultClock()
) {

    constructor(context: Context) : this(context, null, SystemClockImpl(context))
    constructor(prefs: SharedPreferences, clock: Clock) : this(null, prefs, clock)

    enum class Target {
        DOOR,
        VAULT,
        RECOVERY
    }

    interface Clock {
        fun elapsedRealtime(): Long
        fun getBootCount(): Int
    }

    class DefaultClock : Clock {
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
        override fun getBootCount(): Int = 0
    }

    class SystemClockImpl(private val context: Context) : Clock {
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

        override fun getBootCount(): Int {
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            } catch (e: Exception) {
                0
            }
        }
    }

    data class LockoutStatus(
        val isLockedOut: Boolean,
        val remainingMs: Long,
        val failedAttempts: Int
    )

    private val prefs: SharedPreferences = prefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: throw IllegalArgumentException("Either context or prefs must be provided")

    companion object {
        private const val PREFS_NAME = "VaultLockoutPrefs"

        // Exponential backoff tiers (in milliseconds)
        const val TIER_1_FAIL_COUNT = 5
        const val TIER_1_DELAY_MS = 30_000L      // 30 seconds
        const val TIER_2_FAIL_COUNT = 6
        const val TIER_2_DELAY_MS = 60_000L      // 1 minute
        const val TIER_3_FAIL_COUNT = 7
        const val TIER_3_DELAY_MS = 300_000L     // 5 minutes
        const val TIER_4_DELAY_MS = 900_000L     // 15 minutes
    }

    private fun keyFailures(target: Target) = "failures_${target.name}"
    private fun keyRemainingMs(target: Target) = "remaining_ms_${target.name}"
    private fun keyStartElapsed(target: Target) = "start_elapsed_${target.name}"
    private fun keyBootCount(target: Target) = "boot_count_${target.name}"

    fun getBackoffDelayMs(failures: Int): Long {
        return when {
            failures < TIER_1_FAIL_COUNT -> 0L
            failures == TIER_1_FAIL_COUNT -> TIER_1_DELAY_MS
            failures == TIER_2_FAIL_COUNT -> TIER_2_DELAY_MS
            failures == TIER_3_FAIL_COUNT -> TIER_3_DELAY_MS
            else -> TIER_4_DELAY_MS
        }
    }

    @Synchronized
    fun getFailedAttempts(target: Target): Int {
        return prefs.getInt(keyFailures(target), 0)
    }

    @Synchronized
    fun getRemainingLockoutMs(target: Target): Long {
        val remainingSaved = prefs.getLong(keyRemainingMs(target), 0L)
        if (remainingSaved <= 0L) return 0L

        val storedStartElapsed = prefs.getLong(keyStartElapsed(target), 0L)
        val storedBootCount = prefs.getInt(keyBootCount(target), 0)

        val currentBoot = clock.getBootCount()
        val currentElapsed = clock.elapsedRealtime()

        // Detect if device rebooted or elapsed timer shifted backward
        if (currentBoot > storedBootCount || currentElapsed < storedStartElapsed) {
            // Persist the remaining penalty across reboot: reset start point to 0 on new boot
            prefs.edit()
                .putLong(keyStartElapsed(target), currentElapsed)
                .putInt(keyBootCount(target), currentBoot)
                .apply()
            return remainingSaved
        }

        val elapsed = currentElapsed - storedStartElapsed
        val remaining = remainingSaved - elapsed

        return if (remaining > 0L) {
            remaining
        } else {
            // Lockout expired: clear lockout duration while keeping failure counter
            prefs.edit()
                .putLong(keyRemainingMs(target), 0L)
                .putLong(keyStartElapsed(target), 0L)
                .apply()
            0L
        }
    }

    @Synchronized
    fun isLockedOut(target: Target): Boolean {
        return getRemainingLockoutMs(target) > 0L
    }

    @Synchronized
    fun getStatus(target: Target): LockoutStatus {
        val remaining = getRemainingLockoutMs(target)
        val attempts = getFailedAttempts(target)
        return LockoutStatus(
            isLockedOut = remaining > 0L,
            remainingMs = remaining,
            failedAttempts = attempts
        )
    }

    @Synchronized
    fun recordFailedAttempt(target: Target): LockoutStatus {
        val currentAttempts = getFailedAttempts(target) + 1
        val delayMs = getBackoffDelayMs(currentAttempts)

        val editor = prefs.edit().putInt(keyFailures(target), currentAttempts)

        if (delayMs > 0L) {
            editor.putLong(keyRemainingMs(target), delayMs)
            editor.putLong(keyStartElapsed(target), clock.elapsedRealtime())
            editor.putInt(keyBootCount(target), clock.getBootCount())
        }
        editor.apply()

        return LockoutStatus(
            isLockedOut = delayMs > 0L,
            remainingMs = delayMs,
            failedAttempts = currentAttempts
        )
    }

    @Synchronized
    fun recordSuccessfulAttempt(target: Target) {
        prefs.edit()
            .putInt(keyFailures(target), 0)
            .putLong(keyRemainingMs(target), 0L)
            .putLong(keyStartElapsed(target), 0L)
            .apply()
    }

    @Synchronized
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}
