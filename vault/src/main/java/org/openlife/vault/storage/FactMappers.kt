package org.openlife.vault.storage

import org.openlife.vault.facts.Fact
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRevision
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.StaleReason
import java.util.UUID

/** C2-R17: the stored values of `ocr_spans.scriptStatus`; UNKNOWN is the column default only. */
enum class SpanScriptStatus {
    LATIN,
    UNSUPPORTED,
    UNKNOWN,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/* Read-only mappers: fact rows are written by statement (FactSql), never from these models. */

fun FactEntity.toDomain(): Fact = Fact(
    id = UUID.fromString(id),
    sourceId = UUID.fromString(sourceId),
    kind = decodeStored(kind, FactKind.UNRECOGNISED),
    ordinal = ordinal,
    currentRevisionId = currentRevisionId?.let(UUID::fromString),
    createdAt = createdAt,
)

fun FactRevisionEntity.toDomain(): FactRevision = FactRevision(
    id = UUID.fromString(id),
    factId = UUID.fromString(factId),
    revisionNo = revisionNo,
    origin = decodeStored(origin, FactOrigin.UNRECOGNISED),
    kind = decodeStored(kind, FactKind.UNRECOGNISED),
    value = FactValue(valueJson, canonicalText),
    rawText = rawText,
    status = decodeStored(status, FactStatus.UNRECOGNISED),
    staleReason = staleReason?.let { decodeStored(it, StaleReason.UNRECOGNISED) },
    staleAt = staleAt,
    methodId = methodId,
    methodVersion = methodVersion,
    locale = locale,
    createdAt = createdAt,
    supersedesRevisionId = supersedesRevisionId?.let(UUID::fromString),
)

fun CurrentFactRow.toDomain(): FactWithLatestRevision {
    val current = revision.toDomain()
    return FactWithLatestRevision(
        fact = Fact(
            id = current.factId,
            sourceId = UUID.fromString(factSourceId),
            kind = current.kind,
            ordinal = factOrdinal,
            currentRevisionId = UUID.fromString(factCurrentRevisionId),
            createdAt = factCreatedAt,
        ),
        revision = current,
    )
}
