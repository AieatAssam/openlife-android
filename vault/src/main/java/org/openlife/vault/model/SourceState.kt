package org.openlife.vault.model

/**
 * Persisted recovery state for a Source (design §9). The repository is the
 * only thing that may transition a row between these states; storage-layer
 * enforcement of the READY invariant lives alongside the schema in
 * `storage/OpenLifeDatabase.kt`.
 */
enum class SourceState {
    STAGED,
    READY,
    DELETING,
    CORRUPT,

    /**
     * The stored state is not one this build knows (P1-18-R14). Treated as
     * not READY: never shown, never deleted by recovery, never written.
     */
    UNRECOGNISED,
}
