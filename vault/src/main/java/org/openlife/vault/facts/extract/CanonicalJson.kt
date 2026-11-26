package org.openlife.vault.facts.extract

/** Canonical JSON writer (ADR-0008 §2). RED stub. */
internal object CanonicalJson {
    fun of(single: FactValue?, candidates: List<FactValue>): String = ""

    fun hasUnpairedSurrogate(text: String): Boolean = false
}
