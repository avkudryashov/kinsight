package app.kinsight.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.UsageSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomEntryStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "kinsight-device-test.db"
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "phone")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)

    private fun summary(date: String): DecodedEntry = author.create {
        it.copy(usage_summary = UsageSummary(local_date = date, zone_id = "Europe/Madrid", total_ms = 5))
    }

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun storesAndReopens(): Unit = runBlocking {
        val e2 = summary("2026-10-09")
        val db = KinsightDatabaseFactory.open(context, name)
        RoomEntryStore(db, genesis.groupId).applyBatch(listOf(genesis.entry, e2), seen = mapOf(keys.deviceId to setOf(4L)))
        db.close()
        val again = KinsightDatabaseFactory.open(context, name)
        val store = RoomEntryStore(again, genesis.groupId)
        assertEquals(2L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 4))
        assertEquals(listOf(genesis.entry, e2), store.liveEntries())
        again.close()
    }

    @Test
    fun rollsBackFailedBatch(): Unit = runBlocking {
        val db = KinsightDatabaseFactory.open(context, name)
        val store = RoomEntryStore(db, genesis.groupId)
        store.faultInjector = { throw IllegalStateException("injected") }
        assertTrue(runCatching { store.applyBatch(listOf(genesis.entry)) }.isFailure)
        store.faultInjector = null
        assertTrue(store.liveEntries().isEmpty())
        assertFalse(store.isAccounted(keys.deviceId, 1))
        db.close()
    }
}
