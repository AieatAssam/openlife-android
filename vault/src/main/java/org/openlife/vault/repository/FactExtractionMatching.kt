package org.openlife.vault.repository

import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.extract.ExtractedCandidate
import org.openlife.vault.facts.extract.ExtractedKind
import org.openlife.vault.storage.HistoryRow
import kotlin.math.max
import kotlin.math.min

/** The canonical text of a candidate; an ambiguous set joins its values. */
internal fun canonicalTextOf(candidate: ExtractedCandidate): String = candidate.canonicalValue?.canonicalText()
    ?: candidate.ambiguity.joinToString(" | ") { it.canonicalText() }

/** The stored evidence region of a history row, when all four edges are present. */
internal fun HistoryRow.evidenceBox(): EvidenceBox? = boxOf(regionLeft, regionTop, regionRight, regionBottom)

/** The box when all four edges are stored, null otherwise. */
internal fun boxOf(left: Int?, top: Int?, right: Int?, bottom: Int?): EvidenceBox? {
    val l = left ?: return null
    val t = top ?: return null
    val r = right ?: return null
    val b = bottom ?: return null
    return EvidenceBox(l, t, r, b)
}

/** Overlap ratio (IoU, C2 §1): intersection area over union area; 0 when either box is missing or they miss. */
internal fun overlapRatio(a: EvidenceBox, b: EvidenceBox?): Double {
    if (b == null) return 0.0
    val width = min(a.right, b.right) - max(a.left, b.left)
    val height = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (width <= 0 || height <= 0) return 0.0
    val intersection = width.toLong() * height.toLong()
    val union = a.area + b.area - intersection
    return if (union <= 0L) 0.0 else intersection.toDouble() / union.toDouble()
}

/** Exhaustive on purpose: a new extracted kind fails to compile until it has a fact kind (P3-03 brief). */
internal fun mapKind(kind: ExtractedKind): FactKind = when (kind) {
    ExtractedKind.DATE -> FactKind.DATE
    ExtractedKind.TIME -> FactKind.TIME
    ExtractedKind.DATETIME -> FactKind.DATETIME
    ExtractedKind.MONEY -> FactKind.MONEY
    ExtractedKind.URL -> FactKind.URL
    ExtractedKind.EMAIL -> FactKind.EMAIL
    ExtractedKind.PHONE -> FactKind.PHONE
}
