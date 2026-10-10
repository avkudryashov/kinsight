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
import okio.ByteString.Companion.toByteString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app screen of spec §9.3 (stage 5a): one app's time over a range, its hours, category and place in the top. */
class AppDetailTest {
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val today = LocalDate.of(2026, 10, 10) // a Saturday
    private val member = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val min = 60_000L
    private val monFirst = WeekFields.of(DayOfWeek.MONDAY, 4)
    private val game = "com.example.game"
    private val chat = "com.example.chat"
    private val icon = byteArrayOf(7, 8)
    private val catalog = mapOf(
        game to AppCatalog(package_name = game, label = "Game", category = 0, icon_webp = icon.toByteString()),
        chat to AppCatalog(package_name = chat, label = "Chat", category = 4),
    )

    private fun range(p: Period, anchor: LocalDate = today) = DetailRange.of(p, anchor, monFirst)

    private fun summary(date: LocalDate, minutes: Long, vararg apps: Pair<String, Long>) = kid.create {
        it.copy(
            usage_summary = UsageSummary(
                local_date = date.toString(), zone_id = "Europe/Madrid", total_ms = minutes * min,
                apps = apps.map { (p, m) -> AppTotal(p, m * min) },
            ),
        )
    }

    private fun detail(date: LocalDate, vararg cells: Triple<Int, String, Long>) = kid.create {
        it.copy(
            usage_detail = UsageDetail(
                local_date = date.toString(), zone_id = "Europe/Madrid",
                hours = cells.groupBy { c -> c.first }.map { (h, c) -> HourBucket(hour = h, apps = c.map { x -> AppTotal(x.second, x.third * min) }) },
            ),
        )
    }

    private fun app(
        pkg: String,
        r: DetailRange,
        vararg e: DecodedEntry,
        m: MemberView = member,
        overrides: Map<String, AppCategory> = emptyMap(),
        viewerIsParent: Boolean = true,
    ): AppDetail {
        val entries = e.toList()
        return UsageReadModel.app(
            m, UsageReadModel.summaries(entries)[kidKeys.deviceId], UsageReadModel.details(entries)[kidKeys.deviceId], catalog,
            pkg, r, today, overrides, viewerIsParent = viewerIsParent,
        )
    }

    private val week = arrayOf(
        summary(today, 60, game to 30L, chat to 20L),
        summary(today.minusDays(1), 40, chat to 30L, game to 5L),
        summary(today.minusDays(2), 10, chat to 10L), // known, the game not used
        // Thu 8 Oct: no summary
        summary(today.minusDays(9), 99, game to 99L), // the week before
    )

    @Test
    fun perAppTotalsAcrossTheRangesDays() {
        val a = app(game, range(Period.WEEK), *week)
        assertEquals((5..11).map { LocalDate.of(2026, 10, it) }, a.days.map { it.date })
        val byDate = a.days.associateBy { it.date }
        assertEquals(30 * min, byDate.getValue(today).totalMs)
        assertEquals(5 * min, byDate.getValue(today.minusDays(1)).totalMs)
        assertEquals(0L, byDate.getValue(today.minusDays(2)).totalMs)
        assertTrue(byDate.getValue(today.minusDays(2)).known) // a summary without the app is a known zero
        assertFalse(byDate.getValue(today.minusDays(3)).known) // no summary: no data
        assertFalse(byDate.getValue(today.plusDays(1)).known) // future
        assertEquals(35 * min, a.totalMs)
        assertEquals("Game", a.label)
        assertContentEquals(icon, a.icon)
        assertEquals(game, a.packageName)
        assertEquals(range(Period.WEEK), a.range)
        assertTrue(a.canGoBack) // 1 Oct is before Mon 5 Oct
        assertTrue(a.canGoForward.not()) // the week contains today
        // An app without a catalog entry is named by its package.
        assertEquals("com.example.none", app("com.example.none", range(Period.WEEK), *week).label)
    }

    @Test
    fun rankIsThePlaceInTheChildsTopForTheRange() {
        assertEquals(1, app(chat, range(Period.WEEK), *week).rank) // 60 min
        assertEquals(2, app(game, range(Period.WEEK), *week).rank) // 35 min
        assertEquals(1, app(game, range(Period.DAY), *week).rank)
        assertEquals(2, app(chat, range(Period.DAY), *week).rank)
        assertNull(app(game, range(Period.DAY, today.minusDays(2)), *week).rank) // not used that day
        assertNull(app("com.example.none", range(Period.WEEK), *week).rank)
    }

