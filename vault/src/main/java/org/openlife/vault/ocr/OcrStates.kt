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
}
