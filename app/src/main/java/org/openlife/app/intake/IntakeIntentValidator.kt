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

        // ACTION_SEND is exported, so a direct caller must prove it received
        // a one-shot read grant for the supplied content URI. A caller cannot
        // manufacture access merely by naming a public-looking content URI.
        // Photo Picker forwarding adds this same flag in MainActivity before
        // starting this activity, so it follows the identical boundary.
        if (!shape.hasReadUriPermission) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.MISSING_READ_GRANT)
        }

        if (!isSupportedMimeType(shape.intentMimeType)) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.UNSUPPORTED_OR_MISSING_MIME_TYPE)
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
        } catch (_: URISyntaxException) {
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
        if (authority != null &&
            (
                authority.equals(ownPackageName, ignoreCase = true) ||
                    authority.startsWith("$ownPackageName.", ignoreCase = true)
            )
        ) {
            return IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY)
        }

        return IntakeValidationResult.Valid(candidate)
    }

    /**
     * The sender-declared type and the provider's reported type must agree
     * exactly on one supported image format before any bytes are opened. The
     * repository still checks the provider type against the actual magic
     * bytes; this check closes the gap where the incoming Intent type was
     * previously ignored.
     */
    /** P1-02 stub: today's exact-match rule expressed as a typed check. */
    fun checkProviderType(intentMimeType: String?, providerMimeType: String?): ProviderTypeCheck =
        if (mimeTypesMatch(intentMimeType, providerMimeType)) {
            ProviderTypeCheck.Match(providerMimeType!!.lowercase())
        } else {
            ProviderTypeCheck.Mismatch
        }

    fun mimeTypesMatch(intentMimeType: String?, providerMimeType: String?): Boolean =
        isSupportedMimeType(intentMimeType) &&
            intentMimeType.equals(providerMimeType, ignoreCase = true)

    private fun isSupportedMimeType(mimeType: String?): Boolean = mimeType.equals("image/jpeg", ignoreCase = true) ||
        mimeType.equals("image/png", ignoreCase = true)
}

/** How the provider's reported type relates to the incoming intent's type (P1-02-R7). */
sealed interface ProviderTypeCheck {
    /** Supported; [mimeType] is normalised (image/jpeg or image/png). */
    data class Match(val mimeType: String) : ProviderTypeCheck

    /** The provider reports a type OpenLife does not import (for example HEIC or WebP). */
    data object UnsupportedFormat : ProviderTypeCheck

    /** The intent named a specific supported type that the provider contradicts. */
    data object Mismatch : ProviderTypeCheck
}
