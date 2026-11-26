package org.openlife.app.lock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N-01: a content read must see the lock that elapsed background time
 * implies, whichever lifecycle callback runs first on return. The entry's
 * ON_START (the viewer's onVisible and its read) may run before the process
 * ON_START that calls [AppLockState.onForeground]; the read must still wait.
 */
class AppLockAccessTest {
    private var now = 5_000_000L
    private val state = AppLockState(clock = { now })
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After
    fun cancel() = scope.cancel()

    @Test
    fun aReadBeforeTheProcessStartSeesTheTimeoutThatElapsedInTheBackground() {
        unlocked(timeoutSeconds = 30)
        state.onBackground()
        now += 30_000

        // Entry ON_START first: no onForeground yet.
        assertEquals(LockStatus.LOCKED, state.statusForAccess())
        assertFalse("the read waits for unlock", readGoesThrough())

        state.onForeground()
        assertEquals(LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun aReadAfterTheProcessStartSeesTheSameLock() {
        unlocked(timeoutSeconds = 30)
        state.onBackground()
        now += 30_000

        state.onForeground()

        assertEquals(LockStatus.LOCKED, state.statusForAccess())
        assertFalse(readGoesThrough())
    }

    @Test
    fun aShortAbsenceDoesNotLockInEitherOrder() {
        unlocked(timeoutSeconds = 30)
        state.onBackground()
        now += 29_999

        assertEquals("entry start first", LockStatus.UNLOCKED, state.statusForAccess())
        state.onForeground()
        assertEquals("process start after", LockStatus.UNLOCKED, state.statusForAccess())
        assertTrue(readGoesThrough())
    }

    @Test
    fun zeroTimeoutIsLockedForAReadAsSoonAsTheProcessIsBackgrounded() {
        unlocked(timeoutSeconds = 0)

        state.onBackground()

        assertEquals(LockStatus.LOCKED, state.statusForAccess())
        assertFalse(readGoesThrough())
    }

    @Test
    fun anExternalTripIsExemptOnlyWithinItsGraceInEitherOrder() {
        unlocked(timeoutSeconds = 30)
        state.beginExternalActivity()
        state.onBackground()
        now += 45_000
        assertEquals("within the 60 s grace", LockStatus.UNLOCKED, state.statusForAccess())

        now += AppLockState.EXTERNAL_GRACE_MILLIS - 45_000
        assertEquals("at the grace, before the process start", LockStatus.LOCKED, state.statusForAccess())
        state.onForeground()
        assertEquals(LockStatus.LOCKED, state.status.value)
    }

    @Test
    fun theWaitingReadProceedsOnlyAfterUnlock() {
        unlocked(timeoutSeconds = 30)
        state.onBackground()
        now += 31_000
        var done = false
        scope.launch {
            state.awaitAccess()
            done = true
        }
        assertFalse(done)

        state.onForeground()
        assertFalse("returning alone does not let it through", done)
        state.onUnlocked()
        assertTrue(done)
    }

    @Test
    fun disabledLockNeverBlocksAReadAndUnknownAlwaysDoes() {
        assertEquals("policy not read yet", LockStatus.UNKNOWN, state.statusForAccess())
        assertFalse(readGoesThrough())

        state.start(AppLockPolicy(enabled = false))
        state.onBackground()
        now += 3_600_000
        assertEquals(LockStatus.UNLOCKED, state.statusForAccess())
    }

    private fun unlocked(timeoutSeconds: Int) {
        state.start(AppLockPolicy(enabled = true, timeoutSeconds = timeoutSeconds))
        state.onUnlocked()
    }

    private fun readGoesThrough(): Boolean {
        var through = false
        val job = scope.launch {
            state.awaitAccess()
            through = true
        }
        job.cancel()
        return through
    }
}
