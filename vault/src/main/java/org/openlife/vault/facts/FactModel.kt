package org.openlife.vault.facts

import java.util.UUID

/*
 * C2 typed facts (docs/capabilities/C2.md §4). Every enum below is stored as
 * text and decoded tolerantly: a value written by another build decodes to
 * UNRECOGNISED, is shown read-only and is never written back (C2-R51, VS-1).
 */

/** C2 §4.1. Later capabilities add kinds (P6-01 adds MERCHANT). */
enum class FactKind {
    DATE,
    TIME,
    DATETIME,
    MONEY,
    URL,
    EMAIL,
    PHONE,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2-R7. DERIVED arrives with C3 (decision Q-9); the SQL refuses any other origin. */
enum class FactOrigin {
    EXTRACTED,
    USER,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §5: the stored, displayable summary of a revision's state (C2-R22). */
enum class FactStatus {
    UNREVIEWED,
    CONFIRMED,
    REJECTED,
    CLARIFICATION_NEEDED,
    STALE,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §9: why a revision went STALE. INPUT_STALE and INPUT_DELETED arrive with C3. */
enum class StaleReason {
    READING_REPLACED,
    READING_REJECTED,
    SPAN_CORRECTED,
    READING_CORRECTED,
    SPAN_REJECTED,
    BASIS_STALE,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §4.5. */
enum class ReviewDecision {
    CONFIRM,
    REJECT,
    REOPEN,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §4.3. */
enum class EvidenceRole {
    VALUE,
    CURRENCY_CONTEXT,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §4.8. INPUT edges arrive with C3. */
enum class DependencyEdgeKind {
    EVIDENCE,
    BASIS,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §4.8. Records and reminders arrive later (P4-02). */
enum class DependencyNodeKind {
    FACT_REVISION,
    OCR_SPAN,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §4.6. */
enum class ExtractionTrigger {
    AUTO,
    USER,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §7.6. SUPERSEDED and CANCELLED never write a row (C2-R13). */
enum class ExtractionOutcome {
    COMPLETED,
    SOURCE_NOT_READY,
    NO_CURRENT_READING,
    READING_REJECTED,
    READING_CORRECTED_AS_A_WHOLE,
    LIMIT_EXCEEDED,
    TIMEOUT,
    STORAGE_FAILURE,

    /** Stored by a build this one does not understand; never written (C2-R51). */
    UNRECOGNISED,
}

/** C2 §7.2 limits that the storage layer enforces. */
object FactLimits {
    /** C2-R62: current revisions per Source. */
    const val MAX_CURRENT_REVISIONS = 500

    /** C2-R19: edge traversal depth; deeper is refused. */
    const val MAX_TRAVERSAL_DEPTH = 32

    /** C2 §4.2: `valueJson` UTF-8 bytes. */
    const val MAX_VALUE_JSON_BYTES = 4096

    /** C2 §7.2: a user-typed value. */
    const val MAX_USER_TEXT_CHARS = 2048
}

/** C2 §8 method identifiers of USER revisions. */
object UserMethod {
    const val ADD = "user:add"
    const val CLARIFY = "user:clarify"
    const val CORRECT = "user:correct"
}

/**
 * A typed value as ADR-0008 §2-§3 encode it: canonical `valueJson` bytes and
 * its `canonicalText`. The writer that produces them from a typed value is
 * P3-03's; this layer treats them as opaque, checks only the bounds the
 * schema states, and compares values byte for byte (C2-R5).
 */
class FactValue(val valueJson: String, val canonicalText: String) {
    /** True for an ambiguous `{"candidates":[…]}` value. */
    val isCandidates: Boolean get() = valueJson.startsWith(CANDIDATES_PREFIX)

    /** Within the schema's bounds: a JSON object of at most 4,096 UTF-8 bytes with a non-blank text. */
    val isWithinBounds: Boolean
        get() = valueJson.startsWith("{") &&
            valueJson.endsWith("}") &&
            valueJson.toByteArray(Charsets.UTF_8).size <= FactLimits.MAX_VALUE_JSON_BYTES &&
            canonicalText.isNotBlank()

