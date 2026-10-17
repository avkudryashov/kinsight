package app.kinsight.feature.devicedetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.SetupWarning
import app.kinsight.core.designsystem.component.Avatar
import app.kinsight.core.designsystem.component.EmptyState
import app.kinsight.core.designsystem.component.relativeTime
import kotlinx.coroutines.delay
import java.util.Locale

/** The Карта tab of a parent's phone (UI refresh §5); a child's phone has no such tab. */
@Composable
fun MapRoute(onZones: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: MapViewModel = viewModel { MapViewModel(graph.location, graph.cloudRepository, graph.usage) }
    val state by vm.state.collectAsStateWithLifecycle()
    // «2 ч назад» ticks only while the tab is in front; a return refreshes «now» at once.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    if (!state.loaded) Loading() else MapScreen(state, now, selected, onSelect = { selected = it }, onZones = onZones)
}

/**
 * UI refresh §5 (variant A): a big status line per child («В зоне «Дом» с 14:10», «Вне зон · 350 м от «Школа»»,
 * «Последняя точка 2 ч назад», «Местоположения пока нет» with the reason), then the selected child's family map (zones,
 * today's route, the last point; no tiles), «Открыть в Картах» (a geo: link, only on the parent's tap), today's zone
 * events and «Зоны». [selectedId]: a child's id hex; null or unknown selects the first child.
 */
@Composable
fun MapScreen(state: MapState, nowMs: Long, selectedId: String? = null, onSelect: (String) -> Unit = {}, onZones: () -> Unit) {
    val context = LocalContext.current
    val child = state.children.firstOrNull { it.member.id.hex == selectedId } ?: state.children.firstOrNull()
    fun time(ms: Long) = DateUtils.formatDateTime(context, ms, timeFlags(ms, nowMs))
    // Rulings L1: the last point in whatever maps app the parent has; Kinsight itself fetches no tiles. Hidden when
    // nothing on the phone opens a geo: link.
    val mapIntent = remember(child?.last, context) {
        child?.last?.let { Intent(Intent.ACTION_VIEW, geoUri(it.latE7, it.lonE7).toUri()) }
            ?.takeIf { it.resolveActivity(context.packageManager) != null }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("loc_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.map_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        if (child == null) {
            item(key = "empty") { EmptyState(stringResource(R.string.map_no_children_title), stringResource(R.string.map_no_children_body), icon = Icons.Filled.LocationOn) }
            return@LazyColumn
        }
        items(state.children, key = { "status:${it.member.id.hex}" }) { c ->
            StatusCard(c, MapStatus.of(c, state.cloudOn, nowMs), nowMs, selected = c.member.id == child.member.id) { onSelect(c.member.id.hex) }
        }
        item(key = "map") {
            FamilyMap(child.zones, child.route, child.last, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).testTag("loc_map"))
        }
        child.last?.let { p ->
            item(key = "last") {
                Text(
                    stringResource(R.string.loc_last_point, time(p.timeMs), p.accuracyM, Coordinates.format(p.latE7, p.lonE7)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("loc_last"),
                )
            }
        }
        if (mapIntent != null) {
            item(key = "open_map") {
                Button(
                    onClick = {
                        try {
                            context.startActivity(mapIntent)
                        } catch (_: ActivityNotFoundException) {
                            // The maps app went away since the check: nothing to open.
                        }
                    },
                    modifier = Modifier.testTag("loc_open_map"),
                ) { Text(stringResource(R.string.loc_open_map)) }
            }
        }
        item(key = "route") { Text(pluralStringResource(R.plurals.loc_route_points, child.route.size, child.route.size)) }
        item(key = "events_title") { SectionTitle(stringResource(R.string.loc_events)) }
        if (child.zoneEvents.isEmpty()) item(key = "no_events") { Text(stringResource(R.string.loc_no_events)) }
        items(child.zoneEvents, key = { "ev:${it.seq}" }) { e ->
            ListItem(
                headlineContent = { Text(eventText(e)) },
                supportingContent = { Text(time(e.atMs)) },
                modifier = Modifier.testTag("loc_event_${e.seq}"),
            )
        }
        item(key = "zones") {
            OutlinedButton(onZones, Modifier.testTag("loc_zones")) { Text(stringResource(R.string.loc_zones, child.zones.size)) }
        }
    }
}

/** One child's status line; tapping it shows that child's map. */
@Composable
private fun StatusCard(c: ChildLocation, status: MapStatus, nowMs: Long, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }.testTag("loc_card_${c.member.id.hex}"),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(c.member.name, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.member.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statusText(status, nowMs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("loc_status"))
                reasonText(status, c)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("loc_reason"))
                }
            }
        }
    }
}

@Composable
private fun statusText(s: MapStatus, nowMs: Long): String {
    val context = LocalContext.current
    return when (s) {
        is MapStatus.InZone -> when {
            s.zoneName == null -> stringResource(R.string.map_in_unknown_zone)
            s.sinceMs != null -> stringResource(R.string.map_in_zone_since, s.zoneName, DateUtils.formatDateTime(context, s.sinceMs, DateUtils.FORMAT_SHOW_TIME))
            else -> stringResource(R.string.map_in_zone, s.zoneName)
        }
        is MapStatus.Outside -> when {
            s.nearestZone == null || s.distanceM == null -> stringResource(R.string.map_outside)
            s.distanceM == 0 -> stringResource(R.string.map_outside_edge, s.nearestZone)
            else -> stringResource(R.string.map_outside_near, distanceText(s.distanceM), s.nearestZone)
        }
        is MapStatus.Stale -> stringResource(R.string.map_stale, relativeTime(s.atMs, nowMs))
        is MapStatus.NoPlace -> stringResource(R.string.map_none_title)
    }
}

@Composable
private fun distanceText(m: Int): String =
    if (m < 1_000) stringResource(R.string.map_distance_m, m) else stringResource(R.string.map_distance_km, String.format(locale(), "%.1f", m / 1_000.0))

/** Why there is no place (NoPlace), or an open problem under a known place (the place may be about to go stale). */
@Composable
private fun reasonText(s: MapStatus, c: ChildLocation): String? {
    val reason = (s as? MapStatus.NoPlace)?.reason ?: when {
        SetupWarning.LOCATION_PERMISSION_OFF in c.warnings -> NoPlaceReason.PERMISSION_OFF
        SetupWarning.LOCATION_DISABLED in c.warnings -> NoPlaceReason.LOCATION_DISABLED
        else -> null
    } ?: return null
    return stringResource(
        when (reason) {
            NoPlaceReason.CLOUD_OFF -> R.string.map_reason_cloud_off
            NoPlaceReason.NOT_SHARING -> R.string.map_reason_not_sharing
            NoPlaceReason.PERMISSION_OFF -> R.string.map_reason_permission
            NoPlaceReason.LOCATION_DISABLED -> R.string.map_reason_disabled
            NoPlaceReason.WAITING -> R.string.map_reason_waiting
        },
    )
}

/** A `geo:lat,lon?q=lat,lon` link (RFC 5870 plus the `q` pin maps apps read), full precision, dot decimals. */
internal fun geoUri(latE7: Int, lonE7: Int): String {
    val c = String.format(Locale.ROOT, "%.7f,%.7f", latE7 / 1e7, lonE7 / 1e7)
    return "geo:$c?q=$c"
}

/** A time of today shows the time only; an older one (a point may be up to 48 h old) shows its date too (Y4 review Minor 4). */
internal fun timeFlags(ms: Long, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int {
    fun day(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    return if (day(ms) == day(nowMs)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
}
