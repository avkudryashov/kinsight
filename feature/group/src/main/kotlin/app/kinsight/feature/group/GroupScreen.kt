package app.kinsight.feature.group

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LeaveError
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.ErrorState
import app.kinsight.core.designsystem.component.isJustNow
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.delay

private sealed interface GroupAction {
    data class GrantAdmin(val member: MemberView) : GroupAction

    data class Remove(val member: MemberView) : GroupAction

    data object Leave : GroupAction
}

/**
 * The group tab. After a successful leave the snapshot reads NoGroup and the root returns to the first run on its
 * own, so there is no «left» callback.
 */
@Composable
fun GroupRoute(onInvite: (Role) -> Unit) {
    val context = LocalContext.current
    val vm: GroupViewModel = viewModel { GroupViewModel(AppGraph.get(context).group) }
    val state by vm.state.collectAsStateWithLifecycle()
    val leaving by vm.leaving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nowMs by rememberNowMs()
    when (val s = state) {
        is GroupState.Ready -> GroupScreen(s, nowMs, leaving, onInvite, vm::grantAdmin, vm::remove, vm::leave)
        GroupState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        GroupState.Leaving -> LeavingContent()
        GroupState.NoGroup -> EmptyState(stringResource(R.string.grp_no_group_title), stringResource(R.string.grp_no_group_body))
        GroupState.Removed -> EmptyState(stringResource(R.string.grp_removed_title), stringResource(R.string.grp_removed_body))
        is GroupState.KeyError -> ErrorState(stringResource(R.string.grp_unavailable_title), s.message)
        is GroupState.StorageError -> ErrorState(stringResource(R.string.grp_unavailable_title), s.message)
    }
    error?.let { GroupErrorDialog(it, vm::dismissError) }
}

/** GroupState.Leaving: LEFT_GROUP is signed and a session is carrying it to the family before the local wipe. */
@Composable
internal fun LeavingContent() {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("grp_leaving").semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.grp_leaving),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@StringRes
internal fun GroupError.messageRes(): Int = when (this) {
    is GroupError.Leave -> when (reason) {
        LeaveError.NO_GROUP -> R.string.grp_err_leave_no_group
        LeaveError.SOLE_ADMIN -> R.string.grp_err_leave_sole_admin
        LeaveError.NOT_SIGNED -> R.string.grp_err_leave_not_signed
        LeaveError.WIPE_FAILED -> R.string.grp_err_leave_wipe_failed
    }
    GroupError.GrantFailed -> R.string.grp_err_grant
    GroupError.RemoveFailed -> R.string.grp_err_remove
}

@Composable
internal fun GroupErrorDialog(error: GroupError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
        title = { Text(stringResource(R.string.grp_err_title), color = MaterialTheme.colorScheme.error) },
        text = { Text(stringResource(error.messageRes()), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("grp_error_ok")) { Text(stringResource(R.string.grp_ok)) } },
    )
}

@Composable
private fun roleText(role: Role) = stringResource(if (role == Role.CHILD) R.string.grp_child else R.string.grp_parent)

