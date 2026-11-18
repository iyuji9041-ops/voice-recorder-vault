package com.my.seekapplication.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CredentialPolicy:
 * 1. PIN validation (>= 6 numeric digits).
 * 2. Password validation (>= 8 characters).
 * 3. Pattern validation (>= 5 dots).
 * 4. Door passcode validation (>= 6 digits).
 * 5. Constant-time byte and string comparison.
 */
class CredentialPolicyTest {

    @Test
    fun testPinValidation() {
        // Less than 6 digits must fail
        assertTrue(CredentialPolicy.validatePin("12345") is CredentialPolicy.ValidationResult.Invalid)
        assertTrue(CredentialPolicy.validatePin("") is CredentialPolicy.ValidationResult.Invalid)

        // Non-digits must fail
        assertTrue(CredentialPolicy.validatePin("12345a") is CredentialPolicy.ValidationResult.Invalid)
        assertTrue(CredentialPolicy.validatePin("12-456") is CredentialPolicy.ValidationResult.Invalid)

        // 6 or more digits must pass
        assertTrue(CredentialPolicy.validatePin("123456") is CredentialPolicy.ValidationResult.Valid)
        assertTrue(CredentialPolicy.validatePin("9876543210") is CredentialPolicy.ValidationResult.Valid)
    }

    @Test
    fun testPasswordValidation() {
        // Less than 8 characters must fail
        assertTrue(CredentialPolicy.validatePassword("pass123") is CredentialPolicy.ValidationResult.Invalid)
        assertTrue(CredentialPolicy.validatePassword("") is CredentialPolicy.ValidationResult.Invalid)

        // 8 or more characters must pass
        assertTrue(CredentialPolicy.validatePassword("pass1234") is CredentialPolicy.ValidationResult.Valid)
        assertTrue(CredentialPolicy.validatePassword("VeryStrongPassword!#99") is CredentialPolicy.ValidationResult.Valid)
    }

    @Test
    fun testPatternValidation() {
        // Less than 5 dots must fail
        assertTrue(CredentialPolicy.validatePattern("1234") is CredentialPolicy.ValidationResult.Invalid)
        assertTrue(CredentialPolicy.validatePattern("") is CredentialPolicy.ValidationResult.Invalid)

        // 5 or more dots must pass
        assertTrue(CredentialPolicy.validatePattern("12345") is CredentialPolicy.ValidationResult.Valid)
        assertTrue(CredentialPolicy.validatePattern("0124578") is CredentialPolicy.ValidationResult.Valid)
    }

    @Test
    fun testDoorPasscodeValidation() {
        // Less than 6 digits must fail
        assertTrue(CredentialPolicy.validateDoorPasscode("1234") is CredentialPolicy.ValidationResult.Invalid)
        assertTrue(CredentialPolicy.validateDoorPasscode("12345") is CredentialPolicy.ValidationResult.Invalid)

        // 6 or more digits must pass
        assertTrue(CredentialPolicy.validateDoorPasscode("123456") is CredentialPolicy.ValidationResult.Valid)
        assertTrue(CredentialPolicy.validateDoorPasscode("000000") is CredentialPolicy.ValidationResult.Valid)
    }

    @Test
    fun testConstantTimeStringEquals() {
        assertTrue(CredentialPolicy.constantTimeEquals("my_secret_key", "my_secret_key"))
        assertTrue(CredentialPolicy.constantTimeEquals("", ""))

        assertFalse(CredentialPolicy.constantTimeEquals("my_secret_key", "my_secret_kez"))
        assertFalse(CredentialPolicy.constantTimeEquals("my_secret_key", "my_secret_key_extra"))
        assertFalse(CredentialPolicy.constantTimeEquals("short", "longer_password"))
    }

    @Test
    fun testConstantTimeByteEquals() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        val c = byteArrayOf(1, 2, 3, 4, 6)
        val d = byteArrayOf(1, 2, 3)

        assertTrue(CredentialPolicy.constantTimeEquals(a, b))
        assertFalse(CredentialPolicy.constantTimeEquals(a, c))
        assertFalse(CredentialPolicy.constantTimeEquals(a, d))
    }
}
