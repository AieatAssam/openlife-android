package org.openlife.app.lock

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The optional app lock's settings (P1-07-R1, ADR-0004). Stored in the
 * private `app_lock` preferences file; vault reset clears it. Neither value
 * says anything about vault content.
 */
data class AppLockPolicy(val enabled: Boolean = false, val timeoutSeconds: Int = 0) {
    companion object {
        /** Fixed choices; a free-form field would invite mistakes (design decision). */
        val TIMEOUT_OPTIONS_SECONDS = listOf(0, 30, 300, 900)
    }
}

object AppLockPolicyStore {
    const val PREFERENCES = "app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMEOUT = "timeout_seconds"

    /** First access to a preferences file is disk I/O, so it never runs on the main thread (P1-13-R3). */
    suspend fun load(context: Context): AppLockPolicy = withContext(Dispatchers.IO) { read(context) }

    suspend fun save(context: Context, policy: AppLockPolicy) = withContext(Dispatchers.IO) { write(context, policy) }

    fun read(context: Context): AppLockPolicy {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val timeout = preferences.getInt(KEY_TIMEOUT, 0)
        return AppLockPolicy(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            timeoutSeconds = timeout.takeIf { it in AppLockPolicy.TIMEOUT_OPTIONS_SECONDS } ?: 0,
        )
    }

    fun write(context: Context, policy: AppLockPolicy) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_ENABLED, policy.enabled)
            putInt(KEY_TIMEOUT, policy.timeoutSeconds)
        }
    }
}
