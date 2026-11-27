package org.openlife.app.lock

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-21-R6: the authenticator set is chosen by API level. STRONG biometrics
 * combine with the device credential only from API 30; API 26-29 use WEAK.
 */
class AllowedAuthenticatorsTest {
    @Test
    fun apiTwentySixThroughTwentyNineUseWeakBiometricWithCredential() {
        for (sdk in listOf(26, 27, 28, 29)) {
            assertEquals("API $sdk", BIOMETRIC_WEAK or DEVICE_CREDENTIAL, allowedAuthenticatorsFor(sdk))
        }
    }

    @Test
    fun apiThirtyAndLaterUseStrongBiometricWithCredential() {
        for (sdk in listOf(30, 36)) {
            assertEquals("API $sdk", BIOMETRIC_STRONG or DEVICE_CREDENTIAL, allowedAuthenticatorsFor(sdk))
        }
    }
}
