package org.openlife.vault.storage

import org.openlife.vault.facts.Fact
import org.openlife.vault.facts.FactRevision

/** C2-R17: the stored values of `ocr_spans.scriptStatus`; UNKNOWN is the column default only. */
enum class SpanScriptStatus {
    LATIN,
    UNSUPPORTED,
    UNKNOWN,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

fun FactEntity.toDomain(): Fact = TODO("P3-02 GREEN")

fun FactRevisionEntity.toDomain(): FactRevision = TODO("P3-02 GREEN")
