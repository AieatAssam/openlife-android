package org.openlife.vault.ocr

/** One OCR span with its latest attributed user correction, if any. */
data class OcrSpanView(val span: OcrSpan, val correction: OcrUserRevision?)

/**
 * What the viewer shows for a source (P2-01-R1): the latest revision in any
 * state, its immutable spans with their latest corrections, a whole-revision
 * correction (spanId null), and the earlier revisions as history, newest first.
 */
data class OcrView(
    val revision: OcrRevision,
    val spans: List<OcrSpanView>,
    val revisionCorrection: OcrUserRevision?,
    val history: List<OcrRevision>,
)
