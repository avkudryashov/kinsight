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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.LocationReadModel
import app.kinsight.core.data.PlaceStatus
import app.kinsight.core.data.Role as MemberRole
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.TodayComparison
import app.kinsight.core.data.UsageReadModel
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.dataMoment
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import app.kinsight.core.transport.ble.SyncService
import app.kinsight.core.usage.UsageAccessSettings
import kotlinx.coroutines.delay
import java.time.format.TextStyle

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
    // Back from Android's settings (usage access, Bluetooth): re-read now, not on the next 30 s tick (U1).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while the screen is in front; a return refreshes «now» at once.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }
    DashboardScreen(
        ui, now, onOpenDevice, vm::resume, onConsent, vm::start, vm::syncNow, onInviteChild, onOpenSettings,
        onAcknowledgeParents = vm::acknowledgeParents,
    )
}

/**
 * Spec §9.2 and §9.6, reshaped by user feedback 2026-10-10 (the Family Link / Screen Time pattern): a parent's home
 * shows its children only (and an invite while there are none); a child's shows one «who sees my data» row
 * (only a parent changes sharing, spec §10), then its own card. This phone's sync status lives in Settings; Overview shows it only as a
 * banner when the service needs the user, so the move never hides a problem. Pull to refresh runs «Sync now».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    ui: DashboardUi,
    nowMs: Long,
    onOpenDevice: (DeviceId) -> Unit,
    onResume: () -> Unit,
    onConsent: () -> Unit,
    onStart: () -> Unit,
    onSyncNow: () -> Unit,
    onInviteChild: () -> Unit,
    onOpenSettings: () -> Unit,
    onAcknowledgeParents: (Set<DeviceId>) -> Unit = {},
    wallClock: () -> Long = System::currentTimeMillis,
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
    // Only a consenting child collects (signed sharing on, or paused: the paused time is still counted).
    val needsUsageAccess = child && UsageReadModel.collects(d.selfUsage?.sharing, localConsent = false)
    val attention = SyncAttention.of(ui.service, needsUsageAccess)
    val syncNowLabel = stringResource(R.string.dash_sync_now)
    val updateText = updateNoticeText(ui.service, d)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_MS)
            refreshing = false
        }
    }
    // U6: the spinner only acknowledges the pull; the line says what really happens until a newer sync or the window ends.
    var searchSince by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(searchSince) {
        val since = searchSince ?: return@LaunchedEffect
        delay((SyncService.FAST_MS - (wallClock() - since)).coerceAtLeast(0))
        if (searchSince == since) searchSince = null
    }
    val syncNow = {
        searchSince = wallClock()
        onSyncNow()
    }
    // While the user's Stop holds, «Sync now» does nothing (the start honours the Stop): no pull, no action; the banner's
    // Start is the way back.
    val canSync = attention != SyncAttention.STOPPED
    val searching = canSync && stillSearching(searchSince, ui.service?.lastSyncMs)
    val listState = rememberLazyListState() // kept when the pull comes and goes with the Stop
    val list: @Composable BoxScope.() -> Unit = {
        LazyColumn(
            // Pull to refresh is a gesture only: TalkBack and switch users get the same «Sync now» as an action.
            modifier = Modifier.fillMaxSize().testTag("dash_list").semantics {
                if (canSync) {
                    customActions = listOf(
                        CustomAccessibilityAction(syncNowLabel) {
                            syncNow()
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
            if (searching) item { Searching() }
            // Not the user's fault nor fixable here: a neutral note, never the error banner.
            updateText?.let { text -> item { UpdateNotice(text) } }
            if (child && ui.newParents.isNotEmpty()) item { NewParentBanner(ui.newParents, onAcknowledgeParents) }
            if (child && ui.consentOutdated) item { ReconsentCard(onConsent) }
            if (child) {
                item { ChildTransparencyRow(d.parents, d.selfUsage?.sharing ?: Sharing.Never, ui.sharingFailed, onResume, onConsent) }
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
                syncNow()
            },
            modifier = Modifier.fillMaxSize(),
            content = list,
        )
    } else {
        Box(Modifier.fillMaxSize(), content = list)
    }
}

/** U6: a sync was asked for and no newer one has finished yet. */
internal fun stillSearching(sinceMs: Long?, lastSyncMs: Long?): Boolean = sinceMs != null && (lastSyncMs == null || lastSyncMs <= sinceMs)

@Composable
private fun Searching() = Row(
    Modifier.fillMaxWidth().testTag("dash_searching").semantics { liveRegion = LiveRegionMode.Polite },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(stringResource(R.string.dash_searching), style = MaterialTheme.typography.bodyMedium)
}

