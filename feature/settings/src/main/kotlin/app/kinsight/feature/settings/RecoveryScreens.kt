package app.kinsight.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.ErrorState

/** The key error root: a confirmed LocalReset.resetIdentity (new keys, everything local wiped), status app-wide. */
@Composable
fun KeyErrorRoute(message: String) {
    val context = LocalContext.current
    val reset = remember { AppGraph.get(context).reset }
    val status by reset.status.collectAsStateWithLifecycle()
    KeyErrorScreen(
        message,
        onReset = { reset.launchResetIdentity() },
        busy = status is ResetStatus.Running,
        failed = (status as? ResetStatus.Failed)?.kind == ResetKind.NEW_IDENTITY,
    )
}

/**
 * The removed root (removed by an admin, or a leave whose wipe failed): LocalReset.resetIdentity keeping the device
 * name, then the first run. The departed DeviceId can never join again (spec §9.5), so «start over» takes new keys.
 */
@Composable
fun RemovedRoute() {
    val context = LocalContext.current
    val reset = remember { AppGraph.get(context).reset }
    val status by reset.status.collectAsStateWithLifecycle()
    RemovedScreen(
        onStartOver = { reset.launchResetIdentity(keepName = true) },
        busy = status is ResetStatus.Running,
        failed = (status as? ResetStatus.Failed)?.kind == ResetKind.NEW_IDENTITY,
    )
}

/** Spec §6.2: a broken identity is an error with one way out, never a silent new identity; the way out asks first. */
@Composable
fun KeyErrorScreen(message: String, onReset: () -> Unit, busy: Boolean = false, failed: Boolean = false) {
    var asking by rememberSaveable { mutableStateOf(false) }
    ErrorState(
        title = stringResource(R.string.rec_key_title),
        body = stringResource(R.string.rec_key_body, message) + if (failed) "\n\n" + stringResource(R.string.set_error_reset) else "",
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Button({ asking = true }, Modifier.testTag("rec_reset"), enabled = !busy) {
            Text(stringResource(if (busy) R.string.set_resetting else R.string.set_reset))
        }
    }
    if (asking) ResetIdentityDialog(onConfirm = onReset, onDismiss = { asking = false })
}

@Composable
fun RemovedScreen(onStartOver: () -> Unit, busy: Boolean = false, failed: Boolean = false) = ErrorState(
    title = stringResource(R.string.rec_removed_title),
    body = stringResource(R.string.rec_removed_body) + if (failed) "\n\n" + stringResource(R.string.rec_start_over_failed) else "",
    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
) { Button(onStartOver, Modifier.testTag("rec_start_over"), enabled = !busy) { Text(stringResource(R.string.rec_start_over)) } }

/**
 * The strong confirmation of an identity reset: every consequence in plain words, and the destructive button stays
 * disabled until the user ticks «I understand» (tag set_reset_ack).
 */
@Composable
internal fun ResetIdentityDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, cloudOff: Boolean = false) {
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
        title = { Text(stringResource(R.string.set_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.set_reset_body))
                // Spec §7.1: the owner leaving with no parent to take the storage is warned that the cloud turns off.
                if (cloudOff) Text(stringResource(R.string.set_cloud_leave_off))
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(understood, role = Role.Checkbox, onValueChange = { understood = it })
                        .testTag("set_reset_ack"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Text(stringResource(R.string.set_reset_ack))
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    onDismiss()
                    onConfirm()
                },
                Modifier.testTag("set_reset_confirm"),
                enabled = understood,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.set_reset_confirm)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("set_reset_cancel")) { Text(stringResource(R.string.set_cancel)) } },
    )
}
