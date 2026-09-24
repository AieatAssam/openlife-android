package org.openlife.app.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-07-R3: the lock engages on process start and after a background
 * period longer than the timeout, measured on an injected monotonic clock
 * (production: SystemClock.elapsedRealtime, which keeps counting during
 * device sleep and ignores wall-clock changes).
 */
class AppLockStateTest {
    private var now = 1_000_000L
    private val state = AppLockState(clock = { now })

    @Test
    fun locksOnStartWhenEnabled() {
        assertEquals(LockStatus.UNKNOWN, state.status.value)

        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 300))

        assertEquals(LockStatus.LOCKED, state.status.value)
        state.onUnlocked()
        assertEquals(LockStatus.UNLOCKED, state.status.value)
    }

    @Test
    fun locksAfterBackgroundLongerThanTimeoutUsingMonotonicClock() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 30))
        state.onUnlocked()

        state.onBackground()
        now += 29_999
        state.onForeground()
        assertEquals("shorter than the timeout", LockStatus.UNLOCKED, state.status.value)

        state.onBackground()
        now += 30_000
        state.onForeground()
        assertEquals("at the timeout", LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun zeroTimeoutLocksAsSoonAsTheAppIsBackgrounded() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()

        state.onBackground()

        assertEquals("locked before anything can recompose on return", LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun doesNotLockWhenDisabled() {
        state.start(AppLockPolicy(enabled = false))
        assertEquals(LockStatus.UNLOCKED, state.status.value)

        state.onBackground()
        now += 3_600_000
        state.onForeground()

        assertEquals(LockStatus.UNLOCKED, state.status.value)
    }

    @Test
    fun anExternalActivityStartedByOpenLifeDoesNotRelock() {
        // The device-credential screen and the Photo Picker are other apps'
        // activities; leaving for them must not lock OpenLife behind the user.
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.beginExternalActivity()
        state.onBackground()
        state.onForeground()
        state.onUnlocked()

        assertEquals(LockStatus.UNLOCKED, state.status.value)

        state.beginExternalActivity()
        state.onBackground()
        state.onForeground()
        state.endExternalActivity()
        state.onBackground()
        assertEquals("only the round trip is exempt", LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun disablingUnlocksAndEnablingKeepsThePresentUserIn() {
        state.start(AppLockPolicy(enabled = true))
        state.onPolicyChanged(AppLockPolicy(enabled = false))
        assertEquals(LockStatus.UNLOCKED, state.status.value)

        state.onPolicyChanged(AppLockPolicy(enabled = true, timeoutSeconds = 30))
        assertEquals(LockStatus.UNLOCKED, state.status.value)
        state.onBackground()
        now += 30_000
        state.onForeground()
        assertEquals(LockStatus.LOCKED, state.status.value)
    }
}
