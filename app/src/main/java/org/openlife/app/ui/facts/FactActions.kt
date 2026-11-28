package org.openlife.app.ui.facts

import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.ReviewDecision

/** What a fact card can ask for. Every callback carries the item it acts on, from the state that was rendered. */
class FactActions(
    val onOpenEvidence: (FactEvidence) -> Unit,
    val onReview: (FactItem, ReviewDecision) -> Unit,
    val onChoose: (FactItem, Int) -> Unit,
)
