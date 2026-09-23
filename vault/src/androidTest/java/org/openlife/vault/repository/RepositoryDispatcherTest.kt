package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths

/**
 * P1-13-R1: repositories own their dispatcher. A caller on the main thread
 * (a ViewModel's viewModelScope) must never cause Keystore wrap/unwrap,
 * AES-GCM, hashing or file work to run on the main thread. StrictMode sees
 * only disk and network, so this counts Keystore calls made on the main
 * looper directly.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryDispatcherTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var wrapper: MainThreadCountingWrapper
    private lateinit var queue: MutationQueue

    private class MainThreadCountingWrapper(alias: String) : KeystoreWrapper(alias) {
        val mainThreadCalls = AtomicInteger()
        val totalCalls = AtomicInteger()

        private fun record() {
            totalCalls.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCalls.incrementAndGet()
        }

        override fun wrap(plaintext: ByteArray, domain: EnvelopeDomain, sourceId: UUID?): Envelope {
            record()
            return super.wrap(plaintext, domain, sourceId)
        }

        override fun unwrap(envelope: Envelope, domain: EnvelopeDomain, sourceId: UUID?): ByteArray {
            record()
            return super.unwrap(envelope, domain, sourceId)
        }
    }

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
        wrapper = MainThreadCountingWrapper(alias)
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        queue = MutationQueue()
        wrapper.mainThreadCalls.set(0)
        wrapper.totalCalls.set(0)
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
    fun saveViewAndDeleteCalledFromMainNeverRunKeystoreWorkOnMain(): Unit = runBlocking {
        val importRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = queue,
        )
        val viewRepository = SourceViewRepository(paths, db, wrapper, queue)
        val deletionRepository = DeletionRepository(paths, db, queue)
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpeg()),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared

        withContext(Dispatchers.Main) {
            assertTrue(viewRepository.loadStagePreviewBytes(prepared.sourceId) != null)
            assertTrue(importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
            assertTrue(viewRepository.loadReadyBytes(prepared.sourceId) != null)
            assertTrue(deletionRepository.deleteSource(prepared.sourceId) is DeleteResult.Deleted)
        }

        assertTrue("the test must exercise Keystore", wrapper.totalCalls.get() > 0)
        assertEquals("Keystore calls on the main thread", 0, wrapper.mainThreadCalls.get())
    }

    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
