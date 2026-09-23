package org.openlife.app.intake

import android.content.Intent

/** Flags for the "Open existing" hand-off from intake to the list and viewer (P1-13-R9). */
internal fun openExistingIntentFlags(): Int = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
