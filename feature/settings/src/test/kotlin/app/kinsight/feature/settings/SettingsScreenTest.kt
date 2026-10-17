package app.kinsight.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.ChildLockStatus
import app.kinsight.core.data.CloudEnableResult
import app.kinsight.core.data.CloudProblem
import app.kinsight.core.data.CloudStatus
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.OwnerTransferPhase
import app.kinsight.core.data.PinCheck
import app.kinsight.core.data.PinSave
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.SignInState
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.location.LocationPermissions
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
    private val locked = LockUi(ChildLockStatus(pinSet = true))
    private val unlocked = LockUi(ChildLockStatus(pinSet = true), unlockedUntilMs = NOW + 60_000)

    private fun show(
        ui: SettingsUi,
        onSharing: (Boolean) -> Unit = {},
        onReset: () -> Unit = {},
        onConsent: () -> Unit = {},
        onRetention: (Int, Int) -> Unit = { _, _ -> },
        nowMs: Long = NOW,
        onCloudSignIn: () -> Unit = {},
        onCloudCode: (String) -> Unit = {},
        onCloudDismiss: () -> Unit = {},
        onCloudTakeOver: () -> Unit = {},
        onCloudHandover: (DeviceId) -> Unit = {},
        onNewPhone: () -> Unit = {},
        onCloudTurnOff: () -> Unit = {},
        onUnlock: (String) -> Unit = {},
        onLock: () -> Unit = {},
        onBack: (() -> Unit)? = null,
    ) = rule.setContent {
        KinsightTheme() {
            SettingsScreen(
                ui, onSharing = onSharing, onConsent = onConsent,
                onStopSync = { calls += "stop" }, onStartSync = { calls += "start" },
                onRetention = onRetention, onBattery = {}, onDiagnostics = {},
                onResetIdentity = onReset, onSyncNow = { calls += "syncNow" }, nowMs = nowMs,
                onCloudSignIn = onCloudSignIn, onCloudCode = onCloudCode, onCloudDismiss = onCloudDismiss,
                onCloudTakeOver = onCloudTakeOver, onCloudHandover = onCloudHandover, onNewPhone = onNewPhone,
                onCloudTurnOff = onCloudTurnOff, onUnlock = onUnlock, onLock = onLock, onBack = onBack,
            )
        }
    }

    // The sync section is the first section: its nodes exist without scrolling the list.
    @Test
    fun theSyncSectionTopsSettingsAndSyncsNow() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running.copy(lastSyncMs = NOW - 5 * 60_000), lock = unlocked))
        rule.onNodeWithText("Sync on this phone").assertExists()
        rule.onNodeWithText("Last sync: 5 minutes ago").assertExists()
        rule.onNodeWithTag("set_stop").assertExists()
        rule.onNodeWithTag("set_sync_now").performClick()
        assertEquals(listOf("syncNow"), calls)
    }

    @Test
    fun openedFromMoreItHasABackArrowAndOtherwiseNone() {
        var backs = 0
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running), onBack = { backs++ })
        rule.onNodeWithTag("set_back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun withoutOnBackThereIsNoBackArrow() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_back").assertDoesNotExist()
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
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, lock = unlocked), onSharing = { shared = it })
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
    fun parentSeesNoShareSwitch() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_share").assertDoesNotExist()
        rule.onNodeWithText("is not shared", substring = true).assertExists()
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
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running, lock = unlocked))
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
            KinsightTheme() { TransparencyScreen(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), 2L, onBack = {}) }
        }
        rule.onNodeWithText("all parents in your family group", substring = true).assertExists()
        rule.onNodeWithText("Mom", substring = true).assertExists()
        rule.onNodeWithText("time per hour", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun transparencyForAParentSaysNothingOfItsOwnIsShared() {
        rule.setContent {
            KinsightTheme() { TransparencyScreen(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), 2L, onBack = {}) }
        }
        rule.onNodeWithText("does not share its own screen time", substring = true).assertExists()
        rule.onNodeWithText("detailed history 90 d, daily totals 365 d", substring = true).assertExists()
    }

    @Test
    fun keyErrorResetAsksFirst() {
        var reset = false
        rule.setContent { KinsightTheme() { KeyErrorScreen("keys missing", onReset = { reset = true }) } }
        rule.onNodeWithText("keys missing", substring = true).assertExists()
        rule.onNodeWithTag("rec_reset").performClick()
        assertFalse(reset)
        rule.onNodeWithTag("set_reset_ack").performClick()
        rule.onNodeWithTag("set_reset_confirm").performClick()
        assertTrue(reset)
    }

    @Test
    fun startOverIsDisabledWhileTheWipeRuns() {
        rule.setContent { KinsightTheme() { RemovedScreen(onStartOver = {}, busy = true) } }
        rule.onNodeWithTag("rec_start_over").assertIsNotEnabled()
    }

    @Test
    fun aFailedStartOverIsShown() {
        var again = false
        rule.setContent { KinsightTheme() { RemovedScreen(onStartOver = { again = true }, failed = true) } }
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

    @Test
    fun aChildsAdultItemsWaitUnderAdvanced() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, lock = unlocked))
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
    fun aStorageErrorBehindAMissingPermissionLeavesAdvancedClosed() {
        val blocked = running.copy(state = ServiceState.STORAGE_ERROR, missingPermissions = listOf("android.permission.BLUETOOTH_SCAN"))
        assertEquals(SyncStateLine.NO_PERMISSION, syncState(blocked))
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), blocked, lock = unlocked))
        rule.onNodeWithText("Secure storage problem", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
        rule.onNodeWithTag("set_advanced").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test
    fun theSyncLineOrderIsStopThenPermissionThenRadioThenKey() {
        assertEquals(SyncStateLine.RUNNING, syncState(null))
        assertEquals(SyncStateLine.STOPPED, syncState(running.copy(stoppedByUser = true, state = ServiceState.STORAGE_ERROR)))
        assertEquals(SyncStateLine.BLUETOOTH_OFF, syncState(running.copy(state = ServiceState.BLUETOOTH_OFF)))
        assertEquals(SyncStateLine.KEY_ERROR, syncState(running.copy(state = ServiceState.STORAGE_ERROR, syncing = true)))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theAdvancedToggleSaysItsStateInRussian() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, lock = unlocked))
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
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, lock = unlocked))
        rule.onNodeWithText("Pause: your time is kept", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianTermsFollowTheGlossary() {
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running, lock = unlocked))
        rule.onNodeWithText("Обмен экранным временем").assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
        rule.onNodeWithText("Дополнительно").assertExists()
        rule.onNodeWithText("Этот телефон").assertExists()
    }


    private fun ownerUi(problem: CloudProblem? = null, canTransfer: Boolean = true) = SettingsUi(
        GroupState.Ready(group, parent, listOf(parent, kid.copy(isSelf = false))), UsageSettings(), Prefs(), running,
        cloud = CloudStatus(
            available = true, on = true, ownerName = "Mom", selfIsOwner = true, lastSyncMs = NOW - 5 * 60_000, problem = problem,
            canTransfer = canTransfer,
        ),
    )

    private fun scrollToCloud() = rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_cloud"))

    @Test
    fun aParentTurnsTheCloudOnWithTheSwitch() {
        var signIns = 0
        show(
            SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = CloudStatus(available = true, canEnable = true)),
            onCloudSignIn = { signIns++ },
        )
        scrollToCloud()
        rule.onNodeWithText("Sync through Yandex Disk").assertExists()
        rule.onNodeWithText("The developer stores nothing", substring = true).assertExists()
        rule.onNodeWithTag("set_cloud_switch").performClick()
        assertEquals(1, signIns)
    }

    @Test
    fun theOwnerIsAskedToRestoreAccess() {
        var signIns = 0
        show(ownerUi(CloudProblem.UNAUTHORIZED), onCloudSignIn = { signIns++ })
        scrollToCloud()
        rule.onNodeWithText("Restore access to the Disk", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Storage: your Yandex Disk", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Last cloud sync: 5 minutes ago", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_switch").assertIsNotEnabled()
        rule.onNodeWithTag("set_cloud_restore").performClick()
        assertEquals(1, signIns)
    }

    @Test
    fun aChildSeesTheCloudWithoutASwitch() {
        show(
            SettingsUi(
                GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running,
                cloud = CloudStatus(available = true, on = true, ownerName = "Mom", isChild = true, problem = CloudProblem.NO_ACCESS),
            ),
        )
        scrollToCloud()
        rule.onNodeWithText("No access to the cloud, waiting for the owner", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Storage: Yandex Disk of Mom", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("No cloud sync yet", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_switch").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_restore").assertDoesNotExist()
    }

    @Test
    fun aParentWhoCannotTurnItOnSeesNoCloudRow() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = CloudStatus(available = true)))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_cloud").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru")
    fun theSpecWordsInRussian() {
        show(ownerUi(CloudProblem.UNAUTHORIZED))
        scrollToCloud()
        rule.onNodeWithText("Синхронизировать через Яндекс Диск", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Восстановите доступ к Диску", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun aMemberWaitsForTheOwnerInRussian() {
        show(
            SettingsUi(
                GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running,
                cloud = CloudStatus(available = true, on = true, ownerName = "Mom", isChild = true, problem = CloudProblem.NO_ACCESS),
            ),
        )
        scrollToCloud()
        rule.onNodeWithText("Нет доступа к облаку, ждём владельца", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFullDiskIsShownToTheOwner() {
        show(ownerUi(CloudProblem.STORAGE_FULL))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_problem", useUnmergedTree = true).assertTextEquals("Your Yandex Disk is full. Free up space so the sync can go on.")
    }

    @Test
    fun aNetworkProblemIsQuiet() {
        show(ownerUi(CloudProblem.NETWORK))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_problem", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_restore").assertDoesNotExist()
    }

    @Test
    fun thePastedCodeGoesToTheSignIn() {
        val codes = mutableListOf<String>()
        show(ownerUi().copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=1")), onCloudCode = { codes += it })
        rule.onNodeWithText("Confirm the access in the browser", substring = true).assertExists()
        rule.onNodeWithTag("set_cloud_done").assertIsNotEnabled()
        rule.onNodeWithTag("set_cloud_code").performTextInput("1234567")
        rule.onNodeWithTag("set_cloud_done").performClick()
        assertEquals(listOf("1234567"), codes)
    }

    @Test
    fun aNewSignInStartsWithAnEmptyCodeField() {
        val codes = mutableListOf<String>()
        var ui by mutableStateOf(ownerUi().copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=1")))
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    ui, onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {},
                    onRetention = { _, _ -> }, onBattery = {}, onDiagnostics = {},
                    onResetIdentity = {}, nowMs = NOW, onCloudCode = { codes += it },
                )
            }
        }
        rule.onNodeWithTag("set_cloud_code").performTextInput("1234567")
        ui = ui.copy(signIn = SignInState.Done(CloudEnableResult.SIGN_IN_FAILED))
        rule.waitForIdle()
        ui = ui.copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=2"))
        rule.waitForIdle()
        rule.onNodeWithTag("set_cloud_done").assertIsNotEnabled()
        rule.onNodeWithTag("set_cloud_code").performTextInput("7654321")
        rule.onNodeWithTag("set_cloud_done").performClick()
        assertEquals(listOf("7654321"), codes)
    }
    @Test
    fun aRejectedPasteKeepsTheFieldWithAnError() {
        val codes = mutableListOf<String>()
        show(ownerUi().copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=1", rejected = true)), onCloudCode = { codes += it })
        rule.onNodeWithTag("set_cloud_code_error", useUnmergedTree = true).assertTextEquals("Could not sign in to Yandex. Try again.")
        rule.onNodeWithTag("set_cloud_code").performTextInput("7654321")
        rule.onNodeWithTag("set_cloud_done").performClick()
        assertEquals(listOf("7654321"), codes)
    }

    @Test
    fun anUnreachableTokenEndpointAsksToTryAgain() {
        show(ownerUi().copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=1", networkError = true)))
        rule.onNodeWithTag("set_cloud_code_error", useUnmergedTree = true).assertTextEquals("No connection to Yandex Disk. Try again.")
        rule.onNodeWithTag("set_cloud_code").assertExists()
    }

    @Test
    fun cancelClosesTheDialog() {
        var dismissed = 0
        show(ownerUi().copy(signIn = SignInState.Waiting("https://oauth.yandex.ru/authorize?x=1")), onCloudDismiss = { dismissed++ })
        rule.onNodeWithTag("set_cloud_cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theCloudTurnedOnSaysSo() {
        var dismissed = 0
        show(ownerUi().copy(signIn = SignInState.Done(CloudEnableResult.ENABLED)), onCloudDismiss = { dismissed++ })
        rule.onNodeWithTag("set_cloud_result", useUnmergedTree = true).assertTextEquals("Sync through Yandex Disk is on.")
        rule.onNodeWithTag("set_cloud_retry").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_ok").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aDiskFailureAfterTheExchangeStartsAgain() {
        var signIns = 0
        var dismissed = 0
        show(
            ownerUi().copy(signIn = SignInState.Done(CloudEnableResult.NETWORK)),
            onCloudSignIn = { signIns++ }, onCloudDismiss = { dismissed++ },
        )
        rule.onNodeWithTag("set_cloud_result", useUnmergedTree = true).assertTextEquals("No connection to Yandex Disk. Try again.")
        rule.onNodeWithTag("set_cloud_code").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_retry").performClick()
        assertEquals(1, signIns)
        rule.onNodeWithTag("set_cloud_cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun anOldPhoneInTheFamilyIsNotRetried() {
        show(ownerUi().copy(signIn = SignInState.Done(CloudEnableResult.OLD_APP_IN_GROUP)))
        rule.onNodeWithTag("set_cloud_result", useUnmergedTree = true)
            .assertTextEquals("Update Kinsight on every phone of the family, then turn the cloud on.")
        rule.onNodeWithTag("set_cloud_retry").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_ok").assertExists()
    }

    @Test
    fun whileConnectingThereIsNoButton() {
        show(ownerUi().copy(signIn = SignInState.Working))
        rule.onNodeWithText("Connecting…").assertExists()
        rule.onNodeWithTag("set_cloud_done").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_ok").assertDoesNotExist()
    }

    @Test
    fun withoutAYandexClientThereIsNoCloudRow() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = CloudStatus(available = false)))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_cloud").assertDoesNotExist()
    }

    @Test
    fun theTransparencyScreenHasATopBarWithBack() {
        var back = 0
        rule.setContent {
            KinsightTheme() { TransparencyScreen(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), 2L, onBack = { back++ }) }
        }
        rule.onNodeWithText("Who sees my data").assertExists()
        rule.onNodeWithTag("tr_back").performClick()
        assertEquals(1, back)
    }

    private val dadView = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = null)

    @Test
    fun theOwnerHandsTheStorageToAParentOrMovesItToANewPhone() {
        var picked: DeviceId? = null
        var newPhone = 0
        val ui = ownerUi().let { it.copy(cloud = it.cloud.copy(handoverTargets = listOf(dadView), handoverNeeded = true)) }
        show(ui, onCloudHandover = { picked = it }, onNewPhone = { newPhone++ })
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_new_phone").performClick()
        assertEquals(1, newPhone)
        rule.onNodeWithTag("set_cloud_handover").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dad").performClick()
        assertEquals(dadView.id, picked)
        rule.onNodeWithTag("set_cloud_handover_to").assertDoesNotExist()
    }

    @Test
    fun anOwnerWithoutARefreshTokenRestoresAccessBeforeMovingToANewPhone() {
        var signIns = 0
        show(ownerUi(canTransfer = false), onCloudSignIn = { signIns++ })
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_new_phone").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_new_phone_restore", useUnmergedTree = true)
            .assertTextEquals("To move to a new phone, first restore access to the Disk: sign in to Yandex on this phone.")
        rule.onNodeWithTag("set_cloud_restore").assertTextEquals("Sign in to Yandex").performClick()
        assertEquals(1, signIns)
    }

    @Test
    fun aDeadTokenShowsOneSignInEvenWithoutARefreshToken() {
        show(ownerUi(problem = CloudProblem.UNAUTHORIZED, canTransfer = false))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_new_phone_restore", useUnmergedTree = true).assertExists()
        assertEquals(1, rule.onAllNodesWithTag("set_cloud_restore").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "ru")
    fun theRestoreBeforeMovingInRussian() {
        show(ownerUi(canTransfer = false))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_new_phone_restore", useUnmergedTree = true)
            .assertTextEquals("Чтобы перенести на новый телефон, сначала восстановите доступ к Диску: войдите в Яндекс на этом телефоне.")
    }

    @Test
    fun theOwnerWaitsForTheAskedParent() {
        val ui = ownerUi().let { it.copy(cloud = it.cloud.copy(handoverTargets = listOf(dadView), handoverPendingTo = "Dad", handoverNeeded = true)) }
        show(ui)
        scrollToCloud()
        rule.onNodeWithText("Waiting for Dad to take the storage", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_take_over").assertDoesNotExist()
    }

    @Test
    fun theChosenParentTakesTheStorage() {
        var takes = 0
        val asked = CloudStatus(available = true, on = true, ownerName = "Dad", handoverFrom = "Dad", canTakeOver = true)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = asked), onCloudTakeOver = { takes++ })
        scrollToCloud()
        rule.onNodeWithText("Dad hands you the family's storage", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_new_phone").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_handover").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_take_over").assertTextEquals("Take the storage").performClick()
        assertEquals(1, takes)
    }

    @Test
    fun aParentSeesTheOwnerIsUnavailableAndMayTakeOver() {
        val gone = CloudStatus(available = true, on = true, ownerName = "Dad", ownerUnavailable = true, canTakeOver = true)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = gone))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_owner_gone", useUnmergedTree = true).assertTextEquals("The owner is unavailable")
        rule.onNodeWithTag("set_cloud_take_over").assertTextEquals("I am the owner")
    }

    @Test
    @Config(qualifiers = "ru")
    fun theOwnerLifecycleSpecWordsInRussian() {
        val gone = CloudStatus(available = true, on = true, ownerName = "Dad", ownerUnavailable = true, canTakeOver = true)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = gone))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_owner_gone", useUnmergedTree = true).assertTextEquals("Владелец недоступен")
        rule.onNodeWithTag("set_cloud_take_over").assertTextEquals("Я владелец")
    }

    @Test
    fun aRemovedPhoneAsksTheOwnerToRenewAndTheResetWaitsForTheHandover() {
        var signIns = 0
        val ui = ownerUi(CloudProblem.RENEW_ACCESS).let { it.copy(cloud = it.cloud.copy(handoverTargets = listOf(dadView), handoverNeeded = true)) }
        show(ui, onCloudSignIn = { signIns++ })
        scrollToCloud()
        rule.onNodeWithText("A phone left the group", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_restore").performClick()
        assertEquals(1, signIns)
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset").assertIsNotEnabled()
        rule.onNodeWithTag("set_reset_handover", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theLastParentOwnerIsToldTheResetTurnsTheCloudOff() {
        show(ownerUi())
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset_handover").assertDoesNotExist()
        rule.onNodeWithTag("set_reset").assertIsEnabled().performClick()
        rule.onNodeWithText("Sync through Yandex Disk turns off", substring = true).assertExists()
    }

    @Test
    fun theResetOfAPhoneThatOwnsNothingSaysNothingOfTheCloud() {
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset").performClick()
        rule.onNodeWithText("Sync through Yandex Disk turns off", substring = true).assertDoesNotExist()
    }

    @Test
    fun theOwnerTurnsTheCloudOffAfterConfirming() {
        var offs = 0
        show(ownerUi(), onCloudTurnOff = { offs++ })
        scrollToCloud()
        rule.onNodeWithText("Turn off sync through Yandex Disk", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_turn_off", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Turn off sync for the whole family?").assertExists()
        rule.onNodeWithText("The family folder stays on your Disk", substring = true).assertExists()
        // Cancel first: nothing happens.
        rule.onNodeWithTag("set_cloud_turn_off_confirm_cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, offs)
        rule.onNodeWithTag("set_cloud_turn_off_confirm").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_turn_off", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set_cloud_turn_off_confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, offs)
        rule.onNodeWithTag("set_cloud_turn_off_confirm").assertDoesNotExist()
    }

    @Test
    fun onlyTheOwnerHasTheTurnOff() {
        val dadCloud = CloudStatus(available = true, on = true, ownerName = "Mom", selfIsOwner = false)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = dadCloud))
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_turn_off", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anAdminSeesTheCloudTurnedOffAndTheFolderLeftOnTheDisk() {
        var signIns = 0
        val off = CloudStatus(available = true, canEnable = true, turnedOff = true)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = off), onCloudSignIn = { signIns++ })
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_turned_off", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("set_cloud_turn_off", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_switch").performClick()
        assertEquals(1, signIns) // the normal sign-in turns it on again
    }

    @Test
    @Config(qualifiers = "ru")
    fun theTurnOffInRussian() {
        show(ownerUi())
        scrollToCloud()
        rule.onNodeWithText("Выключить синхронизацию через Яндекс Диск", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aRefusedHandoverIsShown() {
        show(ownerUi().copy(error = SettingsError.HANDOVER))
        rule.onNodeWithText("The storage was not handed over", substring = true).assertExists()
    }

    @Test
    fun aChildAndANonAdminParentSeeNoOwnerActions() {
        val kidCloud = CloudStatus(available = true, on = true, ownerName = "Mom", isChild = true)
        show(SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(), Prefs(), running, cloud = kidCloud))
        scrollToCloud()
        for (tag in listOf("set_cloud_take_over", "set_cloud_handover", "set_cloud_new_phone", "set_cloud_owner_gone", "set_cloud_restore")) {
            rule.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun theOldPhoneSeesTheRoleMoveAndThenLeavesByItself() {
        var transfer by mutableStateOf(OwnerTransferPhase.PENDING)
        rule.setContent {
            KinsightTheme() {
                val ui = ownerUi().let { it.copy(cloud = it.cloud.copy(ownerTransfer = transfer)) }
                SettingsScreen(
                    ui, onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {},
                    onRetention = { _, _ -> }, onBattery = {}, onDiagnostics = {},
                    onResetIdentity = {}, nowMs = NOW,
                )
            }
        }
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_transfer", useUnmergedTree = true).assertTextEquals("The owner role is moving to the new phone.")
        rule.onNodeWithTag("set_cloud_new_phone").assertDoesNotExist() // one transfer at a time
        transfer = OwnerTransferPhase.DONE
        rule.waitForIdle()
        rule.onNodeWithTag("set_cloud_transfer", useUnmergedTree = true)
            .assertTextEquals("The new phone is the owner now. This phone leaves the group by itself.")
    }

    @Test
    @Config(qualifiers = "ru")
    fun theRoleMoveInRussian() {
        show(ownerUi().let { it.copy(cloud = it.cloud.copy(ownerTransfer = OwnerTransferPhase.PENDING)) })
        scrollToCloud()
        rule.onNodeWithTag("set_cloud_transfer", useUnmergedTree = true).assertTextEquals("Роль владельца переносится на новый телефон.")
    }

    @Test
    fun theResetWaitsWhileTheStorageMoves() {
        val former = CloudStatus(available = true, on = true, ownerName = "Dad", cloudSwitching = true)
        show(SettingsUi(GroupState.Ready(group, parent, listOf(parent)), UsageSettings(), Prefs(), running, cloud = former))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_reset"))
        rule.onNodeWithTag("set_reset").assertIsNotEnabled()
        rule.onNodeWithTag("set_reset_switching", useUnmergedTree = true)
            .assertTextEquals(
                "The storage is moving to another Disk. You can disconnect this phone once every phone has switched to it. " +
                    "A phone that no longer syncs can be removed from the group.",
            )
        rule.onNodeWithTag("set_reset_handover").assertDoesNotExist()
    }

    private fun showChildLocation(state: LocationPermissions.State?, asked: MutableList<LocationPermissions.State>) = rule.setContent {
        KinsightTheme() {
            SettingsScreen(
                SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1L), Prefs(), running),
                onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                location = state, onLocationAccess = { asked += it },
            )
        }
    }

    /** Y4 Task 6 review Minor 4: «Approximate» is named as such, and «Allow» asks for the precise location again. */
    @Test
    fun anApproximateOnlyChildIsToldToTurnOnPreciseLocation() {
        val asked = mutableListOf<LocationPermissions.State>()
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1L), Prefs(), running),
                    onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                    onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                    location = LocationPermissions.State.COARSE_ONLY, onLocationAccess = { asked += it },
                )
            }
        }
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_location_allow"))
        rule.onNodeWithText("Use precise location", substring = true).assertExists()
        rule.onNodeWithTag("set_location_allow").assertTextEquals("Allow")
        rule.onNodeWithTag("set_location_allow").performClick()
        assertEquals(listOf(LocationPermissions.State.COARSE_ONLY), asked)
    }

    @Test
    fun aChildIsAskedForPreciseThenBackgroundLocation() {
        val asked = mutableListOf<LocationPermissions.State>()
        var state by mutableStateOf<LocationPermissions.State?>(LocationPermissions.State.NONE)
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1L), Prefs(), running),
                    onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                    onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                    location = state, onLocationAccess = { asked += it },
                )
            }
        }
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_location_allow"))
        rule.onNodeWithTag("set_location_allow").assertTextEquals("Allow")
        rule.onNodeWithTag("set_location_allow").performClick()
        state = LocationPermissions.State.WHILE_IN_USE
        rule.onNodeWithTag("set_location_allow").assertTextEquals("Allow all the time")
        rule.onNodeWithTag("set_location_allow").performClick()
        assertEquals(listOf(LocationPermissions.State.NONE, LocationPermissions.State.WHILE_IN_USE), asked)
        state = LocationPermissions.State.ALWAYS
        rule.onNodeWithTag("set_location_allow").assertDoesNotExist()
        rule.onNodeWithText("Your parents see where this phone is", substring = true).assertExists()
    }

    /** Y4 parked minor: a refusal for good turns the dead button into the app's settings page. */
    @Test
    fun aLocationRefusedForGoodOpensTheAppSettings() {
        var opened = 0
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1L), Prefs(), running),
                    onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                    onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                    location = LocationPermissions.State.NONE, locationBlocked = true, onLocationSettings = { opened++ },
                )
            }
        }
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_location_settings"))
        rule.onNodeWithTag("set_location_settings").assertTextEquals("Open app settings")
        rule.onNodeWithTag("set_location_allow").assertDoesNotExist()
        rule.onNodeWithText("Android no longer asks here", substring = true).assertExists()
        rule.onNodeWithTag("set_location_settings").performClick()
        assertEquals(1, opened)
    }

    /** Y4 parked minor: the route's gate and the refusal rule. */
    @Test
    fun theLocationRowIsAChildsWithTheCloudOn() {
        val s = LocationPermissions.State.WHILE_IN_USE
        assertEquals(s, locationRow(cloudOn = true, isChild = true, state = s))
        assertEquals(null, locationRow(cloudOn = false, isChild = true, state = s))
        assertEquals("never on a parent's phone", null, locationRow(cloudOn = true, isChild = false, state = s))
        val none = LocationPermissions.State.NONE
        val inUse = LocationPermissions.State.WHILE_IN_USE
        assertEquals("a first Deny: Android asks again", false, locationBlockedAfter(none, none, rationale = true))
        assertEquals("refused for good", true, locationBlockedAfter(none, none, rationale = false))
        assertEquals(true, locationBlockedAfter(inUse, inUse, rationale = false))
        assertEquals(false, locationBlockedAfter(none, inUse, rationale = false))
        assertEquals(false, locationBlockedAfter(LocationPermissions.State.ALWAYS, LocationPermissions.State.ALWAYS, rationale = false))
        val i = appSettings("app.kinsight")
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, i.action)
        assertEquals("package:app.kinsight", i.dataString)
    }

    @Test
    fun withoutTheCloudThereIsNoLocationRow() {
        showChildLocation(null, mutableListOf())
        rule.onNodeWithTag("set_location").assertDoesNotExist()
    }

    /** Spec §8.1 «Ребёнок видит список зон» (Y4 final review Important 3): the child opens the zones from its settings. */
    @Test
    fun aChildOpensTheSafeZonesFromTheLocationRow() {
        var opened = 0
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1L), Prefs(), running),
                    onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
                    onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
                    location = LocationPermissions.State.ALWAYS, onZones = { opened++ },
                )
            }
        }
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_zones"))
        rule.onNodeWithTag("set_zones").assertTextContains("Safe zones")
        rule.onNodeWithTag("set_zones").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withoutTheCloudThereIsNoZonesRow() {
        showChildLocation(null, mutableListOf())
        rule.onNodeWithTag("set_zones").assertDoesNotExist()
    }

    private val childUi = SettingsUi(GroupState.Ready(group, kid, listOf(kid, mom)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running)

    @Test
    fun aLockedChildPhoneHasNoSwitches() {
        val pins = mutableListOf<String>()
        show(childUi.copy(lock = locked), onUnlock = { pins += it })
        rule.onNodeWithTag("set_share").assertDoesNotExist()
        rule.onNodeWithTag("set_stop").assertDoesNotExist()
        rule.onNodeWithTag("set_sync_now").assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_parent_unlock"))
        rule.onNodeWithTag("set_advanced").assertDoesNotExist()
        rule.onNodeWithTag("set_reset").assertDoesNotExist()
        rule.onNodeWithTag("set_parent_unlock").performClick()
        rule.onNodeWithTag("set_pin_field").performTextInput("2468")
        rule.onNodeWithTag("set_pin_ok").performClick()
        assertEquals(listOf("2468"), pins)
    }

    @Test
    fun anUnlockedChildPhoneShowsTheSwitchesUntilItLocksOrTimesOut() {
        var locks = 0
        show(childUi.copy(lock = unlocked), onLock = { locks++ })
        rule.onNodeWithTag("set_share").assertExists()
        rule.onNodeWithTag("set_stop").assertExists()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_lock"))
        rule.onNodeWithTag("set_lock").performClick()
        assertEquals(1, locks)
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_advanced"))
    }

    @Test
    fun anUnlockEndsWhenItsTimeIsUp() {
        show(childUi.copy(lock = unlocked), nowMs = NOW + 60_000)
        rule.onNodeWithTag("set_share").assertDoesNotExist()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_parent_unlock"))
    }

    @Test
    fun aWrongPinIsSaidInTheDialog() {
        show(childUi.copy(lock = locked.copy(lastCheck = PinCheck.WRONG)))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_parent_unlock"))
        rule.onNodeWithTag("set_parent_unlock").performClick()
        rule.onNodeWithText("Wrong PIN").assertExists()
    }

    @Test
    fun withoutAPinTheUnlockRowSaysWhereItIsSet() {
        show(childUi.copy(lock = LockUi()))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_parent_unlock"))
        rule.onNodeWithText("has not set a PIN yet", substring = true).assertExists()
        rule.onNodeWithTag("set_parent_unlock").assertIsNotEnabled()
    }

    /** Ruling (fix round 1): while the group loads this phone may be a child's, so Settings stay locked. */
    @Test
    fun whileTheGroupLoadsNothingCanBeChanged() {
        show(SettingsUi(GroupState.Loading, UsageSettings(sharing = true, consentAtMs = 1), Prefs(), running))
        rule.onNodeWithTag("set_sync_now").assertExists()
        rule.onNodeWithTag("set_stop").assertDoesNotExist()
        rule.onNodeWithTag("set_share").assertDoesNotExist()
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_battery"))
        rule.onNodeWithTag("set_reset").assertDoesNotExist()
        rule.onNodeWithTag("set_detail_30").assertDoesNotExist()
        rule.onNodeWithTag("set_advanced").assertDoesNotExist()
        rule.onNodeWithText("Diagnostics").assertDoesNotExist()
    }

    /** Review Minor 3: when the settings lock, a question the unlock opened closes with them. */
    @Test
    fun aLockClosesAPauseQuestion() {
        var ui by mutableStateOf(childUi.copy(lock = unlocked))
        var shared: Boolean? = null
        rule.setContent {
            KinsightTheme() {
                SettingsScreen(
                    ui, onSharing = { shared = it }, onConsent = {}, onStopSync = {}, onStartSync = {},
                    onRetention = { _, _ -> }, onBattery = {}, onDiagnostics = {},
                    onResetIdentity = {}, nowMs = NOW,
                )
            }
        }
        rule.onNodeWithTag("set_share").performClick()
        rule.onNodeWithTag("set_pause_confirm").assertExists()
        ui = ui.copy(lock = locked)
        rule.waitForIdle()
        rule.onNodeWithTag("set_pause_confirm").assertDoesNotExist()
        assertEquals(null, shared)
    }

    /** Review Minor 5: a try that could not be checked says so, not «no PIN». */
    @Test
    fun aPinThatCouldNotBeCheckedIsSaid() {
        show(childUi.copy(lock = locked.copy(checkFailed = true)))
        rule.onNodeWithTag("set_list").performScrollToNode(hasTestTag("set_parent_unlock"))
        rule.onNodeWithTag("set_parent_unlock").performClick()
        rule.onNodeWithText("Could not check the PIN. Try again.").assertExists()
        rule.onNodeWithText("has not set a PIN yet", substring = true).assertDoesNotExist()
    }
}
