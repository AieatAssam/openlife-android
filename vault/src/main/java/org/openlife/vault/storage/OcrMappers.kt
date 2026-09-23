package org.openlife.vault.storage

import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrUserRevision
import java.util.UUID

fun OcrRevisionEntity.toDomain() = OcrRevision(
    id = UUID.fromString(id),
    sourceId = UUID.fromString(sourceId),
    state = OcrRevisionState.valueOf(state),
    engineId = engineId,
    modelVersion = modelVersion,
    orientation = Orientation.valueOf(orientation),
    sourceDigest = sourceDigest,
    startedAt = startedAt,
    extractedAt = extractedAt,
    reviewState = OcrReviewState.valueOf(reviewState),
    failureReason = failureReason?.let(OcrFailureReason::valueOf),
    charCount = charCount,
    spanCount = spanCount,
)

fun OcrRevision.toEntity() = OcrRevisionEntity(
    id = id.toString(),
    sourceId = sourceId.toString(),
    state = state.name,
    engineId = engineId,
    modelVersion = modelVersion,
    orientation = orientation.name,
    sourceDigest = sourceDigest,
    startedAt = startedAt,
    extractedAt = extractedAt,
    reviewState = reviewState.name,
    failureReason = failureReason?.name,
    charCount = charCount,
    spanCount = spanCount,
)

fun OcrSpanEntity.toDomain() = OcrSpan(
    id = UUID.fromString(id),
    revisionId = UUID.fromString(revisionId),
    ordinal = ordinal,
    text = text,
    confidence = confidence,
    coordinateSystem = OcrCoordinateSystem.valueOf(coordinateSystem),
    evidenceRegion = toEvidenceRegion(left, top, right, bottom),
)

private fun hasNoEvidence(left: Int?, top: Int?, right: Int?, bottom: Int?): Boolean =
    listOf(left, top, right, bottom).all { it == null }

private fun toEvidenceRegion(left: Int?, top: Int?, right: Int?, bottom: Int?): OcrEvidenceRegion? = when {
    hasNoEvidence(left, top, right, bottom) -> null
    left != null && top != null && right != null && bottom != null -> OcrEvidenceRegion(left, top, right, bottom)
    else -> error("An OCR evidence rectangle must be complete or absent")
}

fun OcrSpan.toEntity() = OcrSpanEntity(
    id = id.toString(),
    revisionId = revisionId.toString(),
    ordinal = ordinal,
    text = text,
    confidence = confidence,
    coordinateSystem = coordinateSystem.name,
    left = evidenceRegion?.left,
    top = evidenceRegion?.top,
    right = evidenceRegion?.right,
    bottom = evidenceRegion?.bottom,
)

fun OcrUserRevisionEntity.toDomain() = OcrUserRevision(
    id = UUID.fromString(id),
    revisionId = UUID.fromString(revisionId),
    spanId = spanId?.let(UUID::fromString),
    correctedText = correctedText,
    createdAt = createdAt,
    actor = actor,
)

fun OcrUserRevision.toEntity() = OcrUserRevisionEntity(
    id = id.toString(),
    revisionId = revisionId.toString(),
    spanId = spanId?.toString(),
    correctedText = correctedText,
    createdAt = createdAt,
    actor = actor,
)
