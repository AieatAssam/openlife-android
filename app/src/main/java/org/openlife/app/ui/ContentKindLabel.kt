package org.openlife.app.ui

import androidx.annotation.StringRes
import org.openlife.app.R
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind

/** The three answers to "Is this a message or request from someone?" (P2-07-R3). */
enum class ContentKindChoice(
    val stored: UserContentKind?,
    @StringRes val label: Int,
) {
    YES(UserContentKind.COMMUNICATION, R.string.viewer_content_kind_yes),
    NO(UserContentKind.NOT_COMMUNICATION, R.string.viewer_content_kind_no),
    NOT_SURE(null, R.string.viewer_content_kind_not_sure),
}

/** P2-07 viewer rules, kept out of Compose so they run on the JVM. */
object ContentKindLabel {
    /**
     * Review T-01: the question is asked only of a saved (READY) source; the
     * label write is a READY-only compare-and-set, so offering it for a
     * CORRUPT or DELETING item would let a tap do nothing.
     */
    fun showQuestion(source: Source): Boolean = source.state == SourceState.READY

    /**
     * Review T-06: the stored label is one this build cannot read (written by
     * another version). The viewer says so instead of presenting it as this
     * build's answer; choosing an answer here replaces it.
     */
    fun recordedByAnotherVersion(source: Source): Boolean = source.userContentKind == UserContentKind.UNRECOGNISED

    /**
     * The answer the user gave, or null when they never answered. "Not sure"
     * stores no kind, so the USER timestamp is what tells it apart from
     * "never answered". A kind this build cannot read selects nothing.
     */
    fun selected(source: Source): ContentKindChoice? = when {
        source.userContentKindAt == null -> null
        source.userContentKind == UserContentKind.UNRECOGNISED -> null
        else -> ContentKindChoice.entries.first { it.stored == source.userContentKind }
    }

    /** The answer to store when the user picks [choice]; null when it is already the stored answer. */
    fun change(source: Source, choice: ContentKindChoice): ContentKindChoice? = choice.takeIf { it != selected(source) }

    /**
     * P2-07-R4: this build runs no assessment, so NOT_ASSESSED is shown as
     * "not assessed in this version". Any other stored state was written by
     * another build; this one cannot explain it and shows no result for it,
     * least of all a reassuring one.
     */
    @StringRes
    fun assessmentText(state: AssessmentState): Int = if (state == AssessmentState.NOT_ASSESSED) {
        R.string.viewer_assessment_not_assessed
    } else {
        R.string.viewer_assessment_other_version
    }
}
