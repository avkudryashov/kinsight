package app.kinsight.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalHoursTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test
    fun anOrdinaryHourIsOneHourLong() {
        assertEquals(HOUR_MS, LocalHours.lengthMs(HourSlot(LocalDate.of(2026, 10, 7), 10), madrid))
    }

    @Test
    fun theRepeatedHourAtDstEndIsTwoHoursLong() {
        // 2026-10-25: 03:00 CEST becomes 02:00 CET, so local 02:00-03:00 happens twice.
        assertEquals(2 * HOUR_MS, LocalHours.lengthMs(HourSlot(LocalDate.of(2026, 10, 25), 2), madrid))
    }

    @Test
    fun theSkippedHourAtDstStartIsEmpty() {
        // 2026-03-29: 02:00 CET becomes 03:00 CEST.
        assertEquals(0L, LocalHours.lengthMs(HourSlot(LocalDate.of(2026, 3, 29), 2), madrid))
        assertEquals(HOUR_MS, LocalHours.lengthMs(HourSlot(LocalDate.of(2026, 3, 29), 1), madrid))
    }

    @Test
    fun splitCutsAtLocalHourBoundaries() {
        val s = Script(madrid, LocalDate.of(2026, 10, 7))
        val parts = mutableListOf<Pair<Int, Long>>()
        LocalHours.split(s.ms("10:50"), s.ms("12:10"), madrid) { slot, ms -> parts += slot.hour to ms }
        assertEquals(listOf(10 to 10 * MIN, 11 to 60 * MIN, 12 to 10 * MIN), parts)
    }

    @Test
    fun halfHourOffsetsKeepLocalHours() {
        val kolkata = ZoneId.of("Asia/Kolkata") // UTC+05:30
        val from = Instant.parse("2026-10-07T04:15:00Z").toEpochMilli() // 09:45 local
        val parts = mutableListOf<Pair<Int, Long>>()
        LocalHours.split(from, from + 30 * MIN, kolkata) { slot, ms -> parts += slot.hour to ms }
        assertEquals(listOf(9 to 15 * MIN, 10 to 15 * MIN), parts)
        assertEquals(HourSlot(LocalDate.of(2026, 10, 7), 10), LocalHours.slot(from + 30 * MIN, kolkata))
    }
}
