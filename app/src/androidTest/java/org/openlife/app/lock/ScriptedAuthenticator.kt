package org.openlife.app.lock

import androidx.fragment.app.FragmentActivity
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test authenticator: records prompts and lets the test decide their outcome. */
class ScriptedAuthenticator(@Volatile var available: LockAvailability = LockAvailability.AVAILABLE) :
    BiometricAuthenticator {
    val requests = AtomicInteger()
    private val pending = AtomicReference<((AuthOutcome) -> Unit)?>(null)

    override fun availability(): LockAvailability = available

    override fun authenticate(activity: FragmentActivity, onResult: (AuthOutcome) -> Unit) {
        pending.set(onResult)
        requests.incrementAndGet()
    }

    fun awaitRequest(timeoutMs: Long = WAIT_MS) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (pending.get() == null) {
            check(System.nanoTime() < deadline) { "no authentication was requested" }
            Thread.sleep(POLL_MS)
        }
    }

    fun complete(outcome: AuthOutcome) {
        val callback = checkNotNull(pending.getAndSet(null)) { "no pending authentication" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { callback(outcome) }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val POLL_MS = 50L
    }
}
