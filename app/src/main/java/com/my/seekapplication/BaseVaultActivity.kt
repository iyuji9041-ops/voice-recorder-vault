package com.my.seekapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * BaseVaultActivity: Base class for all authenticated Vault screens.
 *
 * Guarantees:
 * 1. WindowManager.LayoutParams.FLAG_SECURE applied to prevent OS screenshot leaks.
 * 2. Automated lifecycle hook into AutoLockManager for backgrounding and grace period handling.
 * 3. Dynamic receiver for Intent.ACTION_SCREEN_OFF and ACTION_VAULT_LOCKED.
 */
abstract class BaseVaultActivity : AppCompatActivity() {

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutoLockManager.ACTION_VAULT_LOCKED -> {
                    finish()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    context?.let { AutoLockManager.onScreenOff(it) }
                    if (context != null && AutoLockManager.getGracePeriodSeconds(context) == 0) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and screen recording across all vault views
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val filter = IntentFilter().apply {
            addAction(AutoLockManager.ACTION_VAULT_LOCKED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        AutoLockManager.onActivityStarted(this)
    }

    override fun onResume() {
        super.onResume()
        AutoLockManager.onActivityResumed(this)
    }

    override fun onStop() {
        super.onStop()
        AutoLockManager.onActivityStopped(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(lockReceiver)
        } catch (ignored: Exception) {}
    }
}
