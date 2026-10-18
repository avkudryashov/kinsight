package app.kinsight.core.maps

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
            val usage = MapPlanner.touch(it.usage, points, now)
            it.copy(
                usage = usage,
                centres = MapPlanner.touchCentres(it.centres, points, now),
                capped = it.capped.filterValues { t -> now - t < MapPlanner.KEEP_MS },
                failed = it.failed.filterKeys(usage::containsKey), // a cell no longer wanted forgets its failures
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
     * one, each from at most [PARALLEL] requests at once. The run's [MapPlanner.MAX_TILES] requests (retries included)
     * and [MapPlanner.MAX_BYTES] bytes are counted per tile as it goes, so the run stops at the tile that crosses either.
     * [proceed] is asked before the TileJSON and before every cell (the family's cloud may have gone off, the phone
     * left, or the worker been stopped); [aborted] before every tile request (cheap), so a leave or a stop (roaming)
     * ends a cell at once. Either one ends the run with DONE and the partial deleted.
     *
     * Fix round 1: a cell that fails (a tile still failing after [TILE_TRIES] tries, a tile over the size cap) ends the
     * run with RETRY and waits out [MapPlanner.failureWait]; a success clears its failures. While some wanted cell
     * waits so, the run answers RETRY, so WorkManager comes back for it.
     */
    fun sync(proceed: () -> Boolean = { true }, aborted: () -> Boolean = { false }): SyncRun {
        store.sweepPartials()
        val now = clock()
        if (!MapPlanner.hasRoom(store.freeBytes())) return SyncRun.DONE
        if (plan(store.ledger(), now).isEmpty()) return waiting(now)
        val ledger = store.ledger()
        val due = MapPlanner.sourceDue(ledger.sourceFetchedMs, now)
        val source = ledger.source?.let(TileSource::of)?.takeUnless { due } ?: run {
            // A refused TileJSON is stamped too: not asked for again before a day has passed.
            if (!due) return SyncRun.RETRY
            if (!proceed()) return SyncRun.DONE
            val text = try {
                net.tileJson()
            } catch (e: IOException) {
                return SyncRun.RETRY
            }
            val fetched = TileJson.parse(text)
            store.updateLedger { it.copy(source = fetched?.template, sourceFetchedMs = now) }
            fetched ?: return SyncRun.RETRY
        }
        // Planned again: the fresh TileJSON may show that an old block's build is still the newest.
        val fetches = plan(store.ledger(), now)
        if (fetches.isEmpty()) return waiting(now)
        val budget = Budget()
        val pool = Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r, "kinsight-map").apply { isDaemon = true } }
        try {
            for (f in fetches) {
                if (!proceed()) return SyncRun.DONE
                when (fetchBlock(f, source, budget, pool, aborted)) {
                    Fetched.OK -> store.updateLedger {
                        it.copy(installed = it.installed + (f.cell to BlockVersion(source.build, f.maxZoom).encode()), failed = it.failed - f.cell)
                    }
                    Fetched.EMPTY -> store.updateLedger { it.copy(capped = it.capped + (f.cell to now), failed = it.failed - f.cell) }
                    Fetched.CAPPED -> {
                        store.updateLedger { it.copy(capped = it.capped + (f.cell to now)) }
                        return SyncRun.DONE
                    }
                    Fetched.GONE -> {
                        store.updateLedger { it.copy(sourceFetchedMs = null) }
                        return SyncRun.RETRY
                    }
                    Fetched.FAILED -> {
                        store.updateLedger { l -> l.copy(failed = l.failed + (f.cell to CellFailure((l.failed[f.cell]?.count ?: 0) + 1, clock()))) }
                        return SyncRun.RETRY
                    }
                    Fetched.LIMIT -> return SyncRun.RETRY
                    Fetched.ABORTED -> return SyncRun.DONE
                }
            }
            return waiting(now)
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

    /** Nothing more to fetch now: RETRY while a wanted cell waits out a failure, else DONE. */
    private fun waiting(now: Long): SyncRun {
        val l = store.ledger()
        val backingOff = MapPlanner.backingOff(l.failed, now) intersect MapPlanner.wanted(l.usage, now)
        return if (backingOff.isEmpty()) SyncRun.DONE else SyncRun.RETRY
    }

    private enum class Fetched { OK, EMPTY, CAPPED, GONE, FAILED, LIMIT, ABORTED }

    private class Aborted : IOException("aborted")

    private class Capped : IOException("over the download cap")

    private class Limit : IOException("over the request cap")

    private class Empty : IOException("no tile in the cell")

    /** Another request of the same chunk ended the cell; its cause is in the chunk's stop. */
    private class Halted : IOException("halted")

    /** One run's requests and received bytes, counted by the pool threads as each tile goes and comes. */
    private class Budget {
        private val requests = AtomicInteger()
        private val bytes = AtomicLong()

        fun request(): Boolean = requests.incrementAndGet() <= MapPlanner.MAX_TILES

        fun take(n: Int): Boolean = bytes.addAndGet(n.toLong()) <= MapPlanner.MAX_BYTES
    }

    /**
     * One cell: its tiles in chunks of [CHUNK], written into a partial PMTiles file that is renamed when complete. The
     * first request of a chunk that ends the cell (a failure, the cap, a gone build, an abort) sets the chunk's stop,
     * and every other request of the chunk ends before it asks the network.
     */
    private fun fetchBlock(f: BlockFetch, source: TileSource, budget: Budget, pool: ExecutorService, aborted: () -> Boolean): Fetched = try {
        store.install(f.cell) { part, scratch ->
            PmTilesWriter(scratch).use { w ->
                var added = 0
                for (chunk in TileGrid.tiles(f.cell, f.maxZoom).chunked(CHUNK)) {
                    if (aborted()) throw Aborted()
                    val stop = AtomicReference<IOException>()
                    val results = pool.invokeAll(chunk.map { t -> Callable { fetchTile(source.url(t), budget, stop, aborted) } })
                    stop.get()?.let { throw it }
                    for ((t, result) in chunk.zip(results)) {
                        val tile = unwrap(result) ?: continue
                        w.add(t.z, t.x, t.y, tile.bytes)
                        added++
                    }
                }
                if (added == 0) throw Empty()
                w.finish(part, TileGrid.bounds(f.cell), METADATA)
            }
        }
        Fetched.OK
    } catch (e: Aborted) {
        Fetched.ABORTED
    } catch (e: Capped) {
        Fetched.CAPPED
    } catch (e: Limit) {
        Fetched.LIMIT
    } catch (e: Empty) {
        Fetched.EMPTY
    } catch (e: SourceGone) {
        if (aborted()) Fetched.ABORTED else Fetched.GONE
    } catch (e: IOException) {
        if (aborted()) Fetched.ABORTED else Fetched.FAILED
    } catch (e: IllegalArgumentException) {
        Fetched.FAILED // a tile that claimed gzip and was not
    }

    /**
     * [TILE_TRIES] tries with growing pauses; a removed build or a tile over the size cap is not tried again. Each
     * request counts against the run's [Budget] before it goes, each tile's bytes as it comes. The tile comes back gzip.
     */
    private fun fetchTile(url: String, budget: Budget, stop: AtomicReference<IOException>, aborted: () -> Boolean): Tile? {
        fun end(e: IOException): IOException = e.also { stop.compareAndSet(null, it) }
        var attempt = 1
        while (true) {
            if (stop.get() != null) throw Halted()
            if (aborted()) throw end(Aborted())
            if (!budget.request()) throw end(Limit())
            val tile = try {
                net.tile(url)
            } catch (e: SourceGone) {
                throw end(e)
            } catch (e: TileTooBig) {
                throw end(e)
            } catch (e: IOException) {
                if (attempt >= TILE_TRIES) throw end(e)
                pause(RETRY_PAUSE_MS * attempt)
                attempt++
                continue
            } ?: return null
            if (!budget.take(tile.bytes.size)) throw end(Capped())
            return if (tile.gzip) tile else Tile(PmTiles.gzip(tile.bytes), gzip = true)
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
