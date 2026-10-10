package app.kinsight.core.usage

import app.kinsight.core.database.AuthorState
import app.kinsight.core.database.CollectBatch
import app.kinsight.core.database.CollectorStateRow
import app.kinsight.core.database.DayZone
import app.kinsight.core.database.HourDelta
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.PendingEventRow
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.database.UsageDelta
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublisherTest {
    private val dir = Files.createTempDirectory("kinsight-publisher").toFile()
    private val path = File(dir, "publisher.db").absolutePath
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "phone")
    private val apps = FakeApps()
    private var now = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 10, 9)
    private val sharing = UsageSettings(sharing = true)
    private val opened = mutableListOf<KinsightDatabase>()
    private lateinit var queue: RoomUsageQueue
    private lateinit var store: RoomEntryStore

    private val state = CollectorStateRow(
        cursorMs = 0, zoneId = "Europe/Madrid", screenOn = true, resumed = "",
        wallMs = 0, elapsedMs = 0, bootCount = 0, accessGranted = true, accessLostMs = null,
    )

    private suspend fun open(fresh: Boolean = true) {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        opened += db
        queue = RoomUsageQueue(db)
        store = RoomEntryStore(db, genesis.groupId)
        if (fresh) store.applyBatch(listOf(genesis.entry))
    }

    private fun publisher() = Publisher(queue, store, keys, apps, { now }, { today })

    private suspend fun seed(date: String, hour: Int, pkg: String, minutes: Long, unlocks: Int = 0) = queue.commit(
        CollectBatch(
            usage = listOf(UsageDelta(date, hour, pkg, minutes * MIN, HOUR_MS)),
            hours = listOf(HourDelta(date, hour, minutes * MIN, unlocks, HOUR_MS)),
            days = listOf(DayZone(date, "Europe/Madrid")),
            state = state,
        ),
    )

    /** Own entries after the genesis, ascending by seq. */
    private suspend fun own() = store.liveEntries().filter { it.author == keys.deviceId && it.seq > 1 }

    private suspend fun lastSeq() = store.authorState(keys.deviceId).lastSeq

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun pausedPublishesNothingAndKeepsTheBacklog() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30)
        assertEquals(PublishReport.NONE, publisher().publish(UsageSettings(sharing = false), force = true))
        assertTrue(own().isEmpty())
        assertEquals(1, queue.changedDays().size)
    }

    @Test
    fun publishesSignedSummaryAndDetail() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30, unlocks = 2)
        seed("2026-10-08", 11, CHAT, 5)
        assertEquals(listOf("2026-10-08"), publisher().publish(sharing, force = false).days)
        val (sum, det) = own()
        assertEquals(2L to 3L, sum.seq to det.seq)
        assertEquals(EntryKind.USAGE_SUMMARY to EntryKind.USAGE_DETAIL, sum.kind to det.kind)
        assertTrue(sum.verifySignature(keys.sign.publicKey) && det.verifySignature(keys.sign.publicKey))
        val s = sum.body.usage_summary!!
        assertEquals("Europe/Madrid", s.zone_id)
        assertEquals(35 * MIN, s.total_ms)
        assertEquals(2, s.unlocks)
        assertEquals(listOf(MAPS to 30 * MIN, CHAT to 5 * MIN), s.apps.map { it.package_name to it.foreground_ms })
        assertEquals(listOf(10, 11), det.body.usage_detail!!.hours.map { it.hour })
        assertEquals(AuthorState(3, det.lamport), store.authorState(keys.deviceId))
        assertTrue(queue.changedDays().isEmpty())
    }

    @Test
    fun unchangedContentIsNotRepublished() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30)
        publisher().publish(sharing, force = false)
        now += 2 * HOUR_MS
        queue.commit(CollectBatch(days = listOf(DayZone("2026-10-08", "Europe/Madrid")), state = state))
        assertTrue(publisher().publish(sharing, force = true).days.isEmpty())
        assertEquals(3L, lastSeq())
        assertTrue(queue.changedDays().isEmpty())
    }

    @Test
    fun newVersionsWaitAnHourUnlessForced() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30)
        publisher().publish(sharing, force = false) // seq 2, 3
        seed("2026-10-08", 10, MAPS, 5)
        now += 10 * MIN
        assertTrue(publisher().publish(sharing, force = false).days.isEmpty())
        assertEquals(3L, lastSeq())
        assertEquals(listOf("2026-10-08"), publisher().publish(sharing, force = true).days) // seq 4, 5
        seed("2026-10-08", 11, MAPS, 5)
        now += 61 * MIN
        assertEquals(listOf("2026-10-08"), publisher().publish(sharing, force = false).days) // seq 6, 7
        val summaries = own().filter { it.kind == EntryKind.USAGE_SUMMARY }
        assertEquals(listOf(6L), summaries.map { it.seq })
        assertEquals(40 * MIN, summaries.single().body.usage_summary!!.total_ms)
    }

    @Test
    fun pendingEventsComeFirstInOrder() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30)
        queue.commit(
            CollectBatch(
                events = listOf(
                    PendingEventRow(kind = StateEvent.Kind.USAGE_ACCESS_REVOKED.value, fromMs = 1, toMs = 1),
                    PendingEventRow(kind = StateEvent.Kind.USAGE_ACCESS_RESTORED.value, fromMs = 1, toMs = 5),
                ),
                state = state,
            ),
        )
        assertEquals(2, publisher().publish(sharing, force = false).events)
        val entries = own()
        assertEquals(listOf(2L, 3L, 4L, 5L), entries.map { it.seq })
        assertEquals(
            listOf(StateEvent.Kind.USAGE_ACCESS_REVOKED, StateEvent.Kind.USAGE_ACCESS_RESTORED),
            entries.take(2).map { it.body.state_event!!.kind },
        )
        assertEquals(5L, entries[1].body.state_event!!.to_ms)
        assertTrue(queue.pendingEvents().isEmpty())
    }

    @Test
    fun retentionDecidesWhatIsPublished() = runTest {
        open()
        seed("2026-06-01", 10, MAPS, 30) // older than 90 days: summary only
        seed("2025-01-01", 10, MAPS, 30) // older than 365 days: nothing
        publisher().publish(sharing, force = false)
        assertEquals(listOf(EntryKind.USAGE_SUMMARY to "2026-06-01"), own().map { it.kind to it.usageDate.toString() })
        assertTrue(queue.changedDays().isEmpty())
    }

    @Test
    fun catalogIsEmittedOnlyOnChangeAndOnlyForVisibleApps() = runTest {
        open()
        apps.known[MAPS] = AppInfo("Maps", byteArrayOf(1, 2, 3), 6)
        seed("2026-10-08", 10, MAPS, 30)
        seed("2026-10-08", 11, CHAT, 5) // CHAT is not visible: no catalog entry
        assertEquals(listOf(MAPS), publisher().publish(sharing, force = false).catalog)
        val cat = own().single { it.kind == EntryKind.APP_CATALOG }.body.app_catalog!!
        assertEquals("Maps" to 6, cat.label to cat.category)
        assertContentEquals(byteArrayOf(1, 2, 3), cat.icon_webp.toByteArray())

        seed("2026-10-08", 12, MAPS, 5)
        now += 2 * HOUR_MS
        assertTrue(publisher().publish(sharing, force = false).catalog.isEmpty())

        apps.known[MAPS] = AppInfo("Maps 2", byteArrayOf(1, 2, 3), 6)
        seed("2026-10-08", 13, MAPS, 5)
        now += 2 * HOUR_MS
        assertEquals(listOf(MAPS), publisher().publish(sharing, force = false).catalog)
        assertEquals(listOf("Maps 2"), own().filter { it.kind == EntryKind.APP_CATALOG }.map { it.body.app_catalog!!.label })
    }

    @Test
    fun restartNeverReusesASeq() = runTest {
        open()
        seed("2026-10-08", 10, MAPS, 30)
        publisher().publish(sharing, force = false) // seq 2, 3
        opened.removeLast().close()

        open(fresh = false)
        seed("2026-10-09", 9, MAPS, 10)
        publisher().publish(sharing, force = true) // seq 4, 5
        assertEquals(listOf(2L, 3L, 4L, 5L), own().map { it.seq })
        assertEquals(5L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun sharingChangeIsSignedEvenWhilePaused() = runTest {
        open()
        publisher().recordSharingChange(false)
        val e = own().single().body.state_event!!
        assertEquals(StateEvent.Kind.SHARING_PAUSED, e.kind)
        assertEquals(now to now, e.from_ms to e.to_ms)
    }
}
