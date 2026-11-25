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
     * A-07 (P1-19-R1/R2, review Q-02): a picker result that never arrives must
     * not leave the trip flag set. Returning to the foreground ends the trip,
     * so the next ordinary background period with a zero timeout locks at
     * once, with no grace.
     */
    @Test
    fun aDroppedPickerResultCannotLeaveTheLockDisabled() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()
        state.beginExternalActivity()
        state.onBackground()
        now += 5_000
        state.onForeground()
        // endExternalActivity() is never called: the result was dropped.
        assertEquals("the short trip itself is exempt", LockStatus.UNLOCKED, state.status.value)

        state.onBackground()
        now += 5_000
        assertEquals("an ordinary 5 s background with timeout 0", LockStatus.LOCKED, state.status.value)
        state.onForeground()
        assertEquals(LockStatus.LOCKED, state.status.value)
    }

    /**
     * Review Q-02: the legitimate picker round trip in the order the platform
     * delivers it. The process stops (~700 ms after the activity), returns,
     * and only then does the result callback end the trip.
     */
    @Test
    fun aPickerRoundTripKeepsItsGraceWhenTheResultArrivesAfterTheReturn() {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onUnlocked()

        state.beginExternalActivity()
        state.onBackground()
        now += 20_000
        state.onForeground()
        state.endExternalActivity()
        assertEquals("a 20 s pick is within the grace", LockStatus.UNLOCKED, state.status.value)

        // A pick faster than the process stop delay: no background, no foreground.
        state.beginExternalActivity()
        state.endExternalActivity()
        assertEquals(LockStatus.UNLOCKED, state.status.value)

        state.onBackground()
        assertEquals("the next ordinary background locks", LockStatus.LOCKED, state.status.value)
    }

    /** Review Q-02: a stale flag must not survive into a policy change. */
    @Test
    fun aDroppedResultWhileTheLockWasOffDoesNotExemptItOnceEnabled() {
        state.start(AppLockPolicy(enabled = false))
        state.beginExternalActivity()
        state.onBackground()
        state.onForeground()

        state.onPolicyChanged(AppLockPolicy(enabled = true, timeoutSeconds = 0))
        state.onBackground()
        assertEquals(LockStatus.LOCKED, state.status.value)
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
