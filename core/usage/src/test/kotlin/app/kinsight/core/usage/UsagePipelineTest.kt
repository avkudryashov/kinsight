package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import kotlin.test.assertFalse
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

    @Test
    fun sharingWaitsForARunningCollection(): Unit = runBlocking {
        store.applyBatch(listOf(genesis.entry))
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        val target = PublishTarget(genesis.groupId, keys)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val p = UsagePipeline(
            db = db,
            collector = Collector(RoomUsageQueue(db), source, FakeAccess(), clock, FakeLaunchers()),
            settings = settings,
            apps = FakeApps(),
            target = {
                // The first call comes from collectAndPublish, between collecting and publishing: hold it there.
                if (calls++ == 0) {
                    inside.complete(Unit)
                    release.await()
                }
                target
            },
            clock = { clock.wallMs },
            today = { LocalDate.ofInstant(Instant.ofEpochMilli(clock.wallMs), zone) },
        )
        val run = async(Dispatchers.IO) { p.collectAndPublish(force = true) }
        inside.await()
        val flip = async(Dispatchers.IO) { p.setSharing(true) }
        delay(300)
        assertTrue(flip.isActive, "setSharing must wait for the pipeline lock")
        assertTrue(own().isEmpty())

        release.complete(Unit)
        assertEquals(PublishReport.NONE, run.await().publish) // sharing was still off when it published
        flip.await()
        // One deterministic order: the collection, then RESUMED and the backlog. Nothing lost or doubled.
        assertEquals(true, settings.current().sharing)
        assertEquals(listOf(2L, 3L, 4L), own().map { it.seq })
        assertEquals(StateEvent.Kind.SHARING_RESUMED, own()[0].stateKind())
        assertEquals(30 * MIN, own()[1].body.usage_summary!!.total_ms)
        assertEquals(4L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun ownEntriesShareTheSeqLineWithUsage() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        p.setSharing(true) // seq 2: SHARING_RESUMED
        assertTrue(p.appendOwn { author -> listOf(author.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 1)) }) })
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        p.collectAndPublish(force = true)
        val mine = own().sortedBy { it.seq }
        assertEquals((2L..mine.last().seq).toList(), mine.map { it.seq }) // no gap, no repeat
        assertEquals(EntryKind.HEARTBEAT, mine[1].kind)
        assertEquals(mine.last().seq, store.authorState(keys.deviceId).lastSeq)
    }

    @Test
    fun appendOwnWithoutAGroupDoesNothing() = runTest {
        assertFalse(pipeline(null).appendOwn { error("must not be called without a group") })
    }

    @Test
    fun appendOwnWithAnExplicitTargetStoresTheGenesis() = runTest {
        val target = PublishTarget(genesis.groupId, keys)
        assertTrue(pipeline(null).appendOwn(target) { author ->
            check(author.lastSeq == 0L)
            listOf(genesis.entry)
        })
        assertEquals(1L, store.authorState(keys.deviceId).lastSeq)
        assertEquals(listOf(genesis.entry), store.membershipEntries())
    }

    @Test
    fun queuedEventIsPublishedWithTheOthers() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = 1_000, toMs = 2_000)
        p.setSharing(true)
        val off = own().single { it.stateKind() == StateEvent.Kind.BLUETOOTH_OFF }.body.state_event!!
        assertEquals(1_000L to 2_000L, off.from_ms to off.to_ms)
        assertTrue(RoomUsageQueue(db).pendingEvents().isEmpty())
    }

    @Test
    fun currentSettingsReadsTheStore() = runTest {
        settings.setRetention(detailDays = 30, summaryDays = 200)
        val current = pipeline(null).currentSettings()
        assertEquals(30 to 200, current.detailRetentionDays to current.summaryRetentionDays)
    }
}
