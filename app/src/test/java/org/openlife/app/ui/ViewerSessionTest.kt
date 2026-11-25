package org.openlife.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/**
 * P1-20-R2/R4/R9 on the JVM. The instrumented ViewerByIdTest,
 * MultiWindowScrubTest.backgroundingClearsContentButKeepsTheDestination and
 * SavedStateTest exercise the same session through the real NavHost on a
 * device; this proves the rules they rely on.
 */
class ViewerSessionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val ports = FakePorts()
    private val saved = MapSavedValues()
    private val sourceId = UUID(0L, 42L)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun opensASourceByIdWithNoListInvolved() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        session.onVisible()

        assertEquals(SourceLookup.Found(source()), session.source.value)
        assertTrue("content shown: ${session.content.value}", session.content.value is DecodedContent.Shown)
        assertEquals(1, ports.reads)
        assertTrue("plaintext is zeroed after decoding", ports.lastBytes!!.all { it == 0.toByte() })
    }

    @Test
    fun nothingIsReadBeforeTheEntryIsVisible() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())

        assertEquals(0, ports.reads)
        assertEquals(DecodedContent.Loading, session.content.value)
        assertEquals(0, ports.ocr.subscriptionCount.value)
    }

    @Test
    fun aMissingIdRequestsOneExit() {
        val session = newSession()
        ports.sources.value = SourceLookup.Missing

        assertTrue(session.exitRequested.value)
        assertTrue(session.consumeExit())
        assertFalse("returns to the list once", session.consumeExit())
    }

    @Test
    fun deleteAndTheFlowDroppingTheSourceExitOnlyOnce() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        session.delete()
        // A-10: the flow wins the race against the delete callback.
        ports.sources.value = SourceLookup.Missing
        assertTrue(session.consumeExit())
        ports.deleteCallback!!.invoke(true)

        assertFalse("the delete callback must not pop a second time", session.consumeExit())
    }

    @Test
    fun aFailedDeleteDoesNotExit() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        session.delete()
        ports.deleteCallback!!.invoke(false)

        assertFalse(session.exitRequested.value)
    }

    @Test
    fun hidingClearsContentAndOcrTextSynchronouslyButKeepsTheDestination() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        ports.ocr.value = readyOcr()
        session.onVisible()
        val shown = (session.content.value as DecodedContent.Shown).value
        assertTrue(session.ocr.value is OcrUiState.Ready)

        session.onHidden()

        assertEquals(DecodedContent.Loading, session.content.value)
        assertTrue("the decoded image is released", shown.released)
        assertEquals(OcrUiState.Loading, session.ocr.value)
        assertEquals("nobody observes OCR text while hidden", 0, ports.ocr.subscriptionCount.value)
        assertEquals(sourceId, session.sourceId)
        assertEquals("metadata stays", SourceLookup.Found(source()), session.source.value)
        assertFalse(session.exitRequested.value)

        session.onVisible()

        assertEquals("content reloads by id on return", 2, ports.reads)
        assertTrue(session.content.value is DecodedContent.Shown)
        assertTrue(session.ocr.value is OcrUiState.Ready)
    }

    @Test
    fun aTrimWhileVisibleDropsAndReloads() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        session.onVisible()
        val first = (session.content.value as DecodedContent.Shown).value

        session.scrub()

        assertTrue(first.released)
        assertEquals(2, ports.reads)
        assertTrue(session.content.value is DecodedContent.Shown)
    }

    @Test
    fun aDecodeThatFinishesAfterHidingIsReleasedNotShown() {
        val session = newSession()
        val decodeGate = CompletableDeferred<Unit>()
        ports.decodeGate = decodeGate
        ports.sources.value = SourceLookup.Found(source())
        session.onVisible()

        session.onHidden()
        decodeGate.complete(Unit)

        assertEquals(DecodedContent.Loading, session.content.value)
        assertTrue("a late decode is released at once", ports.decoded.single().released)
    }

    @Test
    fun aTransientReadIsRetried() {
        val session = newSession()
        ports.result = ReadyReadResult.Transient
        ports.sources.value = SourceLookup.Found(source())
        session.onVisible()
        assertEquals(DecodedContent.Transient, session.content.value)

        ports.result = ReadyReadResult.Loaded(byteArrayOf(1, 2, 3))
        session.retry()

        assertTrue(session.content.value is DecodedContent.Shown)
    }

    @Test
    fun aCorruptSourceIsUnreadableWithoutReadingBytes() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source(SourceState.CORRUPT))
        session.onVisible()

        assertEquals(DecodedContent.Unreadable, session.content.value)
        assertEquals(0, ports.reads)
    }

    @Test
    fun selectionZoomAndPanAreSavedAsAnIdAndNumbersOnly() {
        val spanId = UUID(0L, 9L)
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        ports.ocr.value = readyOcr()
        session.onVisible()
        session.selectSpan(spanId)
        session.setViewport(zoom = 3f, panX = 12f, panY = -4f)

        val values = saved.values.values
        assertTrue("saved: $values", values.all { it is Float || (it is String && it.isUuid()) })
        assertFalse("no span text", values.any { it.toString().contains(SPAN_TEXT) })

        // Process death: a new session over the restored values.
        val restored = newSession(initialSpanId = null)
        assertEquals(spanId, restored.selectedSpanId.value)
        assertEquals(3f, restored.zoom)
        assertEquals(12f, restored.panX)
        assertEquals(-4f, restored.panY)
    }

    @Test
    fun theRouteSpanIsTheInitialSelectionUntilTheUserChangesIt() {
        val fromRoute = UUID(0L, 5L)
        val session = newSession(initialSpanId = fromRoute)
        assertEquals(fromRoute, session.selectedSpanId.value)

        session.selectSpan(null)

        assertNull(newSession(initialSpanId = fromRoute).selectedSpanId.value)
    }

    @Test
    fun closingReleasesContent() {
        val session = newSession()
        ports.sources.value = SourceLookup.Found(source())
        session.onVisible()
        val shown = (session.content.value as DecodedContent.Shown).value

        session.close()

        assertTrue(shown.released)
        assertSame(DecodedContent.Loading, session.content.value)
    }

    private fun newSession(initialSpanId: UUID? = null) =
        ViewerSession(sourceId, initialSpanId, ports, saved, scope)

    private fun source(state: SourceState = SourceState.READY) = Source(
        id = sourceId,
        state = state,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 3,
        sha256 = null,
        width = 10,
        height = 10,
        orientation = Orientation.NORMAL,
        wrappedDek = null,
        artefactVersion = 1,
    )

    private fun readyOcr(): OcrUiState {
        val revisionId = UUID(0L, 77L)
        val span = OcrSpan(UUID(0L, 9L), revisionId, 0, SPAN_TEXT, null, OcrCoordinateSystem.SOURCE_PIXELS, null)
        return OcrUiState.Ready(sourceId, revisionId, listOf(span))
    }

    private fun String.isUuid() = runCatching { UUID.fromString(this) }.isSuccess

    class FakeImage {
        var released = false
    }

    private class FakePorts : ViewerPorts<FakeImage> {
        val sources = MutableStateFlow<SourceLookup>(SourceLookup.Loading)
        val ocr = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
        override val ocrGeneration = MutableStateFlow(0)
        var result: ReadyReadResult = ReadyReadResult.Loaded(byteArrayOf(1, 2, 3))
        var reads = 0
        var lastBytes: ByteArray? = null
        val decoded = mutableListOf<FakeImage>()
        var decodeGate: CompletableDeferred<Unit>? = null
        var deleteCallback: ((Boolean) -> Unit)? = null

        override fun observeSource(sourceId: UUID) = sources

        override suspend fun readReadyContent(sourceId: UUID): ReadyReadResult {
            reads++
            return when (val current = result) {
                is ReadyReadResult.Loaded -> ReadyReadResult.Loaded(current.bytes.copyOf())
                else -> current
            }
        }

        override suspend fun decode(bytes: ByteArray, orientation: Orientation): FakeImage {
            lastBytes = bytes
            decodeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return FakeImage().also { decoded += it }
        }

        override fun release(value: FakeImage) {
            value.released = true
        }

        override fun ocrState(sourceId: UUID): StateFlow<OcrUiState> = ocr

        override fun delete(sourceId: UUID, onDeleted: (Boolean) -> Unit) {
            deleteCallback = onDeleted
        }
    }

    private class MapSavedValues : SavedValues {
        val values = mutableMapOf<String, Any?>()

        override fun get(key: String): Any? = values[key]

        override fun set(key: String, value: Any?) {
            values[key] = value
        }
    }

    private companion object {
        const val SPAN_TEXT = "Appointment 14 October"
    }
}
