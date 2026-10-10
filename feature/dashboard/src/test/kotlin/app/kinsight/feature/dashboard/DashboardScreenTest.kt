package app.kinsight.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
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
        onPause: () -> Unit = {},
        onStart: () -> Unit = {},
        onResume: () -> Unit = {},
        onConsent: () -> Unit = {},
        onOpenDevice: (DeviceId) -> Unit = {},
        onSyncNow: () -> Unit = {},
        onInviteChild: () -> Unit = {},
    ) = rule.setContent {
        KinsightTheme(dynamicColor = false) {
            DashboardScreen(
                ui, now, onOpenDevice, onPause = onPause, onResume = onResume, onConsent = onConsent, onStart = onStart,
                onSyncNow = onSyncNow, onInviteChild = onInviteChild,
            )
        }
    }

    private fun childUi(sharing: Sharing, failed: Boolean = false): DashboardUi {
        val self = kid.copy(isSelf = true)
        return DashboardUi(DashboardState(self, usage(self, 30, 30, sharing), emptyList(), listOf(mom.copy(isSelf = false))), running, true, sharingFailed = failed)
    }

    @Test
    fun childHeaderNamesTheParentsAndPauseTakesTwoTaps() {
        var paused = false
        val self = kid.copy(isSelf = true)
        show(DashboardUi(DashboardState(self, usage(self, 30, 30), emptyList(), listOf(mom.copy(isSelf = false), dad)), running, true), onPause = { paused = true })
        rule.onNodeWithText("Mom, Dad", substring = true).assertExists()
        rule.onNodeWithText("your parents are told", substring = true).assertExists()
        rule.onNodeWithTag("dash_pause").performClick()
        rule.waitForIdle()
        assertFalse(paused)
        rule.onNodeWithText("sent when you resume", substring = true).assertExists()
        rule.onNodeWithTag("dash_pause_confirm").performClick()
        rule.waitForIdle()
        assertTrue(paused)
    }

    @Test
    fun parentSeesTheChildCardWithTheComparison() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 120, 60)), listOf(dad)), running, true))
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
        rule.onNodeWithTag("dash_pause").assertDoesNotExist()
        rule.onNodeWithTag("dash_consent").assertDoesNotExist()
    }

    @Test
    fun childSeesTheTransparencyRowAndItsOwnCardButNoThisPhone() {
        show(childUi(Sharing.On))
        rule.onNodeWithText("Your screen time is visible to: Mom").assertExists()
        rule.onNodeWithTag("dash_pause").assertExists()
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
    fun aKeyErrorShowsABanner() = bannerFor(running.copy(state = ServiceState.STORAGE_ERROR), "Key storage error")

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
            KinsightTheme(dynamicColor = false) {
                DashboardScreen(
                    DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), current, true), now, {},
                    onPause = {}, onResume = {}, onConsent = {}, onStart = {}, onSyncNow = {}, onInviteChild = {},
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
    fun withinTheNoContactSettingTheCardReadsUpdated() {
        val quiet = usage(kid.copy(lastSeenMs = now - 7 * hour), 30, 30)
        show(DashboardUi(DashboardState(mom, null, listOf(quiet), emptyList()), running, true, noContactHours = 24))
        rule.onNodeWithText("No contact", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Updated", substring = true).assertExists()
    }

    @Test
    fun aCardUpdatedMomentsAgoReadsJustNow() {
        // Emulator run 2 (0.4.1): «Updated 0 minutes ago» next to «Last sync: just now».
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30).copy(updatedMs = now - 20_000)), emptyList()), running, true))
        rule.onNodeWithText("Updated just now").assertExists()
        rule.onNodeWithText("0 minutes", substring = true).assertDoesNotExist()
    }

    @Test
    fun aCardUpdatedEarlierReadsInThePast() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30).copy(updatedMs = now - 5 * 60_000)), emptyList()), running, true))
        rule.onNodeWithText("Updated 5 minutes ago").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun aCardUpdatedMomentsAgoReadsJustNowInRussian() {
        show(DashboardUi(DashboardState(mom, null, listOf(usage(kid, 30, 30)), emptyList()), running, true))
        rule.onNodeWithText("Обновлено только что").assertExists()
    }

    @Test
    fun cancellingThePauseDialogDoesNotPause() {
        var paused = false
        show(childUi(Sharing.On), onPause = { paused = true })
        rule.onNodeWithTag("dash_pause").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertFalse(paused)
        rule.onNodeWithTag("dash_pause_confirm").assertDoesNotExist()
    }

    @Test
    fun pausedOffersResume() {
        var resumed = false
        show(childUi(Sharing.Paused(now - hour)), onResume = { resumed = true })
        rule.onNodeWithTag("dash_pause").assertDoesNotExist()
        rule.onNodeWithTag("dash_resume").performClick()
        rule.waitForIdle()
        assertTrue(resumed)
    }

    @Test
    fun neverSharedOffersConsent() {
        var consent = false
        show(childUi(Sharing.Never), onConsent = { consent = true })
        rule.onNodeWithTag("dash_pause").assertDoesNotExist()
        rule.onNodeWithTag("dash_consent").performClick()
        rule.waitForIdle()
        assertTrue(consent)
    }

    @Test
    fun leftChildHeaderSaysSo() {
        show(childUi(Sharing.Left))
        rule.onNodeWithText("You left the group", substring = true).assertExists()
        rule.onNodeWithTag("dash_pause").assertDoesNotExist()
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
}
