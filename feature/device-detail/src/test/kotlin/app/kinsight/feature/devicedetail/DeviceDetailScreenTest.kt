package app.kinsight.feature.devicedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
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
import app.kinsight.core.data.TodayComparison
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
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
        KinsightTheme() {
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

    private val shownKinds = StateEvent.Kind.entries

    /** Every kind's line in the current locale, in Kind.entries order. */
    private fun allEventTexts(): List<String> {
        val texts = mutableListOf<String>()
        rule.setContent {
            KinsightTheme() {
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
        list.performScrollToNode(hasText("App 3"))
        list.performScrollToNode(hasTestTag("det_all_apps"))
        rule.onNodeWithText("App 4").assertDoesNotExist()
        rule.onNodeWithTag("det_all_apps").performClick()
        rule.waitForIdle()
        assertEquals(detail.range, opened)
        list.performScrollToNode(hasText("Sync was stopped for 1 h"))
    }

    @Test
    fun allAppsListsEveryApp() {
        rule.setContent { KinsightTheme() { AllAppsScreen(AppList(detail.member, detail.range, detail.apps, today), onBack = {}) } }
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

    /** Review I4: a PIN unblock names the app by the child's catalog label, the package only without one. */
    @Test
    fun aPinUnblockNamesItsApp() {
        val pin = event.copy(kind = StateEvent.Kind.BLOCK_OVERRIDDEN, packageName = "com.game")
        val texts = mutableListOf<String>()
        rule.setContent {
            KinsightTheme() {
                Column {
                    Text(eventText(pin.copy(appLabel = "Space Game")).also { texts += it })
                    Text(eventText(pin).also { texts += it })
                }
            }
        }
        rule.waitForIdle()
        assertEquals(
            listOf("Space Game unblocked by PIN until the end of the day", "com.game unblocked by PIN until the end of the day"),
            texts.takeLast(2),
        )
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
            KinsightTheme() {
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
            KinsightTheme() { Text(eventText(event.copy(kind = StateEvent.Kind.SHARING_RESUMED, firstSharing = true))) }
        }
        rule.onNodeWithText("Обмен включён").assertExists()
    }

    @Test
    fun missingOrReversedSpanHasNoDuration() {
        rule.setContent {
            KinsightTheme() {
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
            KinsightTheme() {
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
        rule.setContent { KinsightTheme() { DeviceDetailScreen(shown.value, onPeriod = {}, onAllApps = {}, onBack = {}) } }
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
    fun recentDaysWithoutDetailSayItHasNotArrivedYet() {
        show(day(today), detailRetentionDays = 30)
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
    @Config(qualifiers = "ru")
    fun levelFourUnavailableInRussian() {
        show(day(today.minusDays(40)), detailRetentionDays = 30)
        list.performScrollToNode(hasText("Почасовые данные за этот период недоступны (хранятся 30 дней)"))
    }

    @Test
    fun dayShowsHourlyBars() {
        val hours = (0..23).map { h ->
            val ms = if (h == 20) 40 * 60_000L else 0L
            HourTotal(h, ms, if (h == 8) 4 else 0, if (ms > 0) mapOf(AppCategory.VIDEO to ms) else emptyMap())
        }
        show(day(today).copy(hours = hours))
        list.performScrollToNode(hasContentDescription("Most time at", substring = true))
        rule.onNode(hasText("Hourly data", substring = true)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h3000dp") // tall enough that every item is composed
    fun everyTabStacksFigureChartCategoriesThenApps() {
        val cats = listOf(CategoryTotal(AppCategory.GAMES, 60 * 60_000L), CategoryTotal(AppCategory.VIDEO, 30 * 60_000L))
        show(detail.copy(categories = cats, today = today))
        fun y(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue(y("det_total") < y("det_chart"))
        assertTrue(y("det_chart") < y("det_strip"))
        assertTrue(y("det_strip") < y("det_app_pkg.1"))
        assertTrue(y("det_app_pkg.3") < y("det_all_apps"))
        rule.onNodeWithTag("det_app_pkg.4").assertDoesNotExist()
    }

    @Test
    fun theCategoriesAreOneStripWithUpToFourNamesAndOther() {
        val m = 60_000L
        val cats = listOf(
            CategoryTotal(AppCategory.GAMES, 50 * m), CategoryTotal(AppCategory.VIDEO, 40 * m), CategoryTotal(AppCategory.NEWS, 30 * m),
            CategoryTotal(AppCategory.PHOTOS, 20 * m), CategoryTotal(AppCategory.MAPS, 10 * m), CategoryTotal(AppCategory.OTHER, 5 * m),
        )
        show(detail.copy(categories = cats, today = today))
        list.performScrollToNode(hasTestTag("det_strip"))
        rule.onNodeWithText("Games 50 min").assertExists()
        rule.onNodeWithText("Photos 20 min").assertExists()
        rule.onNodeWithText("Other 15 min").assertExists()
        rule.onNodeWithText("Maps and navigation 10 min").assertDoesNotExist()
        rule.onNodeWithTag("det_category_GAMES").assertDoesNotExist()
    }

    @Test
    fun aTopAppWithALimitOnItOrItsCategoryCarriesThePill() {
        val rules = listOf(ThresholdRule.of(kid.id, ThresholdTarget.App("pkg.1"), 3_600_000L)!!, gamesLimit)
        showWithLimits(detail.copy(appCategories = mapOf("pkg.2" to AppCategory.GAMES)), rules)
        list.performScrollToNode(hasTestTag("det_app_pkg.3"))
        fun pillIn(pkg: String) = rule.onNode(hasTestTag("det_limit_pill") and hasAnyAncestor(hasTestTag("det_app_$pkg")), useUnmergedTree = true)
        pillIn("pkg.1").assertExists()
        pillIn("pkg.2").assertExists()
        pillIn("pkg.3").assertDoesNotExist()
    }

    @Test
    fun rulesCardSummarisesAndOpens() {
        val rules = listOf(
            ThresholdRule.block(kid.id, ThresholdTarget.App("pkg.1"))!!,
            ThresholdRule.of(kid.id, ThresholdTarget.Category(AppCategory.GAMES), 3_600_000L)!!,
            ThresholdRule.allow(kid.id, "pkg.2")!!,
        )
        var opened = 0
        rule.setContent { KinsightTheme { DeviceDetailScreen(detail, onPeriod = {}, onAllApps = {}, onBack = {}, rules = rules, onRules = { opened++ }) } }
        list.performScrollToNode(hasTestTag("det_rules"))
        rule.onNodeWithText("Limits and blocks").assertExists()
        rule.onNodeWithText("Blocked: 1 · Limits: 1 · Always allowed: 1").assertExists()
        rule.onNodeWithTag("det_rules").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun allAppsRowsCarryPills() {
        val rules = listOf(ThresholdRule.block(kid.id, ThresholdTarget.App("pkg.1"))!!, ThresholdRule.allow(kid.id, "pkg.2")!!)
        val list = AppList(detail.member, detail.range, detail.apps, today, appCategories = emptyMap())
        rule.setContent { KinsightTheme { AllAppsScreen(list, onBack = {}, thresholds = rules) } }
        fun pillIn(tag: String, pkg: String) = rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("det_app_$pkg")), useUnmergedTree = true)
        pillIn("det_blocked_pill", "pkg.1").assertTextEquals("blocked")
        pillIn("det_allowed_pill", "pkg.2").assertTextEquals("always")
        pillIn("det_blocked_pill", "pkg.3").assertDoesNotExist()
    }

    @Test
    fun rulesCardWithoutRulesSaysSo() {
        rule.setContent { KinsightTheme { DeviceDetailScreen(detail, onPeriod = {}, onAllApps = {}, onBack = {}, onRules = {}) } }
        list.performScrollToNode(hasTestTag("det_rules"))
        rule.onNodeWithText("No rules yet").assertExists()
    }

    @Test
    fun noRulesCardWithoutTheRulesScreen() {
        show(detail)
        rule.onNodeWithTag("det_rules").assertDoesNotExist()
    }

    @Test
    fun appRowsCarryBlockedAndAllowedPills() {
        val rules = listOf(ThresholdRule.block(kid.id, ThresholdTarget.App("pkg.1"))!!, ThresholdRule.allow(kid.id, "pkg.2")!!)
        showWithLimits(detail, rules)
        list.performScrollToNode(hasTestTag("det_app_pkg.3"))
        fun pillIn(tag: String, pkg: String) = rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("det_app_$pkg")), useUnmergedTree = true)
        pillIn("det_blocked_pill", "pkg.1").assertTextEquals("blocked")
        pillIn("det_allowed_pill", "pkg.2").assertTextEquals("always")
        pillIn("det_limit_pill", "pkg.1").assertDoesNotExist()
        pillIn("det_blocked_pill", "pkg.3").assertDoesNotExist()
    }

    @Test
    fun aPastDaySaysHowItComparesWithTheWeekBeforeIt() {
        show(day(today.minusDays(2)).copy(dayComparison = TodayComparison.FullDay(20 * 60_000L)))
        rule.onNodeWithTag("det_versus").assertTextEquals("10 min more than the 7-day average")
    }

    @Test
    fun aWeekSaysItsAveragePerDay() {
        show(detail)
        rule.onNodeWithTag("det_versus").assertTextContains("a day on average", substring = true)
    }

    @Test
    fun theReportHasNoLimitsOrLocationRows() {
        showWithLimits(detail, listOf(totalLimit))
        list.performScrollToNode(hasText("Events"))
        rule.onNodeWithTag("det_thresholds").assertDoesNotExist()
        rule.onNodeWithTag("det_location").assertDoesNotExist()
    }

    @Test
    fun allAppsListsTheCategoriesWithTheirShares() {
        val cats = listOf(CategoryTotal(AppCategory.GAMES, 60 * 60_000L), CategoryTotal(AppCategory.VIDEO, 30 * 60_000L))
        rule.setContent { KinsightTheme { AllAppsScreen(AppList(detail.member, detail.range, detail.apps, today, categories = cats), onBack = {}) } }
        rule.onNodeWithText("1 h · 67%").assertExists()
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


    private fun showWithLimits(d: DeviceDetail, thresholds: List<ThresholdRule>?) = rule.setContent {
        KinsightTheme() {
            DeviceDetailScreen(d, onPeriod = {}, onAllApps = {}, onBack = {}, thresholds = thresholds)
        }
    }

    private val totalLimit = ThresholdRule.of(kid.id, ThresholdTarget.Total, 3 * 3_600_000L)!!
    private val gamesLimit = ThresholdRule.of(kid.id, ThresholdTarget.Category(AppCategory.GAMES), 3_600_000L)!!

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
            KinsightTheme() {
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
        rule.setContent { KinsightTheme() { MissingDevice(onBack = { back++ }) } }
        rule.onNodeWithTag("det_missing").assertExists()
        rule.onNodeWithText("Its data is not on this phone", substring = true).assertExists()
        rule.onNodeWithTag("det_missing_back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun zoneEventsNameTheirZone() {
        rule.setContent {
            KinsightTheme() {
                Column {
                    Text(eventText(event.copy(kind = StateEvent.Kind.ZONE_EXIT, zoneName = "Home")))
                    Text(eventText(event.copy(kind = StateEvent.Kind.ZONE_ENTER)))
                }
            }
        }
        rule.onNodeWithText("Left the zone “Home”").assertExists()
        rule.onNodeWithText("Entered a safe zone").assertExists()
    }

    @Test
    fun aLocationProblemReadsOpenUntilItIsClosed() {
        rule.setContent {
            KinsightTheme() {
                Column {
                    Text(eventText(event.copy(kind = StateEvent.Kind.LOCATION_PERMISSION_OFF, fromMs = 5_000, toMs = 5_000)))
                    Text(eventText(event.copy(kind = StateEvent.Kind.LOCATION_DISABLED, fromMs = 5_000, toMs = 5_000 + 3_600_000)))
                }
            }
        }
        rule.onNodeWithText("Location access turned off").assertExists()
        rule.onNodeWithText("Location was off on the phone for 1 h").assertExists()
    }
}
