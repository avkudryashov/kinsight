package app.kinsight.core.maps

import java.io.IOException
import java.io.InputStream

/** The map's network (spec §2): the catalog and whole block files, nothing else. Both throw IOException. */
interface MapNet {
    fun catalog(): String

    /** The block file at a catalog [url]; the caller closes it. */
    fun open(url: String): InputStream
}

enum class SyncRun { DONE, RETRY }

/**
 * What the Карта tab needs (offline map spec §4): [blocks] the block files on the phone (absolute paths), [catalog] the
 * cells the catalog lists (null before the first catalog), [noRoom] listed cells missing for lack of space (spec §3),
 * [downloading] a download runs now.
 */
data class StreetMaps(val blocks: Map<Cell, String>, val catalog: Set<Cell>?, val noRoom: Set<Cell>, val downloading: Boolean) {
    companion object {
        val NONE = StreetMaps(emptyMap(), null, emptySet(), downloading = false)
    }
}

/** Offline map spec §3 over a [BlockStore]: the check (no network) and the network run. */
class MapSync(private val store: BlockStore, private val net: MapNet, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * [points] every point the children have on this phone (today's routes and the last points), [lastPoints] each
     * child's newest. Marks the cells, deletes the blocks a week went without, and says whether a network run is
     * needed: the catalog is due while some cell is wanted, or a wanted listed block is missing and fits.
     */
    fun check(points: List<MapPoint>, lastPoints: List<MapPoint>): Boolean {
        val now = clock()
        val ledger = store.updateLedger { it.copy(usage = MapPlanner.touch(it.usage, points, now)) }
        val evicted = MapPlanner.evictions(store.present(), ledger.usage, MapPlanner.current(lastPoints), now)
        if (evicted.isNotEmpty()) {
            evicted.forEach(store::delete)
            store.updateLedger { it.copy(installed = it.installed - evicted) }
        }
        val wanted = MapPlanner.wanted(ledger.usage, now)
        if (wanted.isEmpty()) return false
        if (MapPlanner.catalogDue(ledger.catalogFetchedMs, now)) return true
        val free = store.freeBytes()
        return MapPlanner.downloads(wanted, ledger.usage, store.catalog(), installed(store.ledger())).any { MapPlanner.hasRoom(free, it.bytes) }
    }

    /** The network run: the catalog when due, then every wanted listed block that is missing, one by one. */
    fun sync(): SyncRun {
        store.sweepPartials()
        val now = clock()
        val ledger = store.ledger()
        if (MapPlanner.wanted(ledger.usage, now).isEmpty()) return SyncRun.DONE
        var catalog = store.catalog()
        if (MapPlanner.catalogDue(ledger.catalogFetchedMs, now)) {
            val text = try {
                net.catalog()
            } catch (e: IOException) {
                return SyncRun.RETRY
            }
            val fetched = CatalogParser.parse(text) ?: return SyncRun.RETRY
            if (catalog == null || fetched.version >= catalog.version) {
                store.saveCatalog(text)
                catalog = fetched
            }
            store.updateLedger { it.copy(catalogFetchedMs = now) }
        }
        val known = catalog ?: return SyncRun.DONE
        var failed = false
        val tried = HashSet<Cell>()
        while (true) {
            val l = store.ledger()
            val next = MapPlanner.downloads(MapPlanner.wanted(l.usage, clock()), l.usage, known, installed(l))
                .firstOrNull { it.cell !in tried } ?: break
            tried += next.cell
            if (!MapPlanner.hasRoom(store.freeBytes(), next.bytes)) continue
            val result = try {
                net.open(next.url).use { store.install(next, it) }
            } catch (e: IOException) {
                null
            }
            if (result == Install.OK) store.updateLedger { it.copy(installed = it.installed + (next.cell to next.sha256)) } else failed = true
        }
        return if (failed) SyncRun.RETRY else SyncRun.DONE
    }

    fun streets(downloading: Boolean): StreetMaps {
        val catalog = store.catalog()
        val present = store.present()
        val free = store.freeBytes()
        val noRoom = catalog?.blocks?.values?.filter { it.cell !in present && !MapPlanner.hasRoom(free, it.bytes) }?.map { it.cell }?.toSet()
        return StreetMaps(present.associateWith { store.file(it).absolutePath }, catalog?.blocks?.keys, noRoom.orEmpty(), downloading)
    }

    /** A file without a ledger entry (a crash after the rename) counts as unknown, so a listed block replaces it. */
    private fun installed(l: BlockLedger): Map<Cell, String> = store.present().associateWith { l.installed[it] ?: "" }
}
