package app.kinsight.feature.devicedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.maps.Bounds
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.OverlayColors
import app.kinsight.core.maps.OverlayZone
import app.kinsight.core.maps.Overlays
import app.kinsight.core.maps.PreviewFrame
import app.kinsight.core.maps.StyleComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** How the street preview is drawn: MapLibre in the app, a stand-in in the JVM tests (no native code there). */
typealias StreetMapSlot = @Composable (style: String, frame: Bounds, modifier: Modifier) -> Unit

private const val STYLE_LIGHT = "map/style-light.json"
private const val STYLE_DARK = "map/style-dark.json"
private val PREVIEW_HEIGHT = 240.dp

/** Offline map spec §4: the street preview when the point's block is here, else the plain family map with its caption. */
@Composable
internal fun ChildMap(child: ChildLocation, mark: Color, preview: StreetPreview, apps: MapApps?, slot: StreetMapSlot) {
    if (preview is StreetPreview.Ready && child.last != null) StreetPreviewCard(child, mark, preview, apps, slot) else PlainMap(child, mark, preview)
}

@Composable
private fun PlainMap(child: ChildLocation, mark: Color, preview: StreetPreview) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FamilyMap(child.zones, child.route, child.last, mark, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large).testTag("loc_map"))
    val caption = when (preview) {
        StreetPreview.Loading -> R.string.map_streets_loading
        StreetPreview.WaitingWifi -> R.string.map_streets_wifi
        else -> null
    }
    if (caption != null) {
        Text(
            stringResource(caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("loc_streets_status"),
        )
    }
}

@Composable
private fun StreetPreviewCard(child: ChildLocation, mark: Color, preview: StreetPreview.Ready, apps: MapApps?, slot: StreetMapSlot) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val overlay = OverlayColors(mark = mark.hex(), zone = colors.primary.hex(), halo = colors.surfaceContainerHighest.hex())
    // The asset read and the composition (71 layers per block) stay off the main thread; the fallback shows meanwhile.
    val style by produceState<String?>(null, dark, preview, child, overlay) {
        value = withContext(Dispatchers.Default) {
            val template = context.assets.open(if (dark) STYLE_DARK else STYLE_LIGHT).use { it.readBytes().decodeToString() }
            previewStyle(template, preview, child, overlay)
        }
    }
    val frame = remember(child) { previewFrame(child) }
    val description = stringResource(R.string.loc_preview_description)
    val open = apps?.preferred
    val ready = style
    if (ready == null) {
        PlainMap(child, mark, preview)
        return
    }
    Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clip(MaterialTheme.shapes.large).testTag("loc_preview")) {
        slot(ready, frame, Modifier.fillMaxSize())
        // Over the map view, so the tap reaches Compose (spec §4: the preview has no gestures of its own).
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = description }
                .then(if (open != null) Modifier.clickable(role = Role.Button) { openMaps(context, open) } else Modifier)
                .testTag("loc_preview_tap"),
        )
        Attribution(Modifier.align(Alignment.BottomEnd))
    }
}

private const val ATTRIBUTION_MS = 5_000L
private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"

/** OSM attribution guidelines: the short credit on show for 5 s, then a 48 dp «ⓘ» that brings it back; the credit links to the licence. */
@Composable
private fun Attribution(modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(true) }
    var shownAt by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown, shownAt) {
        if (shown) {
            delay(ATTRIBUTION_MS)
            shown = false
        }
    }
    if (shown) {
        Text(
            stringResource(R.string.map_osm_attribution),
            style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
            color = colors.onSurface,
            modifier = modifier.background(colors.surface.copy(alpha = 0.8f))
                .clickable(role = Role.Button) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT)))
                    } catch (_: ActivityNotFoundException) {
                        // No browser: nothing to open.
                    }
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("loc_osm"),
        )
    } else {
        val description = stringResource(R.string.loc_osm_info)
        Box(
            modifier.size(48.dp).clickable(role = Role.Button) { shownAt++; shown = true }
                .semantics { contentDescription = description }.testTag("loc_osm_info"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ⓘ",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier.background(colors.surface.copy(alpha = 0.8f), CircleShape).padding(horizontal = 4.dp),
            )
        }
    }
}

private fun ChildLocation.overlayZones() = zones.map { OverlayZone(it.latE7, it.lonE7, it.radiusM) }

private fun ChildLocation.overlayRoute() = route.map { MapPoint(it.latE7, it.lonE7, it.timeMs) }

/** The bundled style with this child's blocks and overlays; a vague fix's circle is capped like FamilyMap's halo. */
internal fun previewStyle(template: String, ready: StreetPreview.Ready, c: ChildLocation, colors: OverlayColors): String {
    val last = requireNotNull(c.last) { "a ready preview has a point" }
    val overlays = Overlays(c.overlayZones(), c.overlayRoute(), MapPoint(last.latE7, last.lonE7, last.timeMs), last.accuracyM.coerceIn(0, MAX_HALO_M), colors)
    return StyleComposer.compose(template, ready.blocks, overlays)
}

internal fun previewFrame(c: ChildLocation): Bounds {
    val last = requireNotNull(c.last) { "a ready preview has a point" }
    return PreviewFrame.of(MapPoint(last.latE7, last.lonE7, last.timeMs), last.accuracyM.coerceIn(0, MAX_HALO_M), c.overlayRoute(), c.overlayZones())
}

private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
