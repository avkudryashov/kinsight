package app.kinsight.feature.group

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LeaveError
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GroupScreenTest {
    @get:Rule
    val rule = createComposeRule()
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val dad = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val group = GroupId("dd".repeat(16))

    private fun show(
        self: MemberView,
        members: List<MemberView>,
        nowMs: Long = 0,
        leaving: Boolean = false,
        ownsStorage: Boolean = false,
        onGrantAdmin: (DeviceId) -> Unit = {},
        onRemove: (DeviceId) -> Unit = {},
        onLeave: () -> Unit = {},
    ) = rule.setContent {
        KinsightTheme() {
            GroupScreen(
                GroupState.Ready(group, self, members, selfOwnsStorage = ownsStorage),
                nowMs = nowMs,
                leaving = leaving,
                onInvite = {},
                onGrantAdmin = onGrantAdmin,
                onRemove = onRemove,
                onLeave = onLeave,
            )
        }
    }

    @Test
    fun memberSeenUnderAMinuteSaysJustNow() {
        val now = 10_000_000L
        show(mom, listOf(mom, dad.copy(lastSeenMs = now - 30_000), kid.copy(lastSeenMs = now + 5_000)), nowMs = now)
        rule.onAllNodesWithText("seen just now", substring = true).assertCountEquals(2)
        rule.onAllNodesWithText("0 minutes", substring = true).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianMemberSeenUnderAMinuteSaysTolkoChto() {
        val now = 10_000_000L
        show(mom, listOf(mom, dad.copy(lastSeenMs = now - 30_000)), nowMs = now)
        rule.onNodeWithText("на связи только что", substring = true).assertExists()
    }

    @Test
    fun memberSeenLongAgoStaysRelative() {
        val now = 10_000_000L
        show(mom, listOf(mom, dad.copy(lastSeenMs = now - 5 * 60_000)), nowMs = now)
        rule.onNodeWithText("seen 5 minutes ago", substring = true).assertExists()
    }

    @Test
    fun adminSeesInviteButtonsRolesAndTheSecondAdminHint() {
        show(mom, listOf(mom, dad, kid))
        rule.onNodeWithTag("grp_invite_child").assertExists()
        rule.onNodeWithTag("grp_invite_parent").assertExists()
        rule.onNodeWithText("Child", substring = true).assertExists()
        rule.onNodeWithTag("grp_second_admin").assertExists()
        rule.onNodeWithText("If this phone is lost", substring = true).assertExists()
    }

    @Test
    fun childSeesNoInviteAndNoActions() {
        val self = kid.copy(isSelf = true)
        show(self, listOf(self, mom.copy(isSelf = false)))
        rule.onNodeWithTag("grp_invite_child").assertDoesNotExist()
        rule.onNodeWithContentDescription("Actions for Mom").assertDoesNotExist()
        rule.onNodeWithTag("grp_second_admin").assertDoesNotExist()
    }

    @Test
    fun soleAdminCannotLeaveAndIsToldWhy() {
        show(mom, listOf(mom, dad, kid))
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").assertIsNotEnabled()
        rule.onNodeWithText("You are the only admin", substring = true).assertExists()
    }

    @Test
    fun removeAsksForConfirmation() {
        var removed: DeviceId? = null
        show(mom, listOf(mom, kid), onRemove = { removed = it })
        rule.onNodeWithContentDescription("Actions for Kid").performClick()
        rule.onNodeWithText("Remove from group").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("can still recognise your family's phones nearby", substring = true).assertExists()
        assertEquals(null, removed)
        rule.onNodeWithTag("grp_confirm").assertTextEquals("Remove").performClick()
        rule.waitForIdle()
        assertEquals(kid.id, removed)
    }

    @Test
    fun cancellingAConfirmationDoesNothing() {
        var removed: DeviceId? = null
        show(mom, listOf(mom, kid), onRemove = { removed = it })
        rule.onNodeWithContentDescription("Actions for Kid").performClick()
        rule.onNodeWithText("Remove from group").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("grp_confirm").assertDoesNotExist()
        assertEquals(null, removed)
    }

    @Test
    fun whileLeavingLeaveIsDisabledAndProgressShown() {
        val self = dad.copy(isSelf = true)
        show(self, listOf(self, mom.copy(isSelf = false)), leaving = true)
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").assertIsNotEnabled()
        rule.onNodeWithText("telling your family", substring = true).assertExists()
    }

    @Test
    fun theClockTicksEveryMinute() {
        var wall = 1_000L
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val now by rememberNowMs(clock = { wall })
            Text(now.toString(), Modifier.testTag("now"))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("now").assertTextEquals("1000")
        wall = 61_000L
        rule.mainClock.advanceTimeBy(30_000L)
        rule.onNodeWithTag("now").assertTextEquals("1000")
        rule.mainClock.advanceTimeBy(31_000L)
        rule.onNodeWithTag("now").assertTextEquals("61000")
    }

    @Test
    fun aChildIsNeverOfferedAdmin() {
        show(mom, listOf(mom, kid))
        rule.onNodeWithContentDescription("Actions for Kid").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Remove from group").assertExists()
        rule.onNodeWithText("Make admin").assertDoesNotExist()
    }

    @Test
    fun anotherParentCanBeMadeAdminAfterConfirming() {
        var granted: DeviceId? = null
        show(mom, listOf(mom, dad), onGrantAdmin = { granted = it })
        rule.onNodeWithContentDescription("Actions for Dad").performClick()
        rule.onNodeWithText("Make admin").performClick()
        rule.waitForIdle()
        assertEquals(null, granted)
        rule.onNodeWithTag("grp_confirm").assertTextEquals("Make admin").performClick()
        rule.waitForIdle()
        assertEquals(dad.id, granted)
    }

    @Test
    fun aMemberWhoMayLeaveConfirmsFirst() {
        var left = false
        val self = dad.copy(isSelf = true)
        show(self, listOf(self, mom.copy(isSelf = false)), onLeave = { left = true })
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Coming back needs a new invitation", substring = true).assertExists()
        rule.onNodeWithText("The group's data on this phone will be deleted", substring = true).assertExists()
        assertEquals(false, left)
        rule.onNodeWithTag("grp_confirm").assertTextEquals("Leave").performClick()
        rule.waitForIdle()
        assertTrue(left)
    }

    @Test
    fun aChildCannotLeaveFromItsOwnPhone() {
        val self = kid.copy(isSelf = true)
        show(self, listOf(self, mom.copy(isSelf = false)))
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave_child"))
        rule.onNodeWithTag("grp_leave").assertDoesNotExist()
        rule.onNodeWithText("A parent can remove this phone", substring = true).assertExists()
    }

    @Test
    fun lastSeenIsShownOrSaidToBeMissing() {
        show(mom, listOf(mom, dad.copy(lastSeenMs = 1_000L), kid), nowMs = 1_000L + 3 * 60 * 60_000L)
        rule.onNodeWithText("Parent · admin · this phone", substring = true).assertExists()
        rule.onNodeWithText("Parent · seen 3 hours ago", substring = true).assertExists()
        rule.onNodeWithText("Child · not seen yet", substring = true).assertExists()
    }

    @Test
    fun leavingShowsProgress() {
        rule.setContent { KinsightTheme() { LeavingContent() } }
        rule.onNodeWithTag("grp_leaving").assertExists()
        rule.onNodeWithText("telling your family", substring = true).assertExists()
    }

    @Test
    fun leaveErrorIsExplainedAndDismissed() {
        var dismissed = false
        rule.setContent {
            KinsightTheme() { GroupErrorDialog(GroupError.Leave(LeaveError.WIPE_FAILED), onDismiss = { dismissed = true }) }
        }
        rule.onNodeWithText("clearing this phone failed", substring = true).assertExists()
        rule.onNodeWithTag("grp_error_ok").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun everyErrorHasItsOwnMessage() = assertDistinctMessages(russian = false)

    @Test
    @Config(qualifiers = "ru")
    fun everyErrorHasItsOwnRussianMessage() = assertDistinctMessages(russian = true)

    private fun assertDistinctMessages(russian: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val errors = LeaveError.entries.map { GroupError.Leave(it) } + GroupError.GrantFailed + GroupError.RemoveFailed
        val texts = errors.map { context.getString(it.messageRes()) }
        assertEquals(errors.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
        assertTrue(texts.all { t -> t.any { it in 'а'..'я' } == russian })
    }

    @Test
    fun anOwnerAloneIsToldTheCloudTurnsOff() {
        show(mom, listOf(mom), ownsStorage = true)
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sync through Yandex Disk turns off", substring = true).assertExists()
    }

    @Test
    fun anOwnerWithAnotherAdminParentIsNotToldTheCloudTurnsOff() {
        // Spec §7.1: the role can go to Dad, so the leave is refused (OWNER_HANDOVER) instead.
        show(mom, listOf(mom, dad.copy(isAdmin = true)), ownsStorage = true)
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Everyone in the group will be told", substring = true).assertExists()
        rule.onNodeWithText("Sync through Yandex Disk turns off", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPhoneWithoutTheStorageIsNotToldTheCloudTurnsOff() {
        show(mom, listOf(mom), ownsStorage = false)
        rule.onNodeWithTag("grp_list").performScrollToNode(hasTestTag("grp_leave"))
        rule.onNodeWithTag("grp_leave").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Everyone in the group will be told", substring = true).assertExists()
        rule.onNodeWithText("Sync through Yandex Disk turns off", substring = true).assertDoesNotExist()
    }
}
