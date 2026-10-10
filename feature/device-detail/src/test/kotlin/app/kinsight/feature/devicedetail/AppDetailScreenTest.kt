package app.kinsight.feature.devicedetail

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppDetailScreenTest {
    @get:Rule
    val rule = createComposeRule()
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val today = LocalDate.of(2026, 10, 10) // a Saturday
    private val weekRange = DetailRange(Period.WEEK, today.minusDays(6), today)

    private fun app(
        range: DetailRange = weekRange,
        canChange: Boolean = true,
        hours: List<Long>? = null,
        category: AppCategory = AppCategory.GAMES,
        overridden: Boolean = false,
        pkg: String = "com.example.game",
        label: String = "Game",
    ): AppDetail {
        val days = range.dates().map { DayTotal(it, 10 * 60_000L, 0, known = true) }
        return AppDetail(
            kid, pkg, range, label, null, category, overridden, days, days.sumOf { it.totalMs }, hours, rank = 2,
            canChangeCategory = canChange, canGoBack = true, canGoForward = false, today = today, defaultCategory = AppCategory.GAMES,
        )
    }

    private fun show(a: AppDetail, onOpenDay: (LocalDate) -> Unit = {}, onCategory: (AppCategory?) -> Unit = {}) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            AppDetailScreen(a, onPeriod = {}, onBack = {}, onOpenDay = onOpenDay, onCategory = onCategory, detailRetentionDays = 30)
        }
    }

    private val list get() = rule.onNodeWithTag("app_list")

    @OptIn(ExperimentalComposeUiApi::class)
    private fun SemanticsNode.tagsAreIds(): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrElse(SemanticsPropertiesAndroid.TestTagsAsResourceId) { false } }

    @Test
    fun showsLabelCategoryRankAndTotal() {
        show(app())
        rule.onNodeWithTag("app_label").assertTextEquals("Game")
        rule.onNodeWithTag("app_category_chip").assertExists()
        rule.onNodeWithText("Games").assertExists()
        rule.onNodeWithText("#2 in top apps").assertExists()
        rule.onNodeWithText("1 h 10 min").assertExists()
    }

    @Test
    fun aParentChangesTheCategoryInADialog() {
        var chosen: AppCategory? = AppCategory.GAMES
        var calls = 0
        show(app(), onCategory = {
            chosen = it
            calls++
        })
        list.performScrollToNode(hasTestTag("app_category"))
        rule.onNodeWithTag("app_category").performClick()
        // Not overridden: the dialog starts on «Default», not on the catalog's category; it says what Default is.
        rule.onNodeWithTag("app_category_option_default").assertIsSelected()
        rule.onNodeWithText("Default (Games)").assertExists()
        // The dialog is its own window: its tags must be resource ids there too.
        assertTrue(rule.onNodeWithTag("app_category_confirm").fetchSemanticsNode().tagsAreIds())
        rule.onNodeWithTag("app_category_option_VIDEO").performClick()
        rule.onNodeWithTag("app_category_confirm").performClick()
        assertEquals(AppCategory.VIDEO, chosen)

        rule.onNodeWithTag("app_category").performClick()
        rule.onNodeWithTag("app_category_option_default").performClick()
        rule.onNodeWithTag("app_category_confirm").performClick()
        assertEquals(null, chosen)
        assertEquals(2, calls)
    }

    @Test
    fun anOverriddenCategoryStartsTheDialogOnIt() {
        show(app(category = AppCategory.SOCIAL, overridden = true))
        list.performScrollToNode(hasTestTag("app_category"))
        rule.onNodeWithTag("app_category").performClick()
        rule.onNodeWithTag("app_category_option_SOCIAL").assertIsSelected()
    }

    @Test
    fun aChildsOwnPhoneDoesNotOfferTheCategoryChange() {
        show(app(canChange = false))
        rule.onNodeWithTag("app_category_chip").assertExists()
        rule.onNodeWithTag("app_category").assertDoesNotExist()
    }

    @Test
    fun aWeekBarOpensThatDay() {
        var opened: LocalDate? = null
        show(app(), onOpenDay = { opened = it })
        list.performScrollToNode(hasTestTag("app_chart"))
        rule.onNodeWithTag("app_chart").assertExists()
        rule.onNodeWithTag("app_hours").assertDoesNotExist()
        rule.onAllNodes(hasClickAction() and hasContentDescription("Open ", substring = true))[5].performClick() // Fri
        rule.waitForIdle()
        assertEquals(today.minusDays(1), opened)
    }

    @Test
    fun aDayShowsHoursOrWhyNot() {
        val day = DetailRange(Period.DAY, today, today)
        show(app(range = day, hours = List(24) { if (it == 9) 20 * 60_000L else 0L }))
        list.performScrollToNode(hasTestTag("app_hours"))
        rule.onNodeWithTag("app_chart").assertDoesNotExist()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nine = hourTime(9, Locale.getDefault(), DateFormat.is24HourFormat(context))
        rule.onNode(hasContentDescription("Most time at $nine (20 min)")).assertExists()
    }

    @Test
    fun aDayPastRetentionSaysHoursAreUnavailable() {
        val old = today.minusDays(40)
        show(app(range = DetailRange(Period.DAY, old, old), hours = null))
        list.performScrollToNode(hasTestTag("app_hours_unavailable"))
        rule.onNodeWithText("Hourly data for this period is not available (it is kept for 30 days)").assertExists()
    }

    @Test
    fun aRecentDayWithoutDetailSaysHoursHaveNotArrived() {
        show(app(range = DetailRange(Period.DAY, today, today), hours = null))
        list.performScrollToNode(hasTestTag("app_hours_not_arrived"))
        rule.onNodeWithText("Hourly data for this period has not arrived yet").assertExists()
    }

    @Test
    fun theRemainderIsNamedOtherAppsAndHasNoHourBars() {
        val day = DetailRange(Period.DAY, today, today)
        show(app(range = day, hours = List(24) { 60_000L }, pkg = "~other", label = "~other", canChange = false))
        rule.onNodeWithTag("app_label").assertTextEquals("Other apps")
        rule.onNodeWithText("~other").assertDoesNotExist() // nor in the title
        list.performScrollToNode(hasTestTag("app_hours_remainder"))
        rule.onNodeWithTag("app_hours").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru")
    fun theRemainderIsNamedInRussian() {
        show(app(pkg = "~other", label = "~other", canChange = false))
        rule.onNodeWithTag("app_label").assertTextEquals("Другие приложения")
    }

    @Test
    fun allAppsNamesTheRemainder() {
        val detail = DeviceDetail(
            kid, weekRange, weekRange.dates().map { DayTotal(it, 0, 0) }, 0, 0,
            listOf(AppUsage("~other", "~other", null, 60_000)), emptyList(), Sharing.On,
        )
        rule.setContent { KinsightTheme(dynamicColor = false) { AllAppsScreen(AppList(detail.member, detail.range, detail.apps, null), onBack = {}) } }
        rule.onNodeWithText("Other apps").assertExists()
    }

    @Test
    fun allAppsRowsOpenTheApp() {
        val detail = DeviceDetail(
            kid, weekRange, weekRange.dates().map { DayTotal(it, 0, 0) }, 0, 0,
            listOf(AppUsage("com.example.game", "Game", null, 60_000)), emptyList(), Sharing.On,
        )
        var opened: String? = null
        rule.setContent { KinsightTheme(dynamicColor = false) { AllAppsScreen(AppList(detail.member, detail.range, detail.apps, null), onBack = {}, onOpenApp = { opened = it }) } }
        rule.onNodeWithTag("det_app_com.example.game").performClick()
        assertEquals("com.example.game", opened)
    }
}
