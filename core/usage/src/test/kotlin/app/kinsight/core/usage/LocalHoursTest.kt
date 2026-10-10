package app.kinsight.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Timeout

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

    private val lordHowe = ZoneId.of("Australia/Lord_Howe") // +10:30 standard, +11:00 summer: 30-minute DST shift

    /** Sums [LocalHours.split] pieces per local hour (folded, so a non-advancing loop cannot exhaust memory). */
    private fun hours(from: Long, until: Long, zone: ZoneId): Map<Int, Long> {
        val sums = LinkedHashMap<Int, Long>()
        LocalHours.split(from, until, zone) { slot, ms -> sums.merge(slot.hour, ms, Long::plus) }
        return sums
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun halfHourFallBackSplitsOnTheLocalTimeline() {
        // 2026-04-05 02:00 +11:00 (= 15:00Z) becomes 01:30 +10:30, so local 01:30-02:00 happens twice.
        // 14:00Z-15:00Z = 01:00-02:00 (+11), 15:00Z-15:30Z = 01:30-02:00 (+10:30), 15:30Z-16:30Z = 02:00-03:00,
        // 16:30Z-17:00Z = 03:00-03:30.
        val from = Instant.parse("2026-04-04T14:00:00Z").toEpochMilli()
        assertEquals(mapOf(1 to 90 * MIN, 2 to 60 * MIN, 3 to 30 * MIN), hours(from, from + 3 * HOUR_MS, lordHowe))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun halfHourSpringForwardSplitsOnTheLocalTimeline() {
        // 2026-10-04 02:00 +10:30 (= 15:30Z) becomes 02:30 +11:00, so local 02:00-02:30 never happens.
        // 15:00Z-15:30Z = 01:30-02:00, 15:30Z-16:00Z = 02:30-03:00, 16:00Z-17:00Z = 03:00-04:00, 17:00Z-18:00Z = 04:00-05:00.
        val from = Instant.parse("2026-10-03T15:00:00Z").toEpochMilli()
        assertEquals(
            mapOf(1 to 30 * MIN, 2 to 30 * MIN, 3 to 60 * MIN, 4 to 60 * MIN),
            hours(from, from + 3 * HOUR_MS, lordHowe),
        )
        assertEquals(30 * MIN, LocalHours.lengthMs(HourSlot(LocalDate.of(2026, 10, 4), 2), lordHowe))
    }
}
