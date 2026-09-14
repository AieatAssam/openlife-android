package org.openlife.app.intake

/**
 * The parts of an incoming intent [IntakeIntentValidator] needs, extracted
 * as plain strings so the validation logic has no `android.content.Intent`
 * or `android.net.Uri` dependency and can run as a fast JVM unit test.
 * [IntakeActivity] is the only place a real `Intent` is read into this
 * shape.
 */
data class IntentShape(
    val action: String?,
    val dataUri: String?,
    val extraStreamUri: String?,
    val clipDataUris: List<String>,
    /** MIME type declared by the incoming ACTION_SEND intent. */
    val intentMimeType: String? = null,
    /** Whether the sender supplied the one-shot read grant required by C0. */
    val hasReadUriPermission: Boolean = false,
)
