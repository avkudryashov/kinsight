package app.kinsight.feature.devicedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.CategoryTotal
import app.kinsight.core.data.Heatmap
import app.kinsight.core.data.HourTotal
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.EventView
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import app.kinsight.core.designsystem.component.ChartAverageLine
import app.kinsight.core.designsystem.component.ChartLimitLine
import app.kinsight.core.designsystem.component.formatDuration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceDetailScreenTest {
    @get:Rule
    val rule = createComposeRule()
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val today = LocalDate.of(2026, 10, 10) // a Saturday
    private val apps = (1..7).map { AppUsage("pkg.$it", "App $it", null, (8 - it) * 60_000L) }
    private val event = EventView(kid.id, 5, StateEvent.Kind.SYNC_STOPPED, 0, 3_600_000, 1_000, 0)
    private val detail = DeviceDetail(
        // Sun 4 to Sat 10 Oct: the Sunday-first calendar week containing today.
        kid, DetailRange(Period.WEEK, today.minusDays(6), today), (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), 90 * 60_000L / 7, 1) },
        totalMs = 90 * 60_000L, unlocks = 7, apps = apps, events = listOf(event), sharing = Sharing.On,
    )

    private fun show(
        d: DeviceDetail,
        onAllApps: (DetailRange) -> Unit = {},
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onOpenDay: (LocalDate) -> Unit = {},
        onOpenApp: ((String) -> Unit)? = null,
        detailRetentionDays: Int = 90,
    ) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            DeviceDetailScreen(
                d, onPeriod = {}, onAllApps = onAllApps, onBack = {}, onPrevious = onPrevious, onNext = onNext,
                onOpenDay = onOpenDay, onOpenApp = onOpenApp, detailRetentionDays = detailRetentionDays,
            )
        }
    }

    private fun day(date: LocalDate, known: Boolean = true) = detail.copy(
        range = DetailRange(Period.DAY, date, date),
        days = listOf(if (known) DayTotal(date, 30 * 60_000L, 3) else DayTotal(date, 0, 0)),
        totalMs = if (known) 30 * 60_000L else 0, unlocks = if (known) 3 else 0, today = today,
    )

    private val list get() = rule.onNodeWithTag("det_list")

    /** Every kind's line in the current locale, in Kind.entries order. */
    // The location kinds (protocol 3) have no text of their own yet: they read as unknown.
    private val shownKinds = StateEvent.Kind.entries - setOf(
        StateEvent.Kind.LOCATION_PERMISSION_OFF, StateEvent.Kind.LOCATION_DISABLED,
        StateEvent.Kind.ZONE_ENTER, StateEvent.Kind.ZONE_EXIT,
    )

    private fun allEventTexts(): List<String> {
        val texts = mutableListOf<String>()
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    shownKinds.forEach { k -> Text(eventText(event.copy(kind = k, shiftMs = -300_000)).also { texts += it }) }
                }
            }
        }
        rule.waitForIdle()
        return texts.takeLast(shownKinds.size)
    }

    @Test
    fun levelsOneAndTwoWithAllAppsLink() {
        var opened: DetailRange? = null
        show(detail, onAllApps = { opened = it })
        rule.onNodeWithText("1 h 30 min").assertExists()
        rule.onNodeWithText("7 unlocks").assertExists()
        val list = rule.onNodeWithTag("det_list")
        list.performScrollToNode(hasText("App 5"))
        list.performScrollToNode(hasTestTag("det_all_apps"))
        rule.onNodeWithText("App 6").assertDoesNotExist()
        rule.onNodeWithTag("det_all_apps").performClick()
        rule.waitForIdle()
        assertEquals(detail.range, opened)
        list.performScrollToNode(hasText("Sync was stopped for 1 h"))
    }

    @Test
    fun allAppsListsEveryApp() {
        rule.setContent { KinsightTheme(dynamicColor = false) { AllAppsScreen(AppList(detail.member, detail.range, detail.apps, today), onBack = {}) } }
        rule.onNodeWithTag("det_apps").performScrollToNode(hasText("App 7"))
        rule.onNodeWithText("Kid: all apps, Oct 4", substring = true).assertExists()
    }

    @Test
    fun everyEventKindHasItsOwnText() {
        val texts = allEventTexts()
        assertEquals(shownKinds.size, texts.size)
        assertTrue(texts.toString(), texts.all { it.isNotBlank() })
        assertEquals(texts.toString(), texts.size, texts.toSet().size)
        rule.onNodeWithText("Sync notification hidden").assertExists()
        rule.onNodeWithText("Left the group").assertExists()
        rule.onNodeWithText("Clock moved back by 5 min").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun everyEventKindHasItsOwnTextInRussian() {
        val texts = allEventTexts()
        assertEquals(texts.toString(), texts.size, texts.toSet().size)
        assertTrue(texts.toString(), texts.none { it.contains("Unknown") || it.contains("Sync ") })
        rule.onNodeWithText("Синхронизация была остановлена на 1 ч").assertExists()
        rule.onNodeWithText("Часы переведены назад на 5 мин").assertExists()
    }

    @Test
    fun noDataIsShownAsNoDataNotAsZero() {
        val empty = detail.copy(
            days = (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), 0, 0) },
            totalMs = 0, unlocks = 0, apps = emptyList(), events = emptyList(),
        )
        show(empty)
        rule.onNodeWithText("No data for this period").assertExists()
        rule.onNodeWithText("<1 min").assertDoesNotExist()
        rule.onNodeWithText("0 unlocks").assertDoesNotExist()
    }

    @Test
    fun parentDeviceSaysOnlyChildrenShare() {
        show(detail.copy(member = kid.copy(role = Role.PARENT), days = detail.days.map { it.copy(totalMs = 0, unlocks = 0) }, totalMs = 0, unlocks = 0, apps = emptyList()))
        rule.onNodeWithText("Kinsight shares only children's screen time", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianChartSummaryNamesTheBusiestDay() {
        val days = (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), if (it == 0) 60 * 60_000L else 10 * 60_000L, 1) }
        show(detail.copy(days = days))
        rule.onNode(hasContentDescription("больше всего: суббота (1 ч)", substring = true)).assertExists()
    }

    @Test
    fun moreThanAHundredEventsShowTheLatestHundred() {
        val many = (1L..130).map { event.copy(kind = StateEvent.Kind.DATA_GAP, seq = 200 - it) }
        show(detail.copy(events = many))
        val list = rule.onNodeWithTag("det_list")
        list.performScrollToNode(hasText("Showing the latest 100 of 130"))
        list.performScrollToNode(hasTestTag("det_event_101"))
        rule.onNodeWithTag("det_event_71").assertDoesNotExist()
    }

    @Test
    fun weekShowsTheChartButDayDoesNot() {
        show(detail.copy(range = DetailRange(Period.DAY, today, today), days = detail.days.takeLast(1)))
        rule.onNode(hasContentDescription("Average", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("1 h 30 min").assertExists()
    }

    @Test
    fun weekChartIsThere() {
        show(detail)
        rule.onNode(hasContentDescription("Average", substring = true)).assertExists()
    }

    @Test
    fun pausedSharingIsNoted() {
        show(detail.copy(sharing = Sharing.Paused(1_000)))
        rule.onNodeWithText("Sharing has been paused since", substring = true).assertExists()
    }

    @Test
    fun theFirstResumeReadsAsSharingStarted() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    Text(eventText(event.copy(kind = StateEvent.Kind.SHARING_RESUMED, firstSharing = true)))
                    Text(eventText(event.copy(kind = StateEvent.Kind.SHARING_RESUMED)))
                }
            }
        }
        rule.onNodeWithText("Sharing started").assertExists()
        rule.onNodeWithText("Sharing resumed").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun theFirstResumeInRussian() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) { Text(eventText(event.copy(kind = StateEvent.Kind.SHARING_RESUMED, firstSharing = true))) }
        }
        rule.onNodeWithText("Обмен включён").assertExists()
    }

    @Test
    fun missingOrReversedSpanHasNoDuration() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    Text(eventText(event.copy(kind = StateEvent.Kind.BLUETOOTH_OFF, fromMs = 5_000, toMs = 0)))
                    Text(eventText(event.copy(kind = StateEvent.Kind.SYNC_STOPPED, fromMs = 5_000, toMs = 1_000)))
                }
            }
        }
        rule.onNodeWithText("Bluetooth was off").assertExists()
        rule.onNodeWithText("Sync was stopped").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianSpansTakeNa() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    Text(eventText(event.copy(kind = StateEvent.Kind.BLUETOOTH_OFF)))
                    Text(eventText(event.copy(kind = StateEvent.Kind.NEARBY_PERMISSION_OFF)))
                    Text(eventText(event.copy(kind = StateEvent.Kind.NOTIFICATION_SHOWN)))
                }
            }
        }
        rule.onNodeWithText("Bluetooth был выключен на 1 ч").assertExists()
        rule.onNodeWithText("Разрешение «Устройства поблизости» было выключено на 1 ч").assertExists()
        rule.onNodeWithText("Уведомление синхронизации снова видно, было скрыто на 1 ч").assertExists()
    }

    @Test
    fun periodHeaderStepsAndNamesTheDay() {
        var steps = ""
        show(day(today).copy(canGoBack = true), onPrevious = { steps += "<" }, onNext = { steps += ">" })
        rule.onNodeWithTag("det_period_title").assertTextEquals("Today")
        rule.onNodeWithTag("det_next").assertIsNotEnabled()
        rule.onNodeWithTag("det_prev").performClick()
        rule.waitForIdle()
        assertEquals("<", steps)
    }

    @Test
    fun yesterdayAndOlderDaysAndWeeksHaveTitles() {
        val shown = mutableStateOf(day(today.minusDays(1)))
        rule.setContent { KinsightTheme(dynamicColor = false) { DeviceDetailScreen(shown.value, onPeriod = {}, onAllApps = {}, onBack = {}) } }
        rule.onNodeWithTag("det_period_title").assertTextEquals("Yesterday")
        shown.value = day(LocalDate.of(2026, 10, 6))
        rule.onNodeWithTag("det_period_title").assertTextContains("Oct", substring = true)
        rule.onNodeWithTag("det_period_title").assertTextContains("6", substring = true)
        shown.value = detail.copy(today = today)
        rule.onNodeWithTag("det_period_title").assertTextContains("Oct 4", substring = true)
        shown.value = detail.copy(range = DetailRange(Period.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), today = today)
        rule.onNodeWithTag("det_period_title").assertTextContains("October 2026", substring = true)
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianDayTitles() {
        show(day(today))
        rule.onNodeWithTag("det_period_title").assertTextEquals("Сегодня")
    }

    @Test
    fun tappingABarOpensThatDay() {
        var opened: LocalDate? = null
        val stacks = detail.days.map { mapOf(AppCategory.GAMES to it.totalMs) }
        show(detail.copy(dayCategories = stacks, today = today), onOpenDay = { opened = it })
        rule.onAllNodes(hasClickAction() and hasContentDescription(": ", substring = true))[2].performClick()
        rule.waitForIdle()
        assertEquals(detail.days[2].date, opened)
    }

    @Test
    fun anUnknownDayShowsNoDataNotZero() {
        show(day(today.minusDays(3), known = false))
        rule.onNodeWithText("No data for this day").assertExists()
        rule.onNodeWithText("0 unlocks").assertDoesNotExist()
        rule.onNodeWithText("<1 min").assertDoesNotExist()
    }

    @Test
    fun levelFourSaysWhenHourlyDataIsUnavailable() {
        // Known days from 4 Oct; with 3 days of detail kept, the oldest ones are past retention.
        show(detail.copy(today = today), detailRetentionDays = 3)
        list.performScrollToNode(hasText("Time of day"))
        list.performScrollToNode(hasText("Hourly data for this period is not available (it is kept for 3 days)"))
    }

    @Test
    fun recentDaysWithoutDetailSayItHasNotArrivedYet() {
        show(day(today), detailRetentionDays = 30)
        list.performScrollToNode(hasText("Time of day"))
        list.performScrollToNode(hasText("Hourly data for this period has not arrived yet"))
        rule.onNode(hasText("is not available", substring = true)).assertDoesNotExist()
    }

    @Test
    fun anUnknownDayHasNoTimeOfDaySection() {
        show(day(today.minusDays(2), known = false))
        list.performScrollToNode(hasText("Events"))
        rule.onNodeWithText("Time of day").assertDoesNotExist()
        rule.onNode(hasText("Hourly data", substring = true)).assertDoesNotExist()
    }

    @Test
    fun futureDaysOfTheWeekCannotBeOpened() {
        val wed = today.minusDays(3)
        val days = detail.days.map { if (it.date.isAfter(wed)) DayTotal(it.date, 0, 0) else it }
        show(detail.copy(days = days, today = wed))
        rule.onAllNodes(hasClickAction() and hasContentDescription(": ", substring = true)).assertCountEquals(4)
    }

    @Test
    fun heatmapRowsFollowTheDataFirstDayOfWeek() {
        // Row 0 is the busiest; the data says rows start on Wednesday whatever the UI locale says.
        val cells = Array(7) { d -> LongArray(24) { h -> if (d == 0 && h == 15) 30 * 60_000L else 0 } }
        show(detail.copy(heatmap = Heatmap(cells, IntArray(24), 3, 7, firstDayOfWeek = DayOfWeek.WEDNESDAY), today = today))
        list.performScrollToNode(hasTestTag("det_heatmap"))
        rule.onNode(hasContentDescription("Most time on Wednesday", substring = true)).assertExists()
    }

    @Test
    fun categoryChartSummaryNamesTheTopCategories() {
        val cats = listOf(
            CategoryTotal(AppCategory.GAMES, 60 * 60_000L), CategoryTotal(AppCategory.VIDEO, 30 * 60_000L),
            CategoryTotal(AppCategory.NEWS, 1 * 60_000L),
        )
        show(detail.copy(categories = cats, today = today))
        rule.onNode(hasContentDescription("Time by category per day. Games 1 h, Video 30 min", substring = true)).assertExists()
        rule.onNode(hasContentDescription("News", substring = true)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru")
    fun levelFourUnavailableInRussian() {
        show(day(today.minusDays(40)), detailRetentionDays = 30)
        list.performScrollToNode(hasText("Почасовые данные за этот период недоступны (хранятся 30 дней)"))
    }

    @Test
    fun weekHeatmapSaysHowManyDaysItAverages() {
        val cells = Array(7) { LongArray(24) { h -> if (h == 15) 30 * 60_000L else 0 } }
        show(detail.copy(heatmap = Heatmap(cells, IntArray(24), coveredDays = 3, rangeDays = 7), today = today))
        list.performScrollToNode(hasText("Average of 3 of 7 days"))
        rule.onNode(hasContentDescription("Most time on", substring = true)).assertExists()
    }

    @Test
    fun weekHeatmapShowsAverageUnlocksByHour() {
        val cells = Array(7) { LongArray(24) { h -> if (h == 15) 30 * 60_000L else 0 } }
        val unlocks = IntArray(24) { h -> if (h == 8) 3 else 0 }
        show(detail.copy(heatmap = Heatmap(cells, unlocks, coveredDays = 3, rangeDays = 7), today = today))
        list.performScrollToNode(hasTestTag("det_heatmap_unlocks"))
        rule.onNodeWithText("Unlocks by hour, average").assertExists()
        rule.onNode(hasContentDescription("Most unlocks at", substring = true)).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun heatmapCoverageInRussian() {
        val cells = Array(7) { LongArray(24) }
        show(detail.copy(heatmap = Heatmap(cells, IntArray(24), coveredDays = 3, rangeDays = 7), today = today))
        list.performScrollToNode(hasText("В среднем по 3 из 7 дней"))
    }

    @Test
    fun dayShowsHourlyBarsAndUnlocks() {
        val hours = (0..23).map { h ->
            val ms = if (h == 20) 40 * 60_000L else 0L
            HourTotal(h, ms, if (h == 8) 4 else 0, if (ms > 0) mapOf(AppCategory.VIDEO to ms) else emptyMap())
        }
        show(day(today).copy(hours = hours))
        list.performScrollToNode(hasContentDescription("Most time at", substring = true))
        list.performScrollToNode(hasContentDescription("Most unlocks at", substring = true))
        rule.onNode(hasText("Hourly data", substring = true)).assertDoesNotExist()
    }

    @Test
    fun categoriesAreListedWithTotalsAndShares() {
        val cats = listOf(CategoryTotal(AppCategory.GAMES, 60 * 60_000L), CategoryTotal(AppCategory.VIDEO, 30 * 60_000L))
        show(detail.copy(categories = cats, today = today))
        list.performScrollToNode(hasTestTag("det_category_GAMES"))
        rule.onNodeWithText("1 h · 67%").assertExists()
        list.performScrollToNode(hasTestTag("det_category_VIDEO"))
        rule.onNodeWithText("30 min · 33%").assertExists()
    }

    @Test
    fun topAppsOpenTheAppScreenWhenWired() {
        var app: String? = null
        show(detail, onOpenApp = { app = it })
        list.performScrollToNode(hasTestTag("det_app_pkg.2"))
        rule.onNodeWithTag("det_app_pkg.2").performClick()
        rule.waitForIdle()
        assertEquals("pkg.2", app)
    }

    @Test
    fun topAppsAreNotClickableWithoutTheAppScreen() {
        show(detail)
        list.performScrollToNode(hasTestTag("det_app_pkg.2"))
        rule.onNodeWithTag("det_app_pkg.2").assert(hasClickAction().not())
    }

    @Test
    fun horizontalSwipeStepsLikeTheArrows() {
        var steps = ""
        show(detail.copy(canGoBack = true, canGoForward = true), onPrevious = { steps += "<" }, onNext = { steps += ">" })
        list.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        list.performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals("><", steps)
    }

    @Test
    fun hourLabelsFollowTheClockFormat() {
        val norm = { l: List<String> -> l.map { it.replace('\u202f', ' ').replace('\u00a0', ' ') } }
        assertEquals(listOf("12 AM", "6 AM", "12 PM", "6 PM"), norm(hourLabels(Locale.US, is24Hour = false)))
        assertEquals(listOf("0", "6", "12", "18"), hourLabels(Locale.forLanguageTag("ru"), is24Hour = true))
    }

    private fun showWithLimits(d: DeviceDetail, thresholds: List<ThresholdRule>?, onThresholds: () -> Unit = {}) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            DeviceDetailScreen(d, onPeriod = {}, onAllApps = {}, onBack = {}, thresholds = thresholds, onThresholds = onThresholds)
        }
    }

    private val totalLimit = ThresholdRule.of(kid.id, ThresholdTarget.Total, 3 * 3_600_000L)!!
    private val gamesLimit = ThresholdRule.of(kid.id, ThresholdTarget.Category(AppCategory.GAMES), 3_600_000L)!!

    @Test
    fun aParentOpensTheLimitsFromTheDetailWithTheirCount() {
        var opened = 0
        showWithLimits(detail, listOf(totalLimit, gamesLimit)) { opened++ }
        list.performScrollToNode(hasTestTag("det_thresholds"))
        rule.onNodeWithTag("det_thresholds").assert(hasClickAction())
        rule.onNodeWithText("Limits").assertExists()
        rule.onNodeWithText("2 limits").assertExists()
        rule.onNodeWithTag("det_thresholds").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noLimitsSaysSo() {
        showWithLimits(detail, emptyList())
        list.performScrollToNode(hasTestTag("det_thresholds"))
        rule.onNodeWithText("None set").assertExists()
    }

    @Test
    fun noLimitsEntryOnAChildsOwnPhone() {
        showWithLimits(detail, null)
        rule.onNodeWithTag("det_thresholds").assertDoesNotExist()
    }

    @Test
    fun onlyTheTotalLimitIsDrawnOnTheChart() {
        showWithLimits(detail, listOf(gamesLimit))
        rule.onNode(hasContentDescription("Limit", substring = true)).assertDoesNotExist()
    }

    @Test
    fun theTotalLimitIsReadWithTheWeekChart() {
        showWithLimits(detail, listOf(totalLimit, gamesLimit))
        rule.onNodeWithTag("det_chart").assert(hasContentDescription("Limit 3 h", substring = true))
    }

    @Test
    fun aDayShowsTheTotalLimitUnderItsTotal() {
        showWithLimits(day(today), listOf(totalLimit))
        rule.onNodeWithTag("det_limit").assertTextEquals("Limit 3 h")
    }

    @Test
    fun todaysRangeSaysWhenItsDataIsFromAndWhenItIsOld() {
        val nowMs = 1_760_000_000_000L
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                DeviceDetailScreen(detail.copy(today = today, updatedMs = nowMs - 3 * 3_600_000L), onPeriod = {}, onAllApps = {}, onBack = {}, nowMs = nowMs)
            }
        }
        rule.onNodeWithTag("det_data_at").assertTextContains("may be out of date", substring = true)
    }

    @Test
    fun aPastRangeHasNoDataTime() {
        val past = detail.copy(range = DetailRange(Period.WEEK, today.minusDays(13), today.minusDays(7)), today = today, updatedMs = 1L)
        show(past)
        rule.onNodeWithTag("det_data_at").assertDoesNotExist()
    }

    private val month = DetailRange(Period.MONTH, today.withDayOfMonth(1), today.withDayOfMonth(31))
    private val monthDetail = detail.copy(
        range = month, today = today,
        days = month.dates().map { if (it.isAfter(today)) DayTotal(it, 0, 0) else DayTotal(it, 20 * 60_000L, 1) },
        totalMs = 10 * 20 * 60_000L,
    )

    @Test
    fun theTotalLimitIsDrawnOnTheMonthToo() {
        showWithLimits(monthDetail, listOf(totalLimit))
        rule.onNodeWithTag("det_chart").assert(hasContentDescription("Limit 3 h", substring = true))
            .assert(SemanticsMatcher.expectValue(ChartLimitLine, 3 * 3_600_000L))
    }

    @Test
    fun theAverageLineIsTheAverageTheSummaryStates() {
        show(detail)
        val node = rule.onNodeWithTag("det_chart").fetchSemanticsNode()
        val average = node.config[ChartAverageLine]
        assertEquals(90 * 60_000L / 7, average)
        val text = formatDuration(ApplicationProvider.getApplicationContext<Context>().resources, average)
        rule.onNodeWithTag("det_chart").assert(hasContentDescription("Average $text a day", substring = true))
    }

    @Test
    @Config(qualifiers = "w400dp-h3000dp") // tall enough that every item is composed
    fun aDaysHourlyChartComesRightUnderItsTotal() {
        val hours = (0..23).map { h -> HourTotal(h, if (h == 9) 30 * 60_000L else 0L, 0, if (h == 9) mapOf(AppCategory.GAMES to 30 * 60_000L) else emptyMap()) }
        show(day(today).copy(hours = hours))
        val hoursY = rule.onNodeWithTag("det_hours").fetchSemanticsNode().positionInRoot.y
        val appY = rule.onNodeWithTag("det_app_pkg.1").fetchSemanticsNode().positionInRoot.y
        assertTrue("hours at $hoursY, first app at $appY", hoursY < appY)
    }

    @Test
    fun aBarIsReadAsItsDayAndItsTime() {
        show(monthDetail)
        rule.onNode(hasContentDescription("Oct 7: 20 min", substring = true) and hasClickAction()).assertExists()
        rule.onNode(hasContentDescription("Oct 7", substring = true) and hasContentDescription("Open", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aMissingDeviceSaysSoAndOffersBack() {
        var back = 0
        rule.setContent { KinsightTheme(dynamicColor = false) { MissingDevice(onBack = { back++ }) } }
        rule.onNodeWithTag("det_missing").assertExists()
        rule.onNodeWithText("Its data is not on this phone", substring = true).assertExists()
        rule.onNodeWithTag("det_missing_back").performClick()
        assertEquals(1, back)
    }
}
