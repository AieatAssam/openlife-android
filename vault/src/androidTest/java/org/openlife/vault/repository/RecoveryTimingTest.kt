package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths

/**
 * P1-14 acceptance measurement, not a gate: how long startup recovery takes
 * with 50 saved sources. Opt-in with
 * `-Pandroid.testInstrumentationRunnerArguments.p114Timing=true`; results go
 * to logcat under the tag `P114Timing` and into docs/verification.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryTimingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "test.${UUID.randomUUID()}"
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("p114Timing") == "true")
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::paths.isInitialized) paths.vaultDir.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun recoveryTimeWithFiftySources(): Unit = runBlocking {
        val wrapper = KeystoreWrapper(alias)
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        val queue = MutationQueue()
        val imports = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = queue,
        )
        val random = Random(SEED)
        repeat(SOURCES) {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(WIDTH * HEIGHT) { random.nextInt() or OPAQUE }
            bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            val prepared = imports.prepareImport(ByteArrayInputStream(out.toByteArray()), "image/jpeg", IntakeKind.SHARE)
                as PrepareResult.Prepared
            check(imports.saveImport(prepared.sourceId) is SaveResult.Saved)
        }
        val totalBytes = paths.artefactsDir.listFiles().orEmpty().sumOf { it.length() }

        val recovery = RecoveryRepository(paths, db, wrapper, queue)
        val timings = List(RUNS) {
            val start = System.nanoTime()
            val report = recovery.recover()
            val elapsedMs = (System.nanoTime() - start) / NANOS_PER_MS
            check(report.confirmedReady == SOURCES) { "unexpected report $report" }
            elapsedMs
        }
        Log.i(TAG, "sources=$SOURCES blobBytes=$totalBytes recoverMs=$timings medianMs=${timings.sorted()[RUNS / 2]}")
    }

    private companion object {
        const val TAG = "P114Timing"
        const val SOURCES = 50
        const val RUNS = 5
        const val WIDTH = 1200
        const val HEIGHT = 900
        const val JPEG_QUALITY = 90
        const val SEED = 114L
        const val OPAQUE = 0xFF000000.toInt()
        const val NANOS_PER_MS = 1_000_000
    }
}