    @Test
    fun categoryFollowsCatalogAndTheParentsOverride() {
        val plain = app(game, range(Period.WEEK), *week)
        assertEquals(AppCategory.GAMES, plain.category)
        assertFalse(plain.overridden)
        assertTrue(plain.canChangeCategory)
        val over = app(game, range(Period.WEEK), *week, overrides = mapOf(game to AppCategory.PRODUCTIVITY))
        assertEquals(AppCategory.PRODUCTIVITY, over.category)
        assertTrue(over.overridden)
        assertEquals(AppCategory.GAMES, over.defaultCategory) // what «Default» returns to
        assertEquals(AppCategory.GAMES, plain.defaultCategory)
        assertEquals(AppCategory.VIDEO, app("com.google.android.youtube", range(Period.WEEK), *week).defaultCategory) // built-in table
        assertEquals(AppCategory.OTHER, app(Categories.REMAINDER_PACKAGE, range(Period.WEEK), *week).defaultCategory)
        // An app without a catalog entry can be categorised too.
        assertTrue(app("com.example.none", range(Period.WEEK), *week).canChangeCategory)
    }

    @Test
    fun onlyAParentChangesCategoriesAndNeverOfTheRemainder() {
        assertFalse(app(game, range(Period.WEEK), *week, viewerIsParent = false).canChangeCategory)
        val rest = app(Categories.REMAINDER_PACKAGE, range(Period.WEEK), *week, overrides = mapOf(Categories.REMAINDER_PACKAGE to AppCategory.GAMES))
        assertEquals(AppCategory.OTHER, rest.category)
        assertFalse(rest.overridden)
        assertFalse(rest.canChangeCategory)
    }

    @Test
    fun hoursOfADayComeFromAUsableDetail() {
        val day = range(Period.DAY)
        val a = app(game, day, summary(today, 60, game to 30L), detail(today, Triple(9, game, 20L), Triple(9, chat, 10L), Triple(21, game, 10L)))
        val hours = assertNotNull(a.hours)
        assertEquals(24, hours.size)
        assertEquals(20 * min, hours[9])
        assertEquals(10 * min, hours[21])
        assertEquals(0L, hours[10])
        // A detail not newer than the summary describes an older state of the day.
        assertNull(app(game, day, detail(today, Triple(9, game, 20L)), summary(today, 60, game to 30L)).hours)
        assertNull(app(game, range(Period.WEEK), summary(today, 60, game to 30L), detail(today, Triple(9, game, 20L))).hours)
    }

    @Test
    fun hoursOfAnUntrustedDetailAreCappedPerSlot() {
        val a = app(game, range(Period.DAY), summary(today, 60, game to 30L), detail(today, Triple(9, game, 500L), Triple(9, game, 500L)))
        assertEquals(UsageReadModel.MAX_MS_PER_HOUR_SLOT, assertNotNull(a.hours)[9])
    }

    @Test
    fun theSameInputsGiveAnEqualAppDetail() {
        // Value equality (final fix F7): the flows drop an identical state instead of redrawing it.
        val a = app(game, range(Period.DAY), summary(today, 60, game to 30L), detail(today, Triple(9, game, 20L)))
        val b = app(game, range(Period.DAY), summary(today, 60, game to 30L), detail(today, Triple(9, game, 20L)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(AppUsage(game, "Game", byteArrayOf(7, 8), 5), AppUsage(game, "Game", byteArrayOf(7, 8), 5))
        assertEquals(AppUsage(game, "Game", byteArrayOf(7, 8), 5).hashCode(), AppUsage(game, "Game", byteArrayOf(7, 8), 5).hashCode())
        assertFalse(AppUsage(game, "Game", byteArrayOf(7, 8), 5) == AppUsage(game, "Game", byteArrayOf(7, 9), 5))
    }

    @Test
    fun theRemainderBucketTakesNoPlaceInTheTop() {
        // ~other is the summary's bucket of apps beyond its list, not an app: it is not ranked and does not push others down.
        val other = Categories.REMAINDER_PACKAGE
        val day = arrayOf(summary(today, 60, other to 40L, game to 15L, chat to 5L))
        assertEquals(1, app(game, range(Period.DAY), *day).rank)
        assertEquals(2, app(chat, range(Period.DAY), *day).rank)
        assertNull(app(other, range(Period.DAY), *day).rank)
    }

    @Test
    fun aParentsUsageIsNeverSurfaced() {
        val a = app(game, range(Period.WEEK), *week, m = member.copy(role = Role.PARENT))
        assertEquals(0L, a.totalMs)
        assertTrue(a.days.none { it.known })
        assertNull(a.rank)
        assertNull(a.hours)
        assertFalse(a.canGoBack)
    }
}
