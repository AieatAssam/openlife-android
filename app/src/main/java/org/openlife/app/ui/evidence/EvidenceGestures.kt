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

    private var abandoned = false

    /** An event seen in the main pass, before parents (the scrolling column) react. */
    fun onMain(changes: List<PointerSample>): Step {
        if (abandoned) return Step.Abandoned
        val tracked = changes.firstOrNull { it.id == pointerId }
        val anotherPointerDown = changes.any { it.id != pointerId && it.pressed }
        val moved = tracked != null && (tracked.position - downPosition).getDistance() > touchSlop
        return when {
            tracked == null || anotherPointerDown || tracked.consumed || moved -> abandon()
            !tracked.pressed -> Step.Released(tracked.position)
            else -> Step.Pending
        }
    }

    /** The same event in the final pass: a change consumed by anyone abandons the tap. */
    fun onFinal(changes: List<PointerSample>): Step = when {
        abandoned -> Step.Abandoned
        changes.any { it.consumed } -> abandon()
        else -> Step.Pending
    }

    private fun abandon(): Step {
        abandoned = true
        return Step.Abandoned
    }
}

/**
 * Double-tap timing that survives zoom and pan (P2-06-R9): the second tap must
 * start within [timeoutMillis] of the first one ending and land within [slop]
 * of it (S-04), so two quick taps on different places are two selections. A
 * double tap is consumed, so a third tap starts a new sequence; an abandoned
 * gesture (drag, pinch) clears the sequence.
 */
internal class DoubleTapClock(private val timeoutMillis: Long, private val slop: Float = Float.POSITIVE_INFINITY) {
    enum class Kind { SINGLE, DOUBLE }

    // Null, not zero: instrumentation clocks start near zero, so a first tap
    // would otherwise look like the second half of a double tap.
    private var lastUpMillis: Long? = null
    private var lastPosition = Offset.Zero

    fun onTap(downMillis: Long, upMillis: Long, position: Offset = Offset.Zero): Kind {
        val previous = lastUpMillis
        val inTime = previous != null && downMillis - previous <= timeoutMillis
        val double = inTime && (position - lastPosition).getDistance() <= slop
        lastUpMillis = if (double) null else upMillis
        lastPosition = position
        return if (double) Kind.DOUBLE else Kind.SINGLE
    }

    fun reset() {
        lastUpMillis = null
    }
}
