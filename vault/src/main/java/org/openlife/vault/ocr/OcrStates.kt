package org.openlife.vault.ocr

enum class OcrRevisionState {
    NOT_STARTED,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,
    STALE,

    /** Stored by a build this one does not understand; never written (P1-18-R14). */
    UNRECOGNISED,
}

enum class OcrReviewState {
    UNREVIEWED,
    ACCEPTED,
    REJECTED,

    /** Stored by a build this one does not understand; never written (P1-18-R14). */
    UNRECOGNISED,
}

enum class OcrCoordinateSystem {
    SOURCE_PIXELS,

    /** Stored by a build this one does not understand; never written (P1-18-R14). */
    UNRECOGNISED,
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

    /** The saved image could not be read right now (Keystore or I/O); retry may succeed (P1-18-R8). */
    SOURCE_TEMPORARILY_UNAVAILABLE,

    /** Stored by a build this one does not understand; never written (P1-18-R14). */
    UNRECOGNISED,
}
