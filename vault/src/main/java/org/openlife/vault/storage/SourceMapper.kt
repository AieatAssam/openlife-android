package org.openlife.vault.storage

import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import java.util.UUID

/**
 * Never writes an unrecognised value back over the stored text (P1-18-R14):
 * a row this build cannot fully read is left exactly as another build wrote it.
 */
fun Source.toEntity(): SourceEntity = SourceEntity(
    id = id.toString(),
    state = state.also { check(it != SourceState.UNRECOGNISED) { "an unrecognised state is never written back" } }.name,
    importedAt = importedAt,
    intakeKind = intakeKind.also {
        check(it != IntakeKind.UNRECOGNISED) { "an unrecognised intake is never written back" }
    }.name,
    mimeType = mimeType?.mimeType,
    byteCount = byteCount,
    sha256 = sha256,
    width = width,
    height = height,
    orientation = orientation?.name,
    wrappedDek = wrappedDek,
    artefactVersion = artefactVersion,
    assessmentState = assessmentState.also {
        check(it != AssessmentState.UNRECOGNISED) { "an unrecognised assessment state is never written back" }
    }.name,
    userContentKind = userContentKind?.also {
        check(it != UserContentKind.UNRECOGNISED) { "an unrecognised content kind is never written back" }
    }?.name,
    userContentKindAt = userContentKindAt,
    assessmentMethodVersion = assessmentMethodVersion,
)

fun SourceEntity.toDomain(): Source = Source(
    id = UUID.fromString(id),
    state = decodeStored(state, SourceState.UNRECOGNISED),
    importedAt = importedAt,
    intakeKind = decodeStored(intakeKind, IntakeKind.UNRECOGNISED),
    mimeType = mimeType?.let { declared -> ImageFormat.entries.firstOrNull { it.mimeType == declared } },
    byteCount = byteCount,
    sha256 = sha256,
    width = width,
    height = height,
    orientation = orientation?.let { decodeStoredOrNull<Orientation>(it) },
    wrappedDek = wrappedDek,
    artefactVersion = artefactVersion,
    assessmentState = decodeStored(assessmentState, AssessmentState.UNRECOGNISED),
    userContentKind = userContentKind?.let { decodeStored(it, UserContentKind.UNRECOGNISED) },
    userContentKindAt = userContentKindAt,
    assessmentMethodVersion = assessmentMethodVersion,
)
