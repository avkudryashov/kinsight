package app.kinsight.core.usage

import app.kinsight.core.database.CollectBatch
import app.kinsight.core.database.CollectorStateRow
import app.kinsight.core.database.DayZone
import app.kinsight.core.database.HourDelta
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.database.UsageDelta
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
import kotlin.test.assertFailsWith
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

    private fun gated(target: PublishTarget?, allowed: Boolean, apps: FakeApps = FakeApps()) = UsagePipeline(
        db = db,
        collector = Collector(RoomUsageQueue(db), source, FakeAccess(), clock, FakeLaunchers()),
        settings = settings,
        apps = apps,
        target = { target },
        clock = { clock.wallMs },
        today = { LocalDate.ofInstant(Instant.ofEpochMilli(clock.wallMs), zone) },
        ownUsage = { allowed },
    )

    private val signals = mutableListOf<StateEvent.Kind>()

    private fun signalling(target: PublishTarget?, allowed: Boolean = true) = UsagePipeline(
        db = db,
        collector = Collector(RoomUsageQueue(db), source, FakeAccess(), clock, FakeLaunchers()),
        settings = settings,
        apps = FakeApps(),
        target = { target },
        clock = { clock.wallMs },
        today = { LocalDate.ofInstant(Instant.ofEpochMilli(clock.wallMs), zone) },
        ownUsage = { allowed },
        onOwnStateEvent = { signals += it },
    )

    private suspend fun signOwn(kind: StateEvent.Kind, p: UsagePipeline) {
        p.appendOwn { author -> listOf(author.create { it.copy(state_event = StateEvent(kind = kind)) }) }
    }

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
                // The first call comes from collectAndPublish before it collects: hold it there.
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
    fun signedSharingChangesAreSignalled() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = signalling(PublishTarget(genesis.groupId, keys))
        p.setSharing(true)
        p.setSharing(false)
        assertEquals(listOf(StateEvent.Kind.SHARING_RESUMED, StateEvent.Kind.SHARING_PAUSED), signals)
    }

    @Test
    fun queuedEventIsSignalledWhileSharingAndAgainWhenSigned() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = signalling(PublishTarget(genesis.groupId, keys))
        p.setSharing(true)
        signals.clear()
        // Now-ish times: collectAndPublish prunes queued events older than the retention.
        assertTrue(p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = clock.wallMs - MIN, toMs = clock.wallMs))
        assertEquals(listOf(StateEvent.Kind.BLUETOOTH_OFF), signals) // queued: the next session signs and sends it
        p.collectAndPublish(force = true)
        assertEquals(listOf(StateEvent.Kind.BLUETOOTH_OFF, StateEvent.Kind.BLUETOOTH_OFF), signals)
    }

    @Test
    fun eventQueuedWhilePausedIsSignalledOnlyWhenSigned() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = signalling(PublishTarget(genesis.groupId, keys))
        assertTrue(p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = 1_000, toMs = 2_000))
        assertTrue(signals.isEmpty()) // sharing is off: nothing would be signed before a session
        p.setSharing(true)
        assertEquals(listOf(StateEvent.Kind.SHARING_RESUMED, StateEvent.Kind.BLUETOOTH_OFF), signals)
    }

    @Test
    fun refusedEventIsNotSignalled() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = signalling(PublishTarget(genesis.groupId, keys), allowed = false)
        assertFalse(p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = 1_000, toMs = 2_000))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun appendOwnSignalsStateEventsOnly() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = signalling(PublishTarget(genesis.groupId, keys))
        p.appendOwn { author -> listOf(author.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 1)) }) }
        assertTrue(signals.isEmpty())
        signOwn(StateEvent.Kind.LEFT_GROUP, p)
        assertEquals(listOf(StateEvent.Kind.LEFT_GROUP), signals)
    }

    @Test
    fun queuedEventIsPublishedWithTheOthers() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        assertTrue(p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = 1_000, toMs = 2_000))
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

    @Test
    fun parentNeverCollectsOrPublishes() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = gated(PublishTarget(genesis.groupId, keys), allowed = false)
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        assertEquals(RunReport(CollectResult.NOT_ALLOWED, null), p.collectAndPublish(force = true))
        assertNull(RoomUsageQueue(db).state()) // nothing was read from the system
        assertFalse(p.queueEvent(StateEvent.Kind.BLUETOOTH_OFF, fromMs = 1_000, toMs = 2_000))
        assertTrue(RoomUsageQueue(db).pendingEvents().isEmpty())
        assertFailsWith<IllegalStateException> { p.setSharing(true) }
        assertFalse(settings.current().sharing)
        assertTrue(own().isEmpty())
    }

    @Test
    fun reconcileTurnsSettingOffAfterSignedPause() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        p.setSharing(true)
        signOwn(StateEvent.Kind.SHARING_PAUSED, p) // the crash: the pause is signed, the setting never written
        val before = store.authorState(keys.deviceId).lastSeq
        p.reconcileSharing()
        assertFalse(settings.current().sharing)
        assertEquals(before, store.authorState(keys.deviceId).lastSeq)
    }

    @Test
    fun reconcileTurnsSettingOnAfterSignedResume() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        p.collectAndPublish()
        signOwn(StateEvent.Kind.SHARING_RESUMED, p)
        p.reconcileSharing()
        assertTrue(settings.current().sharing)
        assertEquals(30 * MIN, own().single { it.kind == EntryKind.USAGE_SUMMARY }.body.usage_summary!!.total_ms)
    }

    @Test
    fun reconcileSignsResumedWhenJoinedWithSharingOn() = runTest {
        settings.setSharing(true) // switched on while no group existed: nothing could be signed then
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(PublishTarget(genesis.groupId, keys))
        p.reconcileSharing()
        assertEquals(StateEvent.Kind.SHARING_RESUMED, own().first().stateKind())
        p.reconcileSharing()
        assertEquals(1, own().count { it.stateKind() == StateEvent.Kind.SHARING_RESUMED })
    }

    @Test
    fun reconcileTurnsSharingOffWhenNotAllowed() = runTest {
        settings.setSharing(true)
        store.applyBatch(listOf(genesis.entry))
        gated(PublishTarget(genesis.groupId, keys), allowed = false).reconcileSharing()
        assertFalse(settings.current().sharing)
        assertTrue(own().isEmpty())
    }

    @Test
    fun reconcileSignsNothingForADeviceThatMayNotShareAfterAResume() = runTest {
        // Preflight C16: a device that may not share (a parent) never signs a StateEvent, not even SHARING_PAUSED.
        store.applyBatch(listOf(genesis.entry))
        pipeline(PublishTarget(genesis.groupId, keys)).setSharing(true) // signed SHARING_RESUMED while still allowed
        val before = store.authorState(keys.deviceId).lastSeq
        gated(PublishTarget(genesis.groupId, keys), allowed = false).reconcileSharing()
        assertFalse(settings.current().sharing)
        assertEquals(before, store.authorState(keys.deviceId).lastSeq)
        assertTrue(own().none { it.stateKind() == StateEvent.Kind.SHARING_PAUSED })
    }

    @Test
    fun reconcileWithoutAGroupChangesNothing() = runTest {
        settings.setSharing(true)
        pipeline(null).reconcileSharing()
        assertTrue(settings.current().sharing)
    }

    @Test
    fun queueIsPrunedPastTheSummaryRetention() = runTest {
        settings.setRetention(detailDays = 10, summaryDays = 30)
        val q = RoomUsageQueue(db)
        val sample = clock.sample()
        val horizon = "2026-09-09" // today (2026-10-09) - summaryDays: the oldest day that is kept
        val inside = clock.wallMs - 10 * 24 * 60 * MIN // an event well inside the 30-day horizon
        q.commit(
            CollectBatch(
                usage = listOf(UsageDelta("2026-08-01", 9, MAPS, MIN, 60 * MIN), UsageDelta(horizon, 9, MAPS, MIN, 60 * MIN)),
                hours = listOf(HourDelta("2026-08-01", 9, MIN, 1, 60 * MIN), HourDelta(horizon, 9, MIN, 1, 60 * MIN)),
                days = listOf(DayZone("2026-08-01", zone.id), DayZone(horizon, zone.id)),
                events = listOf(
                    PendingEventRow(kind = StateEvent.Kind.BLUETOOTH_OFF.value, fromMs = 1_000, toMs = 2_000),
                    PendingEventRow(kind = StateEvent.Kind.BLUETOOTH_OFF.value, fromMs = inside, toMs = inside + MIN),
                ),
                state = CollectorStateRow(
                    cursorMs = sample.wallMs, zoneId = zone.id, screenOn = false, resumed = "", wallMs = sample.wallMs,
                    elapsedMs = sample.elapsedMs, bootCount = sample.bootCount, accessGranted = true, accessLostMs = null,
                ),
            ),
        )
        pipeline(null).collectAndPublish()
        assertTrue(q.dayUsage("2026-08-01").apps.isEmpty())
        assertTrue(q.changedDays().none { it.date == "2026-08-01" })
        assertEquals(listOf(inside), q.pendingEvents().map { it.fromMs })
        assertEquals(listOf(MAPS), q.dayUsage(horizon).apps.map { it.packageName })
        assertTrue(q.changedDays().any { it.date == horizon })
    }

    @Test
    fun switchingOffWithAClosedGateSignsNothing() = runTest {
        // Preflight C16 / fix round 1: a device that may not share only writes the setting, never SHARING_PAUSED.
        settings.setSharing(true)
        store.applyBatch(listOf(genesis.entry))
        gated(PublishTarget(genesis.groupId, keys), allowed = false).setSharing(false)
        assertFalse(settings.current().sharing)
        assertTrue(own().isEmpty())
    }

    @Test
    fun catalogIsRetriedForPackagesWithoutState() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val apps = FakeApps() // MAPS is invisible at first
        val p = gated(PublishTarget(genesis.groupId, keys), allowed = true, apps = apps)
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        p.collectAndPublish()
        p.setSharing(true)
        assertTrue(own().none { it.kind == EntryKind.APP_CATALOG })
        apps.known[MAPS] = AppInfo("Maps", byteArrayOf(1, 2), 6)
        clock.advance(120 * MIN)
        p.collectAndPublish() // the day itself is unchanged, the missing catalog entry still comes
        assertEquals("Maps", own().single { it.kind == EntryKind.APP_CATALOG }.body.app_catalog!!.label)
    }

    /** Since cloud Y5 (spec §10) a hidden notification is a signal the parents see, never a pause: usage goes on. */
    @Test
    fun aHiddenNotificationIsASignalNotAPause() = runTest {
        store.applyBatch(listOf(genesis.entry))
        val apps = FakeApps().also { it.known[MAPS] = AppInfo("Maps", byteArrayOf(1, 2), 6) }
        val p = UsagePipeline(
            db = db,
            collector = Collector(RoomUsageQueue(db), source, FakeAccess(), clock, FakeLaunchers()),
            settings = settings,
            apps = apps,
            target = { PublishTarget(genesis.groupId, keys) },
            clock = { clock.wallMs },
            today = { LocalDate.ofInstant(Instant.ofEpochMilli(clock.wallMs), zone) },
        )
        p.setSharing(true)
        assertTrue(p.queueEvent(StateEvent.Kind.NOTIFICATION_HIDDEN, clock.wallMs, clock.wallMs))
        source.events += listOf(s.resumed("09:00", MAPS), s.paused("09:30", MAPS))
        val report = p.collectAndPublish(force = true).publish!!
        assertEquals(1, report.events)
        assertEquals(listOf("2026-10-09"), report.days)
        assertTrue(StateEvent.Kind.NOTIFICATION_HIDDEN in own().map { it.stateKind() })
        assertEquals(30 * MIN, own().single { it.kind == EntryKind.USAGE_SUMMARY }.body.usage_summary!!.total_ms)
    }
}
