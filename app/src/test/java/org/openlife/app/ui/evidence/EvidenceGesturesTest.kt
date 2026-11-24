package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-06-R9 (E-02): a tap on the evidence image never fires after a drag, a
 * scroll or a pinch, and double-tap timing is independent of zoom and pan.
 */
class EvidenceGesturesTest {
    private val slop = 8f
    private val start = Offset(100f, 100f)

    @Test
    fun aStillPressAndReleaseIsATapAtTheReleasePosition() {
        val tap = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Pending, tap.onMain(listOf(sample(FIRST, Offset(103f, 104f)))))
        assertEquals(TapGesture.Step.Pending, tap.onFinal(listOf(sample(FIRST, Offset(103f, 104f)))))
        assertEquals(
            TapGesture.Step.Released(Offset(103f, 104f)),
            tap.onMain(listOf(sample(FIRST, Offset(103f, 104f), pressed = false))),
        )
    }

    @Test
    fun movementPastTouchSlopAbandonsTheTapEvenIfTheFingerComesBack() {
        val tap = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Abandoned, tap.onMain(listOf(sample(FIRST, Offset(100f, 60f)))))
        // A vertical scroll that returns to where it started is still not a tap.
        assertEquals(TapGesture.Step.Abandoned, tap.onMain(listOf(sample(FIRST, start, pressed = false))))
    }

    @Test
    fun aSecondPointerAbandonsTheTapSoAPinchReleaseNeverSelects() {
        val tap = TapGesture(FIRST, start, slop)
        val pinch = listOf(sample(FIRST, start), sample(SECOND, Offset(160f, 160f)))
        assertEquals(TapGesture.Step.Abandoned, tap.onMain(pinch))
        assertEquals(
            "lifting the first finger after a still two-finger press",
            TapGesture.Step.Abandoned,
            tap.onMain(listOf(sample(FIRST, start, pressed = false), sample(SECOND, Offset(160f, 160f)))),
        )
    }

    @Test
    fun aConsumedChangeAbandonsTheTapInEitherPass() {
        val consumedInMain = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Abandoned, consumedInMain.onMain(listOf(sample(FIRST, start, consumed = true))))

        // The column's scroll consumes after our main pass; the final pass sees it.
        val consumedByParent = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Pending, consumedByParent.onMain(listOf(sample(FIRST, Offset(102f, 100f)))))
        assertEquals(
            TapGesture.Step.Abandoned,
            consumedByParent.onFinal(listOf(sample(FIRST, Offset(102f, 100f), consumed = true))),
        )
        assertEquals(
            TapGesture.Step.Abandoned,
            consumedByParent.onMain(listOf(sample(FIRST, Offset(102f, 100f), pressed = false))),
        )
    }

    @Test
    fun aMissingFirstPointerAbandonsTheTap() {
        val tap = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Abandoned, tap.onMain(listOf(sample(SECOND, start, pressed = false))))
    }

    @Test
    fun twoTapsInsideTheTimeoutAreADoubleTapAndAThirdStartsAgain() {
        val clock = DoubleTapClock(timeoutMillis = 300)
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 0, upMillis = 50))
        assertEquals(DoubleTapClock.Kind.DOUBLE, clock.onTap(downMillis = 300, upMillis = 340))
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 400, upMillis = 450))
    }

    @Test
    fun aSlowSecondTapIsASingleTapAndAFirstTapNearTimeZeroIsNotADoubleTap() {
        val clock = DoubleTapClock(timeoutMillis = 300)
        // Instrumentation clocks start near zero: a first tap must still select.
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 0, upMillis = 0))
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 301, upMillis = 320))
        assertEquals(DoubleTapClock.Kind.DOUBLE, clock.onTap(downMillis = 620, upMillis = 640))
    }

    @Test
    fun anAbandonedGestureBetweenTapsBreaksTheDoubleTap() {
        val clock = DoubleTapClock(timeoutMillis = 300)
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 0, upMillis = 40))
        clock.reset()
        assertEquals(DoubleTapClock.Kind.SINGLE, clock.onTap(downMillis = 100, upMillis = 140))
    }

    /** S-04: two quick taps far apart are two selections, not a zoom. */
    @Test
    fun twoQuickTapsFarApartAreTwoSingleTapsNotAZoom() {
        val clock = DoubleTapClock(timeoutMillis = 300, slop = 50f)
        fun tapAt(downMillis: Long, x: Float, y: Float) = clock.onTap(downMillis, downMillis + 40, Offset(x, y))
        assertEquals(DoubleTapClock.Kind.SINGLE, tapAt(0, 10f, 10f))
        assertEquals("150 px away, inside the timeout", DoubleTapClock.Kind.SINGLE, tapAt(100, 200f, 10f))
        // The far tap starts a new sequence: a quick, near third tap completes it.
        assertEquals(DoubleTapClock.Kind.DOUBLE, tapAt(200, 205f, 12f))
    }

    /** S-06: the adapter feeds the down's final pass to [TapGesture.onFinal]; a parent consuming it ends the tap. */
    @Test
    fun aDownConsumedByAParentIsNotATap() {
        val tap = TapGesture(FIRST, start, slop)
        assertEquals(TapGesture.Step.Abandoned, tap.onFinal(listOf(sample(FIRST, start, consumed = true))))
        assertEquals(TapGesture.Step.Abandoned, tap.onMain(listOf(sample(FIRST, start, pressed = false))))
    }

    private fun sample(id: Long, position: Offset, pressed: Boolean = true, consumed: Boolean = false) =
        PointerSample(id, position, pressed, consumed)

    private companion object {
        const val FIRST = 1L
        const val SECOND = 2L
    }
}
