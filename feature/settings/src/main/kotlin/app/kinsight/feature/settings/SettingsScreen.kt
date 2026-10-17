package app.kinsight.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.PinSave
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SignInState
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.data.UsageReadModel
import app.kinsight.core.designsystem.openPrivacyPolicy
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.ErrorState
import app.kinsight.core.designsystem.component.isJustNow
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import app.kinsight.core.usage.UsageAccessSettings
import kotlinx.coroutines.delay

@Composable
fun SettingsRoute(
    appVersion: String,
    onConsent: () -> Unit,
    onTransparency: () -> Unit,
    onBattery: () -> Unit,
    onDiagnostics: () -> Unit,
    onNewPhone: () -> Unit = {},
    onZones: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel {
        val g = AppGraph.get(context)
        SettingsViewModel(g.group, g.settingsRepository, g.service, g.reset, g.usage, g.cloudRepository, g.childLock)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var locationState by remember { mutableStateOf(LocationPermissions.state(context)) }
    // Y4 parked minor: a request that changed nothing (Android no longer shows the dialog once it was refused for good)
    // turns the button into «Открыть настройки приложения», never a button that does nothing.
    var locationBlocked by rememberSaveable { mutableStateOf(false) }
    val locationChanged: () -> Unit = {
        val before = locationState
        locationState = LocationPermissions.state(context)
        if (locationState != before) locationBlocked = false
        LocationGraph.refresh(context, rearm = true)
        AppGraph.get(context).launchLocationCheck()
    }
    val activity = LocalActivity.current
    // Sweep review Minor 5: blocked only when Android will not ask again (no rationale after a refusal); a first «Deny»
    // keeps the Allow button, Android still shows its dialog once more.
    val afterRequest: (String) -> Unit = { permission ->
        val before = locationState
        locationChanged()
        val rationale = activity?.shouldShowRequestPermissionRationale(permission) ?: true
        locationBlocked = locationBlockedAfter(before, locationState, rationale)
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterRequest(LocationPermissions.FOREGROUND.first())
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterRequest(LocationPermissions.BACKGROUND) }
    // Back from Android's usage access screen: the row goes away at once, not on the next 30 s tick (U1).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshStatus()
        // Back from Android's settings page: a grant or revoke made there starts or stops tracking now (Y4 review Minor 8).
        if (LocationPermissions.state(context) != locationState) locationChanged()
    }
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
        onSyncNow = vm::syncNow, onPrivacyPolicy = { openPrivacyPolicy(context) }, nowMs = now,
        onCloudSignIn = { vm.startCloudSignIn { url -> context.open(Intent(Intent.ACTION_VIEW, url.toUri())) } },
        onCloudCode = vm::finishCloudSignIn,
        onCloudDismiss = vm::dismissCloudSignIn,
        onCloudTakeOver = { vm.startCloudSignIn(takeOver = true) { url -> context.open(Intent(Intent.ACTION_VIEW, url.toUri())) } },
        onCloudHandover = vm::requestHandover,
        onNewPhone = onNewPhone,
        location = locationRow(ui.cloud.on, ui.cloud.isChild, locationState),
        locationBlocked = locationBlocked,
        onLocationSettings = { context.open(appSettings(context.packageName)) },
        onLocationAccess = { st ->
            when (st) {
                // Asking for the precise one again: Android 12+ offers to switch an approximate grant to precise.
                LocationPermissions.State.NONE, LocationPermissions.State.COARSE_ONLY -> foreground.launch(LocationPermissions.FOREGROUND)
                LocationPermissions.State.WHILE_IN_USE -> background.launch(LocationPermissions.BACKGROUND)
                LocationPermissions.State.ALWAYS -> Unit
            }
        },
        onZones = onZones,
        onUnlock = vm::unlock,
        onLock = vm::lock,
        onSetPin = vm::setPin,
    )
}

private enum class Ask { PAUSE, STOP, RESET, SHRINK }

