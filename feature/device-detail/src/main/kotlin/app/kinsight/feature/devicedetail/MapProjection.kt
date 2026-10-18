package app.kinsight.feature.devicedetail

import kotlin.math.cos
import kotlin.math.max

/**
 * A local equirectangular projection (north up, east right) that fits circles (latE7, lonE7, radius m; a point is
 * radius 0) into a box. Fine for a city; a family spread across the antimeridian is out of scope (the route would draw
 * across the map).
 */
internal class MapProjection private constructor(
    private val lat0: Double,
    private val lon0: Double,
    private val cosLat: Double,
    val metersPerPx: Double,
    private val cx: Float,
    private val cy: Float,
) {
    fun x(lonE7: Int): Float = (cx + (lonE7 / 1e7 - lon0) * M_PER_DEG * cosLat / metersPerPx).toFloat()

    fun y(latE7: Int): Float = (cy - (latE7 / 1e7 - lat0) * M_PER_DEG / metersPerPx).toFloat()

    fun px(meters: Double): Float = (meters / metersPerPx).toFloat()

    /** The longest round length that fits in a quarter of [widthPx]. */
    fun scaleBarMeters(widthPx: Float): Int = STEPS.lastOrNull { it / metersPerPx <= widthPx / 4 } ?: STEPS.first()

    companion object {
        const val M_PER_DEG = 111_320.0
        const val MIN_SPAN_M = 300.0
        val STEPS = listOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

        fun fit(circles: List<Triple<Int, Int, Int>>, widthPx: Float, heightPx: Float, paddingPx: Float): MapProjection? {
            if (circles.isEmpty()) return null
            val w = widthPx - 2 * paddingPx
            val h = heightPx - 2 * paddingPx
            if (w <= 0f || h <= 0f) return null
            val lats = circles.map { it.first / 1e7 }
            val latMid = (lats.min() + lats.max()) / 2
            val cosLat = cos(Math.toRadians(latMid)).coerceAtLeast(0.01)
            val lonRef = circles.first().second / 1e7
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for ((lat, lon, r) in circles) {
                val x = (lon / 1e7 - lonRef) * M_PER_DEG * cosLat
                val y = (lat / 1e7 - latMid) * M_PER_DEG
                minX = minOf(minX, x - r)
                maxX = maxOf(maxX, x + r)
                minY = minOf(minY, y - r)
                maxY = maxOf(maxY, y + r)
            }
            val mpp = max(max(maxX - minX, MIN_SPAN_M) / w, max(maxY - minY, MIN_SPAN_M) / h)
            val lon0 = lonRef + (minX + maxX) / 2 / (M_PER_DEG * cosLat)
            val lat0 = latMid + (minY + maxY) / 2 / M_PER_DEG
            return MapProjection(lat0, lon0, cosLat, mpp, widthPx / 2, heightPx / 2)
        }
    }
}
