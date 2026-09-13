package org.openlife.vault.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * File locations for the vault. Everything lives under
 * `noBackupFilesDir/vault` (design §9): the encrypted database, its wrapped
 * secret, and — from Stage 3 on — per-source artefact stage/blob files.
 * Nothing here is exposed to the UI layer directly; the repository and
 * storage layers are the only things that see real paths.
 */
class VaultPaths(context: Context) {

    val vaultDir: File = File(context.noBackupFilesDir, "vault")
    val databaseFile: File = File(vaultDir, "openlife.db")
    val databaseKeyFile: File = File(vaultDir, "database.key")
    val artefactsDir: File = File(vaultDir, "artefacts")

    /** Creates the vault directory tree if it does not already exist. */
    fun ensureDirectoriesExist() {
        vaultDir.mkdirs()
        artefactsDir.mkdirs()
    }

    /**
     * File names are derived exclusively from the app-generated Source
     * UUID — never a provider name or extension (design §9).
     */
    fun stageFile(sourceId: UUID): File = File(artefactsDir, "$sourceId.stage")

    fun blobFile(sourceId: UUID): File = File(artefactsDir, "$sourceId.blob")
}
