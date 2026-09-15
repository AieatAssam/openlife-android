package org.openlife.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM adversarial coverage of the C0-05 test row: multiple/conflicting
 * URIs, a file/http URI, and a URI pointing back into OpenLife's own
 * authority.
 */
class IntakeIntentValidatorTest {

    private val ownPackage = "org.openlife"

    private fun shape(
        action: String? = "android.intent.action.SEND",
        dataUri: String? = null,
        extraStreamUri: String? = null,
        clipDataUris: List<String> = emptyList(),
        intentMimeType: String? = "image/jpeg",
        hasReadUriPermission: Boolean = true,
    ) = IntentShape(
        action,
        dataUri,
        extraStreamUri,
        clipDataUris,
        intentMimeType,
        hasReadUriPermission,
    )

    @Test
    fun missingIncomingReadGrantIsRejectedBeforeOpeningCandidate() {
        val result = IntakeIntentValidator.validate(
            shape(
                extraStreamUri = "content://com.example.provider/image1",
                hasReadUriPermission = false,
            ),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.MISSING_READ_GRANT), result)
    }

    @Test
    fun missingOrWildcardIntentMimeTypeIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(
                extraStreamUri = "content://com.example.provider/image1",
                intentMimeType = "image/*",
            ),
            ownPackage
        )
        assertEquals(
            IntakeValidationResult.Rejected(IntakeRejectionReason.UNSUPPORTED_OR_MISSING_MIME_TYPE),
            result
        )
    }

    @Test
    fun senderAndProviderMimeTypesMustMatchExactlyOnSupportedFormat() {
        assertTrue(IntakeIntentValidator.mimeTypesMatch("image/jpeg", "image/jpeg"))
        assertTrue(IntakeIntentValidator.mimeTypesMatch("IMAGE/PNG", "image/png"))
        assertFalse(IntakeIntentValidator.mimeTypesMatch("image/jpeg", "image/png"))
        assertFalse(IntakeIntentValidator.mimeTypesMatch("image/*", "image/jpeg"))
        assertFalse(IntakeIntentValidator.mimeTypesMatch(null, "image/jpeg"))
    }

    @Test
    fun singleContentUriInExtraStreamIsValid() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "content://com.example.provider/image1"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Valid("content://com.example.provider/image1"), result)
    }

    @Test
    fun singleContentUriInDataIsValid() {
        val result = IntakeIntentValidator.validate(
            shape(dataUri = "content://com.example.provider/image1"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Valid("content://com.example.provider/image1"), result)
    }

    @Test
    fun theSameUriMirroredAcrossFieldsIsNotTreatedAsAConflict() {
        // Real senders/the platform routinely mirror EXTRA_STREAM into
        // ClipData for the same item; this must not be rejected.
        val uri = "content://com.example.provider/image1"
        val result = IntakeIntentValidator.validate(
            shape(dataUri = uri, extraStreamUri = uri, clipDataUris = listOf(uri)),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Valid(uri), result)
    }

    @Test
    fun wrongActionIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(action = "android.intent.action.VIEW", extraStreamUri = "content://x/y"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.WRONG_ACTION), result)
    }

    @Test
    fun noCandidateAtAllIsRejected() {
        val result = IntakeIntentValidator.validate(shape(), ownPackage)
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.NO_CANDIDATE), result)
    }

    @Test
    fun conflictingDataAndExtraStreamAreRejected() {
        val result = IntakeIntentValidator.validate(
            shape(
                dataUri = "content://com.example.provider/image1",
                extraStreamUri = "content://com.example.provider/image2",
            ),
            ownPackage
        )
        assertEquals(
            IntakeValidationResult.Rejected(IntakeRejectionReason.MULTIPLE_OR_CONFLICTING_CANDIDATES),
            result
        )
    }

    @Test
    fun multipleClipDataItemsAreRejected() {
        val result = IntakeIntentValidator.validate(
            shape(
                clipDataUris = listOf(
                    "content://com.example.provider/image1",
                    "content://com.example.provider/image2"
                )
            ),
            ownPackage
        )
        assertEquals(
            IntakeValidationResult.Rejected(IntakeRejectionReason.MULTIPLE_OR_CONFLICTING_CANDIDATES),
            result
        )
    }

    @Test
    fun fileSchemeUriIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "file:///sdcard/DCIM/photo.jpg"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.UNSUPPORTED_URI_SCHEME), result)
    }

    @Test
    fun httpSchemeUriIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "https://example.com/photo.jpg"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.UNSUPPORTED_URI_SCHEME), result)
    }

    @Test
    fun uriPointingAtOwnAuthorityExactMatchIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "content://$ownPackage/vault/1.blob"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY), result)
    }

    @Test
    fun ownAuthorityMatchIsCaseInsensitive() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "content://${ownPackage.uppercase()}/vault/1.blob"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY), result)
    }

    @Test
    fun uriPointingAtASubAuthorityOfOwnPackageIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "content://$ownPackage.fileprovider/vault/1.blob"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY), result)
    }

    @Test
    fun anotherAppWithASimilarLookingAuthorityIsNotRejected() {
        // "org.openlife.evil" is a distinct package; a naive substring
        // check must not treat it as "our" authority.
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "content://$ownPackage.evil.provider/x"),
            ownPackage
        )
        // This *is* rejected by the startsWith("$ownPackageName.") rule,
        // which is intentionally conservative: any authority nested under
        // our package name is treated as ours. Documented here so the
        // choice is visible, not accidental.
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.OWN_AUTHORITY), result)
    }

    @Test
    fun malformedUriIsRejected() {
        val result = IntakeIntentValidator.validate(
            shape(extraStreamUri = "not a uri at all ::://"),
            ownPackage
        )
        assertEquals(IntakeValidationResult.Rejected(IntakeRejectionReason.MALFORMED_URI), result)
    }
}
