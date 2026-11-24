package org.openlife.vault.crypto

import org.junit.Assume.assumeTrue

/**
 * Instances of Android platform exceptions on the JVM. The android.jar stub
 * on the unit-test classpath has the real class names; allocating without
 * running the stub constructor gives an instance whose type is exactly what
 * the platform throws. If that is impossible here the calling test is
 * reported as skipped (Assume), never silently passed.
 */
internal object PlatformExceptions {
    const val KEY_PERMANENTLY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"

    fun keyStoreException(): Throwable = allocate(KeystoreFailures.PLATFORM_KEYSTORE_EXCEPTION)

    fun keyPermanentlyInvalidated(): Throwable = allocate(KEY_PERMANENTLY_INVALIDATED)

    private fun allocate(name: String): Throwable {
        val instance = runCatching {
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val field = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }
            unsafeType.getMethod("allocateInstance", Class::class.java)
                .invoke(field.get(null), Class.forName(name)) as Throwable
        }
        assumeTrue("cannot allocate $name here: ${instance.exceptionOrNull()}", instance.isSuccess)
        return instance.getOrThrow()
    }
}
