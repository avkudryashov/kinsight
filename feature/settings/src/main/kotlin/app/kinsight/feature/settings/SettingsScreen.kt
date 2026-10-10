package app.kinsight.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.ErrorState
import app.kinsight.core.designsystem.component.isJustNow
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import kotlinx.coroutines.delay

@Composable
fun SettingsRoute(appVersion: String, onConsent: () -> Unit, onTransparency: () -> Unit, onBattery: () -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel {
        val g = AppGraph.get(context)
        SettingsViewModel(g.group, g.settingsRepository, g.service, g.reset)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    SettingsScreen(
        ui, appVersion, vm::setSharing, onConsent, onTransparency, vm::stopSync, vm::startSync, vm::setRetention,
        vm::setSummaryMode, vm::setNoContactHours, onBattery, onDiagnostics, vm::resetIdentity, vm::dismissError,
        onSyncNow = vm::syncNow, nowMs = now,
    )
}

private enum class Ask { PAUSE, STOP, RESET, SHRINK }

/**
 * Spec §4.7, §4.8, §9.6, §9.7. Only a child's phone has a «share» switch (Play Stalkerware ruling: an adult's usage is
 * never shared); a parent's says so instead. Turning sharing off asks first and says the pause is open (signed, the
 * parents are told) and the paused time is sent later. Nothing here starts sync on its own: a user's Stop holds until
 * the Start button. The identity reset needs an explicit acknowledgement; its failure is shown, never swallowed.
 */
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    appVersion: String,
    onSharing: (Boolean) -> Unit,
    onConsent: () -> Unit,
    onTransparency: () -> Unit,
    onStopSync: () -> Unit,
    onStartSync: () -> Unit,
    onRetention: (Int, Int) -> Unit,
    onSummaryMode: (SummaryMode) -> Unit,
    onNoContactHours: (Int) -> Unit,
    onBattery: () -> Unit,
    onDiagnostics: () -> Unit,
    onResetIdentity: () -> Unit,
    onDismissError: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(), // the route passes a 30 s tick
) {
    val context = LocalContext.current
    val ready = ui.group as? GroupState.Ready
    val role = ready?.self?.role
    var ask by rememberSaveable { mutableStateOf<Ask?>(null) }
    var pending by rememberSaveable { mutableStateOf(intArrayOf()) } // the detail and summary days a SHRINK asks about
    val retention: (Pair<Int, Int>) -> Unit = { (d, s) ->
        if (d < ui.usage.detailRetentionDays || s < ui.usage.summaryRetentionDays) {
            pending = intArrayOf(d, s)
            ask = Ask.SHRINK
        } else {
            onRetention(d, s)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("set_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.set_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        ui.error?.let { e ->
            item {
                ErrorState(
                    title = stringResource(R.string.set_error_title),
                    body = stringResource(
                        when (e) {
                            SettingsError.SHARING -> R.string.set_error_sharing
                            SettingsError.RETENTION -> R.string.set_error_retention
                            SettingsError.NOTIFICATIONS -> R.string.set_error_notifications
                            SettingsError.RESET -> R.string.set_error_reset
                        },
                    ),
                ) { TextButton(onDismissError) { Text(stringResource(R.string.set_ok)) } }
            }
        }
        // This phone's sync, moved here from Overview (user feedback 2026-10-10); Overview keeps a banner for problems.
        item {
            val s = ui.service
            val stopped = s?.stoppedByUser == true
            Column(Modifier.fillMaxWidth().testTag("set_sync_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_sync), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(syncStateText(s))
                if (!stopped) Text(lastSyncText(s?.lastSyncMs, nowMs))
                if (stopped) {
                    Button(onStartSync, Modifier.testTag("set_start")) { Text(stringResource(R.string.set_start)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onSyncNow, Modifier.testTag("set_sync_now")) { Text(stringResource(R.string.set_sync_now)) }
                        OutlinedButton({ ask = Ask.STOP }, Modifier.testTag("set_stop")) { Text(stringResource(R.string.set_stop)) }
                    }
                }
                if (role == Role.PARENT) Text(stringResource(R.string.set_not_shared), style = MaterialTheme.typography.bodyMedium)
            }
        }
        ready?.let { r ->
            item {
                ListItem(
                    headlineContent = { Text(r.self.name) },
                    supportingContent = {
                        Text(
                            stringResource(if (r.self.role == Role.CHILD) R.string.set_role_child else R.string.set_role_parent) +
                                if (r.self.isAdmin) " · " + stringResource(R.string.set_admin) else "",
                        )
                    },
                )
            }
        }
        when (role) {
            Role.CHILD -> item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.set_share)) },
                    supportingContent = { Text(stringResource(R.string.set_share_body)) },
                    trailingContent = {
                        Switch(
                            checked = ui.usage.sharing,
                            onCheckedChange = { on ->
                                when {
                                    !on -> ask = Ask.PAUSE
                                    ui.usage.consentAtMs == null -> onConsent()
                                    else -> onSharing(true)
                                }
                            },
                            modifier = Modifier.testTag("set_share"),
                        )
                    },
                )
            }
            Role.PARENT, null -> Unit // a parent's «not shared» sentence is in the sync section
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.set_transparency)) },
                supportingContent = { Text(stringResource(R.string.set_transparency_body)) },
                modifier = Modifier.testTag("set_transparency"),
            )
            TextButton(onTransparency) { Text(stringResource(R.string.set_open)) }
        }
        section(R.string.set_retention) {
            Text(stringResource(R.string.set_retention_detail))
            Choices(RetentionRules.DETAIL, ui.usage.detailRetentionDays, "set_detail", { stringResource(R.string.set_days, it) }) {
                retention(RetentionRules.withDetail(it, ui.usage.summaryRetentionDays))
            }
            Text(stringResource(R.string.set_retention_summary))
            Choices(RetentionRules.SUMMARY, ui.usage.summaryRetentionDays, "set_summary", { stringResource(R.string.set_days, it) }) {
                retention(RetentionRules.withSummary(ui.usage.detailRetentionDays, it))
            }
        }
        if (role == Role.PARENT) {
            section(R.string.set_notifications) {
                Text(stringResource(R.string.set_summaries))
                Choices(SummaryMode.entries, ui.prefs.summaryMode, "set_mode", {
                    stringResource(
                        when (it) {
                            SummaryMode.OFF -> R.string.set_summary_off
                            SummaryMode.DAILY -> R.string.set_summary_daily
                            SummaryMode.WEEKLY -> R.string.set_summary_weekly
                        },
                    )
                }, onSummaryMode)
                Text(stringResource(R.string.set_no_contact))
                Choices(RetentionRules.NO_CONTACT, ui.prefs.noContactHours, "set_no_contact", { stringResource(R.string.set_hours, it) }, onNoContactHours)
                TextButton({
                    context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.set_notification_settings)) }
            }
        }
        section(R.string.set_device) {
            TextButton(onBattery) { Text(stringResource(R.string.set_battery)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton({
                    context.open(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()))
                }) { Text(stringResource(R.string.set_language)) }
            }
            TextButton(onDiagnostics) { Text(stringResource(R.string.set_diagnostics)) }
            OutlinedButton(
                onClick = { ask = Ask.RESET },
                enabled = !ui.resetting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("set_reset"),
            ) { Text(stringResource(if (ui.resetting) R.string.set_resetting else R.string.set_reset)) }
            Text(stringResource(R.string.set_version, appVersion), style = MaterialTheme.typography.bodySmall)
        }
    }
    when (ask) {
        Ask.PAUSE -> Confirm(
            stringResource(R.string.set_pause_title), stringResource(R.string.set_pause_body), stringResource(R.string.set_pause), "set_pause_confirm",
            onConfirm = { onSharing(false) }, onDismiss = { ask = null },
        )
        Ask.STOP -> Confirm(
            stringResource(R.string.set_stop_title),
            stringResource(if (role == Role.CHILD) R.string.set_stop_body_child else R.string.set_stop_body_parent),
            stringResource(R.string.set_stop), "set_stop_confirm",
            onConfirm = onStopSync, onDismiss = { ask = null },
        )
        Ask.RESET -> ResetIdentityDialog(onConfirm = onResetIdentity, onDismiss = { ask = null })
        Ask.SHRINK -> if (pending.size == 2) {
            val (d, s) = pending
            // Fewer daily totals deletes everything older; fewer detail days deletes only the detail.
            val body = if (s < ui.usage.summaryRetentionDays) {
                pluralStringResource(R.plurals.set_shrink_body, s, s)
            } else {
                pluralStringResource(R.plurals.set_shrink_detail_body, d, d)
            }
            Confirm(
                stringResource(R.string.set_shrink_title), body, stringResource(R.string.set_shrink), "set_shrink_confirm",
                onConfirm = { onRetention(d, s) }, onDismiss = { ask = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun Confirm(title: String, body: String, confirm: String, tag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = {
        TextButton({
            onDismiss()
            onConfirm()
        }, Modifier.testTag(tag)) { Text(confirm) }
    },
    dismissButton = { TextButton(onDismiss, Modifier.testTag("${tag}_cancel")) { Text(stringResource(R.string.set_cancel)) } },
)

/** What the service is doing now; the user's Stop first, then what blocks the radio (as Overview's banner). */
@Composable
private fun syncStateText(s: ServiceStatus?): String = stringResource(
    when {
        s == null -> R.string.set_sync_running
        s.stoppedByUser -> R.string.set_sync_stopped
        s.missingPermissions.isNotEmpty() || s.state == ServiceState.NO_PERMISSION -> R.string.set_sync_no_permission
        s.state == ServiceState.BLUETOOTH_OFF -> R.string.set_sync_bluetooth_off
        s.state == ServiceState.STORAGE_ERROR -> R.string.set_sync_key_error
        s.state == ServiceState.RADIO_ERROR -> R.string.set_sync_radio_error
        s.syncing -> R.string.set_sync_syncing
        s.problem == SyncProblem.PEER_NEEDS_UPDATE -> R.string.set_sync_peer_update
        else -> R.string.set_sync_running
    },
)

/** «Just now» for a stamp under a minute old or a little ahead of this phone's clock (emulator run 2026-10-10). */
@Composable
private fun lastSyncText(last: Long?, nowMs: Long): String = when {
    last == null -> stringResource(R.string.set_never_synced)
    isJustNow(last, nowMs) -> stringResource(R.string.set_last_sync_now)
    else -> stringResource(R.string.set_last_sync, relativeTime(last, nowMs))
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("KinsightSettings", "no activity for ${intent.action}", e)
    }
}

private fun LazyListScope.section(title: Int, content: @Composable () -> Unit) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A segmented row; each option is tagged "<tagPrefix>_<value>" (lower case) for tests. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, tagPrefix: String, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.testTag("${tagPrefix}_${o.toString().lowercase()}"),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}
