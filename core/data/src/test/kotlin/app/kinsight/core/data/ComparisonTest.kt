package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U2 (audit 2026-10-11): today against «usual by this time», never a part day against whole days. */
class ComparisonTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val madrid = ZoneId.of("Europe/Madrid")
    private val min = 60_000L
    private val game = "com.example.game"
    private val wed = LocalDate.of(2026, 10, 14)

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = madrid) =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun summary(date: LocalDate, minutes: Long, createdMs: Long = ms(date, 23, 59), zone: ZoneId = madrid) = kid.create {
        it.copy(created_at_ms = createdMs, usage_summary = UsageSummary(local_date = date.toString(), zone_id = zone.id, total_ms = minutes * min))
    }

    /** One bucket per (clock hour, minutes of [game]); create it after its day's summary so it is usable. */
    private fun detail(date: LocalDate, vararg cells: Pair<Int, Long>, zone: ZoneId = madrid) = kid.create {
        it.copy(
            usage_detail = UsageDetail(
                local_date = date.toString(), zone_id = zone.id,
                hours = cells.map { (h, m) -> HourBucket(hour = h, apps = listOf(AppTotal(game, m * min))) },
            ),
        )
    }

    private fun compare(today: LocalDate, vararg e: DecodedEntry): TodayComparison? = UsageReadModel.comparison(
        UsageReadModel.summaries(e.toList())[kidKeys.deviceId], UsageReadModel.details(e.toList())[kidKeys.deviceId], today,
    )

    @Test
    fun usualByThisTimeAveragesTheEarlierDaysUpToTheSameClockTime() {
        val c = compare(
            wed,
            summary(wed.minusDays(1), 120), detail(wed.minusDays(1), 10 to 30L, 14 to 40L, 18 to 50L), // by 14:30: 30 + 20
            summary(wed.minusDays(2), 60), detail(wed.minusDays(2), 8 to 60L), //                           by 14:30: 60
            summary(wed.minusDays(3), 90), //                                                                no detail: left out
            summary(wed, 45, createdMs = ms(wed, 14, 30)),
        )
        assertEquals(TodayComparison.ByThisTime(55 * min, 2), c)
    }

    @Test
    fun withoutHourlyDataNothingBeforeEightThenWholeDays() {
        assertNull(compare(wed, summary(wed.minusDays(1), 120), summary(wed.minusDays(2), 60), summary(wed, 45, createdMs = ms(wed, 14, 30))))
        assertNull(compare(wed, summary(wed.minusDays(1), 120), summary(wed, 45, createdMs = ms(wed, 19, 59))))
        assertEquals(
            TodayComparison.FullDay(90 * min),
            compare(wed, summary(wed.minusDays(1), 120), summary(wed.minusDays(2), 60), summary(wed, 200, createdMs = ms(wed, 20, 15))),
        )
    }

    @Test
    fun noSummaryTodayOrNoEarlierDaysIsNoComparison() {
        assertNull(compare(wed, summary(wed.minusDays(1), 120), detail(wed.minusDays(1), 9 to 60L)))
        assertNull(compare(wed, summary(wed, 200, createdMs = ms(wed, 21, 0))))
    }

    @Test
    fun aDetailNotNewerThanItsSummaryIsNotUsed() {
        val y = wed.minusDays(1)
        val old = detail(y, 9 to 60L)
        assertNull(compare(wed, old, summary(y, 60), summary(wed, 30, createdMs = ms(wed, 12, 0))))
    }

    @Test
    fun aFallBackDayCountsItsDoubledHour() {
        // 25 Oct 2026, Europe/Madrid: clock hour 2 lasts two real hours.
        val dst = LocalDate.of(2026, 10, 25)
        val next = dst.plusDays(1)
        val c = compare(next, summary(dst, 180), detail(dst, 2 to 120L, 3 to 60L), summary(next, 10, createdMs = ms(next, 3, 30)))
        assertEquals(TodayComparison.ByThisTime(150 * min, 1), c)
    }

    @Test
    fun onTheFallBackDayItselfTheCutoffIsTheClockTime() {
        val dst = LocalDate.of(2026, 10, 25)
        val y = dst.minusDays(1)
        // 02:30 of the second run of hour 2 (+01:00): the clock reads 02:30, so 2 h 30 min of a normal day count.
        val second = ZonedDateTime.ofLocal(dst.atTime(2, 30), madrid, java.time.ZoneOffset.ofHours(1)).toInstant().toEpochMilli()
        val c = compare(dst, summary(y, 120), detail(y, 0 to 30L, 1 to 30L, 2 to 30L, 3 to 30L), summary(dst, 40, createdMs = second))
        assertEquals(TodayComparison.ByThisTime(75 * min, 1), c)
    }

    @Test
    fun aSkippedSpringForwardHourCountsNothing() {
        // 29 Mar 2026, Europe/Madrid: clock hour 2 does not exist.
        val dst = LocalDate.of(2026, 3, 29)
        val today = compare(dst, summary(dst.minusDays(1), 180), detail(dst.minusDays(1), 1 to 60L, 2 to 60L, 3 to 60L), summary(dst, 20, createdMs = ms(dst, 3, 30)))
        assertEquals(TodayComparison.ByThisTime(150 * min, 1), today, "a normal day before the change")
        val after = dst.plusDays(1)
        val next = compare(after, summary(dst, 120), detail(dst, 1 to 60L, 2 to 60L, 3 to 60L), summary(after, 20, createdMs = ms(after, 3, 30)))
        assertEquals(TodayComparison.ByThisTime(90 * min, 1), next, "the skipped hour of the day before holds nothing")
    }

    @Test
    fun theCutoffIsTheChildsOwnClock() {
        // The child is in New York; its 09:00 is 15:00 in Madrid. Only its hour 8 counts, not its hour 14.
        val ny = ZoneId.of("America/New_York")
        val c = compare(
            wed,
            summary(wed.minusDays(1), 120, zone = ny), detail(wed.minusDays(1), 8 to 60L, 14 to 60L, zone = ny),
            summary(wed, 70, createdMs = ms(wed, 9, 0, ny), zone = ny),
        )
        assertEquals(TodayComparison.ByThisTime(60 * min, 1), c)
    }

    @Test
    fun aSummaryWrittenAfterItsDayComparesWholeDays() {
        val y = wed.minusDays(1)
        val c = compare(wed, summary(y, 60), detail(y, 10 to 30L, 22 to 30L), summary(wed, 100, createdMs = ms(wed.plusDays(1), 0, 10)))
        assertEquals(TodayComparison.ByThisTime(60 * min, 1), c)
    }

    @Test
    fun usedByIsZeroAtMidnightAndTheWholeDayAtItsEnd() {
        val d = UsageDetail(local_date = wed.toString(), zone_id = madrid.id, hours = listOf(HourBucket(hour = 5, apps = listOf(AppTotal(game, 40 * min)))))
        assertEquals(0L, UsageReadModel.usedBy(d, 0))
        assertEquals(20 * min, UsageReadModel.usedBy(d, 5 * 60 + 30))
        assertEquals(40 * min, UsageReadModel.usedBy(d, 24 * 60))
    }

    @Test
    fun theCardCarriesTheComparisonOfAChildOnly() {
        val entries = listOf(summary(wed.minusDays(1), 60), detail(wed.minusDays(1), 9 to 60L), summary(wed, 30, createdMs = ms(wed, 12, 0)))
        val sums = UsageReadModel.summaries(entries)[kidKeys.deviceId]
        val dets = UsageReadModel.details(entries)[kidKeys.deviceId]
        val now = ms(wed, 12, 5)
        assertTrue(UsageReadModel.usage(member, sums, emptyList(), wed, nowMs = now, details = dets).comparison is TodayComparison.ByThisTime)
        assertNull(UsageReadModel.usage(member.copy(role = Role.PARENT), sums, emptyList(), wed, nowMs = now, details = dets).comparison)
    }

    @Test
    fun staleMeansOlderThanTwoHours() {
        val h = 3_600_000L
        assertTrue(UsageReadModel.isStale(0, 2 * h + 1))
        assertEquals(false, UsageReadModel.isStale(0, 2 * h))
    }
}
