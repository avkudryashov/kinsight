package app.kinsight.feature.settings

import android.util.Log
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.designsystem.component.ErrorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A local wipe in progress or failed (LocalReset.resetIdentity or forgetGroup). */
data class RecoveryUi(val busy: Boolean = false, val failed: Boolean = false)

/**
 * Runs one LocalReset operation at a time on [appScope] (Graph.scope), so leaving the screen cannot cancel a wipe
 * halfway; a failure is logged and shown, and the button retries.
 */
class RecoveryViewModel(private val action: suspend () -> Unit, private val appScope: CoroutineScope) : ViewModel() {
    private val _ui = MutableStateFlow(RecoveryUi())
    val ui: StateFlow<RecoveryUi> = _ui.asStateFlow()

    fun run() {
        if (_ui.value.busy) return
        _ui.value = RecoveryUi(busy = true)
        appScope.launch {
            try {
                action()
                _ui.value = RecoveryUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("KinsightRecovery", "local wipe failed", e)
                _ui.value = RecoveryUi(failed = true)
            }
        }
    }
}

/** The key error root: a confirmed LocalReset.resetIdentity (new keys, everything local wiped). */
@Composable
fun KeyErrorRoute(message: String) {
    val context = LocalContext.current
    val vm: RecoveryViewModel = viewModel(key = "key_error") {
        val g = AppGraph.get(context)
        RecoveryViewModel(g.reset::resetIdentity, g.scope)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    KeyErrorScreen(message, vm::run, ui.busy, ui.failed)
}

/** The removed root: LocalReset.forgetGroup (the identity and the device name stay), then the first run. */
@Composable
fun RemovedRoute() {
    val context = LocalContext.current
    val vm: RecoveryViewModel = viewModel(key = "removed") {
        val g = AppGraph.get(context)
        RecoveryViewModel(g.reset::forgetGroup, g.scope)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    RemovedScreen(vm::run, ui.busy, ui.failed)
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
internal fun ResetIdentityDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.set_reset_body))
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
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}
