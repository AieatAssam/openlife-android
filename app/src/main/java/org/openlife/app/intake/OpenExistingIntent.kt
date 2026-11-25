package org.openlife.app.intake

import android.content.Intent
import java.util.UUID

/**
 * Flags for the "Open existing" hand-off from intake to the list and viewer
 * (P1-13-R9, F-44). IntakeActivity runs in the sharing app's task, so
 * NEW_TASK routes to OpenLife's own task; with MainActivity's singleTask
 * launch mode the one existing instance receives the request in onNewIntent.
 */
internal fun openExistingIntentFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

/**
 * P1-19-R17 (A-14): MainActivity is exported, so any app can send it the
 * open-source extra. Only a request carrying the single-use nonce that
 * IntakeActivity issued for this process opens a source; anything else
 * opens the list as a plain launch would. The id is parsed first so a
 * malformed request does not burn a valid nonce.
 */
internal fun openSourceRequest(rawId: String?, nonce: String?, nonces: PickerNonce): UUID? {
    val id = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
    return id.takeIf { nonces.consume(nonce) }
}
