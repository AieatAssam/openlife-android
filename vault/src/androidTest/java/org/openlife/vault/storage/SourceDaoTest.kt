package org.openlife.vault.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.SourceState

/**
 * P1-18-R13 (VS-1): state changes are compare-and-set statements. Two
 * writers acting on the same STAGED snapshot cannot both win, and the loser
 * learns it explicitly instead of silently overwriting the row.
 */
@RunWith(AndroidJUnit4::class)
class SourceDaoTest {
    private lateinit var db: OpenLifeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            OpenLifeDatabase::class.java,
        )
            .openHelperFactory(SupportOpenHelperFactory("p118-cas".toByteArray()))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun compareAndSetStateTransitionLosesRaceExplicitly(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val dao = db.sourceDao()
        dao.insert(completeRow(id, state = "STAGED"))
        val snapshot = dao.findById(id)!!

        // Writer A (Save) commits READY; writer B (recovery) acts on the same STAGED snapshot.
        val first = dao.compareAndSetState(id, SourceState.STAGED, SourceState.READY)
        val second = dao.compareAndSetState(id, SourceState.STAGED, SourceState.DELETING)

        assertEquals(StateTransition.APPLIED, first)
        assertEquals(StateTransition.LOST_RACE, second)
        val after = dao.findById(id)!!
        assertEquals("READY", after.state)
        // Only the state column moved: nothing from the stale snapshot was written back.
        assertEquals(snapshot.copy(state = "READY"), after)
        assertArrayEquals(snapshot.sha256, after.sha256)
        assertEquals(0, dao.transitionState(id, "STAGED", "DELETING"))
    }

    private fun completeRow(id: String, state: String) = SourceEntity(
        id = id,
        state = state,
        importedAt = 1,
        intakeKind = "SHARE",
        mimeType = "image/jpeg",
        byteCount = 10,
        sha256 = ByteArray(32) { 1 },
        width = 10,
        height = 10,
        orientation = "NORMAL",
        wrappedDek = ByteArray(16) { 2 },
        artefactVersion = 1,
    )
}
