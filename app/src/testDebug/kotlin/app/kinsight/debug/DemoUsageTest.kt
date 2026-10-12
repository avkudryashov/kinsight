package app.kinsight.debug

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoUsageTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test
    fun fullDaysStayWithinBounds() {
        for (seed in 1L..5L) {
            val days = DemoUsage.generate(today, 60, seed, 23, 59).dropLast(1)
            for (d in days) {
                assertTrue(d.totalMs in DemoUsage.MIN_DAY_MS..DemoUsage.MAX_DAY_MS, "${d.date}: ${d.totalMs}")
                assertTrue(d.unlocks in DemoUsage.MIN_UNLOCKS..DemoUsage.MAX_UNLOCKS, "${d.date}: ${d.unlocks}")
                assertTrue(d.hours.all { it.hour in 7..22 }, "${d.date}: night use")
                assertTrue(d.hours.all { it.totalMs <= 3_600_000L })
            }
        }
    }

    @Test
    fun hoursSumToTheSummary() {
        for (d in DemoUsage.generate(today, 14, 1, 20, 30)) {
            val summary = d.summary("Europe/Madrid")
            val detail = d.detail("Europe/Madrid")
            assertEquals(summary.total_ms, detail.hours.sumOf { h -> h.apps.sumOf { it.foreground_ms } })
            assertEquals(summary.total_ms, summary.apps.sumOf { it.foreground_ms })
            assertEquals(summary.unlocks, detail.hours.sumOf { it.unlocks })
            assertEquals(d.date.toString(), summary.local_date)
        }
    }

    @Test
    fun theSameSeedGivesTheSameHistory() {
        assertEquals(DemoUsage.generate(today, 14, 1, 20, 30), DemoUsage.generate(today, 14, 1, 20, 30))
        assertTrue(DemoUsage.generate(today, 14, 1, 20, 30) != DemoUsage.generate(today, 14, 2, 20, 30))
    }

    @Test
    fun todayStopsAtTheCurrentTime() {
        val days = DemoUsage.generate(today, 3, 1, 15, 20)
        assertEquals(3, days.size)
        assertEquals(today, days.last().date)
        assertTrue(days.last().hours.all { it.hour <= 15 })
        assertTrue(days.last().totalMs <= DemoUsage.day(today, 1).totalMs)
    }

    @Test
    fun theRemainderIsSpreadNotPiledUp() {
        val out = DemoUsage.split(5, DoubleArray(10) { 1.0 + it / 100.0 }, cap = Int.MAX_VALUE)
        assertEquals(5, out.sum())
        assertEquals(1, out.max())
        val capped = DemoUsage.split(100, doubleArrayOf(0.0, 1.0, 1.0, 5.0), cap = 40)
        assertEquals(listOf(0, 30, 30, 40), capped.toList())
    }

    @Test
    fun weekendsGetMore() {
        val days = DemoUsage.generate(today, 140, 1, 23, 59).dropLast(1)
        val (weekend, weekday) = days.partition { it.date.dayOfWeek.value >= 6 }
        assertTrue(weekend.map { it.totalMs }.average() > weekday.map { it.totalMs }.average())
    }
}
