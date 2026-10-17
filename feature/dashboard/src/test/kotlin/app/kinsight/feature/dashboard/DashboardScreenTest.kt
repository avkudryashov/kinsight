package app.kinsight.feature.dashboard

import app.kinsight.core.data.LocationReadModel
import app.kinsight.core.data.PlaceStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.TodayComparison
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncService
import app.kinsight.core.transport.ble.SyncProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardScreenTest {
    @get:Rule
    val rule = createComposeRule()
    private val today = LocalDate.of(2026, 10, 10)
    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val dad = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = now)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = now)
    private val running = ServiceStatus(ServiceState.RUNNING, stoppedByUser = false, lastSyncMs = now, missingPermissions = emptyList(), syncing = false, problem = null)

    private fun usage(m: MemberView, todayMin: Long, avgMin: Long?, sharing: Sharing = Sharing.On) = DeviceUsage(
        m, DayTotal(today, todayMin * 60_000, 3),
        (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), todayMin * 60_000, 0) }, avgMin?.let { it * 60_000 }, sharing, now,
    )

    private fun show(
        ui: DashboardUi,
        onStart: () -> Unit = {},
        onResume: () -> Unit = {},
        onConsent: () -> Unit = {},
        onOpenDevice: (DeviceId) -> Unit = {},
        onSyncNow: () -> Unit = {},
        onInviteChild: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onAcknowledgeParents: (Set<DeviceId>) -> Unit = {},
    ) = rule.setContent {
        KinsightTheme() {
            DashboardScreen(
                ui, now, onOpenDevice, onResume = onResume, onConsent = onConsent, onStart = onStart,
                onSyncNow = onSyncNow, onInviteChild = onInviteChild, onOpenSettings = onOpenSettings,
                onAcknowledgeParents = onAcknowledgeParents,
            )
        }
    }

    private fun childUi(sharing: Sharing, failed: Boolean = false): DashboardUi {
        val self = kid.copy(isSelf = true)
        return DashboardUi(DashboardState(self, usage(self, 30, 30, sharing), emptyList(), listOf(mom.copy(isSelf = false))), running, true, sharingFailed = failed)
    }

    @Test
    fun childHeaderNamesTheParentsAndOnlyAParentChangesSharing() {
        val self = kid.copy(isSelf = true)
        show(DashboardUi(DashboardState(self, usage(self, 30, 30), emptyList(), listOf(mom.copy(isSelf = false), dad)), running, true))
        rule.onNodeWithText("Mom, Dad", substring = true).assertExists()
        rule.onNodeWithTag("dash_parent_only").assertExists()
        rule.onNodeWithText("Only a parent can change sharing", substring = true).assertExists()
        assertNoPauseButton()
    }

    @Test
    fun parentSeesTheChildCardWithTheComparison() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 120, 60).copy(comparison = TodayComparison.FullDay(60 * 60_000))), listOf(dad)), running, true))
        rule.onNodeWithText("Kid").assertExists()
        rule.onNodeWithText("2 h").assertExists()
        rule.onNodeWithText("more than", substring = true).assertExists()
    }

    @Test
    fun parentWithoutChildrenSeesTheEmptyStateWithAnInvite() {
        var invited = false
        show(DashboardUi(DashboardState(mom, null, emptyList(), emptyList()), running, true), onInviteChild = { invited = true })
        rule.onNodeWithText("No children yet").assertExists()
        rule.onNodeWithTag("dash_invite_child").performClick()
        rule.waitForIdle()
        assertTrue(invited)
    }

    @Test
    fun parentWithChildrenSeesOnlyTheChildren() {
        // User feedback 2026-10-10: the parent's home shows children; this phone's status moved to Settings.
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), listOf(dad)), running.copy(lastSyncMs = now - 5 * 60_000), true))
        rule.onNodeWithText("Kid").assertExists()
        rule.onNodeWithText("This phone").assertDoesNotExist()
        rule.onNodeWithText("is not shared", substring = true).assertDoesNotExist()
        rule.onNodeWithText("also visible to", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Last sync", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("dash_sync_now").assertDoesNotExist()
        rule.onNodeWithTag("dash_banner").assertDoesNotExist()
        assertNoPauseButton()
        rule.onNodeWithTag("dash_consent").assertDoesNotExist()
    }

    @Test
    fun childSeesTheTransparencyRowAndItsOwnCardButNoThisPhone() {
        show(childUi(Sharing.On))
        rule.onNodeWithText("Your screen time is visible to: Mom").assertExists()
        rule.onNodeWithTag("dash_parent_only").assertExists()
        rule.onNodeWithText("Kid").assertExists()
        rule.onNodeWithText("This phone").assertDoesNotExist()
        rule.onNodeWithTag("dash_sync_now").assertDoesNotExist()
        rule.onNodeWithTag("dash_banner").assertDoesNotExist()
    }

    private fun bannerFor(s: ServiceStatus, text: String, role: Role = Role.PARENT) {
        val ui = if (role == Role.CHILD) childUi(Sharing.On).copy(service = s) else DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), s, true)
        show(ui)
        rule.onNodeWithTag("dash_banner").assertExists()
        rule.onNodeWithText(text, substring = true).assertExists()
    }

    @Test
    fun bluetoothOffShowsABanner() = bannerFor(running.copy(state = ServiceState.BLUETOOTH_OFF), "Bluetooth is off")

    @Test
    fun noPermissionShowsABanner() = bannerFor(running.copy(state = ServiceState.NO_PERMISSION), "Nearby devices")

    @Test
    fun aMissingPermissionShowsABannerOnAChild() =
        bannerFor(running.copy(missingPermissions = listOf("android.permission.BLUETOOTH_SCAN")), "Nearby devices", Role.CHILD)

    @Test
    fun aRadioErrorShowsABanner() = bannerFor(running.copy(state = ServiceState.RADIO_ERROR), "Bluetooth error")

    @Test
    fun aKeyErrorShowsABanner() = bannerFor(running.copy(state = ServiceState.STORAGE_ERROR), "Secure storage problem")

    @Test
    fun aKeyErrorBannerOpensSettings() {
        var opened = false
        show(childUi(Sharing.On).copy(service = running.copy(state = ServiceState.STORAGE_ERROR)), onOpenSettings = { opened = true })
        rule.onNodeWithTag("dash_open_settings").performClick()
        rule.waitForIdle()
        assertTrue(opened)
    }

    @Test
    fun syncNowIsAnAccessibilityActionOnTheList() {
        var synced = 0
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running, true), onSyncNow = { synced++ })
        val node = rule.onNodeWithTag("dash_list").fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.CustomActions)?.single { it.label == "Sync now" }
        rule.runOnIdle { action!!.action() }
        assertEquals(1, synced)
    }

    @Test
    fun aStopOnAChildShowsTheBannerWithStart() {
        var started = false
        show(childUi(Sharing.On).copy(service = running.copy(state = ServiceState.STOPPED, stoppedByUser = true)), onStart = { started = true })
        rule.onNodeWithTag("dash_banner").assertExists()
        rule.onNodeWithText("Sync was stopped by you", substring = true).assertExists()
        rule.onNodeWithTag("dash_start").performClick()
        rule.waitForIdle()
        assertTrue(started)
    }

    @Test
    fun theUpdateNoticeNamesThePhoneToUpdate() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running.copy(problem = SyncProblem.PEER_NEEDS_UPDATE, outdated = mapOf(kid.id to false)), true))
        rule.onNodeWithTag("dash_update_notice").assertExists()
        rule.onNodeWithText("Update Kinsight on Kid", substring = true).assertExists()
    }

    @Test
    fun theUpdateNoticeAsksForThisPhoneWhenThePeerIsNewer() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running.copy(outdated = mapOf(kid.id to true)), true))
        rule.onNodeWithText("Update Kinsight on this phone to sync with Kid again", substring = true).assertExists()
    }

    @Test
    fun aPeerNeedingAnUpdateShowsANeutralNoticeNotABanner() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running.copy(problem = SyncProblem.PEER_NEEDS_UPDATE), true))
        rule.onNodeWithTag("dash_update_notice").assertExists()
        rule.onNodeWithText("needs a Kinsight update", substring = true).assertExists()
        rule.onNodeWithTag("dash_banner").assertDoesNotExist()
    }

    @Test
    fun otherProblemsAndNoProblemShowNoUpdateNotice() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running.copy(problem = SyncProblem.LINK_FAILED), true))
        rule.onNodeWithTag("dash_update_notice").assertDoesNotExist()
    }

    @Test
    fun normalStatesShowNoBanner() {
        val states = listOf(
            running,
            running.copy(syncing = true),
            running.copy(lastSyncMs = null), // waiting for the family's phones
            running.copy(state = ServiceState.STARTING),
            null,
        )
        var current by mutableStateOf<ServiceStatus?>(states.first())
        rule.setContent {
            KinsightTheme() {
                DashboardScreen(
                    DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), current, true), now, {},
                    onResume = {}, onConsent = {}, onStart = {}, onSyncNow = {}, onInviteChild = {}, onOpenSettings = {},
                )
            }
        }
        states.forEach {
            current = it
            rule.waitForIdle()
            rule.onNodeWithTag("dash_banner").assertDoesNotExist()
        }
    }

    @Test
    fun pullToRefreshSyncsNow() {
        var synced = 0
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running, true), onSyncNow = { synced++ })
        rule.onNodeWithTag("dash_list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        rule.waitForIdle()
        assertEquals(1, synced)
    }

    @Test
    fun pullToRefreshDoesNothingWhileSyncIsStopped() {
        // The user's Stop holds: a pull would only spin; the banner's Start is the way back.
        var synced = 0
        val stopped = running.copy(state = ServiceState.STOPPED, stoppedByUser = true)
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), stopped, true), onSyncNow = { synced++ })
        rule.onNodeWithTag("dash_list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        rule.waitForIdle()
        assertEquals(0, synced)
    }

    @Test
    fun stoppedSyncOffersStart() {
        var started = false
        show(DashboardUi(DashboardState(mom, null, emptyList(), emptyList()), running.copy(stoppedByUser = true), true), onStart = { started = true })
        rule.onNodeWithTag("dash_start").performClick()
        rule.waitForIdle()
        assertTrue(started)
    }

    @Test
    fun aDayWithoutDataReadsAsNoDataNotZero() {
        val empty = DayTotal(today, 0, 0)
        val u = usage(kid, 0, 60).copy(today = empty, week = (6 downTo 0).map { DayTotal(today.minusDays(it.toLong()), 0, 0) }, updatedMs = null)
        show(DashboardUi(DashboardState(mom, null, listOf(u), emptyList()), running, true))
        rule.onNodeWithText("No data today").assertExists()
        rule.onNodeWithText("<1 min").assertDoesNotExist()
        rule.onNodeWithText("less than", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRealZeroStillReadsAsADuration() {
        val zero = DayTotal(today, 0, 0, hasData = true)
        val u = usage(kid, 0, null).copy(today = zero)
        show(DashboardUi(DashboardState(mom, null, listOf(u), emptyList()), running, true))
        rule.onNodeWithText("<1 min").assertExists()
        rule.onNodeWithText("No data today").assertDoesNotExist()
    }

    @Test
    fun outOfContactFollowsTheNoContactSetting() {
        val quiet = usage(kid.copy(lastSeenMs = now - 7 * hour), 30, 30)
        show(DashboardUi(DashboardState(mom, null, listOf(quiet), emptyList()), running, true, noContactHours = 6))
        rule.onNodeWithText("No contact", substring = true).assertExists()
    }

    @Test
    fun theCardComparesWithUsualByThisTime() {
        val u = usage(kid, 120, 60).copy(comparison = TodayComparison.ByThisTime(60 * 60_000, 3))
        show(DashboardUi(DashboardState(mom, null, listOf(u), listOf(dad)), running, true))
        rule.onNodeWithText("1 h more than usual by this time").assertExists()
    }

    @Test
    fun aFewMinutesEarlyInTheDayReadAsUsual() {
        // 5 min against a usual 2 min: 3 min apart, inside the 5 min floor though far above 10 %.
        val u = usage(kid, 5, null).copy(comparison = TodayComparison.ByThisTime(2 * 60_000, 3))
        show(DashboardUi(DashboardState(mom, null, listOf(u), listOf(dad)), running, true))
        rule.onNodeWithText("About as usual by this time").assertExists()
        rule.onNodeWithText("more than", substring = true).assertDoesNotExist()
    }

    @Test
    fun aChildInAnotherZoneShowsItsOwnClock() {
        // UTC+14: at another offset than any host zone the tests run in.
        val u = usage(kid, 30, 30).copy(childZoneId = "Pacific/Kiritimati")
        show(DashboardUi(DashboardState(mom, null, listOf(u), emptyList()), running, true))
        rule.onNodeWithTag("dash_data_at", useUnmergedTree = true).assertTextContains("(child's time ", substring = true)
    }

    @Test
    fun withoutAComparisonTheCardComparesNothing() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 120, 60)), listOf(dad)), running, true))
        rule.onNodeWithText("average", substring = true).assertDoesNotExist()
        rule.onNodeWithText("usual", substring = true).assertDoesNotExist()
    }

    @Test
    fun theFigureSaysWhenItsDataIsFrom() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30).copy(updatedMs = now - 5 * 60_000)), emptyList()), running, true))
        rule.onNodeWithTag("dash_data_at", useUnmergedTree = true).assertTextContains("Data as of", substring = true)
        rule.onNodeWithText("may be out of date", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Updated", substring = true).assertDoesNotExist()
    }

    @Test
    fun dataOlderThanTwoHoursMayBeOutOfDate() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30).copy(updatedMs = now - 3 * hour)), emptyList()), running, true))
        rule.onNodeWithTag("dash_data_at", useUnmergedTree = true).assertTextContains("may be out of date", substring = true)
    }

    @Test
    @Config(qualifiers = "ru")
    fun freshnessInRussian() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running, true))
        rule.onNodeWithTag("dash_data_at", useUnmergedTree = true).assertTextContains("Данные на", substring = true)
    }

    @Test
    fun withinTheNoContactSettingTheCardShowsTheDataTime() {
        val quiet = usage(kid.copy(lastSeenMs = now - 7 * hour), 30, 30)
        show(DashboardUi(DashboardState(mom, null, listOf(quiet), emptyList()), running, true, noContactHours = 24))
        rule.onNodeWithText("No contact", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("dash_data_at", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pausedOffersResume() {
        var resumed = false
        show(childUi(Sharing.Paused(now - hour)), onResume = { resumed = true })
        assertNoPauseButton()
        rule.onNodeWithTag("dash_resume").performClick()
        rule.waitForIdle()
        assertTrue(resumed)
    }

    @Test
    fun neverSharedOffersConsent() {
        var consent = false
        show(childUi(Sharing.Never), onConsent = { consent = true })
        assertNoPauseButton()
        rule.onNodeWithTag("dash_consent").performClick()
        rule.waitForIdle()
        assertTrue(consent)
    }

    @Test
    fun leftChildHeaderSaysSo() {
        show(childUi(Sharing.Left))
        rule.onNodeWithText("You left the group", substring = true).assertExists()
        assertNoPauseButton()
        rule.onNodeWithTag("dash_consent").assertDoesNotExist()
    }

    @Test
    fun aFailedSharingChangeIsShown() {
        show(childUi(Sharing.On, failed = true))
        rule.onNodeWithText("Couldn't change sharing", substring = true).assertExists()
    }

    @Test
    fun theDeviceCardNamesItsClickAction() {
        var opened: DeviceId? = null
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running, true), onOpenDevice = { opened = it })
        val card = rule.onNode(SemanticsMatcher("click label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open details for Kid" })
        card.performClick()
        rule.waitForIdle()
        assertEquals(kid.id, opened)
    }

    @Test
    fun noDashboardAfterLoadingShowsAnEmptyState() {
        show(DashboardUi(null, running, true))
        rule.onNodeWithText("No family group").assertExists()
    }

    @Test
    fun aHiddenNotificationShowsTheBannerWithNotificationSettings() {
        show(childUi(Sharing.On).copy(service = running.copy(notificationHidden = true)))
        rule.onNodeWithTag("dash_banner").assertExists()
        rule.onNodeWithText("Notification hidden: your family sees it", substring = true).assertExists()
        rule.onNodeWithTag("dash_notification_settings").assertExists()
    }

    @Test
    fun theUsersStopOutranksAHiddenNotification() {
        assertEquals(SyncAttention.STOPPED, SyncAttention.of(running.copy(stoppedByUser = true, notificationHidden = true)))
        assertEquals(SyncAttention.NOTIFICATION_HIDDEN, SyncAttention.of(running.copy(notificationHidden = true)))
    }

    @Test
    fun aConsentingChildWithoutUsageAccessSeesTheBannerWithItsButton() {
        show(childUi(Sharing.On).copy(service = running.copy(usageAccessGranted = false)))
        rule.onNodeWithTag("dash_banner").assertExists()
        rule.onNodeWithText("usage access is off", substring = true).assertExists()
        rule.onNodeWithTag("dash_usage_access").assertExists()
    }

    @Test
    fun aChildThatDidNotConsentAndAParentGetNoUsageAccessBanner() {
        show(childUi(Sharing.Never).copy(service = running.copy(usageAccessGranted = false)))
        rule.onNodeWithTag("dash_usage_access").assertDoesNotExist()
    }

    @Test
    fun theParentsCardSaysCollectionIsOff() {
        val off = usage(kid, 0, null).copy(today = DayTotal(today, 0, 0), updatedMs = null, collectionOff = true)
        show(DashboardUi(DashboardState(mom, null, listOf(off), emptyList()), running, true))
        rule.onNodeWithText("Screen time collection is off on the child's phone").assertExists()
    }

    @Test
    fun theParentsCardNamesAHiddenNotification() {
        val hidden = usage(kid, 30, 30).copy(updatedMs = now - 30 * hour, notificationHidden = true)
        show(DashboardUi(DashboardState(mom, null, listOf(hidden), emptyList()), running, true))
        rule.onNodeWithText("The Kinsight notification is hidden on the child's phone").assertExists()
        rule.onNodeWithText("collection is off", substring = true).assertDoesNotExist()
    }

    @Test
    fun aHiddenNotificationIsNamedBeforeCollectionOff() {
        val both = usage(kid, 30, 30).copy(updatedMs = now - 30 * hour, collectionOff = true, notificationHidden = true)
        show(DashboardUi(DashboardState(mom, null, listOf(both), emptyList()), running, true))
        rule.onNodeWithText("The Kinsight notification is hidden on the child's phone").assertExists()
        rule.onNodeWithText("collection is off", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru")
    fun theRussianParentsCardNamesAHiddenNotification() {
        val hidden = usage(kid, 30, 30).copy(notificationHidden = true)
        show(DashboardUi(DashboardState(mom, null, listOf(hidden), emptyList()), running, true))
        rule.onNodeWithText("На телефоне ребёнка скрыто уведомление Kinsight").assertExists()
        rule.onNodeWithText("Сбор данных не включён", substring = true).assertDoesNotExist()
    }

    @Test
    fun noContactStillWinsOverCollectionOff() {
        val quiet = usage(kid.copy(lastSeenMs = now - 30 * hour), 30, 30).copy(collectionOff = true)
        show(DashboardUi(DashboardState(mom, null, listOf(quiet), emptyList()), running, true, noContactHours = 24))
        rule.onNodeWithText("No contact", substring = true).assertExists()
        rule.onNodeWithText("collection is off", substring = true).assertDoesNotExist()
    }

    @Test
    fun aParentWithoutUsageAccessGetsNoBanner() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running.copy(usageAccessGranted = false), true))
        rule.onNodeWithTag("dash_usage_access").assertDoesNotExist()
        rule.onNodeWithTag("dash_banner").assertDoesNotExist()
    }

    @Test
    fun aPausedChildWithoutUsageAccessStillSeesTheBanner() {
        show(childUi(Sharing.Paused(now - hour)).copy(service = running.copy(usageAccessGranted = false)))
        rule.onNodeWithTag("dash_usage_access").assertExists()
    }

    @Test
    fun aChildIsToldWhoNewlySeesItsTimeUntilItAcknowledges() {
        val acks = mutableListOf<Set<DeviceId>>()
        show(childUi(Sharing.On).copy(newParents = listOf(dad)), onAcknowledgeParents = { acks += it })
        rule.onNodeWithTag("dash_new_parent").assertExists()
        rule.onNodeWithText("New in your family group: Dad", substring = true).assertExists()
        rule.onNodeWithTag("dash_new_parent_ok").performClick()
        rule.waitForIdle()
        assertEquals("only the parents the banner showed", listOf(setOf(dad.id)), acks)
    }

    @Test
    fun noNewParentBannerWithoutNewParents() {
        show(childUi(Sharing.On))
        rule.onNodeWithTag("dash_new_parent").assertDoesNotExist()
    }

    private fun showLive(service: () -> ServiceStatus) = rule.setContent {
        KinsightTheme() {
            DashboardScreen(
                DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), service(), true), now, {},
                onResume = {}, onConsent = {}, onStart = {}, onSyncNow = {}, onInviteChild = {}, onOpenSettings = {},
                wallClock = { now },
            )
        }
    }

    @Test
    fun aPullSaysItIsLookingUntilANewerSyncArrives() {
        var service by mutableStateOf(running.copy(lastSyncMs = now - hour))
        showLive { service }
        rule.onNodeWithTag("dash_searching").assertDoesNotExist()
        rule.onNodeWithTag("dash_list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        rule.waitForIdle()
        rule.onNodeWithTag("dash_searching").assertExists()
        rule.onNodeWithText("Looking for your family's phones", substring = true).assertExists()
        service = running.copy(lastSyncMs = now + 1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("dash_searching").assertDoesNotExist()
    }

    @Test
    fun theSearchEndsWithTheFastWindow() {
        showLive { running.copy(lastSyncMs = now - hour) }
        rule.onNodeWithTag("dash_list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        rule.waitForIdle()
        rule.onNodeWithTag("dash_searching").assertExists()
        rule.mainClock.advanceTimeBy(SyncService.FAST_MS + 1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("dash_searching").assertDoesNotExist()
    }

    @Test
    fun aChildCardSaysWhereTheChildIs() {
        val inZone = usage(kid, 30, 30).copy(place = PlaceStatus.InZone("aa".repeat(16), "Home", now - 5 * 60_000))
        show(DashboardUi(DashboardState(mom, null, listOf(inZone), listOf(dad)), running, true))
        rule.onNodeWithTag("dash_place", useUnmergedTree = true).assertTextContains("In the zone “Home”", substring = true)
    }

    /** Y4 parked minor: an old place says it may be out of date (like old data), a fresh one does not. */
    @Test
    fun anOldPlaceIsMarkedAsMaybeOutOfDate() {
        val old = usage(kid, 30, 30).copy(place = PlaceStatus.InZone("aa".repeat(16), "Home", now - LocationReadModel.PLACE_STALE_MS - 60_000))
        show(DashboardUi(DashboardState(mom, null, listOf(old), listOf(dad)), running, true))
        rule.onNodeWithTag("dash_place", useUnmergedTree = true).assertTextContains("may be out of date", substring = true)
    }

    @Test
    fun aFreshPlaceIsNotMarked() {
        val fresh = usage(kid, 30, 30).copy(place = PlaceStatus.Outside(null, now - LocationReadModel.PLACE_STALE_MS + 60_000))
        show(DashboardUi(DashboardState(mom, null, listOf(fresh), listOf(dad)), running, true))
        rule.onNode(hasTestTag("dash_place") and hasText("may be out of date", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    /** Y4 Task 12 review Minor 3: a zone this phone does not know (any more) is still «in a zone», without a name. */
    @Test
    fun aChildInAZoneWithoutANameIsSaidToBeInAZone() {
        val inUnknown = usage(kid, 30, 30).copy(place = PlaceStatus.InZone("aa".repeat(16), null, now - 60_000))
        show(DashboardUi(DashboardState(mom, null, listOf(inUnknown), listOf(dad)), running, true))
        rule.onNodeWithTag("dash_place", useUnmergedTree = true).assertTextContains("In a safe zone", substring = true)
        rule.onNode(hasTestTag("dash_place") and hasText("“", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    /** Y4 Task 12 review Minor 3: no word of the child's place yet: no place line at all. */
    @Test
    fun aChildWithoutAPlaceHasNoPlaceLine() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30).copy(place = null)), listOf(dad)), running, true))
        rule.onNodeWithTag("dash_place", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aChildOutsideIsSaidSo() {
        val out = usage(kid, 30, 30).copy(place = PlaceStatus.Outside(null, now - 60_000))
        show(DashboardUi(DashboardState(mom, null, listOf(out), listOf(dad)), running, true))
        rule.onNodeWithTag("dash_place", useUnmergedTree = true).assertTextContains("Outside the safe zones", substring = true)
    }

    @Test
    fun aChildWithAnOldConsentIsAskedToSeeWhatChanged() {
        var consent = false
        show(childUi(Sharing.On).copy(consentOutdated = true), onConsent = { consent = true })
        rule.onNodeWithText("through a parent's Yandex Disk", substring = true).assertExists()
        rule.onNodeWithTag("dash_reconsent_open").performClick()
        rule.waitForIdle()
        assertTrue(consent)
    }

    @Test
    fun aCurrentConsentShowsNoCard() {
        show(childUi(Sharing.On))
        rule.onNodeWithTag("dash_reconsent").assertDoesNotExist()
    }

    /**
     * Y5 T8 parked minor: the pause button is gone with its tag, so a tag check could never fail. Nothing clickable on the
     * screen speaks of pausing (spec §10: no switch on a child's phone).
     */
    private fun assertNoPauseButton() {
        rule.onAllNodes(hasClickAction() and hasText("pause", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
    }
}
