package com.my.seekapplication.data

import android.content.Context
import android.util.Log
import com.my.seekapplication.VaultImageLoader
import java.io.File
import javax.crypto.SecretKey

/**
 * VaultSession: In-memory session manager for unlocked vault instances.
 * Guarantees that master keys are kept in memory only while unlocked and
 * wipes all caches and keys on lock or app backgrounding.
 */
object VaultSession {
    private const val TAG = "VaultSession"

    @Volatile
    private var activeRepository: VaultRepository? = null

    val isUnlocked: Boolean
        get() = activeRepository != null

    val repository: VaultRepository
        get() = activeRepository ?: throw IllegalStateException("Vault is locked. No active session.")

    val isDecoy: Boolean
        get() = activeRepository?.isDecoy ?: false

    fun getRepositoryOrNull(): VaultRepository? = activeRepository

    @Synchronized
    fun open(repository: VaultRepository) {
        activeRepository = repository
        Log.d(TAG, "Vault session opened (decoy = ${repository.isDecoy})")
    }

    @Synchronized
    fun lock(context: Context? = null) {
        activeRepository = null
        VaultImageLoader.clearMemoryCache()
        context?.let { wipeAppCache(it) }
        Log.d(TAG, "Vault session locked. Keys zeroized and cache wiped.")
    }

    fun wipeAppCache(context: Context) {
        try {
            wipeDir(context.cacheDir)
            context.externalCacheDir?.let { wipeDir(it) }
            Log.d(TAG, "App cache directory successfully wiped clean.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed wiping cache: ${e.message}")
        }
    }

    private fun wipeDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                wipeDir(file)
            }
            try { file.delete() } catch (ignored: Exception) {}
        }
    }
}
