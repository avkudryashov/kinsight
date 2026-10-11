package app.kinsight.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.kinsight.core.data.SummaryMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.usage.UsageSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsScreenTest {
    private companion object {
        const val NOW = 1_760_000_000_000L
    }

    @get:Rule
    val rule = createComposeRule()
    private val group = GroupId("dd".repeat(16))
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = 1L)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = true, lastSeenMs = null)
    private val running = ServiceStatus(ServiceState.RUNNING, false, null, emptyList(), false, null)
    private val stopped = ServiceStatus(ServiceState.STOPPED, true, null, emptyList(), false, null)
    private val parent = mom.copy(isSelf = true)
    private val calls = mutableListOf<String>()

    private fun show(
        ui: SettingsUi,
        onSharing: (Boolean) -> Unit = {},
        onReset: () -> Unit = {},
        onConsent: () -> Unit = {},
        onRetention: (Int, Int) -> Unit = { _, _ -> },
        onPrivacyPolicy: () -> Unit = {},
        onTransparency: () -> Unit = {},
        nowMs: Long = NOW,
    ) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            SettingsScreen(
                ui, "0.4.0", onSharing = onSharing, onConsent = onConsent, onTransparency = onTransparency,
                onStopSync = { calls += "stop" }, onStartSync = { calls += "start" },
                onRetention = onRetention, onSummaryMode = {}, onNoContactHours = {}, onBattery = {}, onDiagnostics = {},
                onResetIdentity = onReset, onSyncNow = { calls += "syncNow" }, onPrivacyPolicy = onPrivacyPolicy, nowMs = nowMs,
            )
        }
    }

    @Test
    fun thePrivacyPolicyRowOpensThePolicy() {
        var opened = false
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running), onPrivacyPolicy = { opened = true })
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_privacy"))
        rule.onNodeWithTag("set_privacy").assertTextEquals("Privacy policy").performClick()
        assertTrue(opened)
    }

    // The sync section is the first section: its nodes exist without scrolling the list.
    @Test
    fun theSyncSectionTopsSettingsAndSyncsNow() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running.copy(lastSyncMs = NOW - 5 * 60_000)))
        rule.onNodeWithText("Sync on this phone").assertExists()
        rule.onNodeWithText("Last sync: 5 minutes ago").assertExists()
        rule.onNodeWithTag("set_stop").assertExists()
        rule.onNodeWithTag("set_sync_now").performClick()
        assertEquals(listOf("syncNow"), calls)
    }

    @Test
    fun aSyncMomentsAgoOrSlightlyAheadOfTheClockReadsJustNow() {
        // Emulator run 2026-10-10: «Last sync In 0 minutes» (the stamp a little ahead of the screen's clock).
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running.copy(lastSyncMs = NOW + 2_000)))
        rule.onNodeWithText("Last sync: just now").assertExists()
        rule.onNodeWithText("In ", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru")
    fun justNowInRussian() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running.copy(lastSyncMs = NOW)))
        rule.onNodeWithText("Последняя синхронизация: только что").assertExists()
    }

    @Test
    fun theSyncSectionNamesAProblem() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running.copy(state = ServiceState.BLUETOOTH_OFF)))
        rule.onNodeWithText("Bluetooth is off", substring = true).assertExists()
    }

    @Test
    fun aParentsSyncSectionSaysItsOwnTimeIsNotShared() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_sync_section").assertExists()
        rule.onNode(hasTestTag("set_sync_section") and hasAnyDescendant(hasText("is not shared", substring = true))).assertExists()
    }

    @Test
    fun aStoppedSyncOffersStartInsteadOfSyncNow() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), stopped))
        rule.onNodeWithTag("set_sync_now").assertDoesNotExist()
        rule.onNodeWithTag("set_start").assertExists()
    }

    @Test
    fun childTurnsSharingOffOnlyAfterTheBackfillNotice() {
        var shared: Boolean? = null
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running), onSharing = { shared = it })
        rule.onNodeWithText("your parents are told", substring = true).assertExists()
        rule.onNodeWithTag("set_share").performClick()
        assertEquals(null, shared)
        rule.onNodeWithText("sent when you resume", substring = true).assertExists()
        rule.onNodeWithTag("set_pause_confirm").performClick()
        assertEquals(false, shared)
    }

    @Test
    fun childWithoutConsentIsSentToTheConsentScreen() {
        var asked = false
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = false), Prefs(), running), onConsent = { asked = true })
        rule.onNodeWithTag("set_share").performClick()
        assertTrue(asked)
    }

    @Test
    fun parentSeesSummariesAndNoShareSwitch() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_share").assertDoesNotExist()
        rule.onNodeWithText("is not shared", substring = true).assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasText("Daily"))
    }

    @Test
    fun resetAsksFirstAndNeedsAnAcknowledgement() {
        var reset = false
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running), onReset = { reset = true })
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset").performClick()
        rule.onNodeWithText("leaves the group for good", substring = true).assertExists()
        rule.onNodeWithText("pair it again", substring = true).assertExists()
        rule.onNodeWithTag("set_reset_confirm").assertIsNotEnabled().performClick()
        assertFalse(reset)
        rule.onNodeWithTag("set_reset_ack").performClick()
        rule.onNodeWithTag("set_reset_confirm").assertIsEnabled().performClick()
        assertTrue(reset)
    }

    @Test
    fun aFailedResetIsShown() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), error = SettingsError.RESET))
        rule.onNodeWithText("did not finish", substring = true).assertExists()
    }

    @Test
    fun stopAsksFirstAndStartOnlyOnTheButton() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_stop"))
        rule.onNodeWithTag("set_stop").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("Nothing is hidden", substring = true).assertExists()
        rule.onNodeWithTag("set_stop_confirm").performClick()
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun aUserStopStaysUntilStart() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), stopped))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_start"))
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("set_start").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun aShorterSummaryPullsTheDetailDownAfterAConfirm() {
        var picked: Pair<Int, Int>? = null
        show(
            SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(detailRetentionDays = 180, summaryRetentionDays = 365)),
            onRetention = { d, s -> picked = d to s },
        )
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_summary_90"))
        rule.onNodeWithTag("set_summary_90").performClick()
        assertEquals(null, picked)
        rule.onNodeWithText("History older than 90 days will be deleted from this phone now.").assertExists()
        rule.onNodeWithTag("set_shrink_confirm").performClick()
        assertEquals(90 to 90, picked)
    }

    @Test
    fun aShorterDetailAsksAndCancelKeepsEverything() {
        var picked: Pair<Int, Int>? = null
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings()), onRetention = { d, s -> picked = d to s })
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_detail_30"))
        rule.onNodeWithTag("set_detail_30").performClick()
        rule.onNodeWithText("older than 30 days", substring = true).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(null, picked)
    }

    @Test
    fun aLongerRetentionIsInstant() {
        var picked: Pair<Int, Int>? = null
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings()), onRetention = { d, s -> picked = d to s })
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_summary_730"))
        rule.onNodeWithTag("set_summary_730").performClick()
        assertEquals(90 to 730, picked)
        rule.onNodeWithTag("set_shrink_confirm").assertDoesNotExist()
    }

    @Test
    fun transparencyListsTheParentsForAChild() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) { TransparencyScreen(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), 2L, onBack = {}) }
        }
        rule.onNodeWithText("all parents in your family group", substring = true).assertExists()
        rule.onNodeWithText("Mom", substring = true).assertExists()
        rule.onNodeWithText("by hour", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun transparencyForAParentSaysNothingOfItsOwnIsShared() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) { TransparencyScreen(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), 2L, onBack = {}) }
        }
        rule.onNodeWithText("does not share its own screen time", substring = true).assertExists()
        rule.onNodeWithText("detailed history 90 d, daily totals 365 d", substring = true).assertExists()
    }

    @Test
    fun keyErrorResetAsksFirst() {
        var reset = false
        rule.setContent { KinsightTheme(dynamicColor = false) { KeyErrorScreen("keys missing", onReset = { reset = true }) } }
        rule.onNodeWithText("keys missing", substring = true).assertExists()
        rule.onNodeWithTag("rec_reset").performClick()
        assertFalse(reset)
        rule.onNodeWithTag("set_reset_ack").performClick()
        rule.onNodeWithTag("set_reset_confirm").performClick()
        assertTrue(reset)
    }

    @Test
    fun startOverIsDisabledWhileTheWipeRuns() {
        rule.setContent { KinsightTheme(dynamicColor = false) { RemovedScreen(onStartOver = {}, busy = true) } }
        rule.onNodeWithTag("rec_start_over").assertIsNotEnabled()
    }

    @Test
    fun aFailedStartOverIsShown() {
        var again = false
        rule.setContent { KinsightTheme(dynamicColor = false) { RemovedScreen(onStartOver = { again = true }, failed = true) } }
        rule.onNodeWithText("did not finish", substring = true).assertExists()
        rule.onNodeWithTag("rec_start_over").performClick()
        assertTrue(again)
    }

    @Test
    fun aConsentingChildWithoutUsageAccessSeesItInTheSyncSection() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running.copy(usageAccessGranted = false)))
        rule.onNodeWithText("usage access is off", substring = true).assertExists()
        rule.onNodeWithTag("set_usage_access").assertExists()
    }

    @Test
    fun noUsageAccessRowWithoutConsentOrOnAParent() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running.copy(usageAccessGranted = false)))
        rule.onNodeWithTag("set_usage_access").assertDoesNotExist()
    }

    @Test
    fun aChildWithoutConsentGetsNoUsageAccessRow() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running.copy(usageAccessGranted = false)))
        rule.onNodeWithTag("set_usage_access").assertDoesNotExist()
    }

    @Test
    fun theSignedSharingStateDecidesLikeOverview() {
        // Signing SHARING_RESUMED failed: the local consent is set but the family never heard it; Overview shows no
        // banner, so Settings shows no row either.
        val failed = UsageSettings(sharing = true, consentAtMs = 1)
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), failed, Prefs(), running.copy(usageAccessGranted = false), ownSharing = Sharing.Never))
        rule.onNodeWithTag("set_usage_access").assertDoesNotExist()
    }

    @Test
    fun aPausedChildSeesTheUsageAccessRow() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running.copy(usageAccessGranted = false), ownSharing = Sharing.Paused(1)))
        rule.onNodeWithTag("set_usage_access").assertExists()
    }

    private fun showScaled(fontScale: Float, onSummaryMode: (SummaryMode) -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            KinsightTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running), "0.4.0",
                    onSharing = {}, onConsent = {}, onTransparency = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                    onSummaryMode = onSummaryMode, onNoContactHours = {}, onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                )
            }
        }
    }

    @Test
    fun largeFontsTurnTheChoicesIntoAVerticalList() {
        var picked: SummaryMode? = null
        showScaled(2f) { picked = it }
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_mode_weekly"))
        rule.onNodeWithTag("set_mode_list").assertExists()
        rule.onNodeWithTag("set_mode_weekly").performClick()
        assertEquals(SummaryMode.WEEKLY, picked)
    }

    @Test
    fun normalFontsKeepTheSegmentedRow() {
        showScaled(1f) {}
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_mode_weekly"))
        rule.onNodeWithTag("set_mode_list").assertDoesNotExist()
    }

    @Test
    fun theTransparencyRowIsTheButton() {
        var opened = 0
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running), onTransparency = { opened++ })
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_transparency"))
        rule.onNodeWithTag("set_transparency").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Open").assertDoesNotExist() // no separate button under the row any more
    }

    @Test
    fun aChildsAdultItemsWaitUnderAdvanced() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
        rule.onNodeWithTag("set_reset").assertDoesNotExist()
        rule.onNodeWithTag("set_detail_30").assertDoesNotExist()
        rule.onNodeWithText("Diagnostics").assertDoesNotExist()
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithTag("set_advanced").performClick()
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_detail_30"))
        rule.onNodeWithTag("set_list").performScrollToNode(hasText("Diagnostics"))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset").assertTextEquals("Disconnect this phone and pair again").performClick()
        rule.onNodeWithText("leaves the group for good", substring = true).assertExists()
        rule.onNodeWithTag("set_reset_confirm").assertIsNotEnabled()
    }

    @Test
    fun aStorageErrorOpensAdvancedOnAChildsPhone() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running.copy(state = ServiceState.STORAGE_ERROR)))
        rule.onNodeWithText("Secure storage problem", substring = true).assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset")) // no click on «Advanced» first
        rule.onNodeWithTag("set_reset").assertExists()
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theAdvancedToggleSaysItsStateInRussian() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто"))
        rule.onNodeWithTag("set_advanced").performClick()
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
    }

    @Test
    fun aParentHasNoAdvancedGroup() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_advanced").assertDoesNotExist()
    }

    @Test
    fun aChildIsToldPauseFromStop() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running))
        rule.onNodeWithText("Pause: your time is kept", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianTermsFollowTheGlossary() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running))
        rule.onNodeWithText("Обмен экранным временем").assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
        rule.onNodeWithText("Дополнительно").assertExists()
        rule.onNodeWithText("Этот телефон").assertExists()
    }

    @Test
    fun theTransparencyScreenHasATopBarWithBack() {
        var back = 0
        rule.setContent {
            KinsightTheme(dynamicColor = false) { TransparencyScreen(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), 2L, onBack = { back++ }) }
        }
        rule.onNodeWithText("Who sees my data").assertExists()
        rule.onNodeWithTag("tr_back").performClick()
        assertEquals(1, back)
    }
}
