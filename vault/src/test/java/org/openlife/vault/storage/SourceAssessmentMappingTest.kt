package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import java.util.UUID

/**
 * P2-07-R1/R2: every Source carries an assessment state, NOT_ASSESSED by
 * default for new and migrated rows; the user label and its timestamp map
 * both ways; unknown stored values decode tolerantly (P1-18-R14) and are
 * never written back.
 */
class SourceAssessmentMappingTest {

    @Test
    fun newSourcesDefaultToNotAssessedWithNoLabel() {
        val source = source()
        assertEquals(AssessmentState.NOT_ASSESSED, source.assessmentState)
        assertNull(source.userContentKind)
        assertNull(source.userContentKindAt)
        assertNull(source.assessmentMethodVersion)
        val entity = source.toEntity()
        assertEquals("NOT_ASSESSED", entity.assessmentState)
        assertNull(entity.userContentKind)
        assertNull(entity.userContentKindAt)
        assertNull(entity.assessmentMethodVersion)
    }

    @Test
    fun aRowWrittenWithoutAssessmentColumnsReadsAsNotAssessed() {
        // As a v3 row reads after MIGRATION_3_4 adds the columns with their defaults.
        val domain = entity().toDomain()
        assertEquals(AssessmentState.NOT_ASSESSED, domain.assessmentState)
        assertNull(domain.userContentKind)
        assertNull(domain.userContentKindAt)
    }

    @Test
    fun everyKnownStateAndLabelRoundTrips() {
        for (state in AssessmentState.entries - AssessmentState.UNRECOGNISED) {
            val stored = entity().copy(assessmentState = state.name, assessmentMethodVersion = "m-1")
            val domain = stored.toDomain()
            assertEquals(state, domain.assessmentState)
            assertEquals("m-1", domain.assessmentMethodVersion)
            assertEquals(stored, domain.toEntity())
        }
        for (kind in UserContentKind.entries - UserContentKind.UNRECOGNISED) {
            val stored = entity().copy(userContentKind = kind.name, userContentKindAt = 42)
            val domain = stored.toDomain()
            assertEquals(kind, domain.userContentKind)
            assertEquals(42L, domain.userContentKindAt)
            assertEquals(stored, domain.toEntity())
        }
    }

    @Test
    fun notSureIsNoKindWithTheAnswerTime() {
        val domain = entity().copy(userContentKind = null, userContentKindAt = 7).toDomain()
        assertNull(domain.userContentKind)
        assertEquals(7L, domain.userContentKindAt)
    }

    @Test
    fun unknownStoredValuesDecodeToUnrecognisedWithoutCrash() {
        val domain = entity().copy(assessmentState = "VERIFIED_SAFE", userContentKind = "NEWSLETTER").toDomain()
        assertEquals(AssessmentState.UNRECOGNISED, domain.assessmentState)
        assertEquals(UserContentKind.UNRECOGNISED, domain.userContentKind)
    }

    @Test(expected = IllegalStateException::class)
    fun anUnrecognisedAssessmentStateIsNeverWrittenBack() {
        entity().copy(assessmentState = "VERIFIED_SAFE").toDomain().toEntity()
    }

    @Test(expected = IllegalStateException::class)
    fun anUnrecognisedLabelIsNeverWrittenBack() {
        entity().copy(userContentKind = "NEWSLETTER").toDomain().toEntity()
    }

    @Test
    fun equalityIncludesTheNewFields() {
        val base = source()
        assertEquals(false, base == base.copy(assessmentState = AssessmentState.INCONCLUSIVE))
        assertEquals(false, base == base.copy(userContentKind = UserContentKind.COMMUNICATION))
        assertEquals(false, base == base.copy(userContentKindAt = 1))
        assertEquals(false, base == base.copy(assessmentMethodVersion = "m"))
        assertEquals(false, entity() == entity().copy(userContentKindAt = 1))
    }

    private fun source() = Source(
        id = UUID(0, 1),
        state = SourceState.READY,
        importedAt = 1,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32) { 1 },
        width = 10,
        height = 10,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16) { 2 },
        artefactVersion = 1,
    )

    private fun entity() = SourceEntity(
        id = UUID(0, 2).toString(),
        state = "READY",
        importedAt = 1,
        intakeKind = "SHARE",
        mimeType = "image/jpeg",
        byteCount = 10,
        sha256 = ByteArray(32) { 1 },
        width = 10,
        height = 10,
        orientation = "NORMAL",
        wrappedDek = ByteArray(16) { 2 },
        artefactVersion = 1,
    )
}
