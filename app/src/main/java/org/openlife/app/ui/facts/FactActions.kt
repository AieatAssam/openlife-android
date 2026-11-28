package org.openlife.app.ui.facts

import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.ReviewDecision

/** What a fact card can ask for. Every callback carries the item it acts on, from the state that was rendered. */
class FactActions(
    val onOpenEvidence: (FactEvidence) -> Unit,
    /** P3-05-R3 (C2-R39): evidence on an earlier reading opens that reading, read-only. */
    val onOpenEarlierEvidence: (FactEvidence) -> Unit = {},
    val onReview: (FactItem, ReviewDecision) -> Unit,
    val onChoose: (FactItem, Int) -> Unit,
    /** C2-R37: corrects one revision with the typed input of the same kind; the item is the one rendered. */
    val onCorrect: (FactItem, UserFactInput) -> Unit = { _, _ -> },
    /** P3-05-R4: re-runs extraction for the source; offered when a fact is stale. */
    val onFindAgain: () -> Unit = {},
)
