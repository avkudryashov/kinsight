package app.kinsight.feature.group

import androidx.compose.runtime.Composable
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.preview.PreviewTheme
import app.kinsight.core.designsystem.preview.ThemePreviews
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId

private const val NOW = 1_760_000_000_000L
private val MOM = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
private val DAD = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = NOW - 600_000)
private val KID = MemberView(DeviceId("cc".repeat(16)), "Masha", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = NOW - 60_000)

@ThemePreviews
@Composable
internal fun GroupPreview() = PreviewTheme {
    GroupScreen(
        GroupState.Ready(GroupId("dd".repeat(16)), MOM, listOf(MOM, DAD, KID)), NOW, leaving = false,
        onInvite = {}, onGrantAdmin = {}, onRemove = {}, onLeave = {},
    )
}

@ThemePreviews
@Composable
internal fun InvitePreview() = PreviewTheme {
    InviteScreen(
        InviteState.Showing("kinsight://pair/preview", expiresAtMs = NOW + 240_000), Role.CHILD, NOW,
        onConfirm = {}, onDecline = {}, onRetry = {}, onClose = {},
    )
}

@ThemePreviews
@Composable
internal fun JoinPreview() = PreviewTheme { JoinScreen(JoinState.Waiting("Mom"), "Mom", onRetry = {}, onResetAndRetry = {}, onBack = {}) }
