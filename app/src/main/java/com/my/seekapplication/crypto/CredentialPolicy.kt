package com.my.seekapplication.crypto

import java.security.MessageDigest

/**
 * CredentialPolicy: Enforces hardened length requirements and constant-time comparisons.
 *
 * Rules:
 * 1. Numeric PIN: Minimum 6 digits, digits only.
 * 2. Password: Minimum 8 characters.
 * 3. Pattern: Minimum 5 dots.
 * 4. Comparisons: Constant-time byte comparison using MessageDigest.isEqual.
 */
object CredentialPolicy {

    const val MIN_PIN_LENGTH = 6
    const val MIN_PASSWORD_LENGTH = 8
    const val MIN_PATTERN_DOTS = 5

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validatePin(pin: String): ValidationResult {
        val trimmed = pin.trim()
        if (trimmed.length < MIN_PIN_LENGTH) {
            return ValidationResult.Invalid("PIN must be at least $MIN_PIN_LENGTH digits.")
        }
        if (!trimmed.all { it.isDigit() }) {
            return ValidationResult.Invalid("PIN must contain only numbers (0-9).")
        }
        return ValidationResult.Valid
    }

    fun validatePassword(password: String): ValidationResult {
        val trimmed = password.trim()
        if (trimmed.length < MIN_PASSWORD_LENGTH) {
            return ValidationResult.Invalid("Password must be at least $MIN_PASSWORD_LENGTH characters.")
        }
        return ValidationResult.Valid
    }

    fun validatePattern(pattern: String): ValidationResult {
        val trimmed = pattern.trim()
        if (trimmed.length < MIN_PATTERN_DOTS) {
            return ValidationResult.Invalid("Pattern must connect at least $MIN_PATTERN_DOTS dots.")
        }
        return ValidationResult.Valid
    }

    fun validateDoorPasscode(code: String): ValidationResult {
        val trimmed = code.trim()
        if (trimmed.length < MIN_PIN_LENGTH) {
            return ValidationResult.Invalid("Door passcode must be at least $MIN_PIN_LENGTH digits.")
        }
        if (!trimmed.all { it.isDigit() }) {
            return ValidationResult.Invalid("Door passcode must contain only numbers (0-9).")
        }
        return ValidationResult.Valid
    }

    /**
     * Constant-time comparison between two Strings using MessageDigest.isEqual.
     * Prevents timing attacks on passwords, PINs, and hashes.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Constant-time comparison between two ByteArrays.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }
}
