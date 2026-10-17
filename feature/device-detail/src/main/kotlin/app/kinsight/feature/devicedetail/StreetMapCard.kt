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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import app.kinsight.core.data.ChildLocation
import app.kinsight.core.maps.Bounds
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.OverlayColors
import app.kinsight.core.maps.OverlayZone
import app.kinsight.core.maps.Overlays
import app.kinsight.core.maps.PreviewFrame
import app.kinsight.core.maps.StyleComposer
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
    val template = remember(dark) { context.assets.open(if (dark) STYLE_DARK else STYLE_LIGHT).use { it.readBytes().decodeToString() } }
    val overlay = OverlayColors(mark = mark.hex(), zone = colors.primary.hex(), halo = colors.surfaceContainerHighest.hex())
    val style = remember(template, preview, child, overlay) { previewStyle(template, preview, child, overlay) }
    val frame = remember(child) { previewFrame(child) }
    val description = stringResource(R.string.loc_preview_description)
    val open = apps?.preferred
    Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clip(MaterialTheme.shapes.large).testTag("loc_preview")) {
        slot(style, frame, Modifier.fillMaxSize())
        // Over the map view, so the tap reaches Compose (spec §4: the preview has no gestures of its own).
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = description }
                .then(if (open != null) Modifier.clickable(role = Role.Button) { openMaps(context, open) } else Modifier)
                .testTag("loc_preview_tap"),
        )
        // ODbL (spec §4): always visible on the preview.
        Text(
            stringResource(R.string.map_osm_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurface,
            modifier = Modifier.align(Alignment.BottomEnd).background(colors.surface.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("loc_osm"),
        )
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
