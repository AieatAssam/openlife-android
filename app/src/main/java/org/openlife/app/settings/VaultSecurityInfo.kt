package org.openlife.app.settings

import androidx.annotation.StringRes

/** Where Android Keystore says the vault's wrapping key is held (P1-08-R3). */
enum class KeySecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,

    /** Secure hardware, but Android does not say which kind (API 29-30, or UNKNOWN_SECURE). */
    SECURE_HARDWARE_UNSPECIFIED,
    SOFTWARE,

    /** Keystore reported no level, an unknown one, or the query failed. Never read as software or hardware. */
    NOT_REPORTED,

    /** No wrapping key exists yet; querying never creates one. */
    NO_KEY,
}

/** What the Keystore query returned, before any label is chosen. */
sealed interface WrappingKeyReport {
    data object NoKey : WrappingKeyReport

    data object QueryFailed : WrappingKeyReport

    /** [securityLevel] is `KeyInfo.getSecurityLevel()` on API 31+, null below; [insideSecureHardware] is the older flag. */
    data class Reported(val securityLevel: Int?, val insideSecureHardware: Boolean) : WrappingKeyReport
}

/** Pure mapping from KeyInfo values to honest labels; never "hardware" unless KeyInfo says so. */
object VaultSecurityInfo {
    fun levelOf(report: WrappingKeyReport): KeySecurityLevel = TODO("P1-08 RED")

    fun claimsSecureHardware(level: KeySecurityLevel): Boolean = TODO("P1-08 RED")

    @StringRes
    fun label(level: KeySecurityLevel): Int = TODO("P1-08 RED")
}
