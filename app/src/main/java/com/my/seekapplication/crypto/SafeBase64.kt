package com.my.seekapplication.crypto

/**
 * SafeBase64: Dual-environment Base64 utility that runs cleanly on Android OS
 * and JVM unit test environments without requiring Robolectric or native mocking.
 */
object SafeBase64 {

    fun encode(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    fun decode(string: String): ByteArray {
        return try {
            android.util.Base64.decode(string, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(string)
        }
    }
}
