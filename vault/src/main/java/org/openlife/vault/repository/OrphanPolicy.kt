package org.openlife.vault.repository

/** When recovery may delete unreferenced artefact files (P1-18 review R-08). */
internal object OrphanPolicy {
    /**
     * False when the database has no Source rows while saved originals
     * (`.blob`) exist. Every blob is written after its row is committed and
     * its row is deleted only after the blob, so blobs with no rows at all
     * mean the database is not the one they belong to (for example a 0-byte
     * file opened as a new database), never that they are orphans
     * (design §11). Stage files hold no saved original and stay cleanable.
     */
    fun mayRemoveOrphans(sourceRowCount: Int, artefactNames: Collection<String>): Boolean =
        sourceRowCount > 0 || artefactNames.none { it.endsWith(BLOB_SUFFIX) }

    fun blobCount(artefactNames: Collection<String>): Int = artefactNames.count { it.endsWith(BLOB_SUFFIX) }

    private const val BLOB_SUFFIX = ".blob"
}
