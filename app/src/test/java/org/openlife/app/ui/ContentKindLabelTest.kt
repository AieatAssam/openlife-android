package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.R
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import java.io.File
import java.util.UUID

/**
 * P2-07-R3/R4 on the JVM: the label's three answers map to the stored kind
 * (Not sure is null), the viewer tells "never answered" from "Not sure" by
 * the USER timestamp, the assessment line is "not assessed in this version"
 * and no copy calls anything safe.
 */
class ContentKindLabelTest {
    private val strings by lazy {
        val projectDir = File(System.getProperty("openlife.projectDir") ?: "..")
        File(projectDir, "app/src/main/res/values/strings.xml").readText()
    }

    @Test
    fun answersMapToTheStoredKind() {
        assertEquals(UserContentKind.COMMUNICATION, ContentKindChoice.YES.stored)
        assertEquals(UserContentKind.NOT_COMMUNICATION, ContentKindChoice.NO.stored)
        assertNull(ContentKindChoice.NOT_SURE.stored)
    }

    @Test
    fun neverAnsweredSelectsNothing() {
        assertNull(ContentKindLabel.selected(source(kind = null, at = null)))
    }

    @Test
    fun theStoredAnswerIsSelectedAndNotSureNeedsItsTimestamp() {
        assertEquals(ContentKindChoice.YES, ContentKindLabel.selected(source(UserContentKind.COMMUNICATION, 5)))
        assertEquals(ContentKindChoice.NO, ContentKindLabel.selected(source(UserContentKind.NOT_COMMUNICATION, 5)))
        assertEquals(ContentKindChoice.NOT_SURE, ContentKindLabel.selected(source(null, 5)))
    }

    @Test
    fun pickingTheStoredAnswerAgainWritesNothingButAnyOtherAnswerDoes() {
        val answeredYes = source(UserContentKind.COMMUNICATION, 5)
        assertNull(ContentKindLabel.change(answeredYes, ContentKindChoice.YES))
        assertEquals(ContentKindChoice.NOT_SURE, ContentKindLabel.change(answeredYes, ContentKindChoice.NOT_SURE))
        val neverAnswered = source(null, null)
        assertEquals(
            "a first Not sure is an answer",
            ContentKindChoice.NOT_SURE,
            ContentKindLabel.change(neverAnswered, ContentKindChoice.NOT_SURE),
        )
        assertNull(ContentKindLabel.change(source(null, 5), ContentKindChoice.NOT_SURE))
    }

    @Test
    fun aKindThisBuildCannotReadSelectsNothing() {
        assertNull(ContentKindLabel.selected(source(UserContentKind.UNRECOGNISED, 5)))
    }

    /** Review T-06: another build's label is named as such, not shown as this build's answer. */
    @Test
    fun onlyAnUnreadableKindIsRecordedByAnotherVersionAndAnyAnswerReplacesIt() {
        val foreign = source(UserContentKind.UNRECOGNISED, 5)
        assertTrue(ContentKindLabel.recordedByAnotherVersion(foreign))
        assertEquals(ContentKindChoice.NOT_SURE, ContentKindLabel.change(foreign, ContentKindChoice.NOT_SURE))
        assertFalse(ContentKindLabel.recordedByAnotherVersion(source(null, null)))
        assertFalse(ContentKindLabel.recordedByAnotherVersion(source(null, 5)))
        assertFalse(ContentKindLabel.recordedByAnotherVersion(source(UserContentKind.COMMUNICATION, 5)))
        assertTrue(strings.contains("name=\"viewer_content_kind_other_version\""))
    }

    /** Review T-01: the label write is READY-only, so only a READY source is asked. */
    @Test
    fun onlyAReadySourceIsAskedTheQuestion() {
        assertTrue(ContentKindLabel.showQuestion(source(null, null)))
        for (state in SourceState.entries - SourceState.READY) {
            assertFalse(state.name, ContentKindLabel.showQuestion(source(null, null).copy(state = state)))
        }
    }

    @Test
    fun notAssessedIsShownAsNotAssessedInThisVersion() {
        assertEquals(
            R.string.viewer_assessment_not_assessed,
            ContentKindLabel.assessmentText(AssessmentState.NOT_ASSESSED),
        )
        assertTrue(strings.contains(NOT_ASSESSED_RESOURCE))
    }

    @Test
    fun noOtherStateIsShownAsAResultThisBuildProduced() {
        for (state in AssessmentState.entries - AssessmentState.NOT_ASSESSED) {
            assertEquals(state.name, R.string.viewer_assessment_other_version, ContentKindLabel.assessmentText(state))
        }
    }

    @Test
    fun routingCopySaysTheLabelIsNotProofOfSafety() {
        val routing = Regex("<string name=\"viewer_content_kind_routing\">([^<]*)</string>").find(strings)
        assertTrue("viewer_content_kind_routing missing", routing != null)
        assertTrue(routing!!.groupValues[1], routing.groupValues[1].contains("not proof of safety"))
        assertTrue(strings.contains(QUESTION_RESOURCE))
    }

    @Test
    fun noStringCallsAnythingSafe() {
        val safe = Regex("\\bsafe(ly)?\\b", RegexOption.IGNORE_CASE)
        val offending = strings.lines().filter { safe.containsMatchIn(it) }
        assertFalse("copy must never display or imply safe: $offending", offending.isNotEmpty())
    }

    private fun source(kind: UserContentKind?, at: Long?) = Source(
        id = UUID(0, 9),
        state = SourceState.READY,
        importedAt = 1,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 1,
        sha256 = ByteArray(32),
        width = 1,
        height = 1,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
        userContentKind = kind,
        userContentKindAt = at,
    )

    private companion object {
        const val NOT_ASSESSED_RESOURCE =
            "<string name=\"viewer_assessment_not_assessed\">Safety check: not assessed in this version</string>"
        const val QUESTION_RESOURCE =
            "<string name=\"viewer_content_kind_question\">Is this a message or request from someone?</string>"
    }
}
