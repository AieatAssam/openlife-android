package org.openlife.app.intake

import java.net.URI
import java.net.URISyntaxException

/**
 * Validates the shape of an incoming share intent independent of the
 * declared intent filter (design §8: "Validate every incoming intent
 * regardless of the filter; callers can invoke an exported activity
 * directly"). Pure Kotlin using `java.net.URI` rather than
 * `android.net.Uri` so this runs as a JVM unit test against synthetic,
 * adversarial [IntentShape] values (C0-05) — [IntakeActivity] is the only
 * place a real `Intent`/`Uri` is touched.
 *
 * `EXTRA_STREAM` and `ClipData` legitimately duplicate the same URI on
 * real devices (the platform mirrors one into the other for many sharing
 * paths), so this only rejects when the *distinct* URIs found across
 * `data`, `EXTRA_STREAM`, and `ClipData` number anything other than
 * exactly one — not merely because more than one field happened to be
 * populated.
 */
object IntakeIntentValidator {

    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val SUPPORTED_SCHEME = "content"

    fun validate(shape: IntentShape, ownPackageName: String): IntakeValidationResult {
        if (shape.action != ACTION_SEND) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.WRONG_ACTION)
        }

        val candidates = buildSet {
            shape.dataUri?.let { add(it) }
            shape.extraStreamUri?.let { add(it) }
            addAll(shape.clipDataUris)
        }

        val candidate = when (candidates.size) {
            0 -> return IntakeValidationResult.Rejected(IntakeRejectionReason.NO_CANDIDATE)
            1 -> candidates.first()
            else -> return IntakeValidationResult.Rejected(IntakeRejectionReason.MULTIPLE_OR_CONFLICTING_CANDIDATES)
        }

        val parsed = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.MALFORMED_URI)
        }

        if (parsed.scheme?.lowercase() != SUPPORTED_SCHEME) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.UNSUPPORTED_URI_SCHEME)
        }

        // A content:// authority pointing back at this app itself. C0
        // exports no ContentProvider to receive such a URI, but the check
        // is defensive: sender-supplied paths and authorities are never
        // trusted (design §8), and this stops a future provider addition
        // from becoming a same-app confused-deputy vector by default.
        val authority = parsed.authority
        if (authority != null && (authority == ownPackageName || authority.startsWith("$ownPackageName."))) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY)
        }

        return IntakeValidationResult.Valid(candidate)
    }
}
