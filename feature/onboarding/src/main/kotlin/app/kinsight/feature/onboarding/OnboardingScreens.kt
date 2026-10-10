package app.kinsight.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One onboarding page: a heading, scrollable text, actions at the bottom; edge-to-edge safe. */
@Composable
internal fun Page(title: String, actions: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            body()
        }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
internal fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
internal fun ErrorNote(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)

@Composable
internal fun Primary(text: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) =
    Button(onClick, Modifier.fillMaxWidth().testTag(tag), enabled = enabled) { Text(text) }

@Composable
internal fun Secondary(text: String, tag: String, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.fillMaxWidth().testTag(tag)) { Text(text) }

/** [message]: an invite from a deep link that could not be read is reported here, before any path is chosen. */
@Composable
fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit, message: String? = null) = Page(
    title = stringResource(R.string.onb_welcome_title),
    actions = {
        Primary(stringResource(R.string.onb_create), "onb_create", onCreate)
        Secondary(stringResource(R.string.onb_join), "onb_join", onJoin)
    },
) {
    Body(stringResource(R.string.onb_welcome_body))
    Body(stringResource(R.string.onb_create_hint))
    Body(stringResource(R.string.onb_join_hint))
    message?.let { ErrorNote(it) }
}

@Composable
internal fun NameScreen(name: String, invalid: Boolean, onName: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) = Page(
    title = stringResource(R.string.onb_name_title),
    actions = {
        Primary(stringResource(R.string.onb_continue), "onb_continue", onNext)
        TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_back)) }
    },
) {
    Body(stringResource(R.string.onb_name_body))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.onb_name_label)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.onb_name_error)) }) else null,
        modifier = Modifier.fillMaxWidth().testTag("onb_name"),
    )
}

@Composable
internal fun ParentInfoScreen(onNext: () -> Unit) = Page(
    title = stringResource(R.string.onb_parent_title),
    actions = { Primary(stringResource(R.string.onb_continue), "onb_continue", onNext) },
) {
    Body(stringResource(R.string.onb_parent_body))
    Body(stringResource(R.string.onb_parent_privacy))
}

/**
 * Research §6.3, a child's phone only: a plain line for the child first, then what is collected, who sees it (every
 * parent of the group including [recipients], and parents who join later), background counting, the 7-day backlog of the first collection, the persistent notification,
 * an open pause the parents see, Play Protect. Explicit «Agree» / «Not now»; nothing is read before «Agree».
 */
@Composable
fun ConsentScreen(recipients: String, onAgree: () -> Unit, onNotNow: () -> Unit, error: String? = null) = Page(
    title = stringResource(R.string.onb_consent_title),
    actions = {
        Primary(stringResource(R.string.onb_agree), "onb_agree", onAgree)
        Secondary(stringResource(R.string.onb_not_now), "onb_not_now", onNotNow)
    },
) {
    Body(stringResource(R.string.onb_consent_child))
    Body(if (recipients.isBlank()) stringResource(R.string.onb_consent_body_group) else stringResource(R.string.onb_consent_body, recipients))
    Body(stringResource(R.string.onb_consent_backlog))
    Body(stringResource(R.string.onb_consent_privacy))
    Body(stringResource(R.string.onb_consent_pause))
    Body(stringResource(R.string.onb_consent_protect))
    Body(stringResource(R.string.onb_consent_not_now))
    error?.let { ErrorNote(it) }
}

/**
 * A disclosure right before a system request (research §6.1): an app screen, never a lookalike of the system dialog.
 * [onOpenSettings]: the permission was denied for good, so the app's settings page is offered instead.
 */
@Composable
fun DisclosureScreen(
    title: String,
    body: String,
    agreeLabel: String,
    onAgree: () -> Unit,
    onNotNow: () -> Unit,
    note: String? = null,
    onOpenSettings: (() -> Unit)? = null,
) = Page(
    title = title,
    actions = {
        if (onOpenSettings != null) {
            Primary(stringResource(R.string.onb_open_settings), "onb_open_settings", onOpenSettings)
        } else {
            Primary(agreeLabel, "onb_agree", onAgree)
        }
        Secondary(stringResource(R.string.onb_not_now), "onb_not_now", onNotNow)
    },
) {
    Body(body)
    note?.let { ErrorNote(it) }
}

@Composable
internal fun ScanScreen(message: String?, onScan: () -> Unit, onBack: () -> Unit) = Page(
    title = stringResource(R.string.onb_scan_title),
    actions = {
        Primary(stringResource(R.string.onb_scan_button), "onb_scan", onScan)
        TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_back)) }
    },
) {
    Body(stringResource(R.string.onb_scan_body))
    message?.let { ErrorNote(it) }
}

@Composable
fun BatteryScreen(vendor: BatteryVendor, unrestricted: Boolean, onOpenSettings: () -> Unit, onNext: () -> Unit) = Page(
    title = stringResource(R.string.onb_battery_title),
    actions = {
        if (!unrestricted) Secondary(stringResource(R.string.onb_battery_open), "onb_battery_open", onOpenSettings)
        Primary(stringResource(R.string.onb_continue), "onb_continue", onNext)
    },
) {
    Body(stringResource(R.string.onb_battery_body))
    Body(stringResource(vendor.stepsRes()))
    if (unrestricted) Body(stringResource(R.string.onb_battery_ok))
}

@Composable
internal fun DoneScreen(child: Boolean, admin: Boolean, message: String?, onDone: () -> Unit) = Page(
    title = stringResource(R.string.onb_done_title),
    actions = { Primary(stringResource(R.string.onb_done_open), "onb_done", onDone) },
) {
    Body(
        stringResource(
            when {
                child -> R.string.onb_done_body_child
                admin -> R.string.onb_done_body_parent
                else -> R.string.onb_done_body_joined_parent
            },
        ),
    )
    message?.let { ErrorNote(it) }
}

internal fun BatteryVendor.stepsRes(): Int = when (this) {
    BatteryVendor.SAMSUNG -> R.string.onb_battery_samsung
    BatteryVendor.GOOGLE -> R.string.onb_battery_google
    BatteryVendor.XIAOMI -> R.string.onb_battery_xiaomi
    BatteryVendor.HUAWEI -> R.string.onb_battery_huawei
    BatteryVendor.ONEPLUS -> R.string.onb_battery_oneplus
    BatteryVendor.OPPO -> R.string.onb_battery_oppo
    BatteryVendor.VIVO -> R.string.onb_battery_vivo
    BatteryVendor.OTHER -> R.string.onb_battery_other
}
