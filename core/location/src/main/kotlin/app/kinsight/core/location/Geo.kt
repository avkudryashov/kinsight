package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A safe zone as the child's phone watches it (spec §8.1): [id] the zone_id in hex, centre in degrees × 10^7.
 * [toString] hides the place; [signature] (stored on the device only) changes whenever the geometry does.
 */
data class Zone(val id: String, val latE7: Int, val lonE7: Int, val radiusM: Int) {
    val signature: String get() = "$id:$latE7:$lonE7:$radiusM"

    override fun toString() = "Zone(${id.take(8)})" // no radius: with a known centre it narrows the place
}

/** One fix (spec §8: one fixation through LocationManager; [timeMs] = Location.time). Never logged. */
data class Fix(val latE7: Int, val lonE7: Int, val accuracyM: Int, val timeMs: Long, val provider: Location.Provider) {
    override fun toString() = "Fix" // nothing of the fix: not even its accuracy is logged
}

object Geo {
    private const val EARTH_M = 6_371_008.8

    /** Great-circle distance in metres (haversine); longitudes may straddle the antimeridian. */
    fun distanceM(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int): Double {
        val la1 = Math.toRadians(aLatE7 / 1e7)
        val la2 = Math.toRadians(bLatE7 / 1e7)
        val dLa = la2 - la1
        val dLo = Math.toRadians((bLonE7.toLong() - aLonE7.toLong()) / 1e7)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * EARTH_M * asin(sqrt(min(1.0, h)))
    }
}
