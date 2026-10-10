package app.kinsight.feature.dashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role as MemberRole
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.designsystem.component.isJustNow
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import kotlinx.coroutines.delay
import java.time.format.TextStyle
import kotlin.math.abs

private const val HOUR_MS = 3_600_000L

/** The service has no «done» signal for a fast window: the pull spinner is a short acknowledgement only. */
private const val REFRESH_MS = 1_500L

@Composable
fun DashboardRoute(onOpenDevice: (DeviceId) -> Unit, onConsent: () -> Unit, onInviteChild: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel {
        val g = AppGraph.get(context)
        DashboardViewModel(g.usage, g.service, g.settingsRepository)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    DashboardScreen(ui, now, onOpenDevice, vm::pause, vm::resume, onConsent, vm::start, vm::syncNow, onInviteChild, onOpenSettings)
}

/**
 * Spec §9.2 and §9.6, reshaped by user feedback 2026-10-10 (the Family Link / Screen Time pattern): a parent's home
 * shows its children only (and an invite while there are none); a child's shows one «who sees my data» row with the
 * two-tap open pause, then its own card. This phone's sync status lives in Settings; Overview shows it only as a
 * banner when the service needs the user, so the move never hides a problem. Pull to refresh runs «Sync now».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    ui: DashboardUi,
    nowMs: Long,
    onOpenDevice: (DeviceId) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConsent: () -> Unit,
    onStart: () -> Unit,
    onSyncNow: () -> Unit,
    onInviteChild: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val d = ui.dashboard
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!ui.loaded) {
                CircularProgressIndicator()
            } else {
                EmptyState(stringResource(R.string.dash_no_group_title), stringResource(R.string.dash_no_group_body))
            }
        }
        return
    }
    val child = d.self.role == MemberRole.CHILD
    val noContactMs = ui.noContactHours * HOUR_MS
    val attention = SyncAttention.of(ui.service)
    val syncNowLabel = stringResource(R.string.dash_sync_now)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_MS)
            refreshing = false
        }
    }
    // While the user's Stop holds, «Sync now» does nothing (the start honours the Stop): no pull, no action; the banner's
    // Start is the way back.
    val canSync = attention != SyncAttention.STOPPED
    val listState = rememberLazyListState() // kept when the pull comes and goes with the Stop
    val list: @Composable BoxScope.() -> Unit = {
        LazyColumn(
            // Pull to refresh is a gesture only: TalkBack and switch users get the same «Sync now» as an action.
            modifier = Modifier.fillMaxSize().testTag("dash_list").semantics {
                if (canSync) {
                    customActions = listOf(
                        CustomAccessibilityAction(syncNowLabel) {
                            onSyncNow()
                            true
                        },
                    )
                }
            },
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.dash_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
            attention?.let { a -> item { AttentionBanner(a, onStart, onOpenSettings) } }
            // Not the user's fault nor fixable here: a neutral note, never the error banner.
            if (ui.service?.problem == SyncProblem.PEER_NEEDS_UPDATE) item { UpdateNotice() }
            if (child) {
                item { ChildTransparencyRow(d.parents, d.selfUsage?.sharing ?: Sharing.Never, ui.sharingFailed, onPause, onResume, onConsent) }
                d.selfUsage?.let { u -> item { DeviceCard(u, nowMs, noContactMs) { onOpenDevice(u.member.id) } } }
            } else if (d.children.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.dash_empty_title), stringResource(R.string.dash_empty_body)) {
                        Button(onInviteChild, Modifier.testTag("dash_invite_child")) { Text(stringResource(R.string.dash_invite_child)) }
                    }
                }
            } else {
                items(d.children, key = { it.member.id.hex }) { u -> DeviceCard(u, nowMs, noContactMs) { onOpenDevice(u.member.id) } }
            }
        }
    }
    if (canSync) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onSyncNow()
            },
            modifier = Modifier.fillMaxSize(),
            content = list,
        )
    } else {
        Box(Modifier.fillMaxSize(), content = list)
    }
}

/** A sync problem the user has to act on. Running, waiting, syncing and starting are normal and show nothing. */
internal enum class SyncAttention {
    STOPPED,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    KEY_ERROR,
    RADIO_ERROR,
    ;

    companion object {
        /** The old «This phone» card's precedence: the user's Stop first, then what blocks the radio. */
        fun of(s: ServiceStatus?): SyncAttention? = when {
            s == null -> null
            s.stoppedByUser -> STOPPED
            s.missingPermissions.isNotEmpty() || s.state == ServiceState.NO_PERMISSION -> NO_PERMISSION
            s.state == ServiceState.BLUETOOTH_OFF -> BLUETOOTH_OFF
            s.state == ServiceState.STORAGE_ERROR -> KEY_ERROR
            s.state == ServiceState.RADIO_ERROR -> RADIO_ERROR
            else -> null
        }
    }
}

@Composable
private fun UpdateNotice() {
    Card(
        Modifier.fillMaxWidth().testTag("dash_update_notice"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(stringResource(R.string.dash_update_notice), Modifier.padding(16.dp))
    }
}

@Composable
private fun AttentionBanner(a: SyncAttention, onStart: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().testTag("dash_banner").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (a) {
                        SyncAttention.STOPPED -> R.string.dash_stopped
                        SyncAttention.NO_PERMISSION -> R.string.dash_no_permission
                        SyncAttention.BLUETOOTH_OFF -> R.string.dash_bluetooth_off
                        SyncAttention.KEY_ERROR -> R.string.dash_storage_error
                        SyncAttention.RADIO_ERROR -> R.string.dash_radio_error
                    },
                ),
            )
            when (a) {
                SyncAttention.STOPPED -> Button(onStart, Modifier.testTag("dash_start")) { Text(stringResource(R.string.dash_start)) }
                SyncAttention.NO_PERMISSION -> OutlinedButton(
                    { context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
                    Modifier.testTag("dash_app_settings"),
                ) { Text(stringResource(R.string.dash_open_app_settings)) }
                SyncAttention.BLUETOOTH_OFF -> OutlinedButton(
                    { context.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    Modifier.testTag("dash_bt_settings"),
                ) { Text(stringResource(R.string.dash_open_bluetooth)) }
                SyncAttention.KEY_ERROR -> Button(onOpenSettings, Modifier.testTag("dash_open_settings")) {
                    Text(stringResource(R.string.dash_open_kinsight_settings))
                }
                SyncAttention.RADIO_ERROR -> Unit
            }
        }
    }
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("KinsightDashboard", "no activity for ${intent.action}", e)
    }
}

