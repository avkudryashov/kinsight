package app.kinsight.feature.devicedetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.ZoneView
import app.kinsight.core.location.Geo

/**
 * The family map (spec §12) without tiles or a map library (spec §15: online only for the user's Disk; no Play
 * services): zone circles with names, today's route, the last point with its accuracy, a scale bar; north up.
 */
@Composable
internal fun FamilyMap(zones: List<ZoneView>, route: List<GeoPoint>, last: GeoPoint?, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.loc_map_description)
    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val pad = with(density) { 24.dp.toPx() }
        val projection = remember(zones, route, last, w, h) {
            MapProjection.fit(mapCircles(zones, route, last), w, h, pad)
        }
        val scale = projection?.scaleBarMeters(w)
        val scaleLabel = scale?.let { if (it >= 1_000) stringResource(R.string.det_map_scale_km, it / 1_000) else stringResource(R.string.det_map_scale_m, it) }
        val colors = MaterialTheme.colorScheme
        val label = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
        val measurer = rememberTextMeasurer()
        // A halo or a zone larger than the fit (a capped accuracy) must not paint over the list around the map.
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            drawRect(colors.surfaceVariant)
            val p = projection ?: return@Canvas
            fun at(latE7: Int, lonE7: Int) = Offset(p.x(lonE7), p.y(latE7))
            for (z in zones) {
                val c = at(z.latE7, z.lonE7)
                val r = p.px(z.radiusM.toDouble())
                drawCircle(colors.primary.copy(alpha = 0.18f), r, c)
                drawCircle(colors.primary, r, c, style = Stroke(2.dp.toPx()))
                drawText(measurer, z.name, c + Offset(4.dp.toPx(), 4.dp.toPx()), label, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
            }
            if (route.size > 1) {
                val path = Path()
                route.forEachIndexed { i, pt ->
                    val o = at(pt.latE7, pt.lonE7)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, colors.tertiary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            route.forEach { pt -> drawCircle(colors.tertiary, 3.dp.toPx(), at(pt.latE7, pt.lonE7)) }
            last?.let { pt ->
                val o = at(pt.latE7, pt.lonE7)
                drawCircle(colors.primary.copy(alpha = 0.15f), p.px(pt.accuracyM.toDouble()), o)
                drawCircle(colors.primary, 7.dp.toPx(), o)
                drawCircle(colors.onPrimary, 7.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
            if (scale != null && scaleLabel != null) {
                val x0 = 12.dp.toPx()
                val y = size.height - 12.dp.toPx()
                drawLine(colors.onSurface, Offset(x0, y), Offset(x0 + p.px(scale.toDouble()), y), strokeWidth = 2.dp.toPx())
                drawText(measurer, scaleLabel, Offset(x0, y - 18.dp.toPx()), label)
            }
        }
    }
}

/** The last point's accuracy halo counts in the fit up to this; a vaguer fix is drawn clipped rather than zooming out. */
internal const val MAX_HALO_M = 1_000

/** A zone whose edge lies further than this from every point of today counts as far: it stays out of the fit. */
internal const val NEAR_ZONE_M = 5_000

/**
 * What the map must show: the route as points, the last point with its accuracy halo, and the zones near them with their
 * radius (Y4 final review Minor 5: one far-away zone would zoom today's route down to a dot; it is still drawn, clipped).
 * With no point of today, every zone.
 */
internal fun mapCircles(zones: List<ZoneView>, route: List<GeoPoint>, last: GeoPoint?): List<Triple<Int, Int, Int>> {
    val points = route + listOfNotNull(last)
    val near = if (points.isEmpty()) {
        zones
    } else {
        zones.filter { z -> points.any { p -> Geo.distanceM(z.latE7, z.lonE7, p.latE7, p.lonE7) - z.radiusM <= NEAR_ZONE_M } }
    }
    return near.map { Triple(it.latE7, it.lonE7, it.radiusM) } +
        route.map { Triple(it.latE7, it.lonE7, 0) } +
        listOfNotNull(last).map { Triple(it.latE7, it.lonE7, it.accuracyM.coerceIn(0, MAX_HALO_M)) }
}
