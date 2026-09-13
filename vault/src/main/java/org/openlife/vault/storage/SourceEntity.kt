package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Schema v1 Source row (design §9). Validated fields are nullable at the
 * type level because a STAGED row is created before they are known; the
 * invariant that every validated field is non-null once `state = READY` is
 * enforced twice — once here at the SQL level via triggers created in
 * [OpenLifeDatabase] (Room 2.8.x has no declarative CHECK-constraint API to
 * express it directly on the entity; see docs/decisions/0001-c0-defaults.md
 * item 8), and once in the repository, which is the only thing allowed to
 * perform the STAGED -> READY transition.
 *
 * No Fact, Record, or Action tables exist in this schema (design §5, §9).
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val state: String,
    val importedAt: Long,
    val intakeKind: String,
    val mimeType: String?,
    val byteCount: Long?,
    val sha256: ByteArray?,
    val width: Int?,
    val height: Int?,
    val orientation: String?,
    val wrappedDek: ByteArray?,
    val artefactVersion: Int?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceEntity) return false
        return id == other.id &&
            state == other.state &&
            importedAt == other.importedAt &&
            intakeKind == other.intakeKind &&
            mimeType == other.mimeType &&
            byteCount == other.byteCount &&
            (sha256?.contentEquals(other.sha256) ?: (other.sha256 == null)) &&
            width == other.width &&
            height == other.height &&
            orientation == other.orientation &&
            (wrappedDek?.contentEquals(other.wrappedDek) ?: (other.wrappedDek == null)) &&
            artefactVersion == other.artefactVersion
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
        return result
    }
}
