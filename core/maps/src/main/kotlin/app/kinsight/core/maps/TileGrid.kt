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

/** One tile of the z10..z14 pyramid inside a cell (offline map spec §7). It says where a child has been: never logged. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    override fun toString() = "TileKey"
}

/** The z10 grid the blocks are cut by (spec §2), with the same formula as `scripts/maps/build-blocks.sh`. */
object TileGrid {
    const val ZOOM = 10
    const val SIZE = 1 shl ZOOM

    /** Web Mercator stops here; a point further north or south counts as on the edge. */
    const val MAX_LAT = 85.05112878

    /** OpenFreeMap's deepest zoom (spec §7); MapLibre overzooms it on the phone. */
    const val MAX_TILE_ZOOM = 14

    /** Spec §7: every tile of zoom [ZOOM]..[maxZoom] inside [c], zoom by zoom, row by row: 85 to z13, 341 to z14. */
    fun tiles(c: Cell, maxZoom: Int): List<TileKey> {
        require(maxZoom in ZOOM..MAX_TILE_ZOOM) { "max zoom $maxZoom outside $ZOOM..$MAX_TILE_ZOOM" }
        val out = ArrayList<TileKey>(tileCount(maxZoom))
        for (z in ZOOM..maxZoom) {
            val n = 1 shl (z - ZOOM)
            for (y in c.y * n until (c.y + 1) * n) for (x in c.x * n until (c.x + 1) * n) out += TileKey(z, x, y)
        }
        return out
    }

    /** How many tiles [tiles] gives for [maxZoom]. */
    fun tileCount(maxZoom: Int): Int = (ZOOM..maxZoom).sumOf { 1 shl (2 * (it - ZOOM)) }

    fun cellOf(latE7: Int, lonE7: Int): Cell = cellOf(latE7 / 1e7, lonE7 / 1e7)

    fun cellOf(lat: Double, lon: Double): Cell {
        val x = floor((lon + 180.0) / 360.0 * SIZE).toInt().coerceIn(0, SIZE - 1)
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        val y = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * SIZE).toInt().coerceIn(0, SIZE - 1)
        return Cell(x, y)
    }

    fun bounds(c: Cell): Bounds = Bounds(lat(c.y + 1), lon(c.x), lat(c.y), lon(c.x + 1))

    /**
     * Spec §3: the cell and its eight neighbours, the blocks a point in it wants (all together, wherever the point is in
     * its cell, so the server learns only this 3 × 3 area). A neighbour off the grid is left out; one across the
     * antimeridian is not looked for (the family is in one city).
     */
    fun neighbourhood(c: Cell): Set<Cell> {
        val out = linkedSetOf<Cell>()
        for (dy in -1..1) for (dx in -1..1) if (c.x + dx in 0 until SIZE && c.y + dy in 0 until SIZE) out += Cell(c.x + dx, c.y + dy)
        return out
    }

    fun neighbourhood(latE7: Int, lonE7: Int): Set<Cell> = neighbourhood(cellOf(latE7, lonE7))

    /**
     * Spec §4: the point's own cell and every neighbour whose edge is closer than [radiusM], the blocks the preview
     * (3 km around the point) draws from. Downloads take the whole [neighbourhood].
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
