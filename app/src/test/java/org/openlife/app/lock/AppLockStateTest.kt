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
    fun aShortExternalTripStartedByOpenLifeDoesNotRelock() {
        // The device-credential screen and the Photo Picker are other apps'
        // activities; a quick round trip to them must not lock OpenLife
        // behind the user (ADR-0004), even with a zero timeout.
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()
        state.beginExternalActivity()
        state.onBackground()
        now += AppLockState.EXTERNAL_GRACE_MILLIS - 1
        state.onForeground()
        state.endExternalActivity()

        assertEquals(LockStatus.UNLOCKED, state.status.value)

        state.onBackground()
        assertEquals("only the round trip is exempt", LockStatus.LOCKED, state.status.value)
    }

    /** A-01 (P1-19-R1): the exemption is bounded by max(timeout, 60 s) on elapsedRealtime. */
    @Test
    fun externalTripLongerThanGraceLocksOnReturn() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()

        state.beginExternalActivity()
        state.onBackground()
        now += AppLockState.EXTERNAL_GRACE_MILLIS
        state.onForeground()

        assertEquals("a picker left open for a minute is a background period", LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun externalTripUsesTheLongerOfTimeoutAndGrace() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 300))
        state.onUnlocked()

        state.beginExternalActivity()
        state.onBackground()
        now += 299_999
        state.onForeground()
        state.endExternalActivity()
        assertEquals("shorter than the 5 min timeout", LockStatus.UNLOCKED, state.status.value)

        state.beginExternalActivity()
        state.onBackground()
        now += 300_000
        state.onForeground()
        assertEquals("at the timeout", LockStatus.LOCKED, state.status.value)
    }

    /**
     * A-07 backstop (P1-19-R1/R2): a picker result that never arrives leaves
     * the external flag set. Every later absence of at least the grace period
     * still locks, so the lock is never left disabled.
     */
    @Test
    fun aDroppedPickerResultCannotLeaveTheLockDisabled() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()
        state.beginExternalActivity()
        // endExternalActivity() is never called: the result was dropped.
        repeat(3) { round ->
            state.onBackground()
            now += AppLockState.EXTERNAL_GRACE_MILLIS
            state.onForeground()
            assertEquals("round $round locks", LockStatus.LOCKED, state.status.value)
            state.onUnlocked()
            // The user unlocked; a fresh trip that is again dropped must still be bounded.
            state.beginExternalActivity()
        }
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
