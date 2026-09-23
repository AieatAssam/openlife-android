package org.openlife.app.ui

import android.graphics.Bitmap

/** What the viewer's image area shows (P1-13-R2, R4). */
internal sealed interface ViewerContentState {
    /** Loading or authenticating; never shown after a load has finished. */
    data object Loading : ViewerContentState

    /** Authenticated and decoded. The viewer is this bitmap's only owner. */
    class Shown(val bitmap: Bitmap) : ViewerContentState

    /** The saved content failed authentication, is missing, or does not decode. */
    data object Unreadable : ViewerContentState

    /** Keystore or storage failed for now; the content was not judged damaged. */
    data object Transient : ViewerContentState
}
