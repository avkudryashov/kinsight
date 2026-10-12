package app.kinsight.core.database

import androidx.room.useReaderConnection
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDataTest {
    private val dir = Files.createTempDirectory("kinsight-local").toFile()
    private val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "local.db").absolutePath)
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "Mom")
    private val store = RoomEntryStore(db, genesis.groupId)
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val queue = RoomUsageQueue(db)
    private val state = CollectorStateRow(
        cursorMs = 0, zoneId = "UTC", screenOn = false, resumed = "", wallMs = 0, elapsedMs = 0, bootCount = 0,
        accessGranted = true, accessLostMs = null,
    )

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun summary(date: String) = author.create { it.copy(usage_summary = UsageSummary(local_date = date, zone_id = "UTC", total_ms = 60_000)) }
    private fun event(kind: StateEvent.Kind) = author.create { it.copy(state_event = StateEvent(kind = kind)) }

    @Test
    fun uiQueriesReturnTheirKindsOnly() = runTest {
        val sum = summary("2026-10-09")
        val app = author.create { it.copy(app_catalog = AppCatalog(package_name = "com.example.maps", label = "Maps")) }
        val beat = author.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 1)) }
        val pause = event(StateEvent.Kind.SHARING_PAUSED)
        val left = author.leaveGroupEntry()
        store.applyBatch(listOf(genesis.entry, sum, app, beat, pause, left))
        assertEquals(listOf(sum), store.summaries())
        assertEquals(listOf(app), store.catalog())
        assertEquals(listOf(beat), store.heartbeats())
        assertEquals(listOf(pause, left), store.stateEvents())
    }

    @Test
    fun peerControlledKeyTextCannotSpoofAKind() = runTest {
        val fakeApp = author.create { it.copy(app_catalog = AppCatalog(package_name = "x/hb", label = "X")) }
        val fakeSum = summary("x/app/y")
        store.applyBatch(listOf(genesis.entry, fakeApp, fakeSum))
        assertEquals(emptyList(), store.heartbeats())
        assertEquals(listOf(fakeApp), store.catalog())
        assertEquals(listOf(fakeSum), store.summaries())
    }

    @Test
    fun queueIsPrunedBeforeTheHorizon() = runTest {
        queue.commit(
            CollectBatch(
                usage = listOf(UsageDelta("2025-01-01", 9, "old.app", 60_000, 3_600_000), UsageDelta("2026-10-09", 9, "new.app", 60_000, 3_600_000)),
                hours = listOf(HourDelta("2025-01-01", 9, 60_000, 1, 3_600_000), HourDelta("2026-10-09", 9, 60_000, 1, 3_600_000)),
                days = listOf(DayZone("2025-01-01", "UTC"), DayZone("2026-10-09", "UTC")),
                events = listOf(PendingEventRow(kind = 6, fromMs = 1_000, toMs = 2_000), PendingEventRow(kind = 6, fromMs = 9_000, toMs = 10_000)),
                state = state,
            ),
        )
        queue.prune(beforeDate = "2026-01-01", beforeMs = 5_000)
        assertEquals(listOf("2026-10-09"), queue.changedDays().map { it.date })
        assertTrue(queue.dayUsage("2025-01-01").apps.isEmpty() && queue.dayUsage("2025-01-01").hours.isEmpty())
        assertEquals(listOf(9_000L), queue.pendingEvents().map { it.fromMs })
    }

    @Test
    fun packagesWithoutCatalogCoverPublishedDaysOnly() = runTest {
        queue.commit(
            CollectBatch(
                usage = listOf(
                    UsageDelta("2026-10-08", 9, "a.app", 60_000, 3_600_000),
                    UsageDelta("2026-10-08", 9, "b.app", 60_000, 3_600_000),
                    UsageDelta("2026-10-09", 9, "c.app", 60_000, 3_600_000),
                ),
                days = listOf(DayZone("2026-10-08", "UTC"), DayZone("2026-10-09", "UTC")),
                state = state,
            ),
        )
        queue.markPublished("2026-10-08", "h", 1)
        queue.setCatalogHash("a.app", "x")
        assertEquals(listOf("b.app"), queue.packagesWithoutCatalog())
    }

    @Test
    fun wipedTablesAreEveryTableOfTheSchema() {
        val json = File("schemas/app.kinsight.core.database.KinsightDatabase/3.json").readText()
        val tables = Regex("\"tableName\": \"([a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        assertEquals(tables, WIPED_TABLES.toSet())
    }

    @Test
    fun wipeAllEmptiesEveryTable() = runTest {
        store.applyBatch(listOf(genesis.entry, summary("2026-10-09")))
        store.appendOwn(listOf(author.create { it.copy(heartbeat = Heartbeat(app_version = "t")) }))
        queue.commit(
            CollectBatch(
                usage = listOf(UsageDelta("2026-10-09", 9, "a.app", 60_000, 3_600_000)),
                hours = listOf(HourDelta("2026-10-09", 9, 60_000, 1, 3_600_000)),
                days = listOf(DayZone("2026-10-09", "UTC")),
                events = listOf(PendingEventRow(kind = 6, fromMs = 1, toMs = 2)),
                state = state,
            ),
        )
        queue.setLastUsagePublishMs(1)
        queue.setCatalogHash("a.app", "x")
        db.vectorDao().addAbove(AboveRow(keys.deviceId.hex, 9))
        for (table in WIPED_TABLES) assertTrue(count(table) > 0, "$table empty before wipe")
        db.wipeAll()
        for (table in WIPED_TABLES) assertEquals(0L, count(table), table)
        assertEquals(0L, store.authorState(keys.deviceId).lastSeq)
    }

    @Test
    fun entriesChangedFiresAfterACommit() = runBlocking<Unit> {
        val seen = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { db.entriesChanged().collect { seen.send(Unit) } }
        withTimeout(5_000) { seen.receive() } // the initial emission
        store.applyBatch(listOf(genesis.entry))
        withTimeout(5_000) { seen.receive() }
        job.cancel()
    }

    private suspend fun count(table: String): Long = db.useReaderConnection { c ->
        c.usePrepared("SELECT COUNT(*) FROM $table") { st ->
            st.step()
            st.getLong(0)
        }
    }
}
