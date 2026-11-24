package org.openlife.app.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether content may be shown. UNKNOWN (policy not read yet) is treated as locked. */
enum class LockStatus { UNKNOWN, LOCKED, UNLOCKED }

/**
 * The process-wide app-lock state machine (P1-07-R3, ADR-0004). Pure: the
 * caller feeds it process foreground/background transitions and a clock.
 *
 * [clock] must be monotonic and keep counting while the device sleeps; in
 * production it is `SystemClock.elapsedRealtime()`. `System.nanoTime()` and
 * `uptimeMillis()` stop during deep sleep, so an hour in a pocket would read
 * as seconds and a timeout would never fire; wall-clock time can be changed
 * by the user.
 *
 * With a zero timeout the lock engages at the background transition itself:
 * when ProcessLifecycleOwner reports the process stopped, which is about
 * 700 ms after the last activity stops. Content is not exposed in that
 * window: each activity scrubs its own content in onStop, and FLAG_SECURE
 * keeps it out of Recents and screenshots.
 *
 * Leaving for an activity OpenLife itself started (the system credential
 * screen, the Photo Picker) is exempt only for a short round trip
 * (P1-19-R1): the time away is still measured, and on return the lock
 * engages once it reaches max(timeout, [EXTERNAL_GRACE_MILLIS]). A trip whose
 * result never arrives therefore cannot switch the lock off.
 */
class AppLockState(private val clock: () -> Long) {
    private val mutableStatus = MutableStateFlow(LockStatus.UNKNOWN)
    private val mutablePolicy = MutableStateFlow(AppLockPolicy())
    private var backgroundedAt: Long? = null
    private var backgroundedOnExternalTrip = false
    private var externalActivity = false

    val status: StateFlow<LockStatus> = mutableStatus.asStateFlow()
    val policy: StateFlow<AppLockPolicy> = mutablePolicy.asStateFlow()

    /** Process start: lock if enabled. */
    @Synchronized
    fun start(policy: AppLockPolicy) {
        mutablePolicy.value = policy
        backgroundedAt = null
        backgroundedOnExternalTrip = false
        externalActivity = false
        mutableStatus.value = if (policy.enabled) LockStatus.LOCKED else LockStatus.UNLOCKED
    }

    /** As [start], unless something (a test, a settings change) already decided. */
    @Synchronized
    fun startIfUnknown(policy: AppLockPolicy) {
        if (mutableStatus.value == LockStatus.UNKNOWN) start(policy)
    }

    /** The user changed the setting; they are present, so enabling does not lock them out. */
    @Synchronized
    fun onPolicyChanged(policy: AppLockPolicy) {
        mutablePolicy.value = policy
        backgroundedAt = null
        backgroundedOnExternalTrip = false
        if (!policy.enabled) mutableStatus.value = LockStatus.UNLOCKED
    }

    @Synchronized
    fun onBackground() {
        val current = mutablePolicy.value
        if (!current.enabled) return
        backgroundedOnExternalTrip = externalActivity
        if (current.timeoutSeconds == 0 && !externalActivity) {
            mutableStatus.value = LockStatus.LOCKED
        } else {
            backgroundedAt = clock()
        }
    }

    @Synchronized
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val current = mutablePolicy.value
        val timeoutMillis = current.timeoutSeconds * MILLIS_PER_SECOND
        val allowedMillis = if (backgroundedOnExternalTrip) {
            maxOf(timeoutMillis, EXTERNAL_GRACE_MILLIS)
        } else {
            timeoutMillis
        }
        backgroundedOnExternalTrip = false
        if (current.enabled && clock() - since >= allowedMillis) {
            mutableStatus.value = LockStatus.LOCKED
        }
    }

    /** OpenLife is about to start another app's activity and expects to come straight back. */
    @Synchronized
    fun beginExternalActivity() {
        externalActivity = true
    }

    @Synchronized
    fun endExternalActivity() {
        externalActivity = false
    }

    @Synchronized
    fun onUnlocked() {
        externalActivity = false
        backgroundedAt = null
        backgroundedOnExternalTrip = false
        mutableStatus.value = LockStatus.UNLOCKED
    }

    companion object {
        /**
         * P1-19-R1: on a trip to an activity OpenLife started, an absence this
         * long (or the timeout, if longer) still locks on return.
         */
        const val EXTERNAL_GRACE_MILLIS = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
