package org.openlife.app.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import org.openlife.app.R

/**
 * P1-07-R2: [content] is composed only while [status] is UNLOCKED, so no
 * content-bearing composable exists (and none can start loading bytes)
 * while locked. UNKNOWN composes nothing; the caller keeps the splash up.
 */
@Composable
fun AppLockGate(
    status: LockStatus,
    lockState: AppLockState,
    authenticator: BiometricAuthenticator,
    activity: FragmentActivity,
    content: @Composable () -> Unit,
) {
    when (status) {
        LockStatus.UNKNOWN -> Unit
        LockStatus.UNLOCKED -> content()
        LockStatus.LOCKED -> LockedHost(lockState, authenticator, activity)
    }
}

@Composable
private fun LockedHost(lockState: AppLockState, authenticator: BiometricAuthenticator, activity: FragmentActivity) {
    var availability by remember { mutableStateOf(authenticator.availability()) }
    var prompting by remember { mutableStateOf(false) }
    var notVerified by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The credential or fingerprint can be removed while OpenLife is away (R4).
    LifecycleResumeEffect(authenticator) {
        availability = authenticator.availability()
        onPauseOrDispose {}
    }

    val prompt: () -> Unit = {
        if (!prompting) {
            prompting = true
            notVerified = false
            lockState.beginExternalActivity()
            authenticator.authenticate(activity) { outcome ->
                prompting = false
                when (outcome) {
                    AuthOutcome.Succeeded -> lockState.onUnlocked()

                    AuthOutcome.Cancelled -> lockState.endExternalActivity()

                    AuthOutcome.CannotVerify -> {
                        lockState.endExternalActivity()
                        notVerified = true
                        availability = authenticator.availability()
                    }
                }
            }
        }
    }

    // Prompt once, automatically, when the locked screen is resumed and able to verify.
    LaunchedEffect(availability) {
        if (availability == LockAvailability.AVAILABLE) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            prompt()
        }
    }

    if (availability == LockAvailability.AVAILABLE) {
        LockScreen(
            title = stringResource(R.string.lock_title),
            body = stringResource(R.string.lock_body),
            note = if (notVerified) stringResource(R.string.lock_not_verified) else null,
            action = stringResource(R.string.lock_unlock),
            onAction = prompt,
        )
    } else {
        // Never unlock silently when nothing can verify the user (R4).
        LockScreen(
            title = stringResource(R.string.lock_cannot_verify_title),
            body = stringResource(R.string.lock_cannot_verify_body),
            note = null,
            action = stringResource(R.string.lock_check_again),
            onAction = { availability = authenticator.availability() },
        )
    }
}

/** Shows only fixed copy: no item count, label or other content-derived text (R7). */
@Composable
private fun LockScreen(title: String, body: String, note: String?, action: String, onAction: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * P1-07-R1: offered once, right after the first-run explanation in the
 * main app. [onEnable] returns false when the device cannot verify, and the
 * explanation is shown instead.
 */
@Composable
fun AppLockOfferScreen(onEnable: () -> LockAvailability, onSkip: () -> Unit) {
    var refusal by remember { mutableStateOf<LockAvailability?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_lock_offer_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.app_lock_offer_body), style = MaterialTheme.typography.bodyLarge)
            refusal?.let { Text(stringResource(refusalText(it)), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val availability = onEnable()
                    refusal = availability.takeIf { it != LockAvailability.AVAILABLE }
                },
            ) { Text(stringResource(R.string.app_lock_offer_enable)) }
            OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.app_lock_offer_skip)) }
        }
    }
}

fun refusalText(availability: LockAvailability): Int =
    if (availability == LockAvailability.NOT_ENROLLED) R.string.app_lock_not_enrolled else R.string.app_lock_unavailable
