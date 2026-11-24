package org.openlife.vault.repository

/** When recovery may delete unreferenced artefact files (P1-18 review R-08). */
internal object OrphanPolicy {
    fun mayRemoveOrphans(sourceRowCount: Int, artefactNames: Collection<String>): Boolean =
        TODO("P1-18 review R-08")
}
