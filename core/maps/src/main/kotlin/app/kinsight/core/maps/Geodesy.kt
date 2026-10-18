package app.kinsight.core.maps

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A position as GeoJSON orders it. */
data class LonLat(val lon: Double, val lat: Double) {
    override fun toString() = "LonLat"
}

/** Sphere geometry for the map (the same Earth radius as core/location Geo). */
object Geodesy {
    const val EARTH_M = 6_371_008.8

    /** Metres per degree of latitude, as MapProjection uses it. */
    const val M_PER_DEG = 111_320.0

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val la1 = Math.toRadians(aLat)
        val la2 = Math.toRadians(bLat)
        val dLa = la2 - la1
        val dLo = Math.toRadians(bLon - aLon)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * EARTH_M * asin(sqrt(min(1.0, h)))
    }

    /** From the point to the nearest point of [b]; 0 inside. */
    fun distanceToBoundsM(lat: Double, lon: Double, b: Bounds): Double =
        distanceM(lat, lon, lat.coerceIn(b.south, b.north), lon.coerceIn(b.west, b.east))

    /** The point [distanceM] away on [bearingDeg] (0 north, 90 east). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): LonLat {
        val d = distanceM / EARTH_M
        val t = Math.toRadians(bearingDeg)
        val la1 = Math.toRadians(lat)
        val lo1 = Math.toRadians(lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(t))
        val lo2 = lo1 + atan2(sin(t) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return LonLat(normalizeLon(Math.toDegrees(lo2)), Math.toDegrees(la2))
    }

    /** Spec §4: a zone or accuracy circle as a closed ring of [vertices] + 1 positions, the first repeated last. */
    fun circle(latE7: Int, lonE7: Int, radiusM: Double, vertices: Int = 64): List<LonLat> {
        val lat = latE7 / 1e7
        val lon = lonE7 / 1e7
        val ring = (0 until vertices).map { i -> destination(lat, lon, 360.0 * i / vertices, radiusM) }
        return ring + ring.first()
    }

    /** A box [radiusM] around a point (flat: fine for a few kilometres). */
    fun boxAround(latE7: Int, lonE7: Int, radiusM: Double): Bounds {
        val lat = latE7 / 1e7
        val lon = lonE7 / 1e7
        val dLat = radiusM / M_PER_DEG
        val dLon = radiusM / (M_PER_DEG * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return Bounds(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    private fun normalizeLon(lon: Double) = ((lon + 540.0) % 360.0) - 180.0
}
