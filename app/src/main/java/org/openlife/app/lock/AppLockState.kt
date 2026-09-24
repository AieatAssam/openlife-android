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
 * With a zero timeout the lock engages at the background transition itself,
 * so there is no moment on return in which old content could recompose
 * before the gate. Leaving for an activity OpenLife itself started (the
 * system credential screen, the Photo Picker) is not a background period.
 */
class AppLockState(private val clock: () -> Long) {
    private val mutableStatus = MutableStateFlow(LockStatus.UNKNOWN)
    private val mutablePolicy = MutableStateFlow(AppLockPolicy())
    private var backgroundedAt: Long? = null
    private var externalActivity = false

    val status: StateFlow<LockStatus> = mutableStatus.asStateFlow()
    val policy: StateFlow<AppLockPolicy> = mutablePolicy.asStateFlow()

    /** Process start: lock if enabled. */
    @Synchronized
    fun start(policy: AppLockPolicy) {
        mutablePolicy.value = policy
        backgroundedAt = null
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
        if (!policy.enabled) mutableStatus.value = LockStatus.UNLOCKED
    }

    @Synchronized
    fun onBackground() {
        val current = mutablePolicy.value
        if (!current.enabled || externalActivity) return
        if (current.timeoutSeconds == 0) {
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
        if (current.enabled && clock() - since >= current.timeoutSeconds * MILLIS_PER_SECOND) {
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
        mutableStatus.value = LockStatus.UNLOCKED
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
