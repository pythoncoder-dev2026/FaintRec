package com.example.logging

import android.os.SystemClock
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographic verifier for the hidden Developer Mode PIN.
 *
 * The raw PIN is never embedded in the APK or BuildConfig. At build time, Gradle
 * generates a random salt and computes SHA-256("$salt:$pin") and emits only
 * [BuildConfig.DEV_PIN_SALT] and [BuildConfig.DEV_PIN_HASH].
 * Verification uses constant-time byte comparison via [MessageDigest.isEqual].
 */
object DevPinVerifier {

    private const val SHA256_HEX_LENGTH = 64

    fun isConfigured(
        salt: String = BuildConfig.DEV_PIN_SALT,
        expectedHashHex: String = BuildConfig.DEV_PIN_HASH
    ): Boolean {
        return salt.isNotBlank() &&
            expectedHashHex.length == SHA256_HEX_LENGTH &&
            hexToBytes(expectedHashHex) != null
    }

    fun computeSaltedSha256Bytes(salt: String, pin: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = "$salt:$pin".toByteArray(StandardCharsets.UTF_8)
        return digest.digest(payload)
    }

    fun computeSaltedSha256Hex(salt: String, pin: String): String {
        val bytes = computeSaltedSha256Bytes(salt, pin)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun verifyPinConstantTime(
        inputPin: String,
        salt: String = BuildConfig.DEV_PIN_SALT,
        expectedHashHex: String = BuildConfig.DEV_PIN_HASH
    ): Boolean {
        if (!isConfigured(salt, expectedHashHex) || inputPin.isEmpty()) {
            return false
        }
        val expectedBytes = hexToBytes(expectedHashHex) ?: return false
        val candidateBytes = computeSaltedSha256Bytes(salt, inputPin)
        return MessageDigest.isEqual(candidateBytes, expectedBytes)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

data class DevModeUiState(
    val isConfigured: Boolean = false,
    val isUnlocked: Boolean = false,
    val isLogOpen: Boolean = false,
    val showPinDialog: Boolean = false,
    val showIncorrectPinError: Boolean = false,
    val lockoutUntilElapsedMs: Long = 0L
) {
    fun lockoutRemainingSeconds(nowElapsedMs: Long): Int {
        val remainingMs = lockoutUntilElapsedMs - nowElapsedMs
        return if (remainingMs > 0L) ((remainingMs + 999L) / 1000L).toInt() else 0
    }
}

/**
 * Pure in-memory state machine guarding hidden Developer Mode access:
 * - Completely disabled when DEV_PIN_SALT / DEV_PIN_HASH are unset at build time.
 * - Requires 7 rapid taps (each within 2 seconds of the previous tap) with zero hints before tap 7.
 * - Blocks PIN submission for 30 seconds after 3 incorrect attempts.
 * - Session-scoped only (in-memory); automatically locks on process termination or after
 *   more than 5 minutes in the background.
 */
class DevModeSessionGuard(
    private val salt: String,
    private val expectedHashHex: String
) {
    companion object {
        const val REQUIRED_TAPS = 7
        const val TAP_WINDOW_MS = 2_000L
        const val MAX_WRONG_ATTEMPTS = 3
        const val LOCKOUT_DURATION_MS = 30_000L
        const val BACKGROUND_LOCK_TIMEOUT_MS = 5L * 60L * 1000L
    }

    private val configured: Boolean = DevPinVerifier.isConfigured(salt, expectedHashHex)

    private val _state = MutableStateFlow(DevModeUiState(isConfigured = configured))
    val state: StateFlow<DevModeUiState> = _state.asStateFlow()

    private var tapCount: Int = 0
    private var lastTapElapsedMs: Long = 0L
    private var wrongAttempts: Int = 0
    private var backgroundedAtElapsedMs: Long? = null

    fun onVersionTap(nowElapsedMs: Long) {
        if (!configured) return

        val current = _state.value
        if (current.isUnlocked) {
            tapCount = 0
            _state.update { it.copy(isLogOpen = true, showPinDialog = false) }
            return
        }

        if (tapCount == 0 || (nowElapsedMs - lastTapElapsedMs) > TAP_WINDOW_MS) {
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapElapsedMs = nowElapsedMs

        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            _state.update {
                it.copy(
                    showPinDialog = true,
                    showIncorrectPinError = false
                )
            }
        }
    }

    fun dismissPinDialog() {
        _state.update { it.copy(showPinDialog = false, showIncorrectPinError = false) }
    }

    /**
     * Submits a candidate PIN. Returns true on successful unlock.
     */
    fun submitPin(candidatePin: String, nowElapsedMs: Long): Boolean {
        if (!configured) return false
        val current = _state.value
        if (nowElapsedMs < current.lockoutUntilElapsedMs) {
            return false
        }

        val valid = DevPinVerifier.verifyPinConstantTime(
            inputPin = candidatePin,
            salt = salt,
            expectedHashHex = expectedHashHex
        )

        if (valid) {
            wrongAttempts = 0
            tapCount = 0
            backgroundedAtElapsedMs = null
            _state.update {
                it.copy(
                    isUnlocked = true,
                    isLogOpen = true,
                    showPinDialog = false,
                    showIncorrectPinError = false,
                    lockoutUntilElapsedMs = 0L
                )
            }
            return true
        }

        wrongAttempts += 1
        val nextLockoutUntil = if (wrongAttempts >= MAX_WRONG_ATTEMPTS) {
            wrongAttempts = 0
            nowElapsedMs + LOCKOUT_DURATION_MS
        } else {
            0L
        }
        _state.update {
            it.copy(
                showIncorrectPinError = true,
                lockoutUntilElapsedMs = nextLockoutUntil
            )
        }
        return false
    }

    fun closeLog() {
        _state.update { it.copy(isLogOpen = false) }
    }

    fun lockNow() {
        tapCount = 0
        backgroundedAtElapsedMs = null
        _state.update {
            it.copy(
                isUnlocked = false,
                isLogOpen = false,
                showPinDialog = false,
                showIncorrectPinError = false
            )
        }
    }

    fun onAppBackgrounded(nowElapsedMs: Long) {
        val current = _state.value
        if (current.isUnlocked || current.showPinDialog) {
            backgroundedAtElapsedMs = nowElapsedMs
        }
    }

    fun onAppForegrounded(nowElapsedMs: Long) {
        val bgTime = backgroundedAtElapsedMs ?: return
        backgroundedAtElapsedMs = null
        if ((nowElapsedMs - bgTime) > BACKGROUND_LOCK_TIMEOUT_MS) {
            lockNow()
        }
    }
}

@Singleton
class DevModeController @Inject constructor() {

    private val guard = DevModeSessionGuard(
        salt = BuildConfig.DEV_PIN_SALT,
        expectedHashHex = BuildConfig.DEV_PIN_HASH
    )

    val state: StateFlow<DevModeUiState> = guard.state

    fun onVersionTap() = guard.onVersionTap(SystemClock.elapsedRealtime())

    fun dismissPinDialog() = guard.dismissPinDialog()

    fun submitPin(candidatePin: String): Boolean =
        guard.submitPin(candidatePin, SystemClock.elapsedRealtime())

    fun closeLog() = guard.closeLog()

    fun lockNow() = guard.lockNow()

    fun onAppBackgrounded() = guard.onAppBackgrounded(SystemClock.elapsedRealtime())

    fun onAppForegrounded() = guard.onAppForegrounded(SystemClock.elapsedRealtime())
}
