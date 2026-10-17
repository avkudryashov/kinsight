package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Level 4 of spec §9.3 (stage 5a): hours of a day. */
class HoursTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val today = LocalDate.of(2026, 10, 10)
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val min = 60_000L
    private val monFirst = WeekFields.of(DayOfWeek.MONDAY, 4)
    private val game = "com.example.game"
    private val chat = "com.example.chat"
    private val catalog = mapOf(
        game to AppCatalog(package_name = game, category = 0),
        chat to AppCatalog(package_name = chat, category = 4),
    )

    private fun summary(date: LocalDate, minutes: Long) = kid.create {
        it.copy(usage_summary = UsageSummary(local_date = date.toString(), zone_id = "Europe/Madrid", total_ms = minutes * min))
    }

    /** One bucket per hour of the triples (hour, package, minutes); [unlocks] per hour. */
    private fun detail(date: LocalDate, vararg cells: Triple<Int, String, Long>, unlocks: Map<Int, Int> = emptyMap()) =
        detail(date, cells.groupBy { it.first }.map { (h, c) -> HourBucket(hour = h, apps = c.map { AppTotal(it.second, it.third * min) }, unlocks = unlocks[h] ?: 0) })

    private fun detail(date: LocalDate, buckets: List<HourBucket>) = kid.create {
        it.copy(usage_detail = UsageDetail(local_date = date.toString(), zone_id = "Europe/Madrid", hours = buckets))
    }

    private fun split(entries: List<DecodedEntry>) =
        UsageReadModel.summaries(entries)[kidKeys.deviceId] to UsageReadModel.details(entries)[kidKeys.deviceId]

    private fun detailOf(range: DetailRange, vararg e: DecodedEntry): DeviceDetail {
        val (sums, dets) = split(e.toList())
        return UsageReadModel.detail(member, sums, catalog, emptyList(), range, today, details = dets)
    }

    @Test
    fun dayWithDetailHas24HoursSummingToTheDetail() {
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val d = detailOf(
            day,
            summary(today, 50),
            detail(today, Triple(9, game, 20L), Triple(9, chat, 10L), Triple(21, chat, 20L), unlocks = mapOf(9 to 3, 21 to 1)),
        )
        val hours = assertNotNull(d.hours)
        assertEquals((0..23).toList(), hours.map { it.hour })
        assertEquals(50 * min, hours.sumOf { it.totalMs })
        assertEquals(30 * min, hours[9].totalMs)
        assertEquals(mapOf(AppCategory.GAMES to 20 * min, AppCategory.SOCIAL to 10 * min), hours[9].byCategory)
        assertEquals(3, hours[9].unlocks)
        assertEquals(0L, hours[10].totalMs)
        assertEquals(emptyMap(), hours[10].byCategory)
    }

    @Test
    fun theSameInputsGiveAnEqualDetailWithHours() {
        // Value equality (final fix F7): HourTotal and AppUsage compare by content.
        val entries = arrayOf(summary(today, 50), detail(today, Triple(9, game, 20L), Triple(21, chat, 20L), unlocks = mapOf(9 to 3)))
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val week = DetailRange.of(Period.WEEK, today, monFirst)
        assertEquals(detailOf(day, *entries), detailOf(day, *entries))
        assertEquals(detailOf(week, *entries), detailOf(week, *entries))
        assertEquals(detailOf(week, *entries).hashCode(), detailOf(week, *entries).hashCode())
    }

    @Test
    fun detailNotNewerThanTheSummaryIsNotUsed() {
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val stale = detail(today, Triple(9, game, 20L))
        assertNull(detailOf(day, stale, summary(today, 50)).hours)
        // Without a summary the day is not known: no hours either.
        assertNull(detailOf(day, detail(today, Triple(9, game, 20L))).hours)
        // A day without any detail.
        assertNull(detailOf(day, summary(today, 50)).hours)
    }

    @Test
    fun detailOfPicksTheNewestDetailAboveTheSummarySeq() {
        val (sums, dets) = split(listOf(detail(today, Triple(1, game, 5L)), summary(today, 50), detail(today, Triple(2, game, 7L))))
        assertEquals(2, UsageReadModel.detailOf(sums, dets, today)!!.hours.single().hour)
        assertNull(UsageReadModel.detailOf(sums, dets, today.minusDays(1)))
    }



    @Test
    fun fallBackHourInOneBucketHoldsUpToTwoHours() {
        // The publisher writes one bucket per clock hour; on 25 Oct 2026 (Europe/Madrid) hour 2 lasts two real hours.
        val dst = LocalDate.of(2026, 10, 25)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val bucket = HourBucket(hour = 2, apps = listOf(AppTotal(game, 70 * min), AppTotal(chat, 50 * min)))
        val (sums, dets) = split(listOf(summary(dst, 120), detail(dst, listOf(bucket))))
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(120 * min, hours[2].totalMs)
        assertEquals(mapOf(AppCategory.GAMES to 70 * min, AppCategory.SOCIAL to 50 * min), hours[2].byCategory)
    }

    @Test
    fun hostileBucketsOfOneHourStayWithinTwoHours() {
        val dst = LocalDate.of(2026, 10, 25)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val buckets = List(3) { HourBucket(hour = 2, apps = listOf(AppTotal(game, 100 * min))) }
        val (sums, dets) = split(listOf(summary(dst, 120), detail(dst, buckets)))
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(UsageReadModel.MAX_MS_PER_HOUR_SLOT, hours[2].totalMs)
    }

    @Test
    fun overlappingAppsOfANormalHourFillAtMostThatHour() {
        // Phones, 0.5.0: navigation and a messenger both in the foreground (split screen, picture in picture) made 12 PM
        // read 1 h 30 min. The slot is cut to the hour's real length, each category scaled by the same factor.
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val d = detailOf(day, summary(today, 90), detail(today, Triple(12, game, 50L), Triple(12, chat, 40L), Triple(13, game, 30L)))
        val hours = assertNotNull(d.hours)
        assertEquals(60 * min, hours[12].totalMs)
        assertEquals(mapOf(AppCategory.GAMES to 2_000_000L, AppCategory.SOCIAL to 1_600_000L), hours[12].byCategory)
        assertEquals(30 * min, hours[13].totalMs)
    }

    @Test
    fun fallBackHourIsCutAtItsTwoRealHours() {
        val dst = LocalDate.of(2026, 10, 25)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val bucket = HourBucket(hour = 2, apps = listOf(AppTotal(game, 100 * min), AppTotal(chat, 50 * min)))
        val (sums, dets) = split(listOf(summary(dst, 150), detail(dst, listOf(bucket, HourBucket(hour = 3, apps = listOf(AppTotal(game, 90 * min)))))))
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(120 * min, hours[2].totalMs)
        assertEquals(mapOf(AppCategory.GAMES to 80 * min, AppCategory.SOCIAL to 40 * min), hours[2].byCategory)
        assertEquals(60 * min, hours[3].totalMs) // the hour after the repeated one is an ordinary hour
    }

    @Test
    fun aSkippedSpringForwardHourHoldsNothingWhateverItClaims() {
        val dst = LocalDate.of(2026, 3, 29)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val (sums, dets) = split(listOf(summary(dst, 40), detail(dst, Triple(1, game, 20L), Triple(2, game, 20L), unlocks = mapOf(2 to 1))))
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(0L, hours[2].totalMs)
        assertEquals(emptyMap(), hours[2].byCategory)
        assertEquals(0, hours[2].unlocks)
        assertEquals(20 * min, hours[1].totalMs)
    }


    @Test
    fun anUnknownZoneKeepsTheTwoHourCeiling() {
        // Untrusted peer: without a usable zone the hour's length is unknown, so the old ceiling of two hours holds.
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val sum = summary(today, 120) // the detail must be newer than the summary to be used
        val odd = kid.create {
            it.copy(usage_detail = UsageDetail(local_date = today.toString(), zone_id = "Mars/Olympus", hours = listOf(HourBucket(hour = 9, apps = listOf(AppTotal(game, 300 * min))))))
        }
        val hours = assertNotNull(detailOf(day, sum, odd).hours)
        assertEquals(UsageReadModel.MAX_MS_PER_HOUR_SLOT, hours[9].totalMs)
    }

    @Test
    fun springForwardDayHasAnEmptySkippedHour() {
        // 29 Mar 2026, Europe/Madrid: clock hour 2 does not exist; the child writes no bucket for it.
        val dst = LocalDate.of(2026, 3, 29)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val (sums, dets) = split(listOf(summary(dst, 40), detail(dst, Triple(1, game, 20L), Triple(3, game, 20L))))
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(24, hours.size)
        assertEquals(0L, hours[2].totalMs)
        assertEquals(40 * min, hours.sumOf { it.totalMs })
    }

    @Test
    fun detailsSkipsAMalformedDate() {
        val bad = kid.create { it.copy(usage_detail = UsageDetail(local_date = "2026-10-01x", zone_id = "Europe/Madrid")) }
        val good = detail(today, Triple(1, game, 5L))
        assertEquals(setOf(today), UsageReadModel.details(listOf(bad, good))[kidKeys.deviceId]!!.keys)
    }

    @Test
    fun fallBackDaySumsTheDoubledHourAndKeeps24Slots() {
        // 25 Oct 2026, Europe/Madrid: 02:00-03:00 happens twice; the child writes two buckets for clock hour 2.
        val dst = LocalDate.of(2026, 10, 25)
        val day = DetailRange.of(Period.DAY, dst, monFirst)
        val (sums, dets) = split(
            listOf(
                summary(dst, 100),
                detail(
                    dst,
                    listOf(
                        HourBucket(hour = 2, apps = listOf(AppTotal(game, 50 * min)), unlocks = 1),
                        HourBucket(hour = 2, apps = listOf(AppTotal(chat, 40 * min)), unlocks = 1),
                        HourBucket(hour = 3, apps = listOf(AppTotal(game, 10 * min))),
                    ),
                ),
            ),
        )
        val hours = assertNotNull(
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets).hours,
        )
        assertEquals(24, hours.size)
        assertEquals(90 * min, hours[2].totalMs)
        assertEquals(10 * min, hours[3].totalMs)
        assertEquals(2, hours[2].unlocks)
        assertEquals(100 * min, hours.sumOf { it.totalMs })
    }

    @Test
    fun parentUsageAndHoursOutOfRangeAreNotShown() {
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val (sums, dets) = split(listOf(summary(today, 10), detail(today, Triple(24, game, 10L), Triple(5, game, 10L))))
        val hours = assertNotNull(UsageReadModel.detail(member, sums, catalog, emptyList(), day, today, details = dets).hours)
        assertEquals(10 * min, hours.sumOf { it.totalMs })
        val parent = member.copy(role = Role.PARENT)
        assertNull(UsageReadModel.detail(parent, sums, catalog, emptyList(), day, today, details = dets).hours)
    }
}
