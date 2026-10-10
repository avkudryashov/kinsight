package app.kinsight.core.usage

import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectorTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val s = Script(zone, LocalDate.of(2026, 10, 9))
    private val dir = Files.createTempDirectory("kinsight-collector").toFile()
    private val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "usage.db").absolutePath)
    private val queue = RoomUsageQueue(db)
    private val source = FakeSource()
    private val access = FakeAccess()
    private val clock = FakeClock(wallMs = s.ms("10:00"), currentZone = zone)
    private val collector = Collector(queue, source, access, clock, FakeLaunchers())

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun minutes(date: String, hour: Int, pkg: String = MAPS): Long =
        queue.dayUsage(date).apps.filter { it.hour == hour && it.packageName == pkg }.sumOf { it.foregroundMs } / MIN

    private suspend fun kinds() = queue.pendingEvents().map { StateEvent.Kind.fromValue(it.kind) }

    @Test
    fun firstRunWithoutAccessStoresNothing() = runTest {
        access.granted = false
        assertEquals(CollectResult.NO_ACCESS, collector.collect())
        assertNull(queue.state())
        assertTrue(source.reads.isEmpty())
    }

    @Test
    fun firstRunReadsTheLastSevenLocalDays() = runTest {
        clock.wallMs = s.ms("12:00")
        source.events += listOf(s.resumed("10:00", MAPS, plusDays = -6), s.paused("10:30", MAPS, plusDays = -6))
        assertEquals(CollectResult.COLLECTED, collector.collect())
        assertEquals(listOf(s.ms("00:00", -6) to s.ms("12:00")), source.reads)
        assertEquals(30L, minutes("2026-10-03", 10))
        assertEquals(listOf("2026-10-03"), queue.changedDays().map { it.date })
        assertEquals(s.ms("12:00"), queue.state()!!.cursorMs)
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun nextRunContinuesFromTheCursorWithTheOpenSession() = runTest {
        source.events += s.resumed("09:50", MAPS)
        collector.collect() // 10:00, MAPS still open
        source.events += s.paused("10:45", MAPS)
        clock.advance(60 * MIN)
        collector.collect() // 11:00
        assertEquals(s.ms("10:00") to s.ms("11:00"), source.reads.last())
        assertEquals(10L, minutes("2026-10-09", 9))
        assertEquals(45L, minutes("2026-10-09", 10))
        assertEquals("", queue.state()!!.resumed)
    }

    @Test
    fun revokedAccessRecordsOneEventAndHoldsTheCursor() = runTest {
        collector.collect() // 10:00
        access.granted = false
        clock.advance(60 * MIN)
        assertEquals(CollectResult.NO_ACCESS, collector.collect()) // 11:00
        clock.advance(60 * MIN)
        collector.collect() // 12:00
        assertEquals(listOf(StateEvent.Kind.USAGE_ACCESS_REVOKED), kinds())
        assertEquals(s.ms("10:00"), queue.state()!!.cursorMs)

        source.events += listOf(s.resumed("10:30", MAPS), s.paused("11:30", MAPS))
        access.granted = true
        clock.advance(60 * MIN)
        assertEquals(CollectResult.COLLECTED, collector.collect()) // 13:00
        val restored = queue.pendingEvents().last()
        assertEquals(StateEvent.Kind.USAGE_ACCESS_RESTORED.value, restored.kind)
        assertEquals(s.ms("11:00") to s.ms("13:00"), restored.fromMs to restored.toMs)
        assertEquals(s.ms("10:00") to s.ms("13:00"), source.reads.last())
        assertEquals(30L, minutes("2026-10-09", 10))
        assertEquals(30L, minutes("2026-10-09", 11))
    }

    @Test
    fun forwardClockJumpIsRecordedAndReadThrough() = runTest {
        collector.collect() // 10:00
        clock.elapsedMs += 30 * MIN
        clock.wallMs += 90 * MIN // 30 min passed, the clock moved 1 h ahead
        collector.collect()
        val e = queue.pendingEvents().single()
        assertEquals(StateEvent.Kind.CLOCK_CHANGED.value, e.kind)
        assertEquals(60 * MIN, e.clockShiftMs)
        assertEquals(s.ms("10:00") to s.ms("11:30"), e.fromMs to e.toMs)
        assertEquals(s.ms("10:00") to s.ms("11:30"), source.reads.last())
    }

    @Test
    fun backwardClockJumpRebasesTheCursor() = runTest {
        collector.collect() // 10:00
        clock.elapsedMs += 30 * MIN
        clock.wallMs -= 30 * MIN // 30 min passed, the clock now reads 09:30
        collector.collect()
        assertEquals(-60 * MIN, queue.pendingEvents().single().clockShiftMs)
        assertEquals(s.ms("10:00") to s.ms("10:30"), source.reads.last())
        assertEquals(s.ms("09:30"), queue.state()!!.cursorMs)
    }

    @Test
    fun clockBehindTheCursorAfterARebootIsRecorded() = runTest {
        collector.collect() // 10:00
        val readsBefore = source.reads.size
        clock.bootCount += 1
        clock.elapsedMs = 5 * MIN
        clock.wallMs = s.ms("09:00")
        collector.collect()
        assertEquals(-60 * MIN, queue.pendingEvents().single().clockShiftMs)
        assertEquals(readsBefore, source.reads.size)
        assertEquals(s.ms("09:00"), queue.state()!!.cursorMs)
    }

    @Test
    fun staleCursorRecordsADataGap() = runTest {
        collector.collect() // 10:00
        clock.advance(10 * DAY)
        collector.collect()
        val gap = queue.pendingEvents().single()
        assertEquals(StateEvent.Kind.DATA_GAP.value, gap.kind)
        assertEquals(s.ms("10:00") to clock.wallMs - 8 * DAY, gap.fromMs to gap.toMs)
        assertEquals(clock.wallMs - 8 * DAY to clock.wallMs, source.reads.last())
    }

    @Test
    fun accessRevokedDuringTheReadDoesNotCommitIt() = runTest {
        collector.collect() // 10:00
        source.events += listOf(s.resumed("10:10", MAPS), s.paused("10:40", MAPS))
        source.duringRead = { access.granted = false } // the system now answers with no events
        clock.advance(60 * MIN)
        assertEquals(CollectResult.NO_ACCESS, collector.collect()) // 11:00
        assertEquals(listOf(StateEvent.Kind.USAGE_ACCESS_REVOKED), kinds())
        assertEquals(s.ms("10:00"), queue.state()!!.cursorMs)
        assertEquals(s.ms("11:00"), queue.state()!!.accessLostMs)
        assertEquals(0L, minutes("2026-10-09", 10))

        source.duringRead = {}
        access.granted = true
        clock.advance(60 * MIN)
        assertEquals(CollectResult.COLLECTED, collector.collect()) // 12:00: the held period is read again
        assertEquals(s.ms("10:00") to s.ms("12:00"), source.reads.last())
        assertEquals(30L, minutes("2026-10-09", 10))
    }

    @Test
    fun firstRunRevokedDuringTheReadStoresNothing() = runTest {
        source.duringRead = { access.granted = false }
        assertEquals(CollectResult.NO_ACCESS, collector.collect())
        assertNull(queue.state())
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun aZoneChangeAppliesFromTheNextRun() = runTest {
        collector.collect() // 10:00 Madrid
        source.events += listOf(s.resumed("10:00", MAPS), s.paused("10:30", MAPS))
        clock.currentZone = ZoneId.of("Asia/Tokyo")
        clock.advance(40 * MIN)
        collector.collect() // events up to now still count in Madrid time
        assertEquals(30L, minutes("2026-10-09", 10))
        assertEquals("Asia/Tokyo", queue.state()!!.zoneId)

        source.events += listOf(s.resumed("11:00", MAPS), s.paused("11:10", MAPS)) // 18:00 in Tokyo
        clock.advance(40 * MIN)
        collector.collect()
        assertEquals(10L, minutes("2026-10-09", 18))
        assertEquals("Asia/Tokyo", queue.changedDays().single().zoneId)
    }

    @Test
    fun everyTouchedDateIsMarkedChanged() = runTest {
        // An unlock-only day (hours delta, no usage) and a session across midnight (usage on two dates).
        source.events += listOf(
            s.unlock("12:00", plusDays = -2),
            s.resumed("23:50", MAPS, plusDays = -1),
            s.paused("00:10", MAPS),
        )
        collector.collect() // 10:00, first run
        assertEquals(listOf("2026-10-07", "2026-10-08", "2026-10-09"), queue.changedDays().map { it.date })
        assertEquals(10L, minutes("2026-10-08", 23))
        assertEquals(10L, minutes("2026-10-09", 0))
        assertTrue(queue.changedDays().all { it.zoneId == "Europe/Madrid" })
    }

    @Test
    fun anHourNeverHoldsMoreThanItsLength() = runTest {
        source.events += listOf(s.resumed("10:00", MAPS), s.paused("10:50", MAPS))
        clock.advance(60 * MIN)
        collector.collect() // 11:00: 50 min
        clock.wallMs = s.ms("09:59") // the clock is set back 61 min, no time passes
        collector.collect() // CLOCK_CHANGED, cursor 09:59, nothing read
        clock.advance(61 * MIN)
        collector.collect() // re-reads 09:59..11:00: the same 50 min again
        assertEquals(60L, minutes("2026-10-09", 10))
        assertEquals(listOf(StateEvent.Kind.CLOCK_CHANGED), kinds())
    }
}
