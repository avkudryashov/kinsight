package app.kinsight.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregatorTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val day = LocalDate.of(2026, 10, 7)
    private val s = Script(zone, day)

    private fun slot(hour: Int, date: LocalDate = day) = HourSlot(date, hour)
    private fun app(hour: Int, pkg: String, date: LocalDate = day) = AppSlot(slot(hour, date), pkg)

    private fun run(
        events: List<UsageEvent>,
        from: Long = s.ms("00:00"),
        until: Long = s.ms("23:59:59"),
        start: StreamState = StreamState.INITIAL,
        z: ZoneId = zone,
    ) = Aggregator.run(start, events, from, until, z, setOf(LAUNCHER))

    /** A recorded morning: unlock, two apps, screen off mid-session, launcher, split screen. */
    private val morning = listOf(
        s.unlock("08:59"),
        s.resumed("09:00", MAPS), s.paused("09:40", MAPS), s.resumed("09:40", CHAT),
        s.screenOff("10:05"), s.screenOn("10:30"), s.unlock("10:30"),
        s.resumed("10:31", LAUNCHER), s.paused("10:32", LAUNCHER), s.resumed("10:32", MAPS),
        s.resumed("10:50", CHAT), s.paused("11:20", MAPS), s.paused("11:30", CHAT),
    )

    @Test
    fun recordedMorningMatchesTheHandCount() {
        val a = run(morning, s.ms("08:00"), s.ms("12:00"))
        assertEquals(
            mapOf(
                app(9, MAPS) to 40 * MIN, app(9, CHAT) to 20 * MIN,
                app(10, CHAT) to 15 * MIN, app(10, MAPS) to 28 * MIN,
                app(11, MAPS) to 20 * MIN, app(11, CHAT) to 30 * MIN,
            ),
            a.appMs,
        )
        // Union: split screen 10:50-11:20 counts once; the launcher and the dark screen count nothing.
        assertEquals(mapOf(slot(9) to 60 * MIN, slot(10) to 33 * MIN, slot(11) to 30 * MIN), a.screenMs)
        assertEquals(mapOf(slot(8) to 1, slot(10) to 1), a.unlocks)
        assertEquals(StreamState(screenOn = true, resumed = emptySet()), a.end)
    }

    @Test
    fun creditsOneSessionToItsHour() {
        val a = run(listOf(s.resumed("10:15", MAPS), s.paused("10:45", MAPS)))
        assertEquals(mapOf(app(10, MAPS) to 30 * MIN), a.appMs)
        assertEquals(mapOf(slot(10) to 30 * MIN), a.screenMs)
    }

    @Test
    fun splitsAtMidnightIntoTwoDates() {
        val a = run(
            listOf(s.resumed("23:50", MAPS), s.paused("00:10", MAPS, plusDays = 1)),
            from = s.ms("23:00"),
            until = s.ms("01:00", plusDays = 1),
        )
        assertEquals(mapOf(app(23, MAPS) to 10 * MIN, app(0, MAPS, day.plusDays(1)) to 10 * MIN), a.appMs)
    }

    @Test
    fun screenOffEndsTheSessionUntilTheAppResumesAgain() {
        val a = run(
            listOf(s.resumed("10:00", MAPS), s.screenOff("10:20"), s.screenOn("10:30"), s.resumed("10:31", MAPS), s.paused("10:40", MAPS)),
            until = s.ms("11:00"),
        )
        assertEquals(mapOf(app(10, MAPS) to 29 * MIN), a.appMs)
    }

    @Test
    fun aDarkScreenCreditsNothingEvenWithAnOpenActivity() {
        val start = StreamState(screenOn = false, resumed = setOf(ActivityKey(MAPS, "$MAPS.Main")))
        val a = run(emptyList(), from = s.ms("10:00"), until = s.ms("11:00"), start = start)
        assertTrue(a.appMs.isEmpty())
        assertEquals(start, a.end)
    }

    @Test
    fun excludesTheLauncher() {
        val a = run(listOf(s.resumed("10:00", LAUNCHER), s.paused("10:30", LAUNCHER), s.resumed("10:30", MAPS), s.paused("10:40", MAPS)))
        assertEquals(mapOf(app(10, MAPS) to 10 * MIN), a.appMs)
        assertEquals(mapOf(slot(10) to 10 * MIN), a.screenMs)
    }

    @Test
    fun openSessionIsCreditedUpToUntilAndCarried() {
        val first = run(listOf(s.resumed("10:00", MAPS)), until = s.ms("10:30"))
        assertEquals(mapOf(app(10, MAPS) to 30 * MIN), first.appMs)
        assertEquals(setOf(ActivityKey(MAPS, "$MAPS.Main")), first.end.resumed)
        val second = run(listOf(s.paused("10:40", MAPS)), from = s.ms("10:30"), until = s.ms("11:00"), start = first.end)
        assertEquals(mapOf(app(10, MAPS) to 10 * MIN), second.appMs)
        assertTrue(second.end.resumed.isEmpty())
    }

    @Test
    fun incrementalRunsEqualOneRun() {
        val from = s.ms("08:00")
        val until = s.ms("12:00")
        val whole = run(morning, from, until)
        val cuts = (morning.map { it.timeMs } + morning.map { it.timeMs + 7 * MIN }).filter { it in from..until }
        for (cut in cuts) {
            val first = run(morning, from, cut)
            val second = run(morning, cut, until, start = first.end)
            assertEquals(whole.appMs, sumLong(first.appMs, second.appMs), "apps, cut at $cut")
            assertEquals(whole.screenMs, sumLong(first.screenMs, second.screenMs), "screen, cut at $cut")
            assertEquals(whole.unlocks, sumInt(first.unlocks, second.unlocks), "unlocks, cut at $cut")
            assertEquals(whole.end, second.end, "end, cut at $cut")
        }
    }

    @Test
    fun pausedWithoutResumedIsIgnored() {
        val a = run(listOf(s.paused("10:00", MAPS), s.resumed("10:10", CHAT), s.paused("10:20", MAPS), s.paused("10:30", CHAT)))
        assertEquals(mapOf(app(10, CHAT) to 20 * MIN), a.appMs)
    }

    @Test
    fun anAppStaysForegroundWhileAnyOfItsActivitiesIsResumed() {
        val a = run(
            listOf(
                s.resumed("10:00", MAPS), s.resumed("10:05", MAPS, "$MAPS.Settings"),
                s.paused("10:10", MAPS), s.paused("10:25", MAPS, "$MAPS.Settings"),
            ),
        )
        assertEquals(mapOf(app(10, MAPS) to 25 * MIN), a.appMs)
        assertEquals(mapOf(slot(10) to 25 * MIN), a.screenMs)
    }

    @Test
    fun shutdownAndStartupResetTheStream() {
        val a = run(listOf(s.resumed("10:00", MAPS), s.shutdown("10:10"), s.startup("10:20")), until = s.ms("11:00"))
        assertEquals(mapOf(app(10, MAPS) to 10 * MIN), a.appMs)
        assertEquals(StreamState(screenOn = true, resumed = emptySet()), a.end)
    }

    @Test
    fun eventsOutsideTheWindowAreIgnoredAndOrderIsRestored() {
        val a = run(
            listOf(s.paused("10:40", MAPS), s.resumed("10:10", MAPS), s.resumed("09:00", CHAT), s.paused("12:00", CHAT)),
            from = s.ms("10:00"),
            until = s.ms("11:00"),
        )
        assertEquals(mapOf(app(10, MAPS) to 30 * MIN), a.appMs)
    }

    @Test
    fun sameInstantsLandOnZoneLocalDates() {
        val events = listOf(
            UsageEvent(Instant.parse("2026-10-07T23:30:00Z").toEpochMilli(), EventType.RESUMED, MAPS, "$MAPS.Main"),
            UsageEvent(Instant.parse("2026-10-07T23:50:00Z").toEpochMilli(), EventType.PAUSED, MAPS, "$MAPS.Main"),
        )
        val from = Instant.parse("2026-10-07T20:00:00Z").toEpochMilli()
        val until = Instant.parse("2026-10-08T02:00:00Z").toEpochMilli()
        assertEquals(mapOf(app(1, MAPS, day.plusDays(1)) to 20 * MIN), run(events, from, until).appMs)
        assertEquals(mapOf(app(23, MAPS) to 20 * MIN), run(events, from, until, z = ZoneId.of("UTC")).appMs)
    }

    @Test
    fun repeatedHourAtDstEndHoldsTwoHours() {
        // 00:00Z = 02:00 CEST and 02:00Z = 03:00 CET: two real hours inside local hour 2 of 2026-10-25.
        val dstEnd = LocalDate.of(2026, 10, 25)
        val events = listOf(
            UsageEvent(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), EventType.RESUMED, MAPS, "$MAPS.Main"),
            UsageEvent(Instant.parse("2026-10-25T02:00:00Z").toEpochMilli(), EventType.PAUSED, MAPS, "$MAPS.Main"),
        )
        val a = run(events, Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), Instant.parse("2026-10-25T03:00:00Z").toEpochMilli())
        assertEquals(mapOf(app(2, MAPS, dstEnd) to 2 * HOUR_MS), a.appMs)
        assertEquals(2 * HOUR_MS, LocalHours.lengthMs(slot(2, dstEnd), zone))
    }

    private fun <K> sumLong(a: Map<K, Long>, b: Map<K, Long>) = (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }
    private fun <K> sumInt(a: Map<K, Int>, b: Map<K, Int>) = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }
}
