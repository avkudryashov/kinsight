package app.kinsight.feature.devicedetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThresholdsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val kid = DeviceId("cc".repeat(16))
    private val hour = 3_600_000L
    private val q = 15 * 60_000L
    private val total = ThresholdRule.of(kid, ThresholdTarget.Total, 3 * hour)!!
    private val games = ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), hour)!!
    private val game = ThresholdRule.of(kid, ThresholdTarget.App("com.example.game"), 90 * 60_000L)!!
    private val apps = listOf(AppUsage("com.example.video", "Video", null, 5 * hour), AppUsage("com.example.game", "Game", null, 2 * hour))

    private val saved = mutableListOf<Pair<ThresholdTarget, Long>>()
    private val deleted = mutableListOf<ThresholdRule>()
    private val undone = mutableListOf<String>()
    private val committed = mutableListOf<String>()

    /** Like the view model: a delete is pending until «Undo» or the end of its window. */
    private fun show(rules: List<ThresholdRule>, canEdit: Boolean = true, pendingAtStart: Set<String> = emptySet()) = rule.setContent {
        var pending by remember { mutableStateOf(pendingAtStart) }
        KinsightTheme(dynamicColor = false) {
            ThresholdsScreen(
                ThresholdsState("Kid", canEdit, rules, apps),
                pending = pending,
                onSave = { t, ms -> saved += t to ms },
                onDelete = { deleted += it; pending = pending + it.id },
                onUndo = { undone += it; pending = pending - it },
                onCommitDelete = { committed += it; pending = pending - it },
                onBack = {},
            )
        }
    }

    @Test
    fun listsEachRuleWithItsTargetAndLimit() {
        show(listOf(total, games, game))
        rule.onNodeWithTag("thr_list").assertExists()
        rule.onNodeWithText("Total per day").assertExists()
        rule.onNodeWithText("3 h").assertExists()
        rule.onNodeWithText("Games").assertExists()
        rule.onNodeWithText("1 h").assertExists()
        rule.onNodeWithText("Game").assertExists() // the app's label, not its package
        rule.onNodeWithText("1 h 30 min").assertExists()
        rule.onNodeWithTag("thr_delete_${total.id}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Delete limit: Total per day").assertExists()
    }

    @Test
    fun deletingOffersUndo() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_delete_${games.id}").performClick()
        assertEquals(listOf(games), deleted)
        rule.onNodeWithText("Limit removed").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf(games.id), undone)
        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun aDeleteIsCommittedWhenTheSnackbarTimesOut() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_delete_${games.id}").performClick()
        rule.onNodeWithText("Limit removed").assertExists()
        assertEquals(emptyList<String>(), committed)
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        rule.onNodeWithText("Limit removed").assertDoesNotExist()
        assertEquals(listOf(games.id), committed)
        assertEquals(emptyList<String>(), undone)
    }

    /** Final fix item 5: a delete still pending after a recreation offers its «Undo» again, then commits. */
    @Test
    fun aPendingDeleteIsOfferedAgainAfterRecreation() {
        show(listOf(total), pendingAtStart = setOf(games.id))
        rule.onNodeWithText("Limit removed").assertExists()
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        assertEquals(listOf(games.id), committed)
    }

    @Test
    fun addsACategoryLimitInSteps() {
        show(emptyList())
        rule.onNodeWithText("No limits yet").assertExists()
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_save").assertIsNotEnabled() // nothing picked yet
        rule.onNodeWithTag("thr_kind_category").performClick()
        rule.onNodeWithTag("thr_category_GAMES").performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h")
        rule.onNodeWithTag("thr_plus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h 15 min")
        rule.onNodeWithTag("thr_save").assertIsEnabled().performClick()
        assertEquals(listOf(ThresholdTarget.Category(AppCategory.GAMES) to hour + q), saved)
        rule.onNodeWithTag("thr_save").assertDoesNotExist()
    }

    @Test
    fun addsATotalLimit() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_kind_total").performClick()
        rule.onNodeWithTag("thr_minus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdTarget.Total to hour - q), saved)
    }

    @Test
    fun picksAnAppOfTheLast30DaysBySearch() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_kind_app").performClick()
        rule.onNodeWithTag("thr_app_com.example.video").assertExists()
        rule.onNodeWithTag("thr_search").performTextInput("gam")
        rule.onNodeWithTag("thr_app_com.example.video").assertDoesNotExist()
        rule.onNodeWithTag("thr_app_com.example.game").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdTarget.App("com.example.game") to hour), saved)
    }

    @Test
    fun anExistingTargetStartsFromItsLimitAnotherFromTheDefault() {
        show(listOf(ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), 90 * 60_000L)!!))
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_kind_category").performClick()
        rule.onNodeWithTag("thr_category_GAMES").performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h 30 min")
        rule.onNodeWithTag("thr_category_VIDEO").performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h")
    }

    @Test
    fun tappingARuleEditsItsLimit() {
        show(listOf(total))
        rule.onNodeWithTag("thr_rule_${total.id}").performClick()
        rule.onNodeWithTag("thr_kind_category").assertDoesNotExist() // the target stays
        rule.onNodeWithTag("thr_value").assertTextEquals("3 h")
        rule.onNodeWithTag("thr_minus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdTarget.Total to 3 * hour - q), saved)
    }

    @Test
    fun theStepButtonsStopAtTheBounds() {
        val max = ThresholdRule.of(kid, ThresholdTarget.Total, 12 * hour)!!
        val min = ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.VIDEO), q)!!
        show(listOf(max, min))
        rule.onNodeWithTag("thr_rule_${max.id}").performClick()
        rule.onNodeWithTag("thr_plus").assertIsNotEnabled()
        rule.onNodeWithTag("thr_minus").assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("15 minutes more").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("thr_list").performScrollToNode(hasTestTag("thr_rule_${min.id}"))
        rule.onNodeWithTag("thr_rule_${min.id}").performClick()
        rule.onNodeWithTag("thr_minus").assertIsNotEnabled()
        rule.onNodeWithTag("thr_plus").assertIsEnabled()
    }

    @Test
    fun aChildsPhoneOnlyReadsThatParentsSetLimits() {
        show(listOf(total), canEdit = false)
        rule.onNodeWithText("Only a parent can set limits.").assertExists()
        rule.onNodeWithTag("thr_add").assertDoesNotExist()
        rule.onNodeWithTag("thr_delete_${total.id}").assertDoesNotExist()
    }
}
