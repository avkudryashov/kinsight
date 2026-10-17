package app.kinsight.core.maps

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The map's network (offline map spec §7): OpenFreeMap's TileJSON and its tiles, nothing else. Both throw IOException. */
interface MapNet {
    fun tileJson(): String

    /** The tile at [url] as sent; null when the server has none there (204). A removed build throws [SourceGone]. */
    fun tile(url: String): Tile?
}

enum class SyncRun { DONE, RETRY }

/**
 * What the Карта tab needs (offline map spec §4, §7): [blocks] the block files on the phone (absolute paths),
 * [skipped] wanted cells that will not come for now (no room on the phone, or over the size cap), [downloading] a
 * download runs now.
 */
data class StreetMaps(val blocks: Map<Cell, String>, val skipped: Set<Cell>, val downloading: Boolean) {
    companion object {
        val NONE = StreetMaps(emptyMap(), emptySet(), downloading = false)
    }
}

/** Offline map spec §3 and §7 over a [BlockStore]: the check (no network) and the download run. */
class MapSync(
    private val store: BlockStore,
    private val net: MapNet,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [points] every point the children have on this phone (today's routes and the last points), [lastPoints] each
     * child's newest. Marks the cells and centres, deletes the blocks a week went without, and says whether a download
     * run is needed: some wanted cell is missing, too shallow or due for renewal, and the phone has room.
     */
    fun check(points: List<MapPoint>, lastPoints: List<MapPoint>): Boolean {
        val now = clock()
        val ledger = store.updateLedger {
            it.copy(
                usage = MapPlanner.touch(it.usage, points, now),
                centres = MapPlanner.touchCentres(it.centres, points, now),
                capped = it.capped.filterValues { t -> now - t < MapPlanner.KEEP_MS },
            )
        }
        val evicted = MapPlanner.evictions(store.present(), ledger.usage, MapPlanner.current(lastPoints), now)
        if (evicted.isNotEmpty()) {
            evicted.forEach(store::delete)
            store.updateLedger { it.copy(installed = it.installed - evicted) }
        }
        return plan(ledger, now).isNotEmpty() && MapPlanner.hasRoom(store.freeBytes())
    }

    /**
     * One download (spec §7): the TileJSON when the cached one is missing or a day old, then the planned cells one by
     * one, each from at most [PARALLEL] requests at once, within [MapPlanner.MAX_TILES] tiles and
     * [MapPlanner.MAX_BYTES] bytes. [proceed] is asked before the TileJSON and before every cell (the family's cloud
     * may have gone off, or the phone left); [aborted] before every tile request (cheap), so a leave stops a cell at
     * once. Either one ends the run with DONE and the partial deleted.
     */
    fun sync(proceed: () -> Boolean = { true }, aborted: () -> Boolean = { false }): SyncRun {
        store.sweepPartials()
        val now = clock()
        if (plan(store.ledger(), now).isEmpty() || !MapPlanner.hasRoom(store.freeBytes())) return SyncRun.DONE
        val ledger = store.ledger()
        val cached = ledger.source?.let(TileSource::of)?.takeUnless { MapPlanner.sourceDue(ledger.sourceFetchedMs, now) }
        val source = cached ?: run {
            if (!proceed()) return SyncRun.DONE
            val fetched = try {
                TileJson.parse(net.tileJson())
            } catch (e: IOException) {
                null
            } ?: return SyncRun.RETRY
            store.updateLedger { it.copy(source = fetched.template, sourceFetchedMs = now) }
            fetched
        }
        // Planned again: the fresh TileJSON may show that an old block's build is still the newest.
        val fetches = plan(store.ledger(), now)
        if (fetches.isEmpty()) return SyncRun.DONE
        val budget = Budget(MapPlanner.MAX_BYTES)
        val pool = Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r, "kinsight-map").apply { isDaemon = true } }
        try {
            var failed = false
            for (f in fetches) {
                if (!proceed()) return SyncRun.DONE
                when (fetchBlock(f, source, budget, pool, aborted)) {
                    Fetched.OK -> store.updateLedger { it.copy(installed = it.installed + (f.cell to BlockVersion(source.build, f.maxZoom).encode())) }
                    Fetched.CAPPED -> {
                        store.updateLedger { it.copy(capped = it.capped + (f.cell to now)) }
                        return SyncRun.DONE
                    }
                    Fetched.GONE -> {
                        store.updateLedger { it.copy(sourceFetchedMs = null) }
                        return SyncRun.RETRY
                    }
                    Fetched.FAILED -> failed = true
                    Fetched.ABORTED -> return SyncRun.DONE
                }
            }
            return if (failed) SyncRun.RETRY else SyncRun.DONE
        } finally {
            pool.shutdownNow()
        }
    }

    fun streets(downloading: Boolean): StreetMaps {
        val now = clock()
        val l = store.ledger()
        val present = store.present()
        val noRoom = if (MapPlanner.hasRoom(store.freeBytes())) emptySet() else MapPlanner.wanted(l.usage, now) - present
        return StreetMaps(present.associateWith { store.file(it).absolutePath }, MapPlanner.capped(l.capped, now) + noRoom, downloading)
    }

    private fun plan(l: BlockLedger, now: Long): List<BlockFetch> =
        MapPlanner.batch(MapPlanner.downloads(MapPlanner.wanted(l.usage, now), l, store.present(), now))

    private enum class Fetched { OK, CAPPED, GONE, FAILED, ABORTED }

    private class Aborted : IOException("aborted")

    private class Capped : IOException("over the download cap")

    /** The bytes of one download as received; used only by the run's own thread. */
    private class Budget(private val max: Long) {
        private var used = 0L

        fun take(bytes: Long): Boolean {
            used += bytes
            return used <= max
        }
    }

    /** One cell: its tiles in chunks of [CHUNK], written into a partial PMTiles file that is renamed when complete. */
    private fun fetchBlock(f: BlockFetch, source: TileSource, budget: Budget, pool: ExecutorService, aborted: () -> Boolean): Fetched = try {
        store.install(f.cell) { part, scratch ->
            PmTilesWriter(scratch).use { w ->
                for (chunk in TileGrid.tiles(f.cell, f.maxZoom).chunked(CHUNK)) {
                    if (aborted()) throw Aborted()
                    val results = pool.invokeAll(chunk.map { t -> Callable { fetchTile(source.url(t), aborted) } })
                    for ((t, result) in chunk.zip(results)) {
                        val tile = unwrap(result) ?: continue
                        if (!budget.take(tile.bytes.size.toLong())) throw Capped()
                        w.add(t.z, t.x, t.y, if (tile.gzip) tile.bytes else PmTiles.gzip(tile.bytes))
                    }
                }
                w.finish(part, TileGrid.bounds(f.cell), METADATA)
            }
        }
        Fetched.OK
    } catch (e: Aborted) {
        Fetched.ABORTED
    } catch (e: Capped) {
        Fetched.CAPPED
    } catch (e: SourceGone) {
        if (aborted()) Fetched.ABORTED else Fetched.GONE
    } catch (e: IOException) {
        if (aborted()) Fetched.ABORTED else Fetched.FAILED
    } catch (e: IllegalArgumentException) {
        Fetched.FAILED // a tile that claimed gzip and was not
    }

    /** [TILE_TRIES] tries with growing pauses; a removed build is not tried again. */
    private fun fetchTile(url: String, aborted: () -> Boolean): Tile? {
        var attempt = 1
        while (true) {
            if (aborted()) throw Aborted()
            try {
                return net.tile(url)
            } catch (e: SourceGone) {
                throw e
            } catch (e: IOException) {
                if (attempt >= TILE_TRIES) throw e
                pause(RETRY_PAUSE_MS * attempt)
                attempt++
            }
        }
    }

    private fun unwrap(f: Future<Tile?>): Tile? = try {
        f.get()
    } catch (e: ExecutionException) {
        throw (e.cause as? IOException) ?: IOException(e.cause)
    }

    private companion object {
        /** Spec §7, OpenFreeMap terms: at most 4 parallel requests. */
        const val PARALLEL = 4
        const val CHUNK = 16
        const val TILE_TRIES = 3
        const val RETRY_PAUSE_MS = 1_000L
        const val METADATA = """{"attribution":"© OpenMapTiles © OpenStreetMap contributors"}"""
    }
}
