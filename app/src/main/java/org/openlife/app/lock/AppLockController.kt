package org.openlife.app.lock

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's one lock (P1-07, ADR-0004): owns [AppLockState], feeds it
 * process foreground/background from [ProcessLifecycleOwner], persists the
 * policy, and is the gate every content read awaits.
 */
class AppLockController(private val context: Context, private val scope: CoroutineScope) {
    /** elapsedRealtime keeps counting through device sleep; see [AppLockState]. */
    val state = AppLockState(clock = SystemClock::elapsedRealtime)
    val status: StateFlow<LockStatus> get() = state.status
    val policy: StateFlow<AppLockPolicy> get() = state.policy

    private val realAuthenticator by lazy { BiometricPromptAuthenticator(context) }

    @Volatile
    private var authenticatorOverride: BiometricAuthenticator? = null
    val authenticator: BiometricAuthenticator get() = authenticatorOverride ?: realAuthenticator

    /** Test seam: the number of item-content reads let through [awaitContentAccess]. */
    val contentReads = AtomicInteger()

    /** Called once from Application.onCreate: read the policy off the main thread and watch the process. */
    fun start() {
        scope.launch { state.startIfUnknown(AppLockPolicyStore.load(context)) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = state.onForeground()

                override fun onStop(owner: LifecycleOwner) = state.onBackground()
            },
        )
    }

    /**
     * P1-07-R2: every UI path that decrypts item bytes or opens a shared
     * stream awaits this first, so nothing is read while locked even if a
     * screen were composed by mistake.
     */
    suspend fun awaitContentAccess() {
        status.first { it == LockStatus.UNLOCKED }
        contentReads.incrementAndGet()
    }

    /** Settings and onboarding change the policy here; it is persisted off the main thread. */
    fun updatePolicy(policy: AppLockPolicy) {
        state.onPolicyChanged(policy)
        scope.launch { AppLockPolicyStore.save(context, policy) }
    }

    /** Test seam: a scripted authenticator and a policy applied as if at process start. */
    fun setForTest(authenticator: BiometricAuthenticator, policy: AppLockPolicy) {
        authenticatorOverride = authenticator
        AppLockPolicyStore.write(context, policy)
        state.start(policy)
    }

    fun resetForTest() {
        authenticatorOverride = null
        AppLockPolicyStore.write(context, AppLockPolicy())
        state.start(AppLockPolicy())
    }
}
