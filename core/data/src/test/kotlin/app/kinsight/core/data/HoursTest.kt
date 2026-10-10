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

/** Level 4 of spec §9.3 (stage 5a): hours of a day, weekday × hour heatmap of a week or month. */
class HoursTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val today = LocalDate.of(2026, 10, 10)
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val min = 60_000L
    private val monFirst = WeekFields.of(DayOfWeek.MONDAY, 4)
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)
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

    private fun detailOf(range: DetailRange, weekFields: WeekFields, vararg e: DecodedEntry): DeviceDetail {
        val (sums, dets) = split(e.toList())
        return UsageReadModel.detail(member, sums, catalog, emptyList(), range, today, details = dets, weekFields = weekFields)
    }

    @Test
    fun dayWithDetailHas24HoursSummingToTheDetail() {
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val d = detailOf(
            day, monFirst,
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
        assertNull(d.heatmap)
    }

    @Test
    fun detailNotNewerThanTheSummaryIsNotUsed() {
        val day = DetailRange.of(Period.DAY, today, monFirst)
        val stale = detail(today, Triple(9, game, 20L))
        assertNull(detailOf(day, monFirst, stale, summary(today, 50)).hours)
        // Without a summary the day is not known: no hours either.
        assertNull(detailOf(day, monFirst, detail(today, Triple(9, game, 20L))).hours)
        // A day without any detail.
        assertNull(detailOf(day, monFirst, summary(today, 50)).hours)
    }

    @Test
    fun detailOfPicksTheNewestDetailAboveTheSummarySeq() {
        val (sums, dets) = split(listOf(detail(today, Triple(1, game, 5L)), summary(today, 50), detail(today, Triple(2, game, 7L))))
        assertEquals(2, UsageReadModel.detailOf(sums, dets, today)!!.hours.single().hour)
        assertNull(UsageReadModel.detailOf(sums, dets, today.minusDays(1)))
    }

    @Test
    fun weekWithThreeCoveredDaysAveragesOverCoveredDaysInLocaleOrder() {
        // Mon 28 Sep to Sun 4 Oct 2026 (Monday-first): covered Mon 28, Wed 30, Sun 4; Tue 29 has a summary only.
        val week = DetailRange.of(Period.WEEK, LocalDate.of(2026, 9, 30), monFirst)
        val mon = LocalDate.of(2026, 9, 28)
        val wed = LocalDate.of(2026, 9, 30)
        val sun = LocalDate.of(2026, 10, 4)
        val entries = arrayOf(
            summary(mon, 60), detail(mon, Triple(8, game, 60L), unlocks = mapOf(8 to 4)),
            summary(mon.plusDays(1), 30),
            summary(wed, 30), detail(wed, Triple(8, chat, 30L), unlocks = mapOf(8 to 2)),
            summary(sun, 45), detail(sun, Triple(20, game, 45L), unlocks = mapOf(20 to 3)),
        )
        val map = assertNotNull(detailOf(week, monFirst, *entries).heatmap)
        assertEquals(3, map.coveredDays)
        assertEquals(7, map.rangeDays)
        assertEquals(7, map.cells.size)
        assertEquals(24, map.cells[0].size)
        assertEquals(60 * min, map.cells[0][8]) // Monday row first
        assertEquals(0L, map.cells[1][8]) // Tuesday: no hourly data
        assertEquals(30 * min, map.cells[2][8])
        assertEquals(45 * min, map.cells[6][20]) // Sunday last
        assertEquals(2, map.unlocks[8]) // (4 + 2) / 3 covered days
        assertEquals(1, map.unlocks[20])
        assertNull(detailOf(week, monFirst, *entries).hours)

        val sunWeek = DetailRange.of(Period.WEEK, wed, sunFirst) // Sun 27 Sep to Sat 3 Oct
        val sunMap = assertNotNull(detailOf(sunWeek, sunFirst, *entries).heatmap)
        assertEquals(2, sunMap.coveredDays)
        assertEquals(60 * min, sunMap.cells[1][8]) // Monday is the second row when Sunday comes first
        assertEquals(30 * min, sunMap.cells[3][8])
    }

    @Test
    fun monthAveragesEachWeekdayOverItsOwnCoveredDays() {
        val september = DetailRange.of(Period.MONTH, LocalDate.of(2026, 9, 15), monFirst)
        val mon1 = LocalDate.of(2026, 9, 7)
        val mon2 = LocalDate.of(2026, 9, 14)
        val map = assertNotNull(
            detailOf(
                september, monFirst,
                summary(mon1, 60), detail(mon1, Triple(9, game, 60L)),
                summary(mon2, 20), detail(mon2, Triple(9, game, 20L)),
            ).heatmap,
        )
        assertEquals(40 * min, map.cells[0][9])
        assertEquals(2, map.coveredDays)
        assertEquals(30, map.rangeDays)
        assertNull(detailOf(september, monFirst, summary(mon1, 60)).heatmap)
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
            UsageReadModel.detail(member, sums, catalog, emptyList(), day, today = dst, details = dets, weekFields = monFirst).hours,
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
