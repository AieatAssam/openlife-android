package org.openlife.app.intake

import android.content.Intent

/**
 * Flags for the "Open existing" hand-off from intake to the list and viewer
 * (P1-13-R9, F-44). IntakeActivity runs in the sharing app's task, so
 * NEW_TASK routes to OpenLife's own task; with MainActivity's singleTask
 * launch mode the one existing instance receives the request in onNewIntent.
 */
internal fun openExistingIntentFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
