package app.kinsight.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The first run's step for [Page] (OnboardingRoute provides it); null elsewhere (consent from Settings, battery guide). */
internal val LocalStepProgress = staticCompositionLocalOf<StepProgress?> { null }

/** One onboarding page: a heading, scrollable text, actions at the bottom; edge-to-edge safe. */
@Composable
internal fun Page(title: String, actions: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LocalStepProgress.current?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.onb_step, p.number, p.total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("onb_step"),
                    )
                    LinearProgressIndicator(progress = { p.number.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
            }
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onNext() }),
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
 * Research §6.3, a child's phone only (U13): the child's line, then three short points with icons that carry the
 * prominent disclosure (what is seen and counted in the background, every parent including later ones, the open
 * pause and the always-on notification), the privacy policy link, then «More details» with every earlier paragraph
 * word for word (who receives it, the 7-day backlog, privacy, the pause, Play Protect, «Not now»). Explicit «Agree» /
 * «Not now»; nothing is read before «Agree».
 */
@Composable
fun ConsentScreen(recipients: String, onAgree: () -> Unit, onNotNow: () -> Unit, error: String? = null, onPrivacyPolicy: () -> Unit = {}) {
    var more by rememberSaveable { mutableStateOf(false) }
    Page(
        title = stringResource(R.string.onb_consent_title),
        actions = {
            Primary(stringResource(R.string.onb_agree), "onb_agree", onAgree)
            Secondary(stringResource(R.string.onb_not_now), "onb_not_now", onNotNow)
        },
    ) {
        Body(stringResource(R.string.onb_consent_child))
        ConsentPoint(Icons.Filled.DateRange, stringResource(R.string.onb_consent_point_what))
        ConsentPoint(
            Icons.Filled.Person,
            if (recipients.isBlank()) stringResource(R.string.onb_consent_point_who_group) else stringResource(R.string.onb_consent_point_who, recipients),
        )
        ConsentPoint(Icons.Filled.Notifications, stringResource(R.string.onb_consent_point_pause))
        TextButton(onPrivacyPolicy, Modifier.testTag("onb_privacy")) { Text(stringResource(R.string.onb_privacy_policy)) }
        TextButton({ more = !more }, Modifier.testTag("onb_consent_more")) {
            Text(stringResource(if (more) R.string.onb_consent_less else R.string.onb_consent_more))
        }
        if (more) {
            Body(if (recipients.isBlank()) stringResource(R.string.onb_consent_body_group) else stringResource(R.string.onb_consent_body, recipients))
            Body(stringResource(R.string.onb_consent_backlog))
            Body(stringResource(R.string.onb_consent_privacy))
            Body(stringResource(R.string.onb_consent_pause))
            Body(stringResource(R.string.onb_consent_protect))
            Body(stringResource(R.string.onb_consent_not_now))
        }
        error?.let { ErrorNote(it) }
    }
}

@Composable
private fun ConsentPoint(icon: ImageVector, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
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

/**
 * The invite QR (spec §6.4, §9.4): the live camera when it is allowed; otherwise the way through this phone's camera
 * app (the invite is a link) and a button to allow the camera. [scanner] is the camera view: the route passes
 * [QrScanner], tests a stand-in.
 */
@Composable
internal fun ScanScreen(
    message: String?,
    cameraGranted: Boolean,
    onAllowCamera: () -> Unit,
    onBack: () -> Unit,
    scanner: @Composable () -> Unit,
) = Page(
    title = stringResource(R.string.onb_scan_title),
    actions = {
        if (!cameraGranted) Primary(stringResource(R.string.onb_camera_allow), "onb_camera_allow", onAllowCamera)
        TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_back)) }
    },
) {
    Body(stringResource(R.string.onb_scan_body))
    if (cameraGranted) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).testTag("onb_camera")) { scanner() }
    } else {
        Body(stringResource(R.string.onb_scan_no_camera))
    }
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
