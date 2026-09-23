package org.openlife.vault.storage

import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import java.util.UUID

fun Source.toEntity(): SourceEntity = SourceEntity(
    id = id.toString(),
    state = state.name,
    importedAt = importedAt,
    intakeKind = intakeKind.name,
    mimeType = mimeType?.mimeType,
    byteCount = byteCount,
    sha256 = sha256,
    width = width,
    height = height,
    orientation = orientation?.name,
    wrappedDek = wrappedDek,
    artefactVersion = artefactVersion,
)

fun SourceEntity.toDomain(): Source = Source(
    id = UUID.fromString(id),
    state = SourceState.valueOf(state),
    importedAt = importedAt,
    intakeKind = IntakeKind.valueOf(intakeKind),
    mimeType = mimeType?.let { declared -> ImageFormat.entries.first { it.mimeType == declared } },
    byteCount = byteCount,
    sha256 = sha256,
    width = width,
    height = height,
    orientation = orientation?.let { Orientation.valueOf(it) },
    wrappedDek = wrappedDek,
    artefactVersion = artefactVersion,
)
