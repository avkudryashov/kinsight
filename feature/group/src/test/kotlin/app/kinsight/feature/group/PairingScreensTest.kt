package app.kinsight.feature.group

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.PairFailure
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.transport.ble.RadioProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PairingScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val now = 1_000_000L
    private val uri = "kinsight://pair/1/AAAA"

    private fun invite(
        state: InviteState,
        role: Role = Role.CHILD,
        onConfirm: () -> Unit = {},
        onDecline: () -> Unit = {},
        onRetry: () -> Unit = {},
        onClose: () -> Unit = {},
        confirming: Boolean = false,
    ) = rule.setContent {
        KinsightTheme(dynamicColor = false) { InviteScreen(state, role, now, onConfirm, onDecline, onRetry, onClose, confirming = confirming) }
    }

    private fun join(state: JoinState, onRetry: () -> Unit = {}, onBack: () -> Unit = {}) =
        rule.setContent { KinsightTheme(dynamicColor = false) { JoinScreen(state, "Mom", onRetry, onBack) } }

    @Test
    fun inviteShowsTheQrAndTheCountdown() {
        invite(InviteState.Showing(uri, now + 125_000))
        rule.onNodeWithContentDescription("Invitation QR code").assertExists()
        // The visible m:ss ticks every second; what a screen reader hears is the coarse, politely announced form.
        rule.onNodeWithTag("inv_countdown").assertContentDescriptionEquals("Expires within 3 minutes")
        rule.onNodeWithTag("inv_countdown").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("child", substring = true).assertExists()
    }

    @Test
    fun theCountdownIsAnnouncedOnlyAtMinuteBoundariesAndTheLastHalfMinute() {
        var left by mutableStateOf(125_000L)
        rule.setContent { KinsightTheme(dynamicColor = false) { InviteScreen(InviteState.Showing(uri, now + left), Role.CHILD, now, {}, {}, {}, {}) } }
        val countdown = rule.onNodeWithTag("inv_countdown")
        countdown.assertContentDescriptionEquals("Expires within 3 minutes")
        left = 121_000L
        rule.waitForIdle()
        countdown.assertContentDescriptionEquals("Expires within 3 minutes")
        left = 120_000L
        rule.waitForIdle()
        countdown.assertContentDescriptionEquals("Expires within 2 minutes")
        left = 31_000L
        rule.waitForIdle()
        countdown.assertContentDescriptionEquals("Expires within 1 minute")
        left = 30_000L
        rule.waitForIdle()
        countdown.assertContentDescriptionEquals("Expires in less than 30 seconds")
        left = 4_000L
        rule.waitForIdle()
        countdown.assertContentDescriptionEquals("Expires in less than 30 seconds")
    }

    @Test
    fun spokenMinutesChangeOnlyAtBoundaries() {
        assertEquals(listOf(3, 3, 2, 2, 1, 1, null, null), listOf(125L, 121L, 120L, 61L, 60L, 31L, 30L, 0L).map { spokenMinutes(it) })
    }

    @Test
    fun afterAddIsTappedTheAnswerButtonsWaitWithProgress() {
        invite(InviteState.Confirm(uri, now + 60_000, "Kid's phone", Role.CHILD), confirming = true)
        rule.onNodeWithTag("inv_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("inv_decline").assertIsNotEnabled()
        rule.onNodeWithTag("inv_adding").assertExists()
        rule.onNodeWithText("Adding", substring = true).assertExists()
    }

    @Test
    fun aRadioProblemIsShownWhileTheQrStays() {
        invite(InviteState.Showing(uri, now + 60_000, problem = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = 2)))
        rule.onNodeWithContentDescription("Invitation QR code").assertExists()
        rule.onNodeWithText("Bluetooth is busy", substring = true).assertExists()
        rule.onNodeWithText("advertising failed (2), retrying", substring = true).assertExists()
    }

    @Test
    fun aStandingRadioProblemSaysOtherPhonesMayNotFindThisOne() {
        invite(InviteState.Showing(uri, now + 60_000, problem = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, code = 3)))
        rule.onNodeWithContentDescription("Invitation QR code").assertExists()
        rule.onNodeWithText("may not find this phone", substring = true).assertExists()
        rule.onNodeWithText("advertising failed (3)", substring = true).assertExists()
    }

    @Test
    fun theRetryingTextFollowsTheTypedFlagNotTheWording() {
        // A problem that is not retried gets the standing line even when its technical cause mentions retrying.
        val standing = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = false, cause = "retrying exhausted")
        invite(InviteState.Showing(uri, now + 60_000, problem = standing))
        rule.onNodeWithText("may not find this phone", substring = true).assertExists()
        rule.onNodeWithText("Bluetooth is busy", substring = true).assertDoesNotExist()
    }

    @Test
    fun confirmationNamesTheDeviceAndItsRoleAndConfirms() {
        var confirmed = false
        invite(InviteState.Confirm(uri, now + 60_000, "Kid's phone", Role.CHILD), onConfirm = { confirmed = true })
        rule.onNodeWithText("Kid's phone", substring = true).assertExists()
        rule.onNodeWithText("as a child", substring = true).assertExists()
        rule.onNodeWithTag("inv_confirm").performClick()
        rule.waitForIdle()
        assertTrue(confirmed)
    }

    @Test
    fun confirmationCanBeDeclined() {
        var declined = false
        invite(InviteState.Confirm(uri, now + 60_000, "Dad", Role.PARENT), role = Role.PARENT, onDecline = { declined = true })
        rule.onNodeWithText("as a parent", substring = true).assertExists()
        rule.onNodeWithTag("inv_decline").performClick()
        rule.waitForIdle()
        assertTrue(declined)
    }

    @Test
    fun addedOffersDone() {
        var closed = false
        invite(InviteState.Added("Kid's phone"), onClose = { closed = true })
        rule.onNodeWithText("Kid's phone", substring = true).assertExists()
        rule.onNodeWithTag("inv_done").performClick()
        rule.waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun expiredOffersANewCode() {
        var retried = false
        invite(InviteState.Expired, onRetry = { retried = true })
        rule.onNodeWithText("expired", substring = true).assertExists()
        rule.onNodeWithTag("inv_retry").performClick()
        rule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun inviteFailureIsExplainedAndCanBeClosed() {
        var closed = false
        invite(InviteState.Failed(PairFailure.BLUETOOTH_OFF), onClose = { closed = true })
        rule.onNodeWithText("Turn on Bluetooth", substring = true).assertExists()
        rule.onNodeWithTag("inv_close").performClick()
        rule.waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun joinFailureOffersRetry() {
        var retried = false
        join(JoinState.Failed(PairFailure.NOT_FOUND), onRetry = { retried = true })
        rule.onNodeWithText("not found", substring = true).assertExists()
        rule.onNodeWithTag("join_retry").performClick()
        rule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun aUsedOrExpiredTokenReadsAsAnInvalidCode() {
        join(JoinState.Failed(PairFailure.BAD_TOKEN))
        rule.onNodeWithText("This QR code is no longer valid", substring = true).assertExists()
    }

    @Test
    fun aDeclineAlsoCoversAClosedInvitation() {
        join(JoinState.Failed(PairFailure.DECLINED))
        rule.onNodeWithText("The admin declined or closed the invitation.").assertExists()
    }

    @Test
    fun anInvalidInviteHintsAtUpdatingTheApp() {
        join(JoinState.Failed(PairFailure.INVALID))
        rule.onNodeWithText("update", substring = true).assertExists()
    }

    @Test
    fun searchingNamesTheAdminAndCanBeCancelled() {
        var back = false
        join(JoinState.Searching, onBack = { back = true })
        rule.onNodeWithText("Mom", substring = true).assertExists()
        rule.onNodeWithTag("join_cancel").performClick()
        rule.waitForIdle()
        assertTrue(back)
    }

    @Test
    fun connectingCanBeCancelled() {
        var back = false
        join(JoinState.Connecting, onBack = { back = true })
        rule.onNodeWithText("Connecting", substring = true).assertExists()
        rule.onNodeWithTag("join_cancel").performClick()
        rule.waitForIdle()
        assertTrue(back)
    }

    @Test
    fun waitingTextNamesTheAdminAndCanBeCancelled() {
        var back = false
        join(JoinState.Waiting("Mom"), onBack = { back = true })
        rule.onNodeWithText("Waiting for Mom", substring = true).assertExists()
        rule.onNodeWithTag("join_cancel").performClick()
        rule.waitForIdle()
        assertTrue(back)
    }

    @Test
    fun savingCannotBeCancelledHalfway() {
        join(JoinState.Saving)
        rule.onNodeWithText("Saving", substring = true).assertExists()
        rule.onNodeWithTag("join_cancel").assertDoesNotExist()
    }

    @Test
    fun joinedSaysSo() {
        join(JoinState.Joined)
        rule.onNodeWithText("You are in", substring = true).assertIsDisplayed()
    }

    @Test
    fun theScreenIsKeptOnAndBrightOnlyWhileTheQrIsShown() {
        val window = rule.activity.window
        val before = window.attributes.screenBrightness
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) KeepScreenBright() }
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, window.attributes.screenBrightness)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        shown = false
        rule.waitForIdle()
        assertEquals(before, window.attributes.screenBrightness)
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun confirmationShowsTheGuestsCodeToCompare() {
        invite(InviteState.Confirm(uri, now + 60_000, "Kid's phone", Role.CHILD, code = "012345"))
        rule.onNodeWithTag("inv_code").assertTextContains("012 345", substring = true)
        rule.onNodeWithText("same digits", substring = true).assertExists()
    }

    @Test
    fun waitingShowsTheCodeTheAdminCompares() {
        join(JoinState.Waiting("Mom", code = "012345"))
        rule.onNodeWithTag("join_code").assertTextContains("012 345", substring = true)
    }

    @Test
    fun confirmationWithoutACodeDoesNotPretendToCompare() {
        invite(InviteState.Confirm(uri, now + 60_000, "Kid's phone", Role.CHILD, code = ""))
        rule.onNodeWithTag("inv_code").assertDoesNotExist()
        rule.onNodeWithText("same digits", substring = true).assertDoesNotExist()
        rule.onNodeWithText("could not be shown", substring = true).assertExists()
    }

    @Test
    fun waitingWithoutACodeDoesNotPretendToCompare() {
        join(JoinState.Waiting("Mom", code = ""))
        rule.onNodeWithTag("join_code").assertDoesNotExist()
        rule.onNodeWithText("same code", substring = true).assertDoesNotExist()
    }
}
