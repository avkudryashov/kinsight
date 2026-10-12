package app.kinsight.feature.settings

import androidx.compose.runtime.Composable
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.designsystem.preview.PreviewTheme
import app.kinsight.core.designsystem.preview.ThemePreviews
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.usage.UsageSettings

private const val NOW = 1_760_000_000_000L
private val GROUP = GroupId("dd".repeat(16))
private val MOM = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = NOW - 600_000)
private val KID = MemberView(DeviceId("cc".repeat(16)), "Masha", Role.CHILD, isAdmin = false, isSelf = true, lastSeenMs = null)
private val RUNNING = ServiceStatus(ServiceState.RUNNING, false, NOW - 300_000, emptyList(), false, null)

@Composable
private fun Frame(ui: SettingsUi) = PreviewTheme {
    SettingsScreen(
        ui, "0.8.0", onSharing = {}, onConsent = {}, onTransparency = {}, onStopSync = {}, onStartSync = {}, onRetention = { _, _ -> },
        onSummaryMode = {}, onNoContactHours = {}, onBattery = {}, onDiagnostics = {}, onResetIdentity = {}, nowMs = NOW,
    )
}

@ThemePreviews
@Composable
internal fun SettingsChildPreview() =
    Frame(SettingsUi(GroupState.Ready(GROUP, KID, listOf(KID, MOM)), UsageSettings(sharing = true, consentAtMs = 1), Prefs(), RUNNING))

@ThemePreviews
@Composable
internal fun SettingsParentPreview() {
    val mom = MOM.copy(isSelf = true, lastSeenMs = null)
    Frame(SettingsUi(GroupState.Ready(GROUP, mom, listOf(mom, KID.copy(isSelf = false))), UsageSettings(), Prefs(), RUNNING))
}

@ThemePreviews
@Composable
internal fun TransparencyPreview() = PreviewTheme {
    TransparencyScreen(GroupState.Ready(GROUP, KID, listOf(KID, MOM)), UsageSettings(sharing = true), NOW, onBack = {})
}

@ThemePreviews
@Composable
internal fun KeyErrorPreview() = PreviewTheme { KeyErrorScreen("KeyStorageException: key missing", onReset = {}) }

@ThemePreviews
@Composable
internal fun RemovedPreview() = PreviewTheme { RemovedScreen(onStartOver = {}) }
