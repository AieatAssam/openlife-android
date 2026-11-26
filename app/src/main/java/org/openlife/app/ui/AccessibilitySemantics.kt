package org.openlife.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * P1-10: a status that appears without focus moving to it (progress, a
 * result, an error) is read out politely by TalkBack. Status text only:
 * never put document content in a live region.
 */
fun Modifier.announceStatus(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** P1-10: marks a title as a heading, so TalkBack's heading navigation reaches it. */
fun Modifier.asHeading(): Modifier = semantics { heading() }
