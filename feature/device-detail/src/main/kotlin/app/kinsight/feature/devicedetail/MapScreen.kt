package app.kinsight.feature.devicedetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import app.kinsight.core.designsystem.theme.ChildColorKey
import app.kinsight.core.designsystem.theme.OnChildCard
import app.kinsight.core.designsystem.theme.childColor
import kotlinx.coroutines.delay
import java.util.Locale

/** The Карта tab of a parent's phone (UI refresh §5); a child's phone has no such tab. */
@Composable
fun MapRoute(onZones: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: MapViewModel = viewModel { MapViewModel(graph.location, graph.cloudRepository, graph.usage, graph.service) }
    MapRoute(vm, onZones)
}

@Composable
internal fun MapRoute(vm: MapViewModel, onZones: () -> Unit) {
    // Task 12: opening the tab asks for the Overview pull's sync, once per opening (MapViewModel.onOpen).
    LaunchedEffect(vm) { vm.onOpen() }
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
        if (state.searching) item(key = "searching") { Searching() }
        if (child == null) {
            item(key = "empty") { EmptyState(stringResource(R.string.map_no_children_title), stringResource(R.string.map_no_children_body), icon = Icons.Filled.LocationOn) }
            return@LazyColumn
        }
        items(state.children, key = { "status:${it.member.id.hex}" }) { c ->
            StatusCard(c, state.children, MapStatus.of(c, state.cloudOn, nowMs), nowMs, selected = c.member.id == child.member.id, canEditZones = state.canEditZones, onZones = onZones) {
                onSelect(c.member.id.hex)
            }
        }
        item(key = "map") {
            FamilyMap(child.zones, child.route, child.last, childMark(child, state.children), Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).testTag("loc_map"))
        }
        child.last?.let { p ->
            item(key = "last") {
                Text(
                    stringResource(R.string.loc_last_point, time(p.timeMs), p.accuracyM),
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

/** U6 on the Карта tab: the opening's sync is still under way. */
@Composable
private fun Searching() = Row(
    Modifier.fillMaxWidth().testTag("loc_searching").semantics { liveRegion = LiveRegionMode.Polite },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(stringResource(R.string.map_searching), style = MaterialTheme.typography.bodyMedium)
}

/** The child's own colour (Task 12), the same as its Overview card; a lighter tone of it on the dark theme's map. */
@Composable
private fun childMark(c: ChildLocation, all: List<ChildLocation>): Color {
    val color = childColor(c.member.id.hex, all.map { it.member.id.hex })
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) color.onDark else color.base
}

/** One child's status line; tapping it shows that child's map. Without any zone, a hint to add «Дом». */
@Composable
private fun StatusCard(
    c: ChildLocation,
    all: List<ChildLocation>,
    status: MapStatus,
    nowMs: Long,
    selected: Boolean,
    canEditZones: Boolean,
    onZones: () -> Unit,
    onClick: () -> Unit,
) {
    val color = childColor(c.member.id.hex, all.map { it.member.id.hex })
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }.testTag("loc_card_${c.member.id.hex}"),
        border = if (selected) BorderStroke(2.dp, childMark(c, all)) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // White on every palette colour keeps 4.5:1 (ChildColorsTest), in both themes.
            Box(Modifier.semantics { set(ChildColorKey, color) }.testTag("loc_avatar_${c.member.id.hex}")) {
                Avatar(c.member.name, container = color.base, content = OnChildCard)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.member.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statusText(status, c, nowMs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("loc_status"))
                reasonText(status, c)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("loc_reason"))
                }
                if (status is MapStatus.NoZones) ZoneHint(canEditZones, onZones)
            }
        }
    }
}

/** «Добавьте зону «Дом»»: an admin taps it to the zones screen; another parent cannot add one, so it is plain text. */
@Composable
private fun ZoneHint(canEditZones: Boolean, onZones: () -> Unit) {
    val text = stringResource(R.string.map_add_home_zone)
    if (canEditZones) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button, onClick = onZones).minimumInteractiveComponentSize().testTag("loc_zone_hint"),
        )
    } else {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("loc_zone_hint"))
    }
}

/** Task 12: every status with a point says how old it is; «с 14:10» and «Последняя точка N назад» already do. */
@Composable
private fun statusText(s: MapStatus, c: ChildLocation, nowMs: Long): String {
    val context = LocalContext.current
    @Composable
    fun aged(text: String): String = c.status?.let { stringResource(R.string.map_with_age, text, relativeTime(it.atMs, nowMs)) } ?: text
    return when (s) {
        is MapStatus.InZone -> when {
            s.zoneName == null -> aged(stringResource(R.string.map_in_unknown_zone))
            s.sinceMs != null -> stringResource(R.string.map_in_zone_since, s.zoneName, DateUtils.formatDateTime(context, s.sinceMs, DateUtils.FORMAT_SHOW_TIME))
            else -> aged(stringResource(R.string.map_in_zone, s.zoneName))
        }
        is MapStatus.Outside -> aged(
            when {
                s.nearestZone == null || s.distanceM == null -> stringResource(R.string.map_outside)
                s.distanceM == 0 -> stringResource(R.string.map_outside_edge, s.nearestZone)
                else -> stringResource(R.string.map_outside_near, distanceText(s.distanceM), s.nearestZone)
            },
        )
        is MapStatus.NoZones -> stringResource(R.string.map_with_age, stringResource(R.string.map_no_zones), relativeTime(s.atMs, nowMs))
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
