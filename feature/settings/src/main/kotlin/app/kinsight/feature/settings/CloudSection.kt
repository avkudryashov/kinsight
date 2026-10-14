package app.kinsight.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinsight.core.data.CloudEnableResult
import app.kinsight.core.data.CloudProblem
import app.kinsight.core.data.CloudStatus
import app.kinsight.core.data.SignInState
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.isJustNow
import app.kinsight.core.designsystem.component.relativeTime

/**
 * «Синхронизировать через Яндекс Диск» (spec §6, §11). Off: a switch for an admin parent (the sign-in starts from it),
 * nothing for others. On: the storage owner, the last cloud sync and what the user can act on; the switch shows the state
 * and cannot turn the cloud off (that is the owner lifecycle, Y3); a child's phone has no switch at all (spec §10).
 */
internal fun LazyListScope.cloudSection(s: CloudStatus, nowMs: Long, onSignIn: () -> Unit) {
    if (!s.available || !(s.on || s.canEnable)) return
    item {
        val switch: (@Composable () -> Unit)? = if (s.isChild) {
            null
        } else {
            {
                Switch(
                    checked = s.on,
                    onCheckedChange = { on -> if (on && !s.on) onSignIn() },
                    enabled = !s.on,
                    modifier = Modifier.testTag("set_cloud_switch"),
                )
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_cloud)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!s.on) {
                        Text(stringResource(R.string.set_cloud_off_body))
                    } else {
                        Text(if (s.selfIsOwner) stringResource(R.string.set_cloud_owner_self) else stringResource(R.string.set_cloud_owner, s.ownerName.orEmpty()))
                        Text(cloudLastSync(s.lastSyncMs, nowMs))
                        cloudProblemText(s.problem)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("set_cloud_problem"))
                        }
                        // Spec §7.4: the same sign-in renews an exposed token.
                        if ((s.problem == CloudProblem.UNAUTHORIZED || s.problem == CloudProblem.RENEW_ACCESS) && s.selfIsOwner) {
                            OutlinedButton(onSignIn, Modifier.testTag("set_cloud_restore")) { Text(stringResource(R.string.set_cloud_sign_in)) }
                        }
                    }
                }
            },
            trailingContent = switch,
            modifier = Modifier.testTag("set_cloud"),
        )
    }
}

/**
 * The browser shows a code on the verification page: it is pasted here. An app link finishes the sign-in by itself.
 * [SignInState.Waiting] keeps the paste field after a paste that held no code or a token endpoint out of reach, with
 * the reason under it, so the user tries again. A failed [SignInState.Done] (the code is spent or the sign-in expired)
 * offers to start again from the browser ([onSignIn]); a result the user cannot fix from here only has «OK».
 */
@Composable
internal fun CloudSignInDialog(state: SignInState, onCode: (String) -> Unit, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    // A new sign-in (a new address) starts with an empty field: a spent code must not be sent to it.
    var code by rememberSaveable((state as? SignInState.Waiting)?.url) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
        title = { Text(stringResource(R.string.set_cloud_sign_in)) },
        text = {
            when (state) {
                is SignInState.Waiting -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_cloud_dialog_body))
                    val error = when {
                        state.networkError -> R.string.set_cloud_err_network
                        state.rejected -> R.string.set_cloud_err_sign_in
                        else -> null
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.set_cloud_code)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(stringResource(it), Modifier.testTag("set_cloud_code_error")) } },
                        modifier = Modifier.fillMaxWidth().testTag("set_cloud_code"),
                    )
                }
                SignInState.Working -> Text(stringResource(R.string.set_cloud_working))
                is SignInState.Done -> Text(enableResultText(state.result), modifier = Modifier.testTag("set_cloud_result"))
                SignInState.Idle -> Unit
            }
        },
        confirmButton = {
            when (state) {
                is SignInState.Waiting -> TextButton({ onCode(code) }, enabled = code.isNotBlank(), modifier = Modifier.testTag("set_cloud_done")) {
                    Text(stringResource(R.string.set_cloud_done))
                }
                is SignInState.Done -> if (startsAgain(state.result)) {
                    TextButton(onSignIn, Modifier.testTag("set_cloud_retry")) { Text(stringResource(R.string.set_cloud_sign_in)) }
                } else {
                    TextButton(onDismiss, Modifier.testTag("set_cloud_ok")) { Text(stringResource(R.string.set_ok)) }
                }
                SignInState.Working, SignInState.Idle -> Unit
            }
        },
        dismissButton = {
            if (state is SignInState.Waiting || (state is SignInState.Done && startsAgain(state.result))) {
                TextButton(onDismiss, Modifier.testTag("set_cloud_cancel")) { Text(stringResource(R.string.set_cloud_cancel)) }
            }
        },
    )
}

/** The sign-in ended in a way a new sign-in can fix: refused, expired, or the Disk failed after the exchange. */
private fun startsAgain(r: CloudEnableResult): Boolean =
    r == CloudEnableResult.SIGN_IN_FAILED || r == CloudEnableResult.SIGN_IN_EXPIRED || r == CloudEnableResult.NETWORK

@Composable
private fun cloudLastSync(last: Long?, nowMs: Long): String = when {
    last == null -> stringResource(R.string.set_cloud_never)
    isJustNow(last, nowMs) -> stringResource(R.string.set_cloud_last_sync_now)
    else -> stringResource(R.string.set_cloud_last_sync, relativeTime(last, nowMs))
}

/** Spec §11. Network trouble is retried quietly: the last sync time says enough. */
@Composable
private fun cloudProblemText(p: CloudProblem?): String? = when (p) {
    CloudProblem.UNAUTHORIZED, CloudProblem.RENEW_ACCESS -> stringResource(R.string.set_cloud_restore)
    CloudProblem.NO_ACCESS -> stringResource(R.string.set_cloud_wait)
    CloudProblem.STORAGE_FULL -> stringResource(R.string.set_cloud_full)
    CloudProblem.NETWORK, null -> null
}

@Composable
private fun enableResultText(r: CloudEnableResult): String = stringResource(
    when (r) {
        CloudEnableResult.ENABLED, CloudEnableResult.RESTORED, CloudEnableResult.TAKEN_OVER -> R.string.set_cloud_on_done
        CloudEnableResult.OLD_APP_IN_GROUP -> R.string.set_cloud_err_old_app
        CloudEnableResult.SIGN_IN_FAILED -> R.string.set_cloud_err_sign_in
        CloudEnableResult.NETWORK -> R.string.set_cloud_err_network
        CloudEnableResult.SIGN_IN_EXPIRED -> R.string.set_cloud_err_expired
        CloudEnableResult.STORAGE_FULL -> R.string.set_cloud_full
        CloudEnableResult.NOT_CONFIGURED, CloudEnableResult.NOT_ADMIN, CloudEnableResult.ALREADY_ON, CloudEnableResult.NOT_ALLOWED -> R.string.set_cloud_err_other
    },
)
