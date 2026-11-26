package org.openlife.app.settings

import android.security.keystore.KeyProperties
import androidx.annotation.StringRes
import org.openlife.app.R

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

    /** [securityLevel]: `KeyInfo.getSecurityLevel()` on API 31+, null below; [insideSecureHardware]: the older flag. */
    data class Reported(val securityLevel: Int?, val insideSecureHardware: Boolean) : WrappingKeyReport
}

/** Pure mapping from KeyInfo values to honest labels; never "hardware" unless KeyInfo says so. */
object VaultSecurityInfo {
    fun levelOf(report: WrappingKeyReport): KeySecurityLevel = when (report) {
        WrappingKeyReport.NoKey -> KeySecurityLevel.NO_KEY
        WrappingKeyReport.QueryFailed -> KeySecurityLevel.NOT_REPORTED
        is WrappingKeyReport.Reported -> report.securityLevel?.let(::levelFromApi31) ?: levelBeforeApi31(report)
    }

    fun claimsSecureHardware(level: KeySecurityLevel): Boolean = level in SECURE_HARDWARE

    @StringRes
    fun label(level: KeySecurityLevel): Int = when (level) {
        KeySecurityLevel.STRONGBOX -> R.string.vault_key_level_strongbox
        KeySecurityLevel.TRUSTED_ENVIRONMENT -> R.string.vault_key_level_tee
        KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED -> R.string.vault_key_level_secure_unspecified
        KeySecurityLevel.SOFTWARE -> R.string.vault_key_level_software
        KeySecurityLevel.NOT_REPORTED -> R.string.vault_key_level_not_reported
        KeySecurityLevel.NO_KEY -> R.string.vault_key_level_no_key
    }

    /** API 31+: `KeyInfo.getSecurityLevel()` decides; an unknown value is "not reported", never a guess. */
    private fun levelFromApi31(level: Int): KeySecurityLevel = when (level) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
        else -> KeySecurityLevel.NOT_REPORTED
    }

    /** API 29-30: `isInsideSecureHardware` does not say TEE or StrongBox. */
    private fun levelBeforeApi31(report: WrappingKeyReport.Reported): KeySecurityLevel =
        if (report.insideSecureHardware) KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED else KeySecurityLevel.SOFTWARE

    private val SECURE_HARDWARE = setOf(
        KeySecurityLevel.STRONGBOX,
        KeySecurityLevel.TRUSTED_ENVIRONMENT,
        KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED,
    )
}
