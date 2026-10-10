package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsagePipelineTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val s = Script(zone, LocalDate.of(2026, 10, 9))
    private val dir = Files.createTempDirectory("kinsight-pipeline").toFile()
    private val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "pipeline.db").absolutePath)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings = SettingsStore.create(File(dir, "usage.preferences_pb"), scope)
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "phone")
    private val store = RoomEntryStore(db, genesis.groupId)
    private val source = FakeSource()
    private val clock = FakeClock(wallMs = s.ms("10:00"), currentZone = zone)

    private fun pipeline(target: PublishTarget?) = UsagePipeline(
        db = db,
        collector = Collector(RoomUsageQueue(db), source, FakeAccess(), clock, FakeLaunchers()),
        settings = settings,
        apps = FakeApps(),
        target = { target },
        clock = { clock.wallMs },
        today = { LocalDate.ofInstant(Instant.ofEpochMilli(clock.wallMs), zone) },
    )

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun own() = store.liveEntries().filter { it.author == keys.deviceId && it.seq > 1 }
    private fun DecodedEntry.stateKind() = body.state_event?.kind

    @Test
    fun withoutAGroupItOnlyCollects() = runTest {
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        val report = pipeline(null).collectAndPublish()
        assertEquals(CollectResult.COLLECTED, report.collect)
        assertNull(report.publish)
        assertEquals(listOf("2026-10-09"), RoomUsageQueue(db).changedDays().map { it.date })
    }

    @Test
    fun pauseIsVisibleAndBacklogFollowsResume() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        assertEquals(PublishReport.NONE, p.collectAndPublish().publish) // sharing is off by default
        assertTrue(own().isEmpty())

        p.setSharing(true) // RESUMED, then the backlog
        assertEquals(listOf(2L, 3L, 4L), own().map { it.seq })
        assertEquals(StateEvent.Kind.SHARING_RESUMED, own()[0].stateKind())
        assertEquals(30 * MIN, own()[1].body.usage_summary!!.total_ms)

        p.setSharing(true) // no change, no entry
        assertEquals(4L, store.authorState(keys.deviceId).lastSeq)

        p.setSharing(false)
        assertEquals(StateEvent.Kind.SHARING_PAUSED, own().last().stateKind())

        clock.advance(60 * MIN) // 11:00
        source.events += listOf(s.resumed("10:10", MAPS), s.paused("10:20", MAPS))
        p.collectAndPublish(force = true) // counted while paused, not published
        assertEquals(5L, store.authorState(keys.deviceId).lastSeq)

        p.setSharing(true)
        assertEquals(StateEvent.Kind.SHARING_RESUMED, own().single { it.seq == 6L }.stateKind())
        val summary = own().single { it.kind == EntryKind.USAGE_SUMMARY }
        assertEquals(7L, summary.seq)
        assertEquals(40 * MIN, summary.body.usage_summary!!.total_ms)
    }
}
