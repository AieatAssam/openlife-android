package org.openlife.vault.storage

/** C2-R17: the stored values of `ocr_spans.scriptStatus`; UNKNOWN is the column default only. */
enum class SpanScriptStatus {
    LATIN,
    UNSUPPORTED,
    UNKNOWN,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}