/** A sync problem the user has to act on. Running, waiting, syncing and starting are normal and show nothing. */
internal enum class SyncAttention {
    STOPPED,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    KEY_ERROR,
    USAGE_ACCESS_MISSING,
    RADIO_ERROR,
    NOTIFICATION_HIDDEN,
    ;

    companion object {
        /**
         * The old «This phone» card's precedence: the user's Stop first, then what blocks the radio, then missing
         * usage access (U1; only where [needsUsageAccess]: a consenting child), then a radio error, which retries by
         * itself, then a hidden notification (the family sees it).
         */
        fun of(s: ServiceStatus?, needsUsageAccess: Boolean = false): SyncAttention? = when {
            s == null -> null
            s.stoppedByUser -> STOPPED
            s.missingPermissions.isNotEmpty() || s.state == ServiceState.NO_PERMISSION -> NO_PERMISSION
            s.state == ServiceState.BLUETOOTH_OFF -> BLUETOOTH_OFF
            s.state == ServiceState.STORAGE_ERROR -> KEY_ERROR
            needsUsageAccess && !s.usageAccessGranted -> USAGE_ACCESS_MISSING
            s.state == ServiceState.RADIO_ERROR -> RADIO_ERROR
            s.notificationHidden -> NOTIFICATION_HIDDEN
            else -> null
        }
    }
}

/** Roadmap Y5: a child that agreed to the Bluetooth-only text sees what changed before anything goes through the Disk. */
@Composable
private fun ReconsentCard(onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("dash_reconsent"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dash_reconsent_body))
            Button(onOpen, Modifier.testTag("dash_reconsent_open")) { Text(stringResource(R.string.dash_reconsent_open)) }
        }
    }
}

/** U7: who newly sees this child's time, until «Got it»; the notification may have been turned off. */
@Composable
private fun NewParentBanner(parents: List<MemberView>, onOk: (Set<DeviceId>) -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("dash_new_parent").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dash_new_parent, parents.joinToString(", ") { it.name }), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.dash_new_parent_body), style = MaterialTheme.typography.bodySmall)
            TextButton({ onOk(parents.map { it.id }.toSet()) }, Modifier.testTag("dash_new_parent_ok")) { Text(stringResource(R.string.dash_new_parent_ok)) }
        }
    }
}

/**
 * A-11: which phone to update, by name; this phone first when it is the one behind. Falls back to the generic text
 * when the engine reported a version reject without a known member.
 */
@Composable
private fun updateNoticeText(service: ServiceStatus?, d: DashboardState): String? {
    val outdated = service?.outdated.orEmpty()
    val names = (listOf(d.self) + d.parents + d.children.map { it.member } + listOfNotNull(d.selfUsage?.member)).associate { it.id to it.name }
    val pick = outdated.entries.filter { it.key in names }.sortedWith(compareByDescending<Map.Entry<DeviceId, Boolean>> { it.value }.thenBy { names.getValue(it.key) }).firstOrNull()
    return when {
        pick != null && pick.value -> stringResource(R.string.dash_update_self, names.getValue(pick.key))
        pick != null -> stringResource(R.string.dash_update_peer, names.getValue(pick.key))
        outdated.isNotEmpty() || service?.problem == SyncProblem.PEER_NEEDS_UPDATE -> stringResource(R.string.dash_update_notice)
        else -> null
    }
}

