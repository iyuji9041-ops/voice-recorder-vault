package com.my.seekapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.my.seekapplication.data.VaultSession
import java.util.concurrent.atomic.AtomicInteger

/**
 * AutoLockManager: Manages automatic session termination and RAM key zeroization
 * upon backgrounding, screen turn-off (ACTION_SCREEN_OFF), or grace period expiration.
 */
object AutoLockManager {

    private const val TAG = "AutoLockManager"
    private const val PREFS_NAME = "VaultSecurityPrefs"
    private const val KEY_GRACE_PERIOD = "autolock_grace_period_seconds"

    const val ACTION_VAULT_LOCKED = "com.my.seekapplication.ACTION_VAULT_LOCKED"

    private val activeVaultActivitiesCount = AtomicInteger(0)
    private var backgroundTimestamp = 0L
    private var isScreenOff = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getGracePeriodSeconds(context: Context): Int {
        return getPrefs(context).getInt(KEY_GRACE_PERIOD, 0) // Default: 0 (Immediate)
    }

    fun setGracePeriodSeconds(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_GRACE_PERIOD, seconds).apply()
    }

    fun isGracePeriodExpired(context: Context): Boolean {
        if (!VaultSession.isUnlocked) return true

        val graceSeconds = getGracePeriodSeconds(context)
        if (graceSeconds == 0 && (activeVaultActivitiesCount.get() <= 0 || isScreenOff)) {
            return true
        }

        if (backgroundTimestamp > 0L) {
            val elapsedMs = SystemClock.elapsedRealtime() - backgroundTimestamp
            val graceMs = graceSeconds * 1000L
            if (elapsedMs >= graceMs) {
                return true
            }
        }

        return false
    }

    fun onActivityStarted(activity: Activity) {
        if (!VaultSession.isUnlocked || isGracePeriodExpired(activity)) {
            lockAndFinish(activity)
            return
        }
        isScreenOff = false
        backgroundTimestamp = 0L
        activeVaultActivitiesCount.incrementAndGet()
    }

    fun onActivityResumed(activity: Activity) {
        if (!VaultSession.isUnlocked || isGracePeriodExpired(activity)) {
            lockAndFinish(activity)
            return
        }
        backgroundTimestamp = 0L
    }

    fun onActivityStopped(activity: Activity) {
        val remaining = activeVaultActivitiesCount.decrementAndGet()
        if (remaining <= 0) {
            activeVaultActivitiesCount.set(0)
            backgroundTimestamp = SystemClock.elapsedRealtime()
            val graceSec = getGracePeriodSeconds(activity)
            if (graceSec == 0) {
                // Immediate auto-lock
                lockAndFinish(activity)
            }
        }
    }

    fun onScreenOff(context: Context) {
        isScreenOff = true
        backgroundTimestamp = SystemClock.elapsedRealtime()
        val graceSec = getGracePeriodSeconds(context)
        if (graceSec == 0) {
            lockVault(context)
        }
    }

    fun lockVault(context: Context) {
        VaultSecurityManager.lockVault(context)
        backgroundTimestamp = 0L
        isScreenOff = false
        activeVaultActivitiesCount.set(0)

        // Broadcast to finish all open vault activities
        try {
            val intent = Intent(ACTION_VAULT_LOCKED)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting lock event: ${e.message}")
        }
    }

    fun lockAndFinish(activity: Activity) {
        lockVault(activity)
        if (!activity.isFinishing && !activity.isDestroyed) {
            activity.finish()
        }
    }
}
