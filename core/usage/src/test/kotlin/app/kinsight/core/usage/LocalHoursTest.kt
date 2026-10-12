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

    /** Reference: every minute of [from, until) credited to the local hour that minute starts in. */
    private fun minuteByMinute(from: Long, until: Long, zone: ZoneId): Map<HourSlot, Long> {
        val sums = HashMap<HourSlot, Long>()
        var m = from
        while (m < until) {
            val local = Instant.ofEpochMilli(m).atZone(zone)
            sums.merge(HourSlot(local.toLocalDate(), local.hour), MIN, Long::plus)
            m += MIN
        }
        return sums
    }

    private fun splitBySlot(from: Long, until: Long, zone: ZoneId): Map<HourSlot, Long> {
        val sums = HashMap<HourSlot, Long>()
        LocalHours.split(from, until, zone) { slot, ms -> sums.merge(slot, ms, Long::plus) }
        return sums
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun splitMatchesMinuteByMinuteAroundEveryTransition() {
        val zones = listOf("Europe/Madrid", "Australia/Lord_Howe", "Pacific/Chatham", "Antarctica/Troll", "America/New_York")
        val end = Instant.parse("2028-01-01T00:00:00Z")
        var windows = 0
        for (id in zones) {
            val zone = ZoneId.of(id)
            var transition = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
            while (transition != null && transition.instant < end) {
                val at = transition.instant.toEpochMilli()
                for (k in 0..12) {
                    val from = at - k * 15 * MIN
                    val until = from + 4 * HOUR_MS
                    assertEquals(minuteByMinute(from, until, zone), splitBySlot(from, until, zone), "$id from ${Instant.ofEpochMilli(from)}")
                    windows++
                }
                transition = zone.rules.nextTransition(transition.instant)
            }
        }
        assertEquals(true, windows >= 5 * 4 * 13, "windows checked: $windows")
    }

    private val chatham = ZoneId.of("Pacific/Chatham") // +12:45 standard, +13:45 summer, shifts at 02:45/03:45 local

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun chathamSpringForwardSkipsQuarterPastTwoToQuarterToFour() {
        // 2026-09-27 02:45 +12:45 (= 14:00Z) becomes 03:45 +13:45. 13:00Z-13:15Z = 01:45-02:00, 13:15Z-14:00Z = 02:00-02:45,
        // 14:00Z-14:15Z = 03:45-04:00, 14:15Z-15:15Z = 04:00-05:00, 15:15Z-16:00Z = 05:00-05:45.
        val from = Instant.parse("2026-09-26T13:00:00Z").toEpochMilli()
        assertEquals(
            mapOf(1 to 15 * MIN, 2 to 45 * MIN, 3 to 15 * MIN, 4 to 60 * MIN, 5 to 45 * MIN),
            hours(from, from + 3 * HOUR_MS, chatham),
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun chathamFallBackRepeatsQuarterToThreeToQuarterToFour() {
        // 2026-04-05 03:45 +13:45 (= 14:00Z) becomes 02:45 +12:45. 13:00Z-13:15Z = 02:45-03:00, 13:15Z-14:00Z = 03:00-03:45,
        // 14:00Z-14:15Z = 02:45-03:00 again, 14:15Z-15:15Z = 03:00-04:00, 15:15Z-16:00Z = 04:00-04:45.
        val from = Instant.parse("2026-04-04T13:00:00Z").toEpochMilli()
        assertEquals(mapOf(2 to 30 * MIN, 3 to 105 * MIN, 4 to 45 * MIN), hours(from, from + 3 * HOUR_MS, chatham))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun trollTwoHourFallBackRepeatsHoursOneAndTwo() {
        // 2026-10-25 03:00 +02:00 (= 01:00Z) becomes 01:00 +00:00. 00:00Z-01:00Z = 02:00-03:00 (+02),
        // 01:00Z-02:00Z = 01:00-02:00 (+00), 02:00Z-03:00Z = 02:00-03:00 (+00).
        val from = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
        assertEquals(mapOf(1 to 60 * MIN, 2 to 120 * MIN), hours(from, from + 3 * HOUR_MS, ZoneId.of("Antarctica/Troll")))
    }
}