@Composable
private fun UpdateNotice(text: String) {
    Card(
        Modifier.fillMaxWidth().testTag("dash_update_notice"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, Modifier.padding(16.dp))
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
                        SyncAttention.USAGE_ACCESS_MISSING -> R.string.dash_usage_access_missing
                        SyncAttention.RADIO_ERROR -> R.string.dash_radio_error
                        SyncAttention.NOTIFICATION_HIDDEN -> R.string.dash_notification_hidden
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
                SyncAttention.USAGE_ACCESS_MISSING -> OutlinedButton(
                    { UsageAccessSettings.open(context) },
                    Modifier.testTag("dash_usage_access"),
                ) { Text(stringResource(R.string.dash_open_usage_access)) }
                SyncAttention.RADIO_ERROR -> Unit
                SyncAttention.NOTIFICATION_HIDDEN -> OutlinedButton(
                    {
                        context.open(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    Modifier.testTag("dash_notification_settings"),
                ) { Text(stringResource(R.string.dash_open_notification_settings)) }
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
    onResume: () -> Unit,
    onConsent: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (parents.isEmpty()) stringResource(R.string.dash_shared_with_nobody)
                else stringResource(R.string.dash_shared_with, parents.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.titleSmall,
            )
            when (sharing) {
                Sharing.On -> Text(
                    stringResource(R.string.dash_parent_only),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("dash_parent_only"),
                )
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
}

@Composable
private fun DeviceCard(u: DeviceUsage, nowMs: Long, noContactMs: Long, onClick: () -> Unit) {
    // The same list instance between ticks, so the chart skips recomposition (U16).
    val bars = remember(u.week) { u.week.map { it.totalMs } }
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
            } else {
                Text(stringResource(R.string.dash_no_data_today), style = MaterialTheme.typography.titleLarge)
            }
            u.updatedMs?.let { DataAt(it, nowMs, u.childZoneId) }
            u.place?.let { Place(it, nowMs) }
            if (u.today.hasData) comparison(u)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            BarChart(values = bars, labels = emptyList(), summary = summary, height = 40.dp)
            status(u, nowMs, noContactMs)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * «Data as of 14:05» under the figure (U3), on the viewer's clock with the child's own when it differs; older than
 * STALE_AFTER_MS it also says it may be out of date.
 */
@Composable
private fun DataAt(updatedMs: Long, nowMs: Long, childZoneId: String?) {
    val stale = UsageReadModel.isStale(updatedMs, nowMs)
    Text(
        stringResource(if (stale) R.string.dash_data_stale else R.string.dash_data_at, dataMoment(LocalContext.current, updatedMs, nowMs, childZoneId)),
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("dash_data_at"),
    )
}

/**
 * Spec §8: where a child is, on a parent's card; the time is the child's last word. An old word is marked like old data
 * (LocationReadModel.PLACE_STALE_MS): it may be out of date.
 */
@Composable
private fun Place(p: PlaceStatus, nowMs: Long) {
    val ago = relativeTime(p.atMs, nowMs)
    val text = when (p) {
        is PlaceStatus.InZone -> p.zoneName?.let { stringResource(R.string.dash_place_in_zone, it, ago) } ?: stringResource(R.string.dash_place_in_unknown, ago)
        is PlaceStatus.Outside -> stringResource(R.string.dash_place_outside, ago)
    }
    val stale = LocationReadModel.isStale(p, nowMs)
    Text(
        if (stale) stringResource(R.string.dash_place_stale, text) else text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (stale) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
        modifier = Modifier.testTag("dash_place"),
    )
}

/** U2: «usual by this time» with hourly detail, whole days only late in the day, else nothing. */
@Composable
private fun comparison(u: DeviceUsage): String? = when (val c = u.comparison) {
    is TodayComparison.ByThisTime -> versus(u.today.totalMs, c.usualMs, R.string.dash_vs_hour_same, R.string.dash_vs_hour_more, R.string.dash_vs_hour_less)
    is TodayComparison.FullDay -> versus(u.today.totalMs, c.averageMs, R.string.dash_vs_same, R.string.dash_vs_more, R.string.dash_vs_less)
    null -> null
}

/** Text, never colour alone (spec §9.8): within the band of [UsageReadModel.aboutTheSame] counts as «about the same». */
@Composable
private fun versus(todayMs: Long, usual: Long, same: Int, more: Int, less: Int): String {
    val diff = todayMs - usual
    return when {
        UsageReadModel.aboutTheSame(todayMs, usual) -> stringResource(same)
        diff > 0 -> stringResource(more, durationText(diff))
        else -> stringResource(less, durationText(-diff))
    }
}

/**
 * «No contact» uses the same setting and comparison as the alert (AlertPlanner), not a fixed day; the data time is
 * under the figure (U3).
 */
@Composable
private fun status(u: DeviceUsage, nowMs: Long, noContactMs: Long): String? = when (val s = u.sharing) {
    is Sharing.Paused -> stringResource(R.string.dash_card_paused, dateTime(s.sinceMs))
    Sharing.Never -> stringResource(R.string.dash_card_not_sharing)
    Sharing.Left -> stringResource(R.string.dash_card_left)
    Sharing.On -> {
        val seen = u.member.lastSeenMs
        when {
            !u.member.isSelf && seen != null && nowMs - seen > noContactMs -> stringResource(R.string.dash_card_not_seen, relativeTime(seen, nowMs))
            // The more specific cause first: a hidden notification, then collection off (cloud Y5).
            !u.member.isSelf && u.notificationHidden -> stringResource(R.string.dash_card_notification_hidden)
            !u.member.isSelf && u.collectionOff -> stringResource(R.string.dash_card_collection_off)
            u.updatedMs == null -> stringResource(R.string.dash_card_no_data)
            else -> null
        }
    }
}
