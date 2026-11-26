package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.facts.DependencyEdgeKind
import org.openlife.vault.facts.DependencyNodeKind
import org.openlife.vault.facts.EvidenceRole
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevisionState
import java.util.UUID

/**
 * P1-18-R14 (VS-1): a stored enum written by a newer (or older) build must
 * never crash decoding. Unknown values decode to an explicit fallback; known
 * values still decode exactly.
 */
class EnumDecodingTest {

    @Test
    fun unknownStoredValuesDecodeToFallbackWithoutCrash() {
        val source = sourceEntity(
            state = "ARCHIVED_IN_A_FUTURE_BUILD",
            intakeKind = "CAMERA",
            orientation = "ROTATE_45",
        )
            .copy(mimeType = "image/heic")
            .toDomain()
        assertEquals(SourceState.UNRECOGNISED, source.state)
        assertEquals(IntakeKind.UNRECOGNISED, source.intakeKind)
        assertNull("an unknown orientation is unknown, not NORMAL", source.orientation)
        assertNull("an unknown format is unknown, not guessed", source.mimeType)

        val revision = revisionEntity(
            state = "PAUSED",
            orientation = "ROTATE_45",
            reviewState = "DEFERRED",
            failureReason = "QUOTA",
        ).toDomain()
        assertEquals(OcrRevisionState.UNRECOGNISED, revision.state)
        assertEquals(OcrReviewState.UNRECOGNISED, revision.reviewState)
        assertEquals(OcrFailureReason.UNRECOGNISED, revision.failureReason)
        assertNull(revision.orientation)

        val span = spanEntity(coordinateSystem = "DISPLAY_POINTS").toDomain()
        assertEquals(OcrCoordinateSystem.UNRECOGNISED, span.coordinateSystem)
    }

    @Test
    fun unknownFactEnumsDecodeToUnrecognisedAndAreReadOnly() {
        val revision = FactRevisionEntity(
            id = UUID.randomUUID().toString(),
            factId = UUID.randomUUID().toString(),
            revisionNo = 1,
            origin = "DERIVED",
            kind = "MERCHANT",
            valueJson = "{}",
            canonicalText = "t",
            rawText = null,
            status = "DISPUTED",
            staleReason = "INPUT_DELETED",
            staleAt = 1,
            confidence = null,
            methodId = "m",
            methodVersion = null,
            locale = null,
            createdAt = 1,
            supersedesRevisionId = null,
        ).toDomain()
        assertEquals(FactOrigin.UNRECOGNISED, revision.origin)
        assertEquals(FactKind.UNRECOGNISED, revision.kind)
        assertEquals(FactStatus.UNRECOGNISED, revision.status)
        assertEquals(StaleReason.UNRECOGNISED, revision.staleReason)
        assertEquals(false, revision.isRecognised)
        assertEquals(ReviewDecision.UNRECOGNISED, decodeStored("ESCALATE", ReviewDecision.UNRECOGNISED))
        assertEquals(EvidenceRole.UNRECOGNISED, decodeStored("CONTEXT", EvidenceRole.UNRECOGNISED))
        assertEquals(DependencyEdgeKind.UNRECOGNISED, decodeStored("INPUT", DependencyEdgeKind.UNRECOGNISED))
        assertEquals(DependencyNodeKind.UNRECOGNISED, decodeStored("RECORD", DependencyNodeKind.UNRECOGNISED))
        assertEquals(ExtractionTrigger.UNRECOGNISED, decodeStored("SCHEDULED", ExtractionTrigger.UNRECOGNISED))
        assertEquals(ExtractionOutcome.UNRECOGNISED, decodeStored("CANCELLED", ExtractionOutcome.UNRECOGNISED))
        assertEquals(SpanScriptStatus.UNRECOGNISED, decodeStored("CYRILLIC", SpanScriptStatus.UNRECOGNISED))
        assertEquals(FactStatus.CLARIFICATION_NEEDED, decodeStored("CLARIFICATION_NEEDED", FactStatus.UNRECOGNISED))
    }

    @Test
    fun knownStoredValuesStillDecodeExactly() {
        val source = sourceEntity(state = "READY", intakeKind = "PHOTO_PICKER", orientation = "ROTATE_90").toDomain()
        assertEquals(SourceState.READY, source.state)
        assertEquals(IntakeKind.PHOTO_PICKER, source.intakeKind)
        assertEquals(Orientation.ROTATE_90, source.orientation)
        assertEquals(ImageFormat.JPEG, source.mimeType)

        val revision = revisionEntity("READY", "NORMAL", "ACCEPTED", "TIMEOUT").toDomain()
        assertEquals(OcrRevisionState.READY, revision.state)
        assertEquals(OcrReviewState.ACCEPTED, revision.reviewState)
        assertEquals(OcrFailureReason.TIMEOUT, revision.failureReason)
        assertEquals(Orientation.NORMAL, revision.orientation)
        assertEquals(OcrCoordinateSystem.SOURCE_PIXELS, spanEntity("SOURCE_PIXELS").toDomain().coordinateSystem)
    }

    /** P2-06 under P1-18-R14: an unknown span review state decodes to UNRECOGNISED, never crashes. */
    @Test
    fun unknownSpanReviewStateDecodesToFallback() {
        val spanId = UUID.randomUUID().toString()
        val unknown = OcrSpanReviewEntity(UUID.randomUUID().toString(), spanId, "DISPUTED", 1, 1).toDomain()
        assertEquals(OcrReviewState.UNRECOGNISED, unknown.reviewState)
        val known = OcrSpanReviewEntity(UUID.randomUUID().toString(), spanId, "REJECTED", 1, 2).toDomain()
        assertEquals(OcrReviewState.REJECTED, known.reviewState)
    }

    @Test(expected = IllegalStateException::class)
    fun anUnrecognisedSourceIsNeverWrittenBack() {
        sourceEntity(state = "FUTURE", intakeKind = "SHARE", orientation = "NORMAL").toDomain().toEntity()
    }

    private fun sourceEntity(state: String, intakeKind: String, orientation: String) = SourceEntity(
        id = UUID.randomUUID().toString(),
        state = state,
        importedAt = 1,
        intakeKind = intakeKind,
        mimeType = "image/jpeg",
        byteCount = 10,
        sha256 = ByteArray(32) { 1 },
        width = 10,
        height = 10,
        orientation = orientation,
        wrappedDek = ByteArray(16) { 2 },
        artefactVersion = 1,
    )

    private fun revisionEntity(state: String, orientation: String, reviewState: String, failureReason: String) =
        OcrRevisionEntity(
            id = UUID.randomUUID().toString(),
            sourceId = UUID.randomUUID().toString(),
            state = state,
            engineId = "engine",
            modelVersion = "1",
            orientation = orientation,
            sourceDigest = ByteArray(32) { 3 },
            startedAt = 1,
            extractedAt = null,
            reviewState = reviewState,
            failureReason = failureReason,
            charCount = 0,
            spanCount = 0,
        )

    private fun spanEntity(coordinateSystem: String) = OcrSpanEntity(
        id = UUID.randomUUID().toString(),
        revisionId = UUID.randomUUID().toString(),
        ordinal = 0,
        text = "text",
        confidence = null,
        coordinateSystem = coordinateSystem,
        left = null,
        top = null,
        right = null,
        bottom = null,
    )
}
