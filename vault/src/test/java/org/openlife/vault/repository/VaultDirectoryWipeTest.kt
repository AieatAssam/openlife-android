package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** P1-18-R11 (V-10) on the JVM: reset removes every entry, known or not, and keeps the marker. */
class VaultDirectoryWipeTest {

    /** Review R-09: a symlink inside the vault is removed, never followed. */
    @Test
    fun aSymlinkIsRemovedButItsTargetIsNeverFollowed() = withVaultDir { dir, marker ->
        val outside = java.nio.file.Files.createTempDirectory("openlife-outside").toFile()
        try {
            val precious = File(outside, "precious").apply { writeBytes(byteArrayOf(1)) }
            java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())

            assertTrue(VaultDirectoryWipe.deleteAllExcept(dir, marker, ArtefactFileOps.Default))

            assertTrue("a symlink target outside the vault is untouched", precious.exists())
            assertEquals(listOf(marker.name), dir.list()!!.toList())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun strayFilesJournalAndNestedDirectoriesAreAllRemovedAndTheMarkerIsKept() = withVaultDir { dir, marker ->
        val artefacts = File(dir, "artefacts").apply { mkdirs() }
        File(artefacts, "${UUID.randomUUID()}.blob").writeBytes(byteArrayOf(1))
        File(artefacts, "${UUID.randomUUID()}.stage").writeBytes(byteArrayOf(2))
        File(artefacts, "not-a-uuid.txt").writeBytes(byteArrayOf(3))
        File(dir, "openlife.db").writeBytes(byteArrayOf(4))
        File(dir, "openlife.db-journal").writeBytes(byteArrayOf(5))
        File(dir, "openlife.db-wal").writeBytes(byteArrayOf(6))
        File(dir, "database.key").writeBytes(byteArrayOf(7))
        File(dir, "database.key.tmp").writeBytes(byteArrayOf(8))
        File(dir, "nested/deeper").apply { mkdirs() }.resolve("x").writeBytes(byteArrayOf(9))

        assertTrue(VaultDirectoryWipe.deleteAllExcept(dir, marker, ArtefactFileOps.Default))

        assertEquals(listOf(marker.name), dir.list()!!.toList())
    }

    @Test
    fun aFileThatCannotBeDeletedFailsTheWipeAndKeepsTheMarker() = withVaultDir { dir, marker ->
        val stuck = File(dir, "stuck.bin").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "openlife.db").writeBytes(byteArrayOf(2))
        val refusing = object : ArtefactFileOps by ArtefactFileOps.Default {
            override fun deleteIfExists(file: File): Boolean =
                if (file == stuck) false else ArtefactFileOps.Default.deleteIfExists(file)
        }

        assertFalse(VaultDirectoryWipe.deleteAllExcept(dir, marker, refusing))

        assertTrue(marker.exists())
        assertTrue(stuck.exists())
    }

    private fun withVaultDir(block: (File, File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("openlife-vault").toFile()
        try {
            val marker = File(dir, "RESET_IN_PROGRESS").apply { writeBytes(byteArrayOf(1)) }
            block(dir, marker)
        } finally {
            dir.deleteRecursively()
        }
    }
}
