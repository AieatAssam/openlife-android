package org.openlife.vault.ocr

enum class OcrRevisionState {
    NOT_STARTED,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,
    STALE,
}

enum class OcrReviewState {
    UNREVIEWED,
    ACCEPTED,
    REJECTED,
}

enum class OcrCoordinateSystem {
    SOURCE_PIXELS,
}

enum class OcrFailureReason {
    SOURCE_NOT_READY,
    SOURCE_CORRUPT,
    SOURCE_DELETED,
    LIMIT_EXCEEDED,
    UNSUPPORTED_SCRIPT,
    ENGINE_FAILURE,
    CANCELLED,
    PROCESS_RESTART,

    /** The single OCR deadline elapsed; distinct from a user's Cancel (P2-02-R2). */
    TIMEOUT,

    /** The final commit of text and revision failed on this device's storage (P2-02-R1). */
    STORAGE_FAILURE,
}
