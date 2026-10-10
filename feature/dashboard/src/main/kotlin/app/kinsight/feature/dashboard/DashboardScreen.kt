package app.kinsight.feature.dashboard

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import kotlinx.coroutines.delay
import java.time.format.TextStyle
import kotlin.math.abs

private const val HOUR_MS = 3_600_000L

@Composable
fun DashboardRoute(onOpenDevice: (DeviceId) -> Unit, onConsent: () -> Unit) {
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
    DashboardScreen(ui, now, onOpenDevice, vm::pause, vm::resume, onConsent, vm::start, vm::syncNow)
}

/**
 * Spec §9.2 and §9.6. Both roles get a «who sees my data» header. A child's names its parents and, while sharing,
 * pauses in two taps; the pause is open (a signed SHARING_PAUSED its parents are told about), and the screen says so.
 * A parent's own usage is never shared (Play Stalkerware ruling), so it has no pause; it gets a card per child.
 */
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.dash_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        item {
            if (child) {
                ChildTransparencyCard(d.parents, d.selfUsage?.sharing ?: Sharing.Never, ui.sharingFailed, onPause, onResume, onConsent)
            } else {
                ParentTransparencyCard(d.parents)
            }
        }
        item { ThisPhoneCard(ui.service, nowMs, onStart, onSyncNow) }
        if (child) {
            d.selfUsage?.let { u -> item { DeviceCard(u, nowMs, noContactMs) { onOpenDevice(u.member.id) } } }
        } else if (d.children.isEmpty()) {
            item { EmptyState(stringResource(R.string.dash_empty_title), stringResource(R.string.dash_empty_body)) }
        } else {
            items(d.children, key = { it.member.id.hex }) { u -> DeviceCard(u, nowMs, noContactMs) { onOpenDevice(u.member.id) } }
        }
    }
}

@Composable
private fun dateTime(ms: Long): String =
    DateUtils.formatDateTime(LocalContext.current, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
private fun ChildTransparencyCard(
    parents: List<MemberView>,
    sharing: Sharing,
    failed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConsent: () -> Unit,
) {
    var ask by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (parents.isEmpty()) stringResource(R.string.dash_shared_with_nobody)
                else stringResource(R.string.dash_shared_with, parents.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.titleMedium,
            )
            when (sharing) {
                Sharing.On -> {
                    Text(stringResource(R.string.dash_sharing_on))
                    Text(stringResource(R.string.dash_pause_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ ask = true }, Modifier.testTag("dash_pause")) { Text(stringResource(R.string.dash_pause)) }
                }
                is Sharing.Paused -> {
                    Text(stringResource(R.string.dash_paused_since, dateTime(sharing.sinceMs)))
                    Button(onResume, Modifier.testTag("dash_resume")) { Text(stringResource(R.string.dash_resume)) }
                }
                Sharing.Never -> {
                    Text(stringResource(R.string.dash_not_sharing))
                    Button(onConsent, Modifier.testTag("dash_consent")) { Text(stringResource(R.string.dash_turn_on)) }
                }
                Sharing.Left -> Text(stringResource(R.string.dash_left))
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

/** [others]: the other parents, who see the children's data as this parent does. */
@Composable
private fun ParentTransparencyCard(others: List<MemberView>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dash_parent_not_shared), style = MaterialTheme.typography.titleMedium)
            Text(
                if (others.isEmpty()) stringResource(R.string.dash_parent_only_viewer)
                else stringResource(R.string.dash_parent_co_viewers, others.joinToString(", ") { it.name }),
            )
        }
    }
}

@Composable
private fun ThisPhoneCard(s: ServiceStatus?, nowMs: Long, onStart: () -> Unit, onSyncNow: () -> Unit) {
    val last = s?.lastSyncMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dash_this_phone), style = MaterialTheme.typography.titleMedium)
            val text = when {
                s == null -> ""
                s.stoppedByUser -> stringResource(R.string.dash_stopped)
                s.missingPermissions.isNotEmpty() || s.state == ServiceState.NO_PERMISSION -> stringResource(R.string.dash_no_permission)
                s.state == ServiceState.BLUETOOTH_OFF -> stringResource(R.string.dash_bluetooth_off)
                s.state == ServiceState.STORAGE_ERROR -> stringResource(R.string.dash_storage_error)
                s.state == ServiceState.RADIO_ERROR -> stringResource(R.string.dash_radio_error)
                s.syncing -> stringResource(R.string.dash_syncing)
                s.problem == SyncProblem.PEER_NEEDS_UPDATE -> stringResource(R.string.dash_peer_update)
                last != null -> stringResource(R.string.dash_synced, relativeTime(last, nowMs))
                else -> stringResource(R.string.dash_never_synced)
            }
            if (text.isNotEmpty()) Text(text)
            if (s?.stoppedByUser == true) {
                Button(onStart, Modifier.testTag("dash_start")) { Text(stringResource(R.string.dash_start)) }
            } else {
                OutlinedButton(onSyncNow, Modifier.testTag("dash_sync_now")) { Text(stringResource(R.string.dash_sync_now)) }
            }
        }
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
            updated != null -> stringResource(R.string.dash_card_updated, relativeTime(updated, nowMs))
            else -> stringResource(R.string.dash_card_no_data)
        }
    }
}
