package app.kinsight.core.database

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomUsageQueueTest {
    private val dir = Files.createTempDirectory("kinsight-queue").toFile()
    private val path = File(dir, "queue.db").absolutePath
    private val opened = mutableListOf<KinsightDatabase>()
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val date = "2026-10-09"
    private val state = CollectorStateRow(
        cursorMs = 1_000, zoneId = "Europe/Madrid", screenOn = true, resumed = "a/a.Main",
        wallMs = 1_000, elapsedMs = 50, bootCount = 3, accessGranted = true, accessLostMs = null,
    )

    private fun open(): RoomUsageQueue {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        opened += db
        return RoomUsageQueue(db)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun usage(ms: Long, unlocks: Int = 0, zone: String = "Europe/Madrid") = CollectBatch(
        usage = listOf(UsageDelta(date, 10, "a", ms, hour)),
        hours = listOf(HourDelta(date, 10, ms, unlocks, hour)),
        days = listOf(DayZone(date, zone)),
        state = state,
    )

    @Test
    fun accumulatesUsageUpToTheHourCap() = runTest {
        val q = open()
        q.commit(usage(40 * minute, unlocks = 1))
        q.commit(usage(40 * minute, unlocks = 1))
        val day = q.dayUsage(date)
        assertEquals(listOf(PendingUsageRow(date, 10, "a", hour)), day.apps)
        assertEquals(listOf(PendingHourRow(date, 10, hour, 2)), day.hours)
    }

    @Test
    fun dayVersionsTrackWhatWasPublished() = runTest {
        val q = open()
        q.commit(usage(minute))
        assertEquals(listOf(PendingDayRow(date, "Europe/Madrid", 1, 0, null)), q.changedDays())
        q.markPublished(date, "h1", 1)
        assertTrue(q.changedDays().isEmpty())
        q.commit(usage(minute, zone = "Asia/Tokyo"))
        assertEquals(listOf(PendingDayRow(date, "Asia/Tokyo", 2, 1, "h1")), q.changedDays())
        q.markPublished(date, "h2", 1) // stale: built before the second commit
        assertEquals(2L, q.changedDays().single().version)
        q.markPublished(date, "h2", 2)
        assertTrue(q.changedDays().isEmpty())
    }

    @Test
    fun aStaleMarkNeverMovesThePublishedMarkerBack() = runTest {
        val q = open()
        q.commit(usage(minute))
        q.commit(usage(minute))
        q.markPublished(date, "h2", 2)
        q.markPublished(date, "h1", 1) // a late mark from an older run
        q.commit(usage(minute))
        assertEquals(listOf(PendingDayRow(date, "Europe/Madrid", 3, 2, "h2")), q.changedDays())
    }

    @Test
    fun eventsKeepOrderUntilDeleted() = runTest {
        val q = open()
        q.commit(CollectBatch(events = listOf(PendingEventRow(kind = 3, fromMs = 1, toMs = 1)), state = state))
        q.commit(CollectBatch(events = listOf(PendingEventRow(kind = 4, fromMs = 1, toMs = 9)), state = state))
        val events = q.pendingEvents()
        assertEquals(listOf(3, 4), events.map { it.kind })
        q.deleteEvent(events[0].id)
        assertEquals(listOf(4), q.pendingEvents().map { it.kind })
    }

    @Test
    fun stateSurvivesReopenAndIsReplacedWhole() = runTest {
        val first = open()
        assertNull(first.state())
        first.commit(CollectBatch(state = state))
        first.commit(CollectBatch(state = state.copy(cursorMs = 2_000, accessGranted = null)))
        opened.removeLast().close()
        assertEquals(state.copy(cursorMs = 2_000, accessGranted = null), open().state())
    }

    @Test
    fun failedCommitLeavesNoTrace() = runTest {
        val q = open()
        q.faultInjector = { throw IllegalStateException("injected") }
        val batch = usage(minute).copy(events = listOf(PendingEventRow(kind = 3, fromMs = 1, toMs = 1)))
        assertTrue(runCatching { q.commit(batch) }.isFailure)
        q.faultInjector = null
        assertTrue(q.dayUsage(date).apps.isEmpty())
        assertTrue(q.changedDays().isEmpty())
        assertTrue(q.pendingEvents().isEmpty())
        assertNull(q.state())
    }

    @Test
    fun publishAndCatalogStateRoundTrip() = runTest {
        val q = open()
        assertNull(q.lastUsagePublishMs())
        q.setLastUsagePublishMs(5)
        q.setLastUsagePublishMs(7)
        assertEquals(7L, q.lastUsagePublishMs())
        assertNull(q.catalogHash("a"))
        q.setCatalogHash("a", "x")
        q.setCatalogHash("a", "y")
        assertEquals("y", q.catalogHash("a"))
    }
}
