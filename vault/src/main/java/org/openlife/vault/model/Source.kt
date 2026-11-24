package org.openlife.vault.model

import java.util.UUID

/**
 * Domain-level Source (design §9 field table), distinct from
 * `storage.SourceEntity`: this is what the repository and its callers work
 * with (typed state/format/orientation, a real `UUID`), while the entity is
 * Room's on-disk row shape (string-encoded enums, no null-safety beyond
 * what SQLite gives you). `storage.SourceMapper` converts between the two.
 */
data class Source(
    val id: UUID,
    val state: SourceState,
    val importedAt: Long,
    val intakeKind: IntakeKind,
    val mimeType: ImageFormat?,
    val byteCount: Long?,
    val sha256: ByteArray?,
    val width: Int?,
    val height: Int?,
    val orientation: Orientation?,
    /** Encoded [org.openlife.vault.crypto.Envelope] bytes for this Source's per-file DEK. */
    val wrappedDek: ByteArray?,
    val artefactVersion: Int?,
    /** Design §6 / P2-07-R1: every Source carries one; nothing in C1 assesses. */
    val assessmentState: AssessmentState = AssessmentState.NOT_ASSESSED,
    /** The user's optional label (P2-07-R3); null is "not labelled" or "Not sure", see [userContentKindAt]. */
    val userContentKind: UserContentKind? = null,
    /** When the user last answered the label question (actor USER); null if never. */
    val userContentKindAt: Long? = null,
    /** The assessment method that produced [assessmentState]; null while NOT_ASSESSED. */
    val assessmentMethodVersion: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Source) return false
        return id == other.id &&
            state == other.state &&
            importedAt == other.importedAt &&
            intakeKind == other.intakeKind &&
            mimeType == other.mimeType &&
            byteCount == other.byteCount &&
            sha256.contentEqualsOrNull(other.sha256) &&
            width == other.width &&
            height == other.height &&
            orientation == other.orientation &&
            wrappedDek.contentEqualsOrNull(other.wrappedDek) &&
            artefactVersion == other.artefactVersion &&
            assessmentState == other.assessmentState &&
            userContentKind == other.userContentKind &&
            userContentKindAt == other.userContentKindAt &&
            assessmentMethodVersion == other.assessmentMethodVersion
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + importedAt.hashCode()
        result = 31 * result + intakeKind.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (byteCount?.hashCode() ?: 0)
        result = 31 * result + (sha256?.contentHashCode() ?: 0)
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (orientation?.hashCode() ?: 0)
        result = 31 * result + (wrappedDek?.contentHashCode() ?: 0)
        result = 31 * result + (artefactVersion ?: 0)
        result = 31 * result + assessmentState.hashCode()
        result = 31 * result + (userContentKind?.hashCode() ?: 0)
        result = 31 * result + (userContentKindAt?.hashCode() ?: 0)
        result = 31 * result + (assessmentMethodVersion?.hashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean = when {
    this == null -> other == null
    else -> other != null && contentEquals(other)
}