/** Spec §8, §12: the location row shows on a child's phone while the cloud is on, never on a parent's. */
internal fun locationRow(cloudOn: Boolean, isChild: Boolean, state: LocationPermissions.State): LocationPermissions.State? =
    state.takeIf { cloudOn && isChild }

/**
 * A permission request that left the state as it was while Android no longer explains it ([rationale] false after a
 * refusal): refused for good, so only the app's settings page can grant it. A first «Deny» leaves a rationale: ask again.
 */
internal fun locationBlockedAfter(before: LocationPermissions.State, after: LocationPermissions.State, rationale: Boolean): Boolean =
    after == before && after != LocationPermissions.State.ALWAYS && !rationale

/** Android's page of this app, where a permission refused for good is granted. */
internal fun appSettings(packageName: String): Intent =
    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

/**
 * Spec §4.7, §4.8, §9.6, §9.7. Only a child's phone has a «share» switch (Play Stalkerware ruling: an adult's usage is
 * never shared); a parent's says so instead. Turning sharing off asks first and says the pause is open (signed, the
 * parents are told) and the paused time is sent later. Nothing here starts sync on its own: a user's Stop holds until
 * the Start button. Disconnecting this phone needs an explicit acknowledgement; its failure is shown, never swallowed.
 * On a child's phone retention, diagnostics and the disconnect button wait under «Advanced» (U22).
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
    onPrivacyPolicy: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(), // the route passes a 30 s tick
    onCloudSignIn: () -> Unit = {},
    onCloudCode: (String) -> Unit = {},
    onCloudDismiss: () -> Unit = {},
    onCloudTakeOver: () -> Unit = {},
    onCloudHandover: (DeviceId) -> Unit = {},
    onNewPhone: () -> Unit = {},
    /** Spec §8, §12: the child's location access, shown on a child's phone while the cloud is on; null hides the row. */
    location: LocationPermissions.State? = null,
    onLocationAccess: (LocationPermissions.State) -> Unit = {},
    /** The last request changed nothing: the button opens the app's settings page ([onLocationSettings]) instead. */
    locationBlocked: Boolean = false,
    onLocationSettings: () -> Unit = {},
    /** Spec §8.1 «Ребёнок видит список зон»: the read-only list of the family's safe zones, next to the location row. */
    onZones: () -> Unit = {},
    /** Spec §10: the parent PIN typed on a child's phone; «Закрыть настройки»; an admin parent's new PIN. */
    onUnlock: (String) -> Unit = {},
    onLock: () -> Unit = {},
    onSetPin: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val ready = ui.group as? GroupState.Ready
    val role = ready?.self?.role
    var ask by rememberSaveable { mutableStateOf<Ask?>(null) }
    var handover by rememberSaveable { mutableStateOf(false) } // the «who takes the storage» dialog (spec §7.1)
    // No one left to pick: the request is over, it must not come back by itself when targets return.
    LaunchedEffect(ui.cloud.handoverTargets.isEmpty()) { if (ui.cloud.handoverTargets.isEmpty()) handover = false }
    var pending by rememberSaveable { mutableStateOf(intArrayOf()) } // the detail and summary days a SHRINK asks about
    val retention: (Pair<Int, Int>) -> Unit = { (d, s) ->
        if (d < ui.usage.detailRetentionDays || s < ui.usage.summaryRetentionDays) {
            pending = intArrayOf(d, s)
            ask = Ask.SHRINK
        } else {
            onRetention(d, s)
        }
    }
    val child = role == Role.CHILD
    // Spec §10: on a child's phone nothing about sharing changes without the parent PIN; it opens Settings for a while.
    // The view model clears unlockedUntilMs on a monotonic clock; the wall-clock compare can only end it sooner.
    val unlocked = child && (ui.lock.unlockedUntilMs ?: Long.MIN_VALUE) > nowMs
    // Fail closed: until the group is read this phone may be a child's, so neither the child's switches nor the adult
    // actions are shown.
    val loading = ui.group is GroupState.Loading
    val childLocked = (child && !unlocked) || loading
    var askPin by rememberSaveable { mutableStateOf(false) }
    var setPin by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    // A storage error sends the user to Diagnostics and the disconnect button: on a child's phone they must be in sight.
    // The same precedence as the sync line: Advanced opens for a key error only when the line says so.
    val keyError = syncState(ui.service) == SyncStateLine.KEY_ERROR
    val open = advanced || keyError
    // A right PIN closes the PIN dialog; a lock (the button or the time running out) closes a question the unlock
    // opened, so it cannot be answered once the settings are closed. A storage error keeps its way out.
    LaunchedEffect(childLocked) {
        if (!childLocked) {
            askPin = false
        } else {
            advanced = false
            // A storage error keeps its way out (the disconnect and the retention it shows); sharing questions close.
            if (!keyError || ask == Ask.PAUSE || ask == Ask.STOP) ask = null
        }
    }
    val retentionSection: LazyListScope.() -> Unit = {
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
    }
    val adultActions: @Composable () -> Unit = {
        TextButton(onDiagnostics) { Text(stringResource(R.string.set_diagnostics)) }
        OutlinedButton(
            onClick = { ask = Ask.RESET },
            enabled = !ui.resetting && !ui.cloud.handoverNeeded && !ui.cloud.cloudSwitching,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().testTag("set_reset"),
        ) { Text(stringResource(if (ui.resetting) R.string.set_resetting else R.string.set_reset)) }
        if (ui.cloud.handoverNeeded) {
            // Spec §7.1: leaving (this reset is one way out) waits until the storage is handed over.
            Text(
                stringResource(R.string.set_reset_handover),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("set_reset_handover"),
            )
        } else if (ui.cloud.cloudSwitching) {
            // The same wait as leaving (OwnerLeaveBlock.SWITCHING): a wipe now would lose the old token before its revoke.
            Text(
                stringResource(R.string.set_reset_switching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("set_reset_switching"),
            )
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
                            SettingsError.HANDOVER -> R.string.set_error_handover
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
                    // Wraps at 360 dp or with large fonts instead of squeezing the labels.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onSyncNow, Modifier.testTag("set_sync_now")) { Text(stringResource(R.string.set_sync_now)) }
                        if (!childLocked) OutlinedButton({ ask = Ask.STOP }, Modifier.testTag("set_stop")) { Text(stringResource(R.string.set_stop)) }
                    }
                }
                // U1: a consenting child whose phone cannot count; the same check as Overview's banner (the signed
                // sharing state; the local consent only before the snapshot has this device).
                val collects = UsageReadModel.collects(ui.ownSharing, localConsent = ui.usage.consentAtMs != null)
                if (role == Role.CHILD && collects && s?.usageAccessGranted == false) {
                    Text(stringResource(R.string.set_usage_access_missing), color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ UsageAccessSettings.open(context) }, Modifier.testTag("set_usage_access")) {
                        Text(stringResource(R.string.set_open_usage_access))
                    }
                }
                if (child && !childLocked) Text(stringResource(R.string.set_pause_vs_stop), style = MaterialTheme.typography.bodySmall)
                if (role == Role.PARENT) Text(stringResource(R.string.set_not_shared), style = MaterialTheme.typography.bodyMedium)
            }
        }
        cloudSection(ui.cloud, nowMs, onCloudSignIn, onCloudTakeOver, { handover = true }, onNewPhone)
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
            // A consent never given may still be given while locked: agreeing switches nothing off.
            Role.CHILD -> if (!childLocked || ui.usage.consentAtMs == null) item {
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
        if (child && location != null) {
            item {
                Column(Modifier.fillMaxWidth().testTag("set_location"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.set_location)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when (location) {
                                        LocationPermissions.State.NONE -> R.string.set_location_none
                                        LocationPermissions.State.COARSE_ONLY -> R.string.set_location_coarse
                                        LocationPermissions.State.WHILE_IN_USE -> R.string.set_location_while_in_use
                                        LocationPermissions.State.ALWAYS -> R.string.set_location_always
                                    },
                                ),
                            )
                        },
                    )
                    if (location != LocationPermissions.State.ALWAYS && locationBlocked) {
                        Text(
                            stringResource(R.string.set_location_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Button(onLocationSettings, Modifier.padding(horizontal = 16.dp).testTag("set_location_settings")) {
                            Text(stringResource(R.string.set_location_open_settings))
                        }
                    } else if (location != LocationPermissions.State.ALWAYS) {
                        Button({ onLocationAccess(location) }, Modifier.padding(horizontal = 16.dp).testTag("set_location_allow")) {
                            Text(stringResource(if (location == LocationPermissions.State.WHILE_IN_USE) R.string.set_location_allow_always else R.string.set_location_allow))
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.set_zones)) },
                        supportingContent = { Text(stringResource(R.string.set_zones_body)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.set_open), role = SemanticsRole.Button, onClick = onZones)
                            .testTag("set_zones"),
                    )
                }
            }
        }
        if (child) {
            item {
                if (childLocked) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.set_parent_unlock)) },
                        supportingContent = {
                            Text(stringResource(if (ui.lock.status.pinSet) R.string.set_parent_unlock_body else R.string.set_parent_unlock_no_pin))
                        },
                        leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        modifier = Modifier.clickable(enabled = ui.lock.status.pinSet, role = SemanticsRole.Button) { askPin = true }
                            .testTag("set_parent_unlock"),
                    )
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.set_unlocked), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onLock, Modifier.testTag("set_lock")) { Text(stringResource(R.string.set_lock)) }
                    }
                }
            }
        }
        item {
            // U21: the row is the button (no separate «Open» under it).
            ListItem(
                headlineContent = { Text(stringResource(R.string.set_transparency)) },
                supportingContent = { Text(stringResource(R.string.set_transparency_body)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.set_open), role = SemanticsRole.Button, onClick = onTransparency)
                    .testTag("set_transparency"),
            )
        }
        if (!child && !loading) retentionSection()
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
        if (role == Role.PARENT && ui.lock.status.canSetPin) {
            section(R.string.set_pin_section) {
                Text(stringResource(if (ui.lock.status.pinSet) R.string.set_pin_set_body else R.string.set_pin_none))
                OutlinedButton({ setPin = true }, Modifier.testTag("set_pin_set")) {
                    Text(stringResource(if (ui.lock.status.pinSet) R.string.set_pin_change else R.string.set_pin_set))
                }
                when (ui.lock.pinSaved) {
                    PinSave.SAVED -> Text(stringResource(R.string.set_pin_saved))
                    PinSave.INVALID, PinSave.NOT_ALLOWED, PinSave.FAILED ->
                        Text(stringResource(R.string.set_pin_failed), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            }
        }
        section(R.string.set_device) {
            TextButton(onBattery) { Text(stringResource(R.string.set_battery)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton({
                    context.open(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()))
                }) { Text(stringResource(R.string.set_language)) }
            }
            TextButton(onPrivacyPolicy, Modifier.testTag("set_privacy")) { Text(stringResource(R.string.set_privacy)) }
            if (!child && !loading) adultActions()
            Text(stringResource(R.string.set_version, appVersion), style = MaterialTheme.typography.bodySmall)
        }
        // U22: on a child's phone what is for adults waits under «Advanced», and only while a parent unlocked it (spec §10);
        // a storage error still opens it by itself, it is the way out of a broken phone.
        if (child && (unlocked || keyError)) {
            item {
                val state = stringResource(if (open) R.string.set_expanded else R.string.set_collapsed)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.set_advanced)) },
                    supportingContent = { Text(stringResource(R.string.set_advanced_body)) },
                    trailingContent = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !keyError, role = SemanticsRole.Button) { advanced = !advanced }
                        .semantics { stateDescription = state }
                        .testTag("set_advanced"),
                )
            }
            if (open) {
                retentionSection()
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { adultActions() } }
            }
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
        Ask.RESET -> ResetIdentityDialog(onConfirm = onResetIdentity, onDismiss = { ask = null }, cloudOff = ui.cloud.selfIsOwner)
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
    if (ui.signIn !is SignInState.Idle) CloudSignInDialog(ui.signIn, onCloudCode, onCloudSignIn, onCloudDismiss)
    if (handover && ui.cloud.handoverTargets.isNotEmpty()) {
        CloudHandoverDialog(ui.cloud.handoverTargets, onPick = { onCloudHandover(it); handover = false }, onDismiss = { handover = false })
    }
    // A right PIN unlocks, so childLocked turns false and the dialog closes by itself.
    if (askPin && childLocked) UnlockDialog(ui.lock, onUnlock = onUnlock, onDismiss = { askPin = false })
    if (setPin) {
        SetPinDialog(
            onSave = {
                onSetPin(it)
                setPin = false
            },
            onDismiss = { setPin = false },
        )
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

/** The Settings sync line, one value per text. */
internal enum class SyncStateLine { RUNNING, STOPPED, NO_PERMISSION, BLUETOOTH_OFF, KEY_ERROR, RADIO_ERROR, SYNCING, PEER_UPDATE }

/** What the service is doing now; the user's Stop first, then what blocks the radio (as Overview's banner). */
internal fun syncState(s: ServiceStatus?): SyncStateLine = when {
    s == null -> SyncStateLine.RUNNING
    s.stoppedByUser -> SyncStateLine.STOPPED
    s.missingPermissions.isNotEmpty() || s.state == ServiceState.NO_PERMISSION -> SyncStateLine.NO_PERMISSION
    s.state == ServiceState.BLUETOOTH_OFF -> SyncStateLine.BLUETOOTH_OFF
    s.state == ServiceState.STORAGE_ERROR -> SyncStateLine.KEY_ERROR
    s.state == ServiceState.RADIO_ERROR -> SyncStateLine.RADIO_ERROR
    s.syncing -> SyncStateLine.SYNCING
    s.problem == SyncProblem.PEER_NEEDS_UPDATE -> SyncStateLine.PEER_UPDATE
    else -> SyncStateLine.RUNNING
}

@Composable
private fun syncStateText(s: ServiceStatus?): String = stringResource(
    when (syncState(s)) {
        SyncStateLine.RUNNING -> R.string.set_sync_running
        SyncStateLine.STOPPED -> R.string.set_sync_stopped
        SyncStateLine.NO_PERMISSION -> R.string.set_sync_no_permission
        SyncStateLine.BLUETOOTH_OFF -> R.string.set_sync_bluetooth_off
        SyncStateLine.KEY_ERROR -> R.string.set_sync_key_error
        SyncStateLine.RADIO_ERROR -> R.string.set_sync_radio_error
        SyncStateLine.SYNCING -> R.string.set_sync_syncing
        SyncStateLine.PEER_UPDATE -> R.string.set_sync_peer_update
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

/** Above this font scale a segmented row would cut its labels (U11: «Раз в неделю» needs about 160 dp at 200 %). */
internal const val VERTICAL_FONT_SCALE = 1.3f

/**
 * A single choice, each option tagged "<tagPrefix>_<value>" (lower case) for tests: a segmented row, or above
 * [VERTICAL_FONT_SCALE] a vertical radio list (tagged "<tagPrefix>_list") with 48 dp rows.
 */
@Composable
private fun <T> Choices(options: List<T>, selected: T, tagPrefix: String, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    if (LocalDensity.current.fontScale > VERTICAL_FONT_SCALE) {
        Column(Modifier.fillMaxWidth().selectableGroup().testTag("${tagPrefix}_list")) {
            options.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(o == selected, onClick = { onSelect(o) }, role = SemanticsRole.RadioButton)
                        .testTag("${tagPrefix}_${o.toString().lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = o == selected, onClick = null)
                    Text(label(o), Modifier.padding(start = 12.dp))
                }
            }
        }
        return
    }
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
