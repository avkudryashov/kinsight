package app.kinsight.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.ChildLockStatus
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.PinSave
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SetupItem
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.usage.UsageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MoreScreenTest {
    @get:Rule
    val rule = createComposeRule()
    private val group = GroupId("dd".repeat(16))
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = 1L)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = true, lastSeenMs = null)
    private val parent = mom.copy(isSelf = true)
    private val running = ServiceStatus(ServiceState.RUNNING, false, null, emptyList(), false, null)
    private val stopped = ServiceStatus(ServiceState.STOPPED, true, null, emptyList(), false, null)
    private val calls = mutableListOf<String>()

    private fun show(ui: SettingsUi, pending: List<SetupItem> = emptyList(), onSetPin: (String) -> Unit = {}) = rule.setContent {
        KinsightTheme {
            MoreScreen(
                ui, "0.9.0", pending,
                onFamily = { calls += "family" }, onLimits = { calls += "limits" }, onSync = { calls += "sync" },
                onTransparency = { calls += "transparency" }, onAbout = { calls += "about" }, onSetup = { calls += "setup" },
                onSetPin = onSetPin,
            )
        }
    }

    private fun parentUi(service: ServiceStatus = running, lock: LockUi = LockUi(ChildLockStatus(canSetPin = true))) =
        SettingsUi(GroupState.Ready(group, parent, listOf(parent, kid.copy(isSelf = false))), UsageSettings(), Prefs(), service, lock = lock)

    private val consentingChild = SettingsUi(
        GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, ownSharing = Sharing.On,
    )

    @Test
    fun aParentSeesEveryRow() {
        show(parentUi())
        for (tag in listOf("more_family", "more_limits", "more_sync", "more_pin", "more_transparency", "more_about")) {
            rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag(tag))
        }
        rule.onNodeWithTag("more_setup").assertDoesNotExist()
        rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_limits"))
        rule.onNodeWithTag("more_limits").performClick()
        rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_transparency"))
        rule.onNodeWithTag("more_transparency").performClick()
        assertEquals(listOf("limits", "transparency"), calls)
    }

    @Test
    fun theSyncRowSaysAllGoodOrNamesTheProblem() {
        show(parentUi())
        rule.onNodeWithText("All good", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aStoppedSyncIsNamedOnTheRow() {
        show(parentUi(service = stopped))
        rule.onNodeWithText("All good", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aChildHasNoLimitsNorPinButItsUnfinishedSetupFirst() {
        show(consentingChild, pending = listOf(SetupItem.LOCATION, SetupItem.BATTERY))
        rule.onNodeWithText("Setup is not finished", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Location, background work", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("more_limits").assertDoesNotExist()
        rule.onNodeWithTag("more_pin").assertDoesNotExist()
        rule.onNodeWithTag("more_setup").performClick()
        assertEquals(listOf("setup"), calls)
    }

    @Test
    fun aChildWithEverythingGrantedOrNoConsentHasNoSetupRow() {
        show(consentingChild.copy(usage = UsageSettings(), ownSharing = Sharing.Never), pending = listOf(SetupItem.LOCATION))
        rule.onNodeWithTag("more_setup").assertDoesNotExist()
    }

    @Test
    fun whileTheGroupLoadsNoAdultRowIsShown() {
        show(SettingsUi(GroupState.Loading, UsageSettings(), Prefs(), running, lock = LockUi(ChildLockStatus(canSetPin = true))))
        rule.onNodeWithTag("more_limits").assertDoesNotExist()
        rule.onNodeWithTag("more_pin").assertDoesNotExist()
        rule.onNodeWithTag("more_family").assertExists()
    }

    @Test
    fun anAdminParentSetsThePinTypedTwice() {
        val set = mutableListOf<String>()
        show(parentUi(), onSetPin = { set += it })
        rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_pin"))
        rule.onNodeWithText("Not set", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("more_pin").performClick()
        rule.onNodeWithTag("set_pin_new").performTextInput("2468")
        rule.onNodeWithTag("set_pin_repeat").performTextInput("2469")
        rule.onNodeWithTag("set_pin_save").assertIsNotEnabled()
        rule.onNodeWithTag("set_pin_repeat").performTextClearance()
        rule.onNodeWithTag("set_pin_repeat").performTextInput("2468")
        rule.onNodeWithTag("set_pin_save").assertIsEnabled().performClick()
        assertEquals(listOf("2468"), set)
    }

    @Test
    fun thePinRowCaptionIsAShortStatus() {
        show(parentUi(lock = LockUi(ChildLockStatus(canSetPin = true, pinSet = true))))
        rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_pin"))
        rule.onNodeWithText("Set", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aParentWhoIsNotAnAdminHasNoPinRow() {
        show(parentUi(lock = LockUi()))
        rule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_about"))
        rule.onNodeWithTag("more_pin").assertDoesNotExist()
    }

    /** Rulings P6: a PIN that could not be saved is said, in red, under the row. */
    @Test
    fun aPinThatCouldNotBeSavedIsSaid() {
        show(parentUi(lock = LockUi(ChildLockStatus(canSetPin = true), pinSaved = PinSave.FAILED)))
        rule.onNodeWithTag("more_list").performScrollToNode(hasText("Could not save the PIN."))
    }

    @Test
    fun aboutShowsTheVersionAndOpensThePrivacyPolicy() {
        var opened = false
        rule.setContent { KinsightTheme { AboutScreen("0.9.0", onPrivacyPolicy = { opened = true }, onLanguage = null, onBack = {}) } }
        rule.onNodeWithTag("about_version").assertTextEquals("Kinsight 0.9.0 · 0+")
        rule.onNodeWithTag("set_privacy").assertTextEquals("Privacy policy").performClick()
        assertTrue(opened)
    }

    @Test
    fun theLimitsScreenListsTheChildrenAndOpensOne() {
        var opened: DeviceId? = null
        rule.setContent { KinsightTheme { LimitsNotificationsScreen(parentUi(), onChild = { opened = it }, onSummaryMode = {}, onNoContactHours = {}, onBack = {}) } }
        rule.onNodeWithTag("lim_child_${kid.id.hex}").performClick()
        assertEquals(kid.id, opened)
    }

    private fun showLimitsScaled(fontScale: Float, onSummaryMode: (SummaryMode) -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            KinsightTheme { LimitsNotificationsScreen(parentUi(), onChild = {}, onSummaryMode = onSummaryMode, onNoContactHours = {}, onBack = {}) }
        }
    }

    @Test
    fun largeFontsTurnTheChoicesIntoAVerticalList() {
        var picked: SummaryMode? = null
        showLimitsScaled(2f) { picked = it }
        rule.onNodeWithTag("lim_list").performScrollToNode(hasTestTag("set_mode_weekly"))
        rule.onNodeWithTag("set_mode_list").assertExists()
        rule.onNodeWithTag("set_mode_weekly").performClick()
        assertEquals(SummaryMode.WEEKLY, picked)
    }

    @Test
    fun normalFontsKeepTheSegmentedRow() {
        showLimitsScaled(1f) {}
        rule.onNodeWithTag("lim_list").performScrollToNode(hasTestTag("set_mode_weekly"))
        rule.onNodeWithTag("set_mode_list").assertDoesNotExist()
    }

    /** Offline map spec §4: the full list of licenses in «Ещё → О приложении». */
    @Test
    fun aboutShowsTheOpenSourceLicenses() {
        rule.setContent { KinsightTheme { AboutScreen("0.9.0", onPrivacyPolicy = {}, onLanguage = null, onBack = {}) } }
        rule.onNodeWithTag("about_licenses").assertTextEquals("Open source licenses").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("about_licenses_text")).fetchSemanticsNodes().isNotEmpty() }
        // The list is lazy: scroll to each notice, then it is on screen.
        for (needle in listOf("© OpenStreetMap contributors", "MapLibre Native Android", "SIL OPEN FONT LICENSE", "MIT License")) {
            rule.onNodeWithTag("about_licenses_text").performScrollToNode(hasText(needle, substring = true, ignoreCase = true))
            rule.onAllNodes(hasText(needle, substring = true, ignoreCase = true)).onFirst().assertExists()
        }
    }
}
