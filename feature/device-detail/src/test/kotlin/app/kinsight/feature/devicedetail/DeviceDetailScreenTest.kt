package app.kinsight.feature.devicedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.EventView
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
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
        kid, Period.WEEK, (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), 90 * 60_000L / 7, 1) },
        totalMs = 90 * 60_000L, unlocks = 7, apps = apps, events = listOf(event), sharing = Sharing.On,
    )

    private fun show(d: DeviceDetail, onAllApps: (Period) -> Unit = {}) = rule.setContent {
        KinsightTheme(dynamicColor = false) { DeviceDetailScreen(d, onPeriod = {}, onAllApps = onAllApps, onBack = {}) }
    }

    /** Every kind's line in the current locale, in Kind.entries order. */
    private fun allEventTexts(): List<String> {
        val texts = mutableListOf<String>()
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    StateEvent.Kind.entries.forEach { k -> Text(eventText(event.copy(kind = k, shiftMs = -300_000)).also { texts += it }) }
                }
            }
        }
        rule.waitForIdle()
        return texts.takeLast(StateEvent.Kind.entries.size)
    }

    @Test
    fun levelsOneAndTwoWithAllAppsLink() {
        var opened: Period? = null
        show(detail, onAllApps = { opened = it })
        rule.onNodeWithText("1 h 30 min").assertExists()
        rule.onNodeWithText("7 unlocks").assertExists()
        val list = rule.onNodeWithTag("det_list")
        list.performScrollToNode(hasText("App 5"))
        list.performScrollToNode(hasTestTag("det_all_apps"))
        rule.onNodeWithText("App 6").assertDoesNotExist()
        rule.onNodeWithTag("det_all_apps").performClick()
        rule.waitForIdle()
        assertEquals(Period.WEEK, opened)
        list.performScrollToNode(hasText("Sync was stopped for 1 h"))
    }

    @Test
    fun allAppsListsEveryApp() {
        rule.setContent { KinsightTheme(dynamicColor = false) { AllAppsScreen(detail, onBack = {}) } }
        rule.onNodeWithTag("det_apps").performScrollToNode(hasText("App 7"))
    }

    @Test
    fun everyEventKindHasItsOwnText() {
        val texts = allEventTexts()
        assertEquals(StateEvent.Kind.entries.size, texts.size)
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
        rule.onNode(hasContentDescription("больше всего — суббота (1 ч)", substring = true)).assertExists()
    }
}
