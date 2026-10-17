package app.kinsight.core.maps

/** A child's fix as the map needs it: degrees × 10^7 and the child's time of it. Never logged. */
data class MapPoint(val latE7: Int, val lonE7: Int, val timeMs: Long) {
    override fun toString() = "MapPoint"
}

/** One block to fetch (spec §7): its cell and how deep, [MapPlanner.CENTRE_ZOOM] or [MapPlanner.NEIGHBOUR_ZOOM]. Never logged. */
data class BlockFetch(val cell: Cell, val maxZoom: Int) {
    override fun toString() = "BlockFetch"
}

/** Offline map spec §3 with §7 as pure rules: which cells are wanted and how deep, which go, what one download takes. */
object MapPlanner {
    /** The preview reaches this far from the point (spec §4). */
    const val NEAR_M = 3_000.0

    /** A block without points this long is deleted (spec §3); a capped cell waits this long. */
    const val KEEP_MS = 7 * 24 * 3_600_000L

    /** The TileJSON is asked for at most this often (spec §7). */
    const val SOURCE_EVERY_MS = 24 * 3_600_000L

    /** Spec §2 «раз в несколько месяцев»: a block from a build this old is renewed from a newer one. */
    const val REFRESH_MS = 90 * 24 * 3_600_000L

    /** Spec §7, OpenFreeMap terms: one download (one run) at most 3 000 tiles and 50 MB. */
    const val MAX_TILES = 3_000
    const val MAX_BYTES = 50_000_000L

    /** Spec §3: room for a download plus this much. */
    const val SPARE_BYTES = 100L shl 20

    /** Spec §7: the point's own cell to z14, its neighbours to z13. */
    const val CENTRE_ZOOM = 14
    const val NEIGHBOUR_ZOOM = 13

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

    /** Spec §7: every point marks its own cell (the one that comes to z14), capped at [nowMs]; week-old marks go. */
    fun touchCentres(centres: Map<Cell, Long>, points: List<MapPoint>, nowMs: Long): Map<Cell, Long> {
        val out = centres.toMutableMap()
        for (p in points) {
            val t = minOf(p.timeMs, nowMs)
            val c = TileGrid.cellOf(p.latE7, p.lonE7)
            if ((out[c] ?: Long.MIN_VALUE) < t) out[c] = t
        }
        return out.filterValues { nowMs - it < KEEP_MS }
    }

    fun wanted(usage: Map<Cell, Long>, nowMs: Long): Set<Cell> = usage.filterValues { nowMs - it < KEEP_MS }.keys

    /** The neighbourhood of each child's newest point: never deleted, however old the mark (spec §3). */
    fun current(lastPoints: List<MapPoint>): Set<Cell> = lastPoints.flatMap { TileGrid.neighbourhood(it.latE7, it.lonE7) }.toSet()

    fun evictions(present: Set<Cell>, usage: Map<Cell, Long>, current: Set<Cell>, nowMs: Long): Set<Cell> =
        present.filter { c -> c !in current && (usage[c]?.let { nowMs - it >= KEEP_MS } ?: true) }.toSet()

    fun depth(c: Cell, centres: Map<Cell, Long>, nowMs: Long): Int =
        if (centres[c]?.let { nowMs - it < KEEP_MS } == true) CENTRE_ZOOM else NEIGHBOUR_ZOOM

    /** Spec resolution 4: the cells that passed the 50 MB cap less than [KEEP_MS] ago. */
    fun capped(capped: Map<Cell, Long>, nowMs: Long): Set<Cell> = capped.filterValues { nowMs - it < KEEP_MS }.keys

    /**
     * Every wanted cell whose file is missing ([present] says what is on the phone), not described by the ledger,
     * shallower than [depth], or [REFRESH_MS] old while a newer build may be out (the cached TileJSON names another
     * build, or it is due); capped cells wait. Centres first, then the newest mark.
     */
    fun downloads(wanted: Set<Cell>, ledger: BlockLedger, present: Set<Cell>, nowMs: Long): List<BlockFetch> {
        val skip = capped(ledger.capped, nowMs)
        val latest = ledger.source?.let(TileSource::of)?.build
        val due = sourceDue(ledger.sourceFetchedMs, nowMs)
        return wanted.filter { it !in skip }.mapNotNull { c ->
            val need = depth(c, ledger.centres, nowMs)
            val have = if (c in present) BlockVersion.parse(ledger.installed[c]) else null
            val renew = have != null && stale(have, nowMs) && (due || have.build != latest)
            if (have != null && have.maxZoom >= need && !renew) null else BlockFetch(c, need)
        }.sortedWith(compareByDescending<BlockFetch> { it.maxZoom }.thenByDescending { ledger.usage[it.cell] ?: 0L })
    }

    fun stale(v: BlockVersion, nowMs: Long): Boolean = v.builtMs()?.let { nowMs - it >= REFRESH_MS } ?: false

    /** One run is one download (spec §7): the fetches, in order, that fit within [MAX_TILES]. */
    fun batch(fetches: List<BlockFetch>): List<BlockFetch> {
        var tiles = 0
        return fetches.filter { f ->
            val n = TileGrid.tileCount(f.maxZoom)
            (tiles + n <= MAX_TILES).also { fits -> if (fits) tiles += n }
        }
    }

    /** Never asked, a day old, or this phone's clock went back since. */
    fun sourceDue(fetchedMs: Long?, nowMs: Long): Boolean = fetchedMs == null || nowMs - fetchedMs >= SOURCE_EVERY_MS || nowMs < fetchedMs

    /** Spec resolution 6: a run starts only with room for a whole download plus [SPARE_BYTES]. */
    fun hasRoom(freeBytes: Long): Boolean = freeBytes >= MAX_BYTES + SPARE_BYTES
}
