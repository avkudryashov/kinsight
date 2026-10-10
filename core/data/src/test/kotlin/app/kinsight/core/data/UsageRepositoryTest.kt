package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UsageRepositoryTest {
    private val mom = TestDevice()
    private val today = LocalDate.of(2026, 10, 10)
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)

    @AfterTest
    fun cleanUp() = mom.close()

    private suspend fun Flow<DeviceDetail?>.firstWithin(p: (DeviceDetail?) -> Boolean): DeviceDetail =
        withTimeout(10_000) { first(p)!! }

    @Test
    fun detailFlowCarriesTheStoredUsageDetails() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val monday = LocalDate.of(2026, 10, 5)
        fun day(date: LocalDate, hour: Int) = listOf(
            kid.create { it.copy(usage_summary = UsageSummary(local_date = date.toString(), zone_id = "UTC", total_ms = 600_000)) },
            kid.create {
                it.copy(
                    usage_detail = UsageDetail(
                        local_date = date.toString(), zone_id = "UTC",
                        hours = listOf(HourBucket(hour = hour, apps = listOf(AppTotal("com.example.game", 600_000)), unlocks = 2)),
                    ),
                )
            },
        )
        // A detail outside the requested range must not leak in.
        mom.store().applyBatch(day(monday, 9) + day(today, 15) + day(LocalDate.of(2026, 9, 30), 7))
        val usage = UsageRepository(mom.snapshots, mom.prefs, date = { today })

        val dayRange = DetailRange.of(Period.DAY, today, sunFirst)
        val hours = assertNotNull(usage.detail(kidKeys.deviceId, dayRange).firstWithin { it?.hours != null }.hours)
        assertEquals(600_000L, hours[15].totalMs)

        val week = DetailRange.of(Period.WEEK, today, sunFirst) // Sun 4 to Sat 10 Oct
        val map = assertNotNull(usage.detail(kidKeys.deviceId, week, sunFirst).firstWithin { it?.heatmap != null }.heatmap)
        assertEquals(2, map.coveredDays)
        assertEquals(600_000L, map.cells[1][9]) // Monday is the second row, Sunday first
        assertEquals(600_000L, map.cells[6][15])
        assertEquals(0L, map.cells[3].sum())
    }

    @Test
    fun appsFlowListsTheRangesAppsLikeTheDetail() = runBlocking<Unit> {
        // The «All apps» screen reads this lighter flow (final fix F6): no UsageDetail query, no heatmap.
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        fun day(date: LocalDate, vararg apps: Pair<String, Long>) = kid.create {
            it.copy(
                usage_summary = UsageSummary(
                    local_date = date.toString(), zone_id = "UTC", total_ms = apps.sumOf { a -> a.second },
                    apps = apps.map { (p, ms) -> AppTotal(p, ms) },
                ),
            )
        }
        mom.store().applyBatch(
            listOf(day(today, "a" to 60_000L, "b" to 120_000L), day(today.minusDays(1), "a" to 120_000L), day(today.minusDays(9), "c" to 1L)),
        )
        val usage = UsageRepository(mom.snapshots, mom.prefs, date = { today })
        val week = DetailRange.of(Period.WEEK, today, sunFirst)
        val list = withTimeout(10_000) { usage.apps(kidKeys.deviceId, week).first { it != null }!! }
        assertEquals(listOf("a" to 180_000L, "b" to 120_000L), list.apps.map { it.packageName to it.totalMs })
        assertEquals("Kid", list.member.name)
        assertEquals(week, list.range)
        assertEquals(today, list.today)
        assertEquals(usage.detail(kidKeys.deviceId, week, sunFirst).firstWithin { it != null }.apps, list.apps)
    }

    @Test
    fun aCategoryOverrideReEmitsTheDetailAndTheApp() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kidKeys = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        val kid = EntryAuthor(mom.groups.read()!!.groupId, kidKeys, lastSeq = 0)
        val game = "com.example.game" // no catalog entry, not in the built-in table: Other
        mom.store().applyBatch(
            listOf(
                kid.create {
                    it.copy(usage_summary = UsageSummary(local_date = today.toString(), zone_id = "UTC", total_ms = 600_000, apps = listOf(AppTotal(game, 600_000))))
                },
            ),
        )
        val usage = UsageRepository(mom.snapshots, mom.prefs, date = { today })
        val week = DetailRange.of(Period.WEEK, today, sunFirst)
        fun categories(d: DeviceDetail) = d.categories.map { it.category to it.totalMs }

        assertEquals(listOf(AppCategory.OTHER to 600_000L), categories(usage.detail(kidKeys.deviceId, week, sunFirst).firstWithin { it?.categories?.isNotEmpty() == true }))
        val app = withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it != null }!! }
        assertEquals(AppCategory.OTHER, app.category)
        assertEquals(true, app.canChangeCategory) // the viewer is Mom, a parent

        usage.setCategory(game, AppCategory.GAMES)
        usage.detail(kidKeys.deviceId, week, sunFirst).firstWithin { d -> d != null && categories(d) == listOf(AppCategory.GAMES to 600_000L) }
        val changed = withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it?.category == AppCategory.GAMES }!! }
        assertEquals(true, changed.overridden)

        usage.setCategory(game, null) // «По умолчанию»
        usage.detail(kidKeys.deviceId, week, sunFirst).firstWithin { d -> d != null && categories(d) == listOf(AppCategory.OTHER to 600_000L) }
        assertEquals(false, withTimeout(10_000) { usage.app(kidKeys.deviceId, game, week).first { it?.category == AppCategory.OTHER }!! }.overridden)
    }
}
