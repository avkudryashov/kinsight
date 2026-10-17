package app.kinsight.core.data

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.MapSync
import app.kinsight.core.maps.StreetMaps
import app.kinsight.core.maps.SyncRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The street map blocks of this phone (offline map spec §3). The children's points mark their cells; a check deletes
 * what a week went without; the network run fetches what is missing. Only a parent's phone sees children
 * (LocationRepository.children), so a child's phone never marks a cell; and while the family's cloud is off nothing is
 * marked or fetched (the points come through it, and the privacy policy says the app stays offline then).
 */
class MapBlocks(
    private val sync: MapSync,
    private val store: BlockStore,
    private val children: Flow<List<ChildLocation>>,
    private val cloudOn: suspend () -> Boolean,
    downloading: Flow<Boolean>,
) {
    private val changed = MutableStateFlow(0L)

    /** The Карта tab's view of the blocks: again after every check, run or forget, and when a download starts or ends. */
    val streets: Flow<StreetMaps> = combine(changed, downloading) { _, d -> d }.map { sync.streets(it) }.flowOn(Dispatchers.IO)

    /** True when a network run is needed (MapWork.syncSoon). Eviction runs whether or not the cloud is on. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        val on = cloudOn()
        val kids = if (on) withTimeoutOrNull(READ_MS) { children.first() }.orEmpty() else emptyList()
        val (points, last) = points(kids)
        val needed = sync.check(points, last)
        changed.update { it + 1 }
        on && needed
    }

    suspend fun run(): SyncRun {
        val run = withContext(Dispatchers.IO) { if (cloudOn()) sync.sync() else SyncRun.DONE }
        changed.update { it + 1 }
        return run
    }

    fun forget() {
        store.forget()
        changed.update { it + 1 }
    }

    companion object {
        const val DIR = "maps"

        /** How long a check waits for the snapshot (a locked group may never give one). */
        const val READ_MS = 30_000L

        /** Every point on this phone (today's routes and the last points) and each child's newest one. */
        fun points(children: List<ChildLocation>): Pair<List<MapPoint>, List<MapPoint>> {
            fun p(g: GeoPoint) = MapPoint(g.latE7, g.lonE7, g.timeMs)
            val all = children.flatMap { c -> c.route.map(::p) + listOfNotNull(c.last?.let(::p)) }
            return all to children.mapNotNull { c -> c.last?.let(::p) }
        }
    }
}
