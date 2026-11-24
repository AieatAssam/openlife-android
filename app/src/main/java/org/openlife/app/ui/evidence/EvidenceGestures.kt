package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset

/** One pointer in one pointer event, reduced to what tap classification needs. */
internal data class PointerSample(val id: Long, val position: Offset, val pressed: Boolean, val consumed: Boolean)

/**
 * Decides whether one gesture on the evidence image is a tap (P2-06-R9, E-02).
 * Pure: the pointer-input adapter feeds it every event. A gesture stops being
 * a tap, for good, when the first pointer moves past touch slop, when a
 * second pointer goes down, or when anyone (the column scroll, the zoom/pan
 * transform) consumes a change.
 */
internal class TapGesture(private val pointerId: Long, private val downPosition: Offset, private val touchSlop: Float) {
    sealed interface Step {
        data object Pending : Step

        data object Abandoned : Step

        data class Released(val position: Offset) : Step
    }

    /** An event seen in the main pass, before parents (the scrolling column) react. */
    fun onMain(changes: List<PointerSample>): Step = TODO("P2-06-R9")

    /** The same event in the final pass: a change consumed by anyone abandons the tap. */
    fun onFinal(changes: List<PointerSample>): Step = TODO("P2-06-R9")
}

/**
 * Double-tap timing that survives zoom and pan (P2-06-R9): the second tap must
 * start within [timeoutMillis] of the first one ending. A double tap is
 * consumed, so a third tap starts a new sequence; an abandoned gesture
 * (drag, pinch) clears the sequence.
 */
internal class DoubleTapClock(private val timeoutMillis: Long) {
    enum class Kind { SINGLE, DOUBLE }

    fun onTap(downMillis: Long, upMillis: Long): Kind = TODO("P2-06-R9: $downMillis $upMillis $timeoutMillis")

    fun reset(): Unit = TODO("P2-06-R9")
}
