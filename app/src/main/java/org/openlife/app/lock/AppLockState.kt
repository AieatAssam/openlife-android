package org.openlife.app.lock

import kotlinx.coroutines.flow.StateFlow

/** Whether content may be shown. UNKNOWN (policy not read yet) is treated as locked. */
enum class LockStatus { UNKNOWN, LOCKED, UNLOCKED }

/** P1-07 stub. */
class AppLockState(private val clock: () -> Long) {
    val status: StateFlow<LockStatus> get() = TODO()

    fun start(policy: AppLockPolicy): Unit = TODO()

    fun onPolicyChanged(policy: AppLockPolicy): Unit = TODO()

    fun onBackground(): Unit = TODO()

    fun onForeground(): Unit = TODO()

    fun beginExternalActivity(): Unit = TODO()

    fun endExternalActivity(): Unit = TODO()

    fun onUnlocked(): Unit = TODO()
}
