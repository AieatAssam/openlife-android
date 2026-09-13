package org.openlife.vault.storage

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the SQL-level READY invariant from
 * docs/decisions/0001-c0-defaults.md item 8 actually fires — a `state =
 * READY` row with any validated field left null must be rejected by the
 * database itself, independent of whatever the repository layer does.
 * In-memory Room database (still via the real SQLCipher factory, since the
 * production `OpenLifeDatabase` is never built without it) so this test
 * needs no on-disk cleanup.
 */
@RunWith(AndroidJUnit4::class)
class OpenLifeDatabaseReadyInvariantTest {

    private lateinit var db: OpenLifeDatabase

    private fun stagedRow(id: String = UUID.randomUUID().toString()) = SourceEntity(
        id = id,
        state = "STAGED",
        importedAt = 1_700_000_000_000L,
        intakeKind = "SHARE",
        mimeType = null,
        byteCount = null,
        sha256 = null,
        width = null,
        height = null,
        orientation = null,
        wrappedDek = null,
        artefactVersion = null,
    )

    private fun completeReadyRow(id: String = UUID.randomUUID().toString()) = SourceEntity(
        id = id,
        state = "READY",
        importedAt = 1_700_000_000_000L,
        intakeKind = "SHARE",
        mimeType = "image/jpeg",
        byteCount = 1024L,
        sha256 = ByteArray(32) { it.toByte() },
        width = 100,
        height = 200,
        orientation = "NORMAL",
        wrappedDek = ByteArray(16) { it.toByte() },
        artefactVersion = 1,
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OpenLifeDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory("test-passphrase".toByteArray()))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun stagedRowWithAllFieldsNullInsertsFine(): Unit = runBlocking {
        db.sourceDao().insert(stagedRow())
    }

    @Test
    fun readyRowWithAllFieldsPresentInsertsFine(): Unit = runBlocking {
        db.sourceDao().insert(completeReadyRow())
    }

    @Test
    fun readyRowMissingASingleFieldIsRejectedOnInsert(): Unit = runBlocking {
        val incomplete = completeReadyRow().copy(sha256 = null)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.sourceDao().insert(incomplete) }
        }
    }

    @Test
    fun transitioningAStagedRowToReadyWithoutFillingFieldsIsRejectedOnUpdate(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        db.sourceDao().insert(stagedRow(id))
        val prematureReady = stagedRow(id).copy(state = "READY")
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.sourceDao().update(prematureReady) }
        }
    }
}
