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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class UsageReadModelTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val today = LocalDate.of(2026, 10, 10)
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val min = 60_000L
    private val monFirst = WeekFields.of(DayOfWeek.MONDAY, 4)

    /** The [p] containing [anchor], Monday-first: today's WEEK is Mon 5 to Sun 11 Oct 2026, its MONTH is October. */
    private fun range(p: Period, anchor: LocalDate = today) = DetailRange.of(p, anchor, monFirst)

    private fun summary(date: LocalDate, minutes: Long, unlocks: Int = 0, vararg apps: Pair<String, Long>) = kid.create {
        it.copy(
            usage_summary = UsageSummary(
                local_date = date.toString(), zone_id = "Europe/Madrid", total_ms = minutes * min,
                apps = apps.map { (p, m) -> AppTotal(p, m * min) }, unlocks = unlocks,
            ),
        )
    }

    private val noonToday = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun event(kind: StateEvent.Kind, createdMs: Long = noonToday, fromMs: Long = 0, toMs: Long = 0) =
        kid.create { it.copy(created_at_ms = createdMs, state_event = StateEvent(kind = kind, from_ms = fromMs, to_ms = toMs)) }

    private fun utc(date: LocalDate, hour: Int = 12) = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
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
    fun onlyTheFirstSharingEventOfAnAuthorReadsAsStarted() {
        // Emulator run 2026-10-10: the consent's SHARING_RESUMED read «Sharing resumed» although sharing had just begun.
        val first = event(StateEvent.Kind.SHARING_RESUMED)
        val paused = event(StateEvent.Kind.SHARING_PAUSED)
        val again = event(StateEvent.Kind.SHARING_RESUMED)
        val events = UsageReadModel.events(listOf(again, paused, first)).associateBy { it.seq }
        assertTrue(events.getValue(first.seq).firstSharing)
        assertFalse(events.getValue(again.seq).firstSharing)
        assertFalse(events.getValue(paused.seq).firstSharing)
        // A device that paused first never «started» with a resume.
        val pausedFirst = UsageReadModel.events(listOf(event(StateEvent.Kind.SHARING_PAUSED), event(StateEvent.Kind.SHARING_RESUMED)))
        assertTrue(pausedFirst.none { it.firstSharing })
    }

    @Test
    fun anIntervalEventIsShownAtItsStartLikeItsAlert() {
        // «sync was stopped, When: 7:34 PM» in the alert, «Oct 10, 7:38 PM» (signing time) in the list: one time now.
        val stopped = UsageReadModel.events(listOf(event(StateEvent.Kind.SYNC_STOPPED, createdMs = 9_000, fromMs = 5_000, toMs = 8_000))).single()
        assertEquals(5_000, stopped.atMs)
        val paused = UsageReadModel.events(listOf(event(StateEvent.Kind.SHARING_PAUSED, createdMs = 9_000))).single()
        assertEquals(9_000, paused.atMs)
    }

    @Test
    fun usageAndDetailTotalThePeriod() {
        val entries = listOf(summary(today, 30, 4), summary(today.minusDays(3), 60, 6), summary(today.minusDays(20), 100, 1))
        val by = UsageReadModel.summaries(entries)[kidKeys.deviceId]
        val events = UsageReadModel.events((1..25).map { event(StateEvent.Kind.CLOCK_CHANGED) })
        val usage = UsageReadModel.usage(member, by, events, today)
        assertEquals(30 * min, usage.today.totalMs)
        assertEquals(60 * min, usage.averageMs)
        val week = UsageReadModel.detail(member, by, emptyMap(), events, range(Period.WEEK), today)
        assertEquals(90 * min, week.totalMs)
        assertEquals(10, week.unlocks)
        assertEquals(25, week.events.size)
        // MONTH is the calendar month: October holds today and 7 Oct, 20 Sep belongs to September.
        val october = range(Period.MONTH)
        assertEquals(90 * min, UsageReadModel.detail(member, by, emptyMap(), events, october, today).totalMs)
        assertEquals(100 * min, UsageReadModel.detail(member, by, emptyMap(), events, october.previous(), today).totalMs)
    }

    private val day = 86_400_000L
    private val nowMs = 1_760_000_000_000L

    private fun raw(date: LocalDate, totalMs: Long, unlocks: Int, apps: List<AppTotal> = emptyList(), createdMs: Long = nowMs) =
        kid.create {
            it.copy(
                created_at_ms = createdMs,
                usage_summary = UsageSummary(local_date = date.toString(), zone_id = "Europe/Madrid", total_ms = totalMs, apps = apps, unlocks = unlocks),
            )
        }

    @Test
    fun peerNumbersAreClamped() {
        val pkg = "com.example.x"
        // Every day from 1 Sep to today; September is a whole 30-day month in the past.
        val entries = (0L..39).map { raw(today.minusDays(it), Long.MAX_VALUE, Int.MAX_VALUE, listOf(AppTotal(pkg, Long.MAX_VALUE)), Long.MAX_VALUE) } +
            raw(today.minusDays(40), -5, -7)
        val by = UsageReadModel.summaries(entries)[kidKeys.deviceId]
        val month = UsageReadModel.detail(member, by, emptyMap(), emptyList(), range(Period.MONTH).previous(), today)
        assertEquals(30 * day, month.totalMs)
        assertEquals(30 * UsageReadModel.MAX_UNLOCKS_PER_DAY, month.unlocks)
        assertEquals(30 * day, month.apps.single().totalMs)
        assertEquals(day, UsageReadModel.average(by, today))
        val usage = UsageReadModel.usage(member, by, emptyList(), today, nowMs = nowMs)
        assertEquals(nowMs + 5 * min, usage.updatedMs)
        val negative = UsageReadModel.summaries(listOf(raw(today, -5, -7, listOf(AppTotal(pkg, -1)))))[kidKeys.deviceId]
        val d = UsageReadModel.days(negative, today, 1).single()
        assertEquals(0L, d.totalMs)
        assertEquals(0, d.unlocks)
        assertTrue(UsageReadModel.apps(negative, listOf(today), emptyMap()).isEmpty())
    }

    @Test
    fun dayTotalTellsNoDataFromZero() {
        val by = byDate(raw(today, 0, 0))
        val two = UsageReadModel.days(by, today, 2)
        assertFalse(two[0].hasData)
        assertTrue(two[1].hasData)
        assertEquals(0L, two[1].totalMs)
    }

    @Test
    fun usageAndDetailUseOnlyTheMembersOwnEvents() {
        val other = EntryAuthor(groupId, DeviceKeys.generate(), lastSeq = 0)
        val mine = event(StateEvent.Kind.SHARING_RESUMED)
        val theirs = listOf(
            other.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) },
            other.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.CLOCK_CHANGED)) },
        )
        val events = UsageReadModel.events(theirs + mine)
        assertEquals(Sharing.On, UsageReadModel.usage(member, null, events, today).sharing)
        val detail = UsageReadModel.detail(member, null, emptyMap(), events, range(Period.DAY), today)
        assertEquals(Sharing.On, detail.sharing)
        assertEquals(listOf(kidKeys.deviceId), detail.events.map { it.author })
    }

    @Test
    fun aParentsUsageIsNeverSurfaced() {
        val parentKeys = DeviceKeys.generate()
        val parent = EntryAuthor(groupId, parentKeys, lastSeq = 0)
        val entry = parent.create {
            it.copy(usage_summary = UsageSummary(local_date = today.toString(), zone_id = "Europe/Madrid", total_ms = 30 * min, apps = listOf(AppTotal("p", 30 * min)), unlocks = 3))
        }
        val by = UsageReadModel.summaries(listOf(entry))[parentKeys.deviceId]
        val view = MemberView(parentKeys.deviceId, "Mom", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = null)
        val usage = UsageReadModel.usage(view, by, emptyList(), today)
        assertEquals(0L, usage.today.totalMs)
        assertFalse(usage.today.hasData)
        assertNull(usage.averageMs)
        assertNull(usage.updatedMs)
        val detail = UsageReadModel.detail(view, by, emptyMap(), emptyList(), range(Period.WEEK), today)
        assertEquals(0L, detail.totalMs)
        assertEquals(0, detail.unlocks)
        assertTrue(detail.apps.isEmpty())
    }

    @Test
    fun manyGapsDoNotPushAnIntegrityEventOutOfTheWeek() {
        val hidden = event(StateEvent.Kind.NOTIFICATION_HIDDEN, createdMs = utc(today.minusDays(5)), fromMs = utc(today.minusDays(5)))
        val gaps = (1..30).map { event(StateEvent.Kind.DATA_GAP, createdMs = utc(today, 10), fromMs = utc(today, 9), toMs = utc(today, 10)) }
        val events = UsageReadModel.events(listOf(hidden) + gaps)
        val week = UsageReadModel.detail(member, null, emptyMap(), events, range(Period.WEEK), today, zone = ZoneOffset.UTC)
        assertEquals(31, week.events.size)
        assertEquals(StateEvent.Kind.NOTIFICATION_HIDDEN, week.events.last().kind)
        assertEquals(week.events.map { it.seq }.sortedDescending(), week.events.map { it.seq })
    }

    @Test
    fun eventsAreScopedToThePeriodWindow() {
        val threeDaysAgo = event(StateEvent.Kind.BLUETOOTH_OFF, createdMs = utc(today.minusDays(3)), fromMs = utc(today.minusDays(3), 8), toMs = utc(today.minusDays(3), 9))
        val tenDaysAgo = event(StateEvent.Kind.CLOCK_CHANGED, createdMs = utc(today.minusDays(10)))
        val fortyDaysAgo = event(StateEvent.Kind.LEFT_GROUP, createdMs = utc(today.minusDays(40)))
        // Stopped two days ago and reported on restart this morning: it reaches into today.
        val stopped = event(StateEvent.Kind.SYNC_STOPPED, createdMs = utc(today, 8), fromMs = utc(today.minusDays(2)), toMs = utc(today, 8))
        val events = UsageReadModel.events(listOf(threeDaysAgo, tenDaysAgo, fortyDaysAgo, stopped))
        fun kinds(r: DetailRange) = UsageReadModel.detail(member, null, emptyMap(), events, r, today, zone = ZoneOffset.UTC).events.map { it.kind }.toSet()
        assertEquals(setOf(StateEvent.Kind.SYNC_STOPPED), kinds(range(Period.DAY)))
        assertEquals(setOf(StateEvent.Kind.SYNC_STOPPED, StateEvent.Kind.BLUETOOTH_OFF), kinds(range(Period.WEEK)))
        // Calendar month: 30 Sep (ten days ago) is in September, not in October.
        assertEquals(setOf(StateEvent.Kind.SYNC_STOPPED, StateEvent.Kind.BLUETOOTH_OFF), kinds(range(Period.MONTH)))
        assertEquals(setOf(StateEvent.Kind.CLOCK_CHANGED), kinds(range(Period.MONTH).previous()))
        // A past day sees the interval that reached into it, a day before it does not.
        assertEquals(setOf(StateEvent.Kind.SYNC_STOPPED), kinds(range(Period.DAY, today.minusDays(1))))
        assertEquals(setOf(StateEvent.Kind.BLUETOOTH_OFF), kinds(range(Period.DAY, today.minusDays(3))))
        // Sharing still reads every own event, not only the period's.
        assertEquals(Sharing.Left, UsageReadModel.detail(member, null, emptyMap(), events, range(Period.DAY), today, zone = ZoneOffset.UTC).sharing)
    }

    @Test
    fun detailCoversTheWholeRangeWithFutureDaysEmptyAndUnknown() {
        // Today is Saturday 10 Oct; the Monday-first week runs to Sunday 11 Oct. A clock-ahead child already wrote 11 Oct.
        val by = byDate(summary(today, 30, 3, "a" to 30L), summary(today.minusDays(1), 0), summary(today.plusDays(1), 500, 9, "a" to 500L))
        val week = UsageReadModel.detail(member, by, emptyMap(), emptyList(), range(Period.WEEK), today)
        assertEquals(range(Period.WEEK), week.range)
        assertEquals(Period.WEEK, week.period)
        assertEquals((5..11).map { LocalDate.of(2026, 10, it) }, week.days.map { it.date })
        val sunday = week.days.last()
        assertFalse(sunday.known)
        assertFalse(sunday.hasData)
        assertEquals(0L, sunday.totalMs)
        assertEquals(0, sunday.unlocks)
        assertEquals(30 * min, week.totalMs)
        assertEquals(3, week.unlocks)
        assertEquals(listOf(30 * min), week.apps.map { it.totalMs })
        val month = UsageReadModel.detail(member, by, emptyMap(), emptyList(), range(Period.MONTH), today)
        assertEquals(31, month.days.size)
        assertTrue(month.days.drop(10).none { it.known })
    }

    @Test
    fun knownTellsAZeroDayFromAMissingOne() {
        val by = byDate(summary(today.minusDays(1), 0))
        val days = UsageReadModel.detail(member, by, emptyMap(), emptyList(), range(Period.WEEK), today).days.associateBy { it.date }
        val zero = days.getValue(today.minusDays(1))
        assertTrue(zero.known)
        assertEquals(0L, zero.totalMs)
        val missing = days.getValue(today.minusDays(2))
        assertFalse(missing.known)
        assertEquals(0L, missing.totalMs)
        assertFalse(days.getValue(today).known)
    }

    @Test
    fun canGoBackOnlyWhileAnOlderSummaryExists() {
        val oldest = LocalDate.of(2026, 9, 29) // Tuesday of the week before today's
        val by = byDate(summary(oldest, 10), summary(today, 20))
        fun detail(r: DetailRange, m: MemberView = member, b: Map<LocalDate, DecodedEntry>? = by) =
            UsageReadModel.detail(m, b, emptyMap(), emptyList(), r, today)
        val week = range(Period.WEEK)
        assertTrue(detail(week).canGoBack)
        assertFalse(detail(week.previous()).canGoBack)
        assertTrue(detail(range(Period.DAY, oldest.plusDays(1))).canGoBack)
        assertFalse(detail(range(Period.DAY, oldest)).canGoBack)
        assertTrue(detail(range(Period.MONTH)).canGoBack) // 29 Sep is before 1 Oct
        assertFalse(detail(range(Period.MONTH).previous()).canGoBack)
        assertFalse(detail(week, b = null).canGoBack)
        // A parent's summaries are never surfaced, so they never offer older periods either.
        assertFalse(detail(week, member.copy(role = Role.PARENT)).canGoBack)
    }

    private fun cat(pkg: String, category: Int) = AppCatalog(package_name = pkg, label = pkg, category = category)

    @Test
    fun categoriesComeFromTheDaysAppTotalsWithTheRestInOther() {
        val roblox = "com.roblox.client" // catalog says undefined: the built-in table makes it a game
        val tube = "com.google.android.youtube" // catalog says social (4): the app's own category wins over the table
        val unknown = "com.example.unknown" // no catalog entry, not in the table
        val by = byDate(
            summary(today, 70, 0, roblox to 30L, tube to 20L, "~other" to 5L, unknown to 5L), // 10 min unattributed
            summary(today.minusDays(1), 10, 0, roblox to 10L),
            summary(today.minusDays(2), 0),
        )
        val catalog = mapOf(roblox to cat(roblox, -1), tube to cat(tube, 4))
        val week = UsageReadModel.detail(member, by, catalog, emptyList(), range(Period.WEEK), today)
        assertEquals(week.days.size, week.dayCategories.size)
        val byDay = week.days.map { it.date }.zip(week.dayCategories).toMap()
        assertEquals(
            mapOf(AppCategory.GAMES to 30 * min, AppCategory.SOCIAL to 20 * min, AppCategory.OTHER to 20 * min),
            byDay.getValue(today),
        )
        assertEquals(mapOf(AppCategory.GAMES to 10 * min), byDay.getValue(today.minusDays(1)))
        assertEquals(emptyMap(), byDay.getValue(today.minusDays(2))) // known, zero
        assertEquals(emptyMap(), byDay.getValue(today.minusDays(3))) // no summary
        assertEquals(emptyMap(), byDay.getValue(today.plusDays(1))) // future
        for ((d, cats) in week.days.zip(week.dayCategories)) assertEquals(d.totalMs, cats.values.sum(), "stack of ${d.date}")
        assertEquals(
            listOf(AppCategory.GAMES to 40 * min, AppCategory.SOCIAL to 20 * min, AppCategory.OTHER to 20 * min),
            week.categories.map { it.category to it.totalMs },
        )
        assertEquals(week.totalMs, week.categories.sumOf { it.totalMs })
    }

    @Test
    fun overridesRecategoriseAnApp() {
        val tube = "com.google.android.youtube"
        val by = byDate(summary(today, 20, 0, tube to 20L))
        val catalog = mapOf(tube to cat(tube, 4))
        val d = UsageReadModel.detail(
            member, by, catalog, emptyList(), range(Period.DAY), today, overrides = mapOf(tube to AppCategory.VIDEO),
        )
        assertEquals(listOf(AppCategory.VIDEO to 20 * min), d.categories.map { it.category to it.totalMs })
    }

    @Test
    fun appsAboveTheDayTotalAreScaledToTheStack() {
        // Untrusted peer: apps claim 90 minutes of a 60-minute day; the stack still equals the day total.
        val by = byDate(summary(today, 60, 0, "com.roblox.client" to 60L, "com.whatsapp" to 30L))
        val d = UsageReadModel.detail(member, by, emptyMap(), emptyList(), range(Period.DAY), today)
        val stack = d.dayCategories.single()
        assertEquals(60 * min, stack.values.sum())
        assertEquals(setOf(AppCategory.GAMES, AppCategory.SOCIAL), stack.keys)
        assertEquals(40 * min, stack.getValue(AppCategory.GAMES))
        assertEquals(20 * min, stack.getValue(AppCategory.SOCIAL))
    }

    @Test
    fun aParentHasNoCategories() {
        val by = byDate(summary(today, 20, 0, "com.whatsapp" to 20L))
        val d = UsageReadModel.detail(member.copy(role = Role.PARENT), by, emptyMap(), emptyList(), range(Period.DAY), today)
        assertTrue(d.categories.isEmpty())
        assertEquals(listOf(emptyMap()), d.dayCategories)
    }

    @Test
    fun canGoForwardUntilTheRangeReachesToday() {
        fun forward(r: DetailRange) = UsageReadModel.detail(member, null, emptyMap(), emptyList(), r, today).canGoForward
        assertFalse(forward(range(Period.DAY)))
        assertTrue(forward(range(Period.DAY, today.minusDays(1))))
        assertFalse(forward(range(Period.WEEK)))
        assertTrue(forward(range(Period.WEEK).previous()))
        assertFalse(forward(range(Period.MONTH)))
        assertTrue(forward(range(Period.MONTH).previous()))
    }
}
