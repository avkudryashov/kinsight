package app.kinsight.feature.devicedetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.PlaceStatus
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun LocationRoute(id: DeviceId, onZones: () -> Unit, onBack: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: LocationViewModel = viewModel(key = "location-${id.hex}") { LocationViewModel(graph.location, graph.usage, id) }
    val child by vm.child.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val c = child
    when {
        c != null -> LocationScreen(c, now, onZones, onBack)
        loaded -> MissingDevice(onBack)
        else -> Loading()
    }
}

/** Spec §8, §12: where the child is, today's route on the family map, the zones and today's zone events. */
@Composable
fun LocationScreen(child: ChildLocation, nowMs: Long, onZones: () -> Unit, onBack: () -> Unit) =
    Screen(stringResource(R.string.loc_title, child.member.name), onBack) { padding ->
        val context = LocalContext.current
        fun time(ms: Long) = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME)
        // Rulings L1: the last point in whatever maps app the parent has; Kinsight itself fetches no tiles. Hidden
        // when nothing on the phone opens a geo: link.
        val mapIntent = remember(child.last, context) {
            child.last?.let { Intent(Intent.ACTION_VIEW, geoUri(it.latE7, it.lonE7).toUri()) }
                ?.takeIf { it.resolveActivity(context.packageManager) != null }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("loc_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                Text(statusText(child.status, nowMs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("loc_status"))
            }
            item(key = "map") { FamilyMap(child.zones, child.route, child.last, Modifier.fillMaxWidth().aspectRatio(4f / 3f).testTag("loc_map")) }
            child.last?.let { p ->
                item(key = "last") {
                    Text(
                        stringResource(R.string.loc_last_point, time(p.timeMs), p.accuracyM, Coordinates.format(p.latE7, p.lonE7)),
                        modifier = Modifier.testTag("loc_last"),
                    )
                }
            }
            if (mapIntent != null) {
                item(key = "open_map") {
                    OutlinedButton(
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

@Composable
private fun statusText(s: PlaceStatus?, nowMs: Long): String = when (s) {
    null -> stringResource(R.string.loc_no_data)
    is PlaceStatus.InZone -> s.zoneName?.let { stringResource(R.string.loc_in_zone, it, relativeTime(s.atMs, nowMs)) }
        ?: stringResource(R.string.loc_in_unknown_zone, relativeTime(s.atMs, nowMs))
    is PlaceStatus.Outside -> stringResource(R.string.loc_outside, relativeTime(s.atMs, nowMs))
}

/** A `geo:lat,lon?q=lat,lon` link (RFC 5870 plus the `q` pin maps apps read), full precision, dot decimals. */
internal fun geoUri(latE7: Int, lonE7: Int): String {
    val c = String.format(Locale.ROOT, "%.7f,%.7f", latE7 / 1e7, lonE7 / 1e7)
    return "geo:$c?q=$c"
}
