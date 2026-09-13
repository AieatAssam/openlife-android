package org.openlife.app.intake

enum class IntakeRejectionReason {
    WRONG_ACTION,
    NO_CANDIDATE,
    MULTIPLE_OR_CONFLICTING_CANDIDATES,
    UNSUPPORTED_URI_SCHEME,
    OWN_AUTHORITY,
    MALFORMED_URI,
}

sealed interface IntakeValidationResult {
    data class Valid(val uriString: String) : IntakeValidationResult
    data class Rejected(val reason: IntakeRejectionReason) : IntakeValidationResult
}
