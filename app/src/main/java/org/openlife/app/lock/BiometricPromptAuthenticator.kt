package org.openlife.app.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.openlife.app.R

/**
 * BiometricPrompt with the device credential as fallback (P1-07 constraint).
 * API 30+ allows STRONG biometrics or the credential; API 29 cannot combine
 * STRONG with the credential, so it uses WEAK or the credential. The
 * deprecated `setDeviceCredentialAllowed` is not used, and no negative
 * button is set (the builder rejects one when the credential is allowed).
 */
class BiometricPromptAuthenticator(private val context: Context) : BiometricAuthenticator {
    private val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

    /** Anything other than success, including unknown results, means "cannot verify" (P1-07-R4). */
    override fun availability(): LockAvailability = when (BiometricManager.from(context).canAuthenticate(allowed)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NOT_ENROLLED
        else -> LockAvailability.UNAVAILABLE
    }

    override fun authenticate(activity: FragmentActivity, onResult: (AuthOutcome) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(AuthOutcome.Succeeded)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(if (errorCode in CANCEL_CODES) AuthOutcome.Cancelled else AuthOutcome.CannotVerify)
            }
            // onAuthenticationFailed: a rejected attempt; the prompt stays up for another.
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_prompt_title))
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }

    private companion object {
        val CANCEL_CODES = setOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
        )
    }
}