    /**
     * The entries of a `candidates` value, each exactly as stored, so a
     * choice can be compared byte for byte (C2-R36). Empty for any other value.
     */
    fun candidateEntries(): List<String> = if (isCandidates) {
        splitTopLevelObjects(valueJson.substring(CANDIDATES_PREFIX.length, valueJson.length - CANDIDATES_SUFFIX.length))
    } else {
        emptyList()
    }

    /** The entries' `canonicalText`, in the same order (ADR-0008 §3: joined by ` | `). */
    fun candidateTexts(): List<String> = if (isCandidates) canonicalText.split(CANDIDATE_TEXT_SEPARATOR) else emptyList()

    override fun equals(other: Any?): Boolean =
        other is FactValue && other.valueJson == valueJson && other.canonicalText == canonicalText

    override fun hashCode(): Int = 31 * valueJson.hashCode() + canonicalText.hashCode()

    /** Never prints the value: values are user content (C2-R50). */
    override fun toString(): String = "FactValue(…)"

    private companion object {
        const val CANDIDATES_PREFIX = "{\"candidates\":["
        const val CANDIDATES_SUFFIX = "]}"
        const val CANDIDATE_TEXT_SEPARATOR = " | "

        /** Splits `{…},{…}` at depth 0, respecting strings and escapes. */
        fun splitTopLevelObjects(body: String): List<String> {
            val entries = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escaped = false
            var start = 0
            body.forEachIndexed { index, char ->
                when {
                    escaped -> escaped = false
                    inString && char == '\\' -> escaped = true
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' -> if (depth++ == 0) start = index
                    char == '}' -> if (--depth == 0) entries += body.substring(start, index + 1)
                }
            }
            return entries
        }
    }
}

/** A fact's stable identity (C2-R3). */
data class Fact(
    val id: UUID,
    val sourceId: UUID,
    val kind: FactKind,
    val ordinal: Int,
    val currentRevisionId: UUID?,
    val createdAt: Long,
)

/** One immutable assertion of a fact (C2 §4.2). */
data class FactRevision(
    val id: UUID,
    val factId: UUID,
    val revisionNo: Int,
    val origin: FactOrigin,
    val kind: FactKind,
    val value: FactValue,
    val rawText: String?,
    val status: FactStatus,
    val staleReason: StaleReason?,
    val staleAt: Long?,
    val methodId: String,
    val methodVersion: String?,
    val locale: String?,
    val createdAt: Long,
    val supersedesRevisionId: UUID?,
) {
    /** C2-R51: a row with any unrecognised enum is shown read-only and never reviewed or written back. */
    val isRecognised: Boolean
        get() = origin != FactOrigin.UNRECOGNISED &&
            kind != FactKind.UNRECOGNISED &&
            status != FactStatus.UNRECOGNISED
}

/**
 * A fact with its **current** revision, the one `facts.currentRevisionId`
 * names (P3-02-R5 as amended: not the latest). The name is the brief's.
 */
data class FactWithLatestRevision(val fact: Fact, val revision: FactRevision)

/** Why a fact write was refused; nothing was written (C2-R23). */
enum class FactRefusal {
    /** The Source is not READY (C2-R60), or does not exist. */
    SOURCE_NOT_READY,

    /** C2-R62: the Source already has 500 current revisions. */
    LIMIT_REACHED,

    /** The value is outside the schema's bounds, not one of the candidates, or of another kind. */
    INVALID_VALUE,

    /** The transition is not in the §5 table, the revision is not current, or it is read-only. */
    NOT_ALLOWED,
}

/** The outcome of a FactRepository write. */
sealed interface FactWriteResult {
    data class Written(val factId: UUID, val revisionId: UUID) : FactWriteResult

    data class Refused(val reason: FactRefusal) : FactWriteResult
}
