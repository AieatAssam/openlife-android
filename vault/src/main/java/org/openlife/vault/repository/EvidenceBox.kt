package org.openlife.vault.repository

/** A pixel box of a span or of a stored evidence region (C2 §1). */
internal data class EvidenceBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val area: Long get() = (right - left).toLong() * (bottom - top).toLong()
}
