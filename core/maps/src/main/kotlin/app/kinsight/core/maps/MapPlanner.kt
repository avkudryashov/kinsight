package app.kinsight.core.maps

/** A child's fix as the map needs it: degrees × 10^7 and the child's time of it. Never logged. */
data class MapPoint(val latE7: Int, val lonE7: Int, val timeMs: Long) {
    override fun toString() = "MapPoint"
}

/** Offline map spec §3 as pure rules: which cells are wanted, which blocks go, what to fetch, when to ask. */
object MapPlanner {
    /** The preview reaches this far from the point (spec §4). */
    const val NEAR_M = 3_000.0

    /** A block without points this long is deleted (spec §3). */
    const val KEEP_MS = 7 * 24 * 3_600_000L

    /** The catalog is asked for at most this often (spec §3). */
    const val CATALOG_EVERY_MS = 24 * 3_600_000L

    /** Spec §3: a block is fetched only with its size plus this much free. */
    const val SPARE_BYTES = 100L shl 20

    /**
     * Every point marks the 3 × 3 neighbourhood of its cell ([TileGrid.neighbourhood]) with its time, never later than
     * [nowMs] (a child's clock may run ahead); marks [KEEP_MS] old are dropped. So a block counts as used while a point
     * lies within one cell of it, and one point wants its whole neighbourhood at once (spec §3).
     */
    fun touch(usage: Map<Cell, Long>, points: List<MapPoint>, nowMs: Long): Map<Cell, Long> {
        val out = usage.toMutableMap()
        for (p in points) {
            val t = minOf(p.timeMs, nowMs)
            for (c in TileGrid.neighbourhood(p.latE7, p.lonE7)) if ((out[c] ?: Long.MIN_VALUE) < t) out[c] = t
        }
        return out.filterValues { nowMs - it < KEEP_MS }
    }

    fun wanted(usage: Map<Cell, Long>, nowMs: Long): Set<Cell> = usage.filterValues { nowMs - it < KEEP_MS }.keys

    /** The neighbourhood of each child's newest point: never deleted, however old the mark (spec §3). */
    fun current(lastPoints: List<MapPoint>): Set<Cell> = lastPoints.flatMap { TileGrid.neighbourhood(it.latE7, it.lonE7) }.toSet()

    fun evictions(present: Set<Cell>, usage: Map<Cell, Long>, current: Set<Cell>, nowMs: Long): Set<Cell> =
        present.filter { c -> c !in current && (usage[c]?.let { nowMs - it >= KEEP_MS } ?: true) }.toSet()

    /**
     * Wanted, listed (a neighbour the catalog lacks is skipped), and missing or not the catalog's file (a newer catalog),
     * the newest mark first. One run (MapSync.sync) takes them all, so a neighbourhood is fetched together.
     */
    fun downloads(wanted: Set<Cell>, usage: Map<Cell, Long>, catalog: Catalog?, installed: Map<Cell, String>): List<CatalogBlock> {
        catalog ?: return emptyList()
        return wanted.mapNotNull { catalog.blocks[it] }.filter { installed[it.cell] != it.sha256 }.sortedByDescending { usage[it.cell] ?: 0L }
    }

    /** Never asked, a day old, or this phone's clock went back since. */
    fun catalogDue(fetchedMs: Long?, nowMs: Long): Boolean = fetchedMs == null || nowMs - fetchedMs >= CATALOG_EVERY_MS || nowMs < fetchedMs

    fun hasRoom(freeBytes: Long, blockBytes: Long): Boolean = freeBytes >= blockBytes + SPARE_BYTES
}
