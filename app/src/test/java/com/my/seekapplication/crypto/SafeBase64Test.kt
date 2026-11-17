package com.my.seekapplication.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class SafeBase64Test {

    private val random = SecureRandom()

    @Test
    fun testBase64RoundTrip() {
        // Test lengths from 0 to 100 bytes (testing all modulo 3 padding cases)
        for (len in 0..100) {
            val original = ByteArray(len)
            random.nextBytes(original)

            val encoded = SafeBase64.encode(original)
            val decoded = SafeBase64.decode(encoded)

            assertArrayEquals("Failed for byte array of length $len", original, decoded)
        }
    }

    @Test
    fun testKnownStrings() {
        val testString = "Hello, Secure Vault World!"
        val encoded = SafeBase64.encode(testString.toByteArray(Charsets.UTF_8))
        val decoded = SafeBase64.decode(encoded)
        assertEquals(testString, String(decoded, Charsets.UTF_8))
    }
}
