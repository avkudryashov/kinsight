package app.kinsight.feature.dashboard

import androidx.compose.runtime.Composable
import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.TodayComparison
import app.kinsight.core.designsystem.preview.PreviewTheme
import app.kinsight.core.designsystem.preview.ThemePreviews
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import java.time.LocalDate

private const val NOW = 1_760_000_000_000L
private val TODAY = LocalDate.of(2026, 10, 10)
private val MOM = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
private val DAD = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = NOW)
private val KID = MemberView(DeviceId("cc".repeat(16)), "Masha", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = NOW)
private val RUNNING = ServiceStatus(ServiceState.RUNNING, false, NOW - 300_000, emptyList(), false, null)

private fun usage(m: MemberView) = DeviceUsage(
    m, DayTotal(TODAY, 95 * 60_000L, 31),
    (6 downTo 0).map { DayTotal(TODAY.minusDays(it.toLong()), (60L + it * 15) * 60_000L, 20) }, 80 * 60_000L, Sharing.On, NOW - 20 * 60_000,
    comparison = TodayComparison.ByThisTime(70 * 60_000L, 7),
)

@Composable
private fun Frame(ui: DashboardUi) = PreviewTheme {
    DashboardScreen(
        ui, NOW, {}, onResume = {}, onConsent = {}, onStart = {}, onSyncNow = {}, onInviteChild = {}, onOpenSettings = {},
        wallClock = { NOW },
    )
}

@ThemePreviews
@Composable
internal fun DashboardParentPreview() = Frame(DashboardUi(DashboardState(MOM, null, listOf(usage(KID)), listOf(DAD)), RUNNING, true))

@ThemePreviews
@Composable
internal fun DashboardChildPreview() {
    val self = KID.copy(isSelf = true, lastSeenMs = null)
    Frame(
        DashboardUi(
            DashboardState(self, usage(self), emptyList(), listOf(MOM.copy(isSelf = false), DAD)),
            RUNNING.copy(usageAccessGranted = false), true, newParents = listOf(DAD),
        ),
    )
}