@Composable
private fun dateTime(ms: Long): String =
    DateUtils.formatDateTime(LocalContext.current, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

/** One compact row: who sees this phone's time, what sharing does now, and the one button for it. */
@Composable
private fun ChildTransparencyRow(
    parents: List<MemberView>,
    sharing: Sharing,
    failed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConsent: () -> Unit,
) {
    var ask by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (parents.isEmpty()) stringResource(R.string.dash_shared_with_nobody)
                else stringResource(R.string.dash_shared_with, parents.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.titleSmall,
            )
            when (sharing) {
                Sharing.On -> {
                    Text(stringResource(R.string.dash_pause_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ ask = true }, Modifier.testTag("dash_pause")) { Text(stringResource(R.string.dash_pause)) }
                }
                is Sharing.Paused -> {
                    Text(stringResource(R.string.dash_paused_since, dateTime(sharing.sinceMs)), style = MaterialTheme.typography.bodySmall)
                    Button(onResume, Modifier.testTag("dash_resume")) { Text(stringResource(R.string.dash_resume)) }
                }
                Sharing.Never -> {
                    Text(stringResource(R.string.dash_not_sharing), style = MaterialTheme.typography.bodySmall)
                    Button(onConsent, Modifier.testTag("dash_consent")) { Text(stringResource(R.string.dash_turn_on)) }
                }
                Sharing.Left -> Text(stringResource(R.string.dash_left), style = MaterialTheme.typography.bodySmall)
            }
            if (failed) {
                Text(
                    stringResource(R.string.dash_sharing_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
            title = { Text(stringResource(R.string.dash_pause_title)) },
            text = { Text(stringResource(R.string.dash_pause_body)) },
            confirmButton = {
                TextButton({
                    ask = false
                    onPause()
                }, Modifier.testTag("dash_pause_confirm")) { Text(stringResource(R.string.dash_pause)) }
            },
            dismissButton = { TextButton({ ask = false }, Modifier.testTag("dash_pause_cancel")) { Text(stringResource(R.string.dash_cancel)) } },
        )
    }
}

@Composable
private fun DeviceCard(u: DeviceUsage, nowMs: Long, noContactMs: Long, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val withData = u.week.filter { it.hasData }
    val busiest = withData.maxByOrNull { it.totalMs }
    val summary = if (busiest == null) {
        stringResource(R.string.dash_week_no_data)
    } else {
        stringResource(
            R.string.dash_week_summary,
            durationText(withData.sumOf { it.totalMs }),
            busiest.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
        )
    }
    val label = stringResource(R.string.dash_open_details, u.member.name)
    Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(u.member.name, style = MaterialTheme.typography.titleMedium)
            if (u.today.hasData) {
                Text(durationText(u.today.totalMs), style = MaterialTheme.typography.displaySmall)
                comparison(u)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } else {
                Text(stringResource(R.string.dash_no_data_today), style = MaterialTheme.typography.titleLarge)
            }
            BarChart(values = u.week.map { it.totalMs }, labels = emptyList(), summary = summary, height = 40.dp)
            Text(status(u, nowMs, noContactMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Text, never colour alone (spec §9.8): within 10 % of the 7-day mean counts as «about the same». */
@Composable
private fun comparison(u: DeviceUsage): String? {
    val avg = u.averageMs ?: return null
    val diff = u.today.totalMs - avg
    return when {
        diff == 0L || (avg > 0 && abs(diff) * 10 < avg) -> stringResource(R.string.dash_vs_same)
        diff > 0 -> stringResource(R.string.dash_vs_more, durationText(diff))
        else -> stringResource(R.string.dash_vs_less, durationText(-diff))
    }
}

/** «No contact» uses the same setting and comparison as the alert (AlertPlanner), not a fixed day (preflight C19). */
@Composable
private fun status(u: DeviceUsage, nowMs: Long, noContactMs: Long): String = when (val s = u.sharing) {
    is Sharing.Paused -> stringResource(R.string.dash_card_paused, dateTime(s.sinceMs))
    Sharing.Never -> stringResource(R.string.dash_card_not_sharing)
    Sharing.Left -> stringResource(R.string.dash_card_left)
    Sharing.On -> {
        val seen = u.member.lastSeenMs
        val updated = u.updatedMs
        when {
            !u.member.isSelf && seen != null && nowMs - seen > noContactMs -> stringResource(R.string.dash_card_not_seen, relativeTime(seen, nowMs))
            updated != null && isJustNow(updated, nowMs) -> stringResource(R.string.dash_card_updated_now)
            updated != null -> stringResource(R.string.dash_card_updated, relativeTime(updated, nowMs))
            else -> stringResource(R.string.dash_card_no_data)
        }
    }
}
