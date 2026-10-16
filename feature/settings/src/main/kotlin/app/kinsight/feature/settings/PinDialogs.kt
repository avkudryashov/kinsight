package app.kinsight.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.kinsight.core.data.ParentPins
import app.kinsight.core.data.PinCheck
import app.kinsight.core.designsystem.component.dialogTestTags

/** Digits only, at most the longest PIN; held in `remember`, never in saved state (it could be written to disk). */
@Composable
private fun PinField(value: String, onValue: (String) -> Unit, label: Int, tag: String) = OutlinedTextField(
    value = value,
    onValueChange = { onValue(it.filter { c -> c in '0'..'9' }.take(ParentPins.LENGTH.last)) },
    label = { Text(stringResource(label)) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    modifier = Modifier.testTag(tag),
)

/** Spec §10 «по PIN на телефоне ребёнка»: the parent types the PIN; the outcome of the last try is said under it. */
@Composable
internal fun UnlockDialog(lock: LockUi, nowMs: Long, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val waitUntil = lock.status.waitUntilMs?.takeIf { it > nowMs }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
        title = { Text(stringResource(R.string.set_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, { pin = it }, R.string.set_pin_title, "set_pin_field")
                val note = when {
                    waitUntil != null -> stringResource(R.string.set_pin_wait, DateUtils.formatDateTime(context, waitUntil, DateUtils.FORMAT_SHOW_TIME))
                    lock.lastCheck == PinCheck.WRONG -> stringResource(R.string.set_pin_wrong)
                    lock.lastCheck == PinCheck.NO_PIN -> stringResource(R.string.set_parent_unlock_no_pin)
                    else -> null
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                {
                    onUnlock(pin)
                    pin = ""
                },
                enabled = waitUntil == null && ParentPins.valid(pin),
                modifier = Modifier.testTag("set_pin_ok"),
            ) { Text(stringResource(R.string.set_pin_ok)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("set_pin_cancel")) { Text(stringResource(R.string.set_cancel)) } },
    )
}

/** An admin parent's new family PIN, typed twice. */
@Composable
internal fun SetPinDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogTestTags(),
        title = { Text(stringResource(R.string.set_pin_section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, { pin = it }, R.string.set_pin_new, "set_pin_new")
                PinField(again, { again = it }, R.string.set_pin_repeat, "set_pin_repeat")
            }
        },
        confirmButton = {
            TextButton({ onSave(pin) }, enabled = ParentPins.valid(pin) && pin == again, modifier = Modifier.testTag("set_pin_save")) {
                Text(stringResource(R.string.set_pin_save))
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("set_pin_save_cancel")) { Text(stringResource(R.string.set_cancel)) } },
    )
}
