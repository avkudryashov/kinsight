package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import okio.ByteString.Companion.toByteString
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageReadModelTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val today = LocalDate.of(2026, 10, 10)
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val min = 60_000L

    private fun summary(date: LocalDate, minutes: Long, unlocks: Int = 0, vararg apps: Pair<String, Long>) = kid.create {
        it.copy(
            usage_summary = UsageSummary(
                local_date = date.toString(), zone_id = "Europe/Madrid", total_ms = minutes * min,
                apps = apps.map { (p, m) -> AppTotal(p, m * min) }, unlocks = unlocks,
            ),
        )
    }

    private fun event(kind: StateEvent.Kind) = kid.create { it.copy(state_event = StateEvent(kind = kind)) }
    private fun byDate(vararg e: DecodedEntry) = UsageReadModel.summaries(e.toList())[kidKeys.deviceId]

    @Test
    fun missingDaysAreZeroNotAbsent() {
        val week = UsageReadModel.days(byDate(summary(today, 30), summary(today.minusDays(2), 60)), today, 7)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(6), week.first().date)
        assertEquals(listOf(0L, 0, 0, 0, 60, 0, 30).map { it * min }, week.map { it.totalMs })
    }

    @Test
    fun averageUsesOnlyPriorDaysWithData() {
        val by = byDate(summary(today, 999), summary(today.minusDays(1), 120), summary(today.minusDays(2), 60))
        assertEquals(90 * min, UsageReadModel.average(by, today))
        assertNull(UsageReadModel.average(byDate(summary(today, 10)), today))
    }

    @Test
    fun latestVersionOfADayWins() {
        val by = byDate(summary(today, 10), summary(today, 20))
        assertEquals(20 * min, UsageReadModel.days(by, today, 1).single().totalMs)
    }

    @Test
    fun appsAreSummedOverThePeriodWithCatalogLabels() {
        val maps = "com.example.maps"
        val video = "com.example.video"
        val icon = byteArrayOf(1, 2, 3)
        val entries = listOf(
            summary(today, 30, 0, maps to 30L),
            summary(today.minusDays(1), 60, 0, maps to 10L, video to 50L),
            kid.create { it.copy(app_catalog = AppCatalog(package_name = maps, label = "Maps", icon_webp = icon.toByteString())) },
        )
        val by = UsageReadModel.summaries(entries)[kidKeys.deviceId]
        val catalog = UsageReadModel.catalogOf(entries, kidKeys.deviceId)
        val week = UsageReadModel.apps(by, (0L..6).map { today.minusDays(it) }, catalog)
        assertEquals(listOf(video to 50 * min, maps to 40 * min), week.map { it.packageName to it.totalMs })
        assertEquals(listOf(video, "Maps"), week.map { it.label })
        assertContentEquals(icon, week[1].icon)
        assertEquals(listOf(maps), UsageReadModel.apps(by, listOf(today), catalog).map { it.packageName })
    }

    @Test
    fun sharingFollowsTheLastSignedEvent() {
        assertEquals(Sharing.Never, UsageReadModel.sharing(emptyList(), departed = false))
        val resumed = event(StateEvent.Kind.SHARING_RESUMED)
        val paused = event(StateEvent.Kind.SHARING_PAUSED)
        val events = UsageReadModel.events(listOf(resumed))
        assertEquals(Sharing.On, UsageReadModel.sharing(events, departed = false))
        val both = UsageReadModel.events(listOf(resumed, paused))
        assertEquals(Sharing.Paused(paused.body.created_at_ms), UsageReadModel.sharing(both, departed = false))
        assertEquals(Sharing.Left, UsageReadModel.sharing(UsageReadModel.events(listOf(event(StateEvent.Kind.LEFT_GROUP))), departed = false))
        assertEquals(Sharing.Left, UsageReadModel.sharing(events, departed = true))
    }

    @Test
    fun usageAndDetailTotalThePeriod() {
        val entries = listOf(summary(today, 30, 4), summary(today.minusDays(3), 60, 6), summary(today.minusDays(20), 100, 1))
        val by = UsageReadModel.summaries(entries)[kidKeys.deviceId]
        val events = UsageReadModel.events((1..25).map { event(StateEvent.Kind.CLOCK_CHANGED) })
        val usage = UsageReadModel.usage(member, by, events, today)
        assertEquals(30 * min, usage.today.totalMs)
        assertEquals(60 * min, usage.averageMs)
        val week = UsageReadModel.detail(member, by, emptyMap(), events, today, Period.WEEK)
        assertEquals(90 * min, week.totalMs)
        assertEquals(10, week.unlocks)
        assertEquals(UsageReadModel.MAX_EVENTS, week.events.size)
        assertEquals(190 * min, UsageReadModel.detail(member, by, emptyMap(), events, today, Period.MONTH).totalMs)
    }
}
