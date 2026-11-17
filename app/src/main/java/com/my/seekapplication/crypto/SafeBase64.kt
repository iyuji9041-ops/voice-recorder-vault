package com.my.seekapplication.crypto

/**
 * SafeBase64: Pure Kotlin Base64 implementation.
 * Zero dependency on Android SDK or Java 8 java.util.Base64.
 * Runs identically and safely on Android API 24-36+ and JVM unit tests without NewApi lint errors.
 */
object SafeBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in ALPHABET.indices) {
            this[ALPHABET[i].code] = i
        }
        this['='.code] = 0
    }

    fun encode(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            val c0 = b0 ushr 2
            val c1 = ((b0 and 0x03) shl 4) or if (b1 != -1) (b1 ushr 4) else 0

            sb.append(ALPHABET[c0])
            sb.append(ALPHABET[c1])

            if (b1 != -1) {
                val c2 = ((b1 and 0x0F) shl 2) or if (b2 != -1) (b2 ushr 6) else 0
                sb.append(ALPHABET[c2])
            } else {
                sb.append('=')
            }

            if (b2 != -1) {
                val c3 = b2 and 0x3F
                sb.append(ALPHABET[c3])
            } else {
                sb.append('=')
            }

            i += 3
        }
        return sb.toString()
    }

    fun decode(string: String): ByteArray {
        val clean = string.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)

        val padding = when {
            clean.endsWith("==") -> 2
            clean.endsWith("=") -> 1
            else -> 0
        }
        val outLen = (clean.length * 3) / 4 - padding
        val out = ByteArray(outLen)

        var srcIdx = 0
        var dstIdx = 0
        while (srcIdx < clean.length) {
            val c0 = DECODE_TABLE[clean[srcIdx++].code]
            val c1 = DECODE_TABLE[clean[srcIdx++].code]
            val c2 = if (srcIdx < clean.length) DECODE_TABLE[clean[srcIdx++].code] else 0
            val c3 = if (srcIdx < clean.length) DECODE_TABLE[clean[srcIdx++].code] else 0

            if (dstIdx < outLen) out[dstIdx++] = (((c0 and 0x3F) shl 2) or ((c1 and 0x3F) ushr 4)).toByte()
            if (dstIdx < outLen) out[dstIdx++] = (((c1 and 0x0F) shl 4) or ((c2 and 0x3F) ushr 2)).toByte()
            if (dstIdx < outLen) out[dstIdx++] = (((c2 and 0x03) shl 6) or (c3 and 0x3F)).toByte()
        }
        return out
    }
}
