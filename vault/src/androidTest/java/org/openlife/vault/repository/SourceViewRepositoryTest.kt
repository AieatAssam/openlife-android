package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStoreException
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain

/**
 * P1-13-R5: a read failure marks a Source CORRUPT only when the stored
 * artefact itself fails authentication or its digest. A Keystore that is
 * temporarily unavailable is a transient condition and must leave the row
 * READY (design §10: distinguish a temporarily unavailable vault from
 * confirmed loss; never destroy recoverable data).
 */
@RunWith(AndroidJUnit4::class)
class SourceViewRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var wrapper: FlakyKeystoreWrapper
    private lateinit var importRepository: ImportRepository
    private lateinit var viewRepository: SourceViewRepository

    /** Real Keystore, with a switch that makes key access fail like a keystore daemon error. */
    private class FlakyKeystoreWrapper(alias: String) : KeystoreWrapper(alias) {
        @Volatile var failKeyAccess = false

        override fun wrappingKey(): SecretKey {
            if (failKeyAccess) throw KeyStoreException("synthetic keystore failure")
            return super.wrappingKey()
        }
    }

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
        wrapper = FlakyKeystoreWrapper(alias)
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        val queue = MutationQueue()
        importRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = queue,
        )
        viewRepository = SourceViewRepository(paths, db, wrapper, queue)
    }

    @After
    fun tearDown() {
        db.close()
        paths.vaultDir.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun transientKeystoreFailureReturnsTransientAndLeavesRowReady(): Unit = runBlocking {
        val (sourceId, original) = saveASource()

        wrapper.failKeyAccess = true
        val failed = viewRepository.readReadyBytes(sourceId)
        assertEquals(ReadyReadResult.Transient, failed)
        assertEquals(SourceState.READY, stateOf(sourceId))

        wrapper.failKeyAccess = false
        val retried = viewRepository.readReadyBytes(sourceId)
        assertTrue("a retry after the keystore recovers must succeed", retried is ReadyReadResult.Loaded)
        assertArrayEquals(original, (retried as ReadyReadResult.Loaded).bytes)
    }

    @Test
    fun tamperedBlobAtReadMarksCorruptOnce(): Unit = runBlocking {
        val (sourceId, _) = saveASource()
        val blobFile = paths.blobFile(sourceId)
        val bytes = blobFile.readBytes()
        val tamperIndex = bytes.size - 5
        bytes[tamperIndex] = (bytes[tamperIndex].toInt() xor 0xFF).toByte()
        blobFile.writeBytes(bytes)
        val wrappedDekBefore = db.sourceDao().findById(sourceId.toString())!!.wrappedDek!!.copyOf()

        assertEquals(ReadyReadResult.Corrupt, viewRepository.readReadyBytes(sourceId))
        assertEquals(SourceState.CORRUPT, stateOf(sourceId))

        // Idempotent and non-destructive: a second read does not re-mark or
        // fail differently, and the artefact and wrapped key are retained
        // for diagnosis (design §9 CORRUPT row retention).
        assertEquals(ReadyReadResult.Unavailable, viewRepository.readReadyBytes(sourceId))
        assertEquals(SourceState.CORRUPT, stateOf(sourceId))
        assertTrue("blob must be retained", blobFile.exists())
        assertArrayEquals(wrappedDekBefore, db.sourceDao().findById(sourceId.toString())!!.wrappedDek)
    }

    private suspend fun stateOf(sourceId: UUID): SourceState =
        db.sourceDao().findById(sourceId.toString())!!.toDomain().state

    private suspend fun saveASource(): Pair<UUID, ByteArray> {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        val original = out.toByteArray()
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(original),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        assertTrue(importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        return prepared.sourceId to original
    }
}
