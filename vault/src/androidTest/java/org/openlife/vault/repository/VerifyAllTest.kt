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
 * P1-14-R3: deep authentication of every READY item is an explicit,
 * user-started action. It marks CORRUPT only on a genuine authentication
 * failure and changes nothing on a transient Keystore error.
 */
@RunWith(AndroidJUnit4::class)
class VerifyAllTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var wrapper: SwitchableWrapper
    private lateinit var queue: MutationQueue
    private lateinit var importRepository: ImportRepository

    private class SwitchableWrapper(alias: String) : KeystoreWrapper(alias) {
        @Volatile var failKeyAccess = false

        override fun existingWrappingKey(): SecretKey? {
            if (failKeyAccess) throw KeyStoreException("synthetic keystore failure")
            return super.existingWrappingKey()
        }
    }

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
        wrapper = SwitchableWrapper(alias)
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        queue = MutationQueue()
        importRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = queue,
        )
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
    fun tamperedBlobBecomesCorruptOnlyThroughExplicitVerify(): Unit = runBlocking {
        val intact = save(1)
        val tampered = save(2)
        val blob = paths.blobFile(tampered)
        blob.writeBytes(blob.readBytes().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 0xFF).toByte() })
        val recovery = RecoveryRepository(paths, db, wrapper, queue)

        recovery.recover()
        assertEquals("startup does not judge the ciphertext", SourceState.READY, stateOf(tampered))

        val report = recovery.verifyAll()

        assertEquals(VerifyReport(verified = 1, markedCorrupt = 1, transient = 0), report)
        assertEquals(SourceState.CORRUPT, stateOf(tampered))
        assertEquals(SourceState.READY, stateOf(intact))
        assertTrue("verification keeps the damaged blob", blob.exists())
    }

    @Test
    fun transientKeystoreFailureDuringVerifyChangesNothing(): Unit = runBlocking {
        val id = save(3)
        wrapper.failKeyAccess = true

        val report = RecoveryRepository(paths, db, wrapper, queue).verifyAll()

        assertEquals(VerifyReport(verified = 0, markedCorrupt = 0, transient = 1), report)
        assertEquals(SourceState.READY, stateOf(id))
    }

    private suspend fun stateOf(id: UUID): SourceState = db.sourceDao().findById(id.toString())!!.toDomain().state

    private suspend fun save(variant: Int): UUID {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt() or (variant * 0x112233))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(out.toByteArray()),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        assertTrue(importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        return prepared.sourceId
    }
}
