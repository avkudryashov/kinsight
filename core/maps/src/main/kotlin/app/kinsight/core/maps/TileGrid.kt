package app.kinsight.core.maps

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One z10 Web Mercator tile; a map block covers it (offline map spec §2: about 30 × 30 km at Valencia's latitude). */
data class Cell(val x: Int, val y: Int) {
    /** The block's file and catalog name. */
    val name: String get() = "z${TileGrid.ZOOM}-$x-$y"

    override fun toString() = "Cell" // a cell narrows where a child has been: never logged
}

/** A box in degrees. */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun union(o: Bounds) = Bounds(minOf(south, o.south), minOf(west, o.west), maxOf(north, o.north), maxOf(east, o.east))

    fun intersect(o: Bounds) = Bounds(maxOf(south, o.south), maxOf(west, o.west), minOf(north, o.north), minOf(east, o.east))

    override fun toString() = "Bounds"
}

/** The z10 grid the blocks are cut by (spec §2), with the same formula as `scripts/maps/build-blocks.sh`. */
object TileGrid {
    const val ZOOM = 10
    const val SIZE = 1 shl ZOOM

    /** Web Mercator stops here; a point further north or south counts as on the edge. */
    const val MAX_LAT = 85.05112878

    fun cellOf(latE7: Int, lonE7: Int): Cell = cellOf(latE7 / 1e7, lonE7 / 1e7)

    fun cellOf(lat: Double, lon: Double): Cell {
        val x = floor((lon + 180.0) / 360.0 * SIZE).toInt().coerceIn(0, SIZE - 1)
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        val y = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * SIZE).toInt().coerceIn(0, SIZE - 1)
        return Cell(x, y)
    }

    fun bounds(c: Cell): Bounds = Bounds(lat(c.y + 1), lon(c.x), lat(c.y), lon(c.x + 1))

    /**
     * Spec §3: the point's own cell and every neighbour whose edge is closer than [radiusM] (the preview reaches 3 km).
     * A neighbour across the antimeridian is not looked for (the family is in one city).
     */
    fun cellsNear(latE7: Int, lonE7: Int, radiusM: Double): Set<Cell> {
        val lat = latE7 / 1e7
        val lon = lonE7 / 1e7
        val own = cellOf(lat, lon)
        val out = linkedSetOf(own)
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = own.x + dx
                val y = own.y + dy
                if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                val c = Cell(x, y)
                if (Geodesy.distanceToBoundsM(lat, lon, bounds(c)) < radiusM) out += c
            }
        }
        return out
    }

    private fun lon(x: Int) = x.toDouble() / SIZE * 360.0 - 180.0

    private fun lat(y: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / SIZE))))
}