/** Spec §9.5: members with roles and contact, invites and admin actions, leaving with its consequence spelled out. */
@Composable
fun GroupScreen(
    state: GroupState.Ready,
    nowMs: Long,
    leaving: Boolean,
    onInvite: (Role) -> Unit,
    onGrantAdmin: (DeviceId) -> Unit,
    onRemove: (DeviceId) -> Unit,
    onLeave: () -> Unit,
) {
    val self = state.self
    var pending by remember { mutableStateOf<GroupAction?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("grp_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                stringResource(R.string.grp_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (GroupRules.needsSecondAdmin(self, state.members)) {
            item {
                Card(
                    Modifier.testTag("grp_second_admin"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(stringResource(R.string.grp_second_admin), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        if (GroupRules.canInvite(self)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onInvite(Role.CHILD) }, Modifier.fillMaxWidth().testTag("grp_invite_child")) { Text(stringResource(R.string.grp_invite_child)) }
                    OutlinedButton({ onInvite(Role.PARENT) }, Modifier.fillMaxWidth().testTag("grp_invite_parent")) {
                        Text(stringResource(R.string.grp_invite_parent))
                    }
                }
            }
        }
        items(state.members, key = { it.id.hex }) { m -> MemberRow(m, self, nowMs) { pending = it } }
        item {
            val canLeave = GroupRules.canLeave(state)
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leaving) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.grp_leaving), color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(
                    onClick = { pending = GroupAction.Leave },
                    enabled = canLeave && !leaving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("grp_leave"),
                ) { Text(stringResource(R.string.grp_leave)) }
                if (!canLeave) {
                    Text(
                        stringResource(R.string.grp_leave_sole_admin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    pending?.let { action ->
        val (title, body) = when (action) {
            is GroupAction.GrantAdmin -> stringResource(R.string.grp_grant_title, action.member.name) to stringResource(R.string.grp_grant_body)
            is GroupAction.Remove -> stringResource(R.string.grp_remove_title, action.member.name) to stringResource(R.string.grp_remove_body)
            GroupAction.Leave -> stringResource(R.string.grp_leave_title) to stringResource(R.string.grp_leave_body)
        }
        val confirmLabel = stringResource(
            when (action) {
                is GroupAction.GrantAdmin -> R.string.grp_make_admin
                is GroupAction.Remove -> R.string.grp_confirm_remove
                GroupAction.Leave -> R.string.grp_confirm_leave
            },
        )
        val destructive = action !is GroupAction.GrantAdmin
        AlertDialog(
            onDismissRequest = { pending = null },
            modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            is GroupAction.GrantAdmin -> onGrantAdmin(action.member.id)
                            is GroupAction.Remove -> onRemove(action.member.id)
                            GroupAction.Leave -> onLeave()
                        }
                        pending = null
                    },
                    modifier = Modifier.testTag("grp_confirm"),
                    colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
                ) { Text(confirmLabel) }
            },
            dismissButton = { TextButton({ pending = null }, Modifier.testTag("grp_cancel")) { Text(stringResource(R.string.grp_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(m: MemberView, self: MemberView, nowMs: Long, onAction: (GroupAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val grant = GroupRules.canGrantAdmin(self, m)
    val remove = GroupRules.canRemove(self, m)
    val seen = if (m.isSelf) {
        stringResource(R.string.grp_this_phone)
    } else {
        m.lastSeenMs?.let {
            if (isJustNow(it, nowMs)) stringResource(R.string.grp_seen_now) else stringResource(R.string.grp_seen, relativeTime(it, nowMs))
        } ?: stringResource(R.string.grp_not_seen)
    }
    val role = roleText(m.role) + if (m.isAdmin) " · " + stringResource(R.string.grp_admin) else ""
    ListItem(
        headlineContent = { Text(m.name) },
        supportingContent = { Text("$role · $seen") },
        trailingContent = if (grant || remove) {
            {
                Box {
                    IconButton({ menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.grp_actions_for, m.name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (grant) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.grp_make_admin)) }, onClick = {
                                menu = false
                                onAction(GroupAction.GrantAdmin(m))
                            })
                        }
                        if (remove) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.grp_remove)) }, onClick = {
                                menu = false
                                onAction(GroupAction.Remove(m))
                            })
                        }
                    }
                }
            }
        } else {
            null
        },
    )
}

/** The wall clock, re-read every [periodMs] so «seen 5 minutes ago» stays current while the screen is open. */
@Composable
internal fun rememberNowMs(clock: () -> Long = System::currentTimeMillis, periodMs: Long = 60_000L): State<Long> =
    produceState(clock(), clock, periodMs) {
        while (true) {
            delay(periodMs)
            value = clock()
        }
    }
