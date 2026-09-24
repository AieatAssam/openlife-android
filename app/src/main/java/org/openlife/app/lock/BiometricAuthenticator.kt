package org.openlife.app.lock

import androidx.fragment.app.FragmentActivity

/** Whether this device can verify the user right now (P1-07-R4). */
enum class LockAvailability { AVAILABLE, NOT_ENROLLED, UNAVAILABLE }

sealed interface AuthOutcome {
    data object Succeeded : AuthOutcome

    /** The user dismissed the prompt; content stays locked and they can try again. */
    data object Cancelled : AuthOutcome

    /** The device cannot verify (lockout, credential removed, hardware error); content stays locked. */
    data object CannotVerify : AuthOutcome
}

/** The real implementation is BiometricPrompt; tests script the outcome. */
interface BiometricAuthenticator {
    fun availability(): LockAvailability

    fun authenticate(activity: FragmentActivity, onResult: (AuthOutcome) -> Unit)
}
