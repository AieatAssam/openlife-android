package org.openlife.app.ui

import androidx.annotation.StringRes
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.Source
import org.openlife.vault.model.UserContentKind

/** The three answers to "Is this a message or request from someone?" (P2-07-R3). */
enum class ContentKindChoice(val stored: UserContentKind?) {
    YES(UserContentKind.COMMUNICATION),
    NO(UserContentKind.NOT_COMMUNICATION),
    NOT_SURE(null),
}

/** P2-07 viewer rules, kept out of Compose so they run on the JVM. RED stub. */
object ContentKindLabel {
    fun selected(source: Source): ContentKindChoice? = TODO("P2-07 GREEN")

    @StringRes
    fun assessmentText(state: AssessmentState): Int = TODO("P2-07 GREEN")
}
