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
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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

    private val saved = mutableListOf<ThresholdRule>()
    private val deleted = mutableListOf<ThresholdRule>()
    private val undone = mutableListOf<String>()
    private val committed = mutableListOf<String>()
    private val offered = mutableListOf<String>()

    /** Like the view model: a delete is pending until «Undo» or the end of its window. */
    private fun show(rules: List<ThresholdRule>, canEdit: Boolean = true, pendingAtStart: Set<String> = emptySet()) = rule.setContent {
        var pending by remember { mutableStateOf(pendingAtStart) }
        KinsightTheme() {
            ThresholdsScreen(
                ThresholdsState("Kid", canEdit, rules, apps, child = kid),
                pending = pending,
                onSave = { saved += it },
                onDelete = { deleted += it; pending = pending + it.id },
                onUndo = { undone += it; pending = pending - it },
                onCommitDelete = { committed += it; pending = pending - it },
                onOffered = { offered += it },
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
        rule.onNodeWithContentDescription("Delete rule: Total per day").assertExists()
    }

    @Test
    fun deletingOffersUndo() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_delete_${games.id}").performClick()
        assertEquals(listOf(games), deleted)
        rule.onNodeWithText("Rule removed").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf(games.id), undone)
        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun aDeleteIsCommittedWhenTheSnackbarTimesOut() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_delete_${games.id}").performClick()
        rule.onNodeWithText("Rule removed").assertExists()
        assertEquals(emptyList<String>(), committed)
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        rule.onNodeWithText("Rule removed").assertDoesNotExist()
        assertEquals(listOf(games.id), committed)
        assertEquals(emptyList<String>(), undone)
    }

    /** Re-review item 4: a second delete waits for the first snackbar; its window starts when its own appears. */
    @Test
    fun aQueuedDeleteIsOfferedWhenItsSnackbarAppears() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_delete_${games.id}").performClick()
        rule.waitForIdle()
        assertEquals(listOf(games.id), offered)
        rule.onNodeWithTag("thr_delete_${total.id}").performClick()
        rule.waitForIdle()
        assertEquals("queued behind the first snackbar", listOf(games.id), offered)
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        assertEquals(listOf(games.id, total.id), offered)
    }

    /** Final fix item 5: a delete still pending after a recreation offers its «Undo» again, then commits. */
    @Test
    fun aPendingDeleteIsOfferedAgainAfterRecreation() {
        show(listOf(total), pendingAtStart = setOf(games.id))
        rule.onNodeWithText("Rule removed").assertExists()
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        assertEquals(listOf(games.id), committed)
    }

    @Test
    fun addsACategoryLimitInSteps() {
        show(emptyList())
        rule.onNodeWithText("No rules yet").assertExists()
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_save").assertIsNotEnabled() // nothing picked yet
        pickKind("thr_kind_category")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_category_GAMES"))
        rule.onNodeWithTag("thr_category_GAMES").performClick()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_value"))
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_plus"))
        rule.onNodeWithTag("thr_plus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_value"))
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h 15 min")
        rule.onNodeWithTag("thr_save").assertIsEnabled().performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), hour + q)!!), saved)
        rule.onNodeWithTag("thr_save").assertDoesNotExist()
    }

    @Test
    fun addsATotalLimit() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        pickKind("thr_kind_total")
        rule.onNodeWithTag("thr_minus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Total, hour - q)!!), saved)
    }

    @Test
    fun picksAnAppOfTheLast30DaysBySearch() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        pickKind("thr_kind_app")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_app_com.example.video"))
        rule.onNodeWithTag("thr_app_com.example.video").assertExists()
        rule.onNodeWithTag("thr_search").performTextInput("gam")
        rule.onNodeWithTag("thr_app_com.example.video").assertDoesNotExist()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_app_com.example.game"))
        rule.onNodeWithTag("thr_app_com.example.game").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.App("com.example.game"), hour)!!), saved)
    }

    @Test
    fun anExistingTargetStartsFromItsLimitAnotherFromTheDefault() {
        show(listOf(ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), 90 * 60_000L)!!))
        rule.onNodeWithTag("thr_add").performClick()
        pickKind("thr_kind_category")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_category_GAMES"))
        rule.onNodeWithTag("thr_category_GAMES").performClick()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_value"))
        rule.onNodeWithTag("thr_value").assertTextEquals("1 h 30 min")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_category_VIDEO"))
        rule.onNodeWithTag("thr_category_VIDEO").performClick()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_value"))
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
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Total, 3 * hour - q)!!), saved)
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
        rule.onNodeWithTag("thr_cancel").performClick()
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
        rule.onNodeWithTag("thr_total_set").assertDoesNotExist()
        rule.onNodeWithTag("thr_delete_${total.id}").assertDoesNotExist()
    }

    @Test
    fun theDayTotalIsACardWithTodaysBar() {
        rule.setContent {
            KinsightTheme {
                ThresholdsScreen(
                    ThresholdsState("Kid", true, listOf(total, games), apps, todayMs = 2 * hour, child = kid),
                    pending = emptySet(), onSave = { saved += it }, onDelete = {}, onUndo = {}, onCommitDelete = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithTag("thr_total").assertExists()
        rule.onNodeWithText("Today 2 h").assertExists()
        rule.onNodeWithTag("limit_bar_text", useUnmergedTree = true).assertTextEquals("1 h left of 3 h")
        rule.onNodeWithTag("thr_list").performScrollToNode(hasTestTag("thr_section_limits"))
        rule.onNodeWithTag("thr_section_limits").assertExists()
        rule.onNodeWithTag("thr_subtitle").assertTextEquals("Limits notify once a day. Blocks work on the child's phone while Kinsight's accessibility service is on there.")
    }

    @Test
    fun withoutADayTotalTheCardInvitesToSetOne() {
        show(listOf(games))
        rule.onNodeWithText("No limit for the whole day yet").assertExists()
        rule.onNodeWithTag("thr_total_set").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Total, ThresholdValues.DEFAULT_MS)!!), saved)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun theLimitDialogIsFullScreen() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_dialog").assertWidthIsEqualTo(360.dp)
        rule.onNodeWithTag("thr_cancel").performClick()
        rule.onNodeWithTag("thr_dialog").assertDoesNotExist()
        assertEquals(emptyList<ThresholdRule>(), saved)
    }

    @Test
    fun aPresetSetsTheLimitInOneTap() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        pickKind("thr_kind_total")
        rule.onNodeWithTag("thr_preset_120").performScrollTo().performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("2 h")
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Total, 2 * hour)!!), saved)
    }

    @Test
    fun holdingPlusRepeatsTheStepAndTheReleaseAddsNothing() {
        show(listOf(total)) // 3 h
        rule.onNodeWithTag("thr_rule_${total.id}").performClick()
        rule.onNodeWithTag("thr_plus").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_DELAY_MS + 2 * HOLD_REPEAT_MS + 50) // steps at 400, 500 and 600 ms
        rule.onNodeWithTag("thr_plus").performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag("thr_value").assertTextEquals("3 h 45 min")
        // Released: no further steps, however long the clock runs.
        rule.mainClock.advanceTimeBy(10 * HOLD_REPEAT_MS + HOLD_DELAY_MS)
        rule.waitForIdle()
        rule.onNodeWithTag("thr_value").assertTextEquals("3 h 45 min")
    }

    @Test
    fun aTapStillStepsOnce() {
        show(listOf(total))
        rule.onNodeWithTag("thr_rule_${total.id}").performClick()
        rule.onNodeWithTag("thr_minus").performClick()
        rule.onNodeWithTag("thr_value").assertTextEquals("2 h 45 min")
    }

    /** The target choices follow the mode step and the value picker in the dialog's list: scroll to one first. */
    private fun pickKind(tag: String) {
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }

    private val blocked = ThresholdRule.block(kid, ThresholdTarget.App("com.example.video"))!!
    private val allowed = ThresholdRule.allow(kid, "com.example.game")!!

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test
    @Config(qualifiers = "w360dp-h1200dp")
    fun listsRulesInTheirSections() {
        show(listOf(total, games, blocked, allowed))
        val order = listOf("thr_section_blocked", "thr_rule_${blocked.id}", "thr_section_limits", "thr_rule_${games.id}", "thr_section_allowed", "thr_rule_${allowed.id}")
        val ys = order.map(::top)
        assertEquals(ys.sorted(), ys)
        rule.onNodeWithText("Blocked").assertExists()
        rule.onNodeWithText("Always allowed").assertExists()
        rule.onNodeWithTag("thr_pill_blocked", useUnmergedTree = true).assertTextEquals("blocked")
        rule.onNodeWithTag("thr_pill_allowed", useUnmergedTree = true).assertTextEquals("always")
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp")
    fun anEmptySectionIsNotDrawn() {
        show(listOf(total, games))
        rule.onNodeWithTag("thr_section_limits").assertExists()
        rule.onNodeWithTag("thr_section_blocked").assertDoesNotExist()
        rule.onNodeWithTag("thr_section_allowed").assertDoesNotExist()
        rule.onNodeWithText("No rules yet").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp")
    fun aLimitThatBlocksAlsoShowsTheBlockPill() {
        val strict = ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), hour, blockOnLimit = true)!!
        show(listOf(strict))
        rule.onNodeWithText("1 h").assertExists()
        rule.onNodeWithText("block").assertExists()
    }

    @Test
    fun addRuleAsksTheModeFirst() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_mode_limit").assertExists()
        rule.onNodeWithTag("thr_mode_block").assertExists()
        rule.onNodeWithTag("thr_mode_allow").assertExists()
        rule.onNodeWithTag("thr_mode_allow").performClick()
        rule.onNodeWithTag("thr_kind_total").assertDoesNotExist()
        rule.onNodeWithTag("thr_kind_category").assertDoesNotExist()
        rule.onNodeWithText("An always-allowed app opens even when its category or the day is blocked.").assertExists()
    }

    @Test
    fun blockingAnAppSavesABlockRule() {
        show(listOf(games))
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_mode_block").performClick()
        rule.onNodeWithTag("thr_kind_total").assertDoesNotExist() // the day total is only limited
        rule.onNodeWithTag("thr_value").assertDoesNotExist()
        pickKind("thr_kind_app")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_app_com.example.video"))
        rule.onNodeWithTag("thr_app_com.example.video").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(blocked), saved)
    }

    @Test
    fun allowingAnAppSavesAnAllowRule() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        rule.onNodeWithTag("thr_mode_allow").performClick()
        rule.onNodeWithTag("thr_save").assertIsNotEnabled()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_app_com.example.game"))
        rule.onNodeWithTag("thr_app_com.example.game").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(allowed), saved)
    }

    @Test
    fun theCheckboxMakesACategoryLimitBlock() {
        show(emptyList())
        rule.onNodeWithTag("thr_add").performClick()
        pickKind("thr_kind_category")
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_category_GAMES"))
        rule.onNodeWithTag("thr_category_GAMES").performClick()
        rule.onNodeWithTag("thr_dialog_list").performScrollToNode(hasTestTag("thr_block_on_limit"))
        rule.onNodeWithTag("thr_block_on_limit").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, ThresholdTarget.Category(AppCategory.GAMES), hour, blockOnLimit = true)!!), saved)
    }

    @Test
    fun editingABlockStartsOnItsModeAndCanTurnItIntoALimit() {
        show(listOf(blocked))
        rule.onNodeWithTag("thr_rule_${blocked.id}").performClick()
        rule.onNodeWithTag("thr_value").assertDoesNotExist()
        rule.onNodeWithTag("thr_mode_limit").performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(ThresholdRule.of(kid, blocked.target, hour)!!), saved) // one rule per target: same id, a default limit
    }

    @Test
    fun theTotalCardSwitchSavesTheRuleWithBlockOnLimit() {
        show(listOf(total))
        rule.onNodeWithTag("thr_total_block_on_limit").performClick()
        assertEquals(listOf(total.copy(blockOnLimit = true)), saved)
    }

    @Test
    fun editingTheTotalKeepsItsBlockOnLimit() {
        val strict = total.copy(blockOnLimit = true)
        show(listOf(strict))
        rule.onNodeWithTag("thr_rule_${strict.id}").performClick()
        rule.onNodeWithTag("thr_block_on_limit").assertDoesNotExist() // the total has its own switch
        rule.onNodeWithTag("thr_minus").performScrollTo().performClick()
        rule.onNodeWithTag("thr_save").performClick()
        assertEquals(listOf(strict.copy(limitMs = 3 * hour - q)), saved)
    }

    private fun showFlags(off: Boolean, consent: Boolean) = rule.setContent {
        KinsightTheme {
            ThresholdsScreen(
                ThresholdsState("Kid", true, listOf(total), apps, child = kid, blockingOff = off, awaitingConsent = consent),
                pending = emptySet(), onSave = {}, onDelete = {}, onUndo = {}, onCommitDelete = {}, onBack = {},
            )
        }
    }

    @Test
    fun blockingOffShowsTheWarning() {
        showFlags(off = true, consent = false)
        rule.onNodeWithText("Blocks are not enforced: the accessibility service is off on the child's phone.").assertExists()
        rule.onNodeWithTag("thr_awaiting_consent").assertDoesNotExist()
    }

    @Test
    fun awaitingConsentShowsItsNote() {
        showFlags(off = false, consent = true)
        rule.onNodeWithText("Blocks start once the child agrees to the updated consent on their phone.").assertExists()
        rule.onNodeWithTag("thr_blocking_off").assertDoesNotExist()
    }
}
