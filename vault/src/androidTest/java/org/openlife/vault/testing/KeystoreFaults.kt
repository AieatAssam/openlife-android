package org.openlife.vault.testing

import android.util.Log
import org.openlife.vault.crypto.KeystoreWrapper
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException

/**
 * Faults shaped like what AndroidKeyStore raises from `engineDoFinal` for a
 * keymaster error other than a failed tag (P1-18-R1): an
 * [IllegalBlockSizeException] whose cause is `android.security.KeyStoreException`
 * (AOSP `AndroidKeyStoreCipherSpiBase.engineDoFinal`, android-10.0.0_r1 and
 * android-14.0.0_r1).
 *
 * That exception's constructor is `@hide`, so the test builds it by
 * reflection when the platform allows and otherwise falls back to a plain
 * stand-in. Assertions must not depend on which one was used; [describe]
 * says which, for the test log and the evidence.
 */
object KeystoreFaults {
    private const val PLATFORM_CLASS = "android.security.KeyStoreException"
    private const val TAG = "P118KeystoreFaults"

    /** A real `android.security.KeyStoreException`, or null when it cannot be constructed here. */
    val platformKeyStoreException: Throwable? by lazy { construct() }

    /** The platform exception if available, otherwise a stand-in that no classifier recognises by type. */
    fun keystoreCause(): Throwable = platformKeyStoreException ?: RuntimeException("stand-in for $PLATFORM_CLASS")

    fun describe(): String = if (platformKeyStoreException != null) {
        "cause=$PLATFORM_CLASS (constructed by reflection)"
    } else {
        "cause=RuntimeException stand-in ($PLATFORM_CLASS not constructible on this platform)"
    }

    /** What AndroidKeyStore throws at doFinal for an unknown keymaster error. */
    fun doFinalFailure(): Exception =
        IllegalBlockSizeException("synthetic keymaster error").apply { initCause(keystoreCause()) }

    private fun construct(): Throwable? {
        val result = runCatching {
            val type = Class.forName(PLATFORM_CLASS)
            val constructor = type.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, String::class.java))
            } ?: error("no (int, String) constructor")
            constructor.isAccessible = true
            // -1000 is KeymasterDefs.KM_ERROR_UNKNOWN_ERROR.
            constructor.newInstance(-1000, "synthetic keymaster error") as Throwable
        }.recoverCatching {
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val field = unsafeType.declaredFields.first { it.type == unsafeType }
            field.isAccessible = true
            val unsafe = field.get(null)
            unsafeType.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, Class.forName(PLATFORM_CLASS)) as Throwable
        }
        Log.i(TAG, "platform KeyStoreException constructible=${result.isSuccess} (${result.exceptionOrNull()})")
        return result.getOrNull()
    }
}

/** A real Keystore wrapper whose `doFinal` can be made to fail like a keymaster hiccup. */
open class DoFinalFaultWrapper(alias: String) : KeystoreWrapper(alias) {
    /** When set, the next and every following `doFinal` throws this. */
    @Volatile var failure: (() -> Exception)? = null

    override fun doFinal(cipher: Cipher, input: ByteArray): ByteArray {
        failure?.let { throw it() }
        return super.doFinal(cipher, input)
    }
}
