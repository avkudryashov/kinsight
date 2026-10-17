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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * The street map blocks of this phone (offline map spec §3). The children's points mark their cells; a check deletes
 * what a week went without; the network run fetches what is missing. Only a parent's phone sees children
 * (LocationRepository.children), so a child's phone never marks a cell; and while the family's cloud is off nothing is
 * marked or fetched (the points come through it, and the privacy policy says the app stays offline then).
 *
 * [cloudOn] is «the cloud is on and this phone is a parent» (CloudRunner.isOnAsParent). [check], [run] and [forget]
 * take one [lock], so the ledger writes, the run's sweep of partial files and the eviction never overlap. A leave bumps
 * [epoch] before it waits for the lock: a running download stops at its next read, and the run asks nothing more.
 */
class MapBlocks(
    private val sync: MapSync,
    private val store: BlockStore,
    private val children: Flow<List<ChildLocation>>,
    private val cloudOn: suspend () -> Boolean,
    downloading: Flow<Boolean>,
) {
    private val changed = MutableStateFlow(0L)
    private val lock = Mutex()
    private val epoch = AtomicLong()

    /** The Карта tab's view of the blocks: again after every check, run or forget, and when a download starts or ends. */
    val streets: Flow<StreetMaps> = combine(changed, downloading) { _, d -> d }.map { sync.streets(it) }.flowOn(Dispatchers.IO)

    /** True when a network run is needed (MapWork.syncSoon). Eviction runs whether or not the cloud is on. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        val on = cloudOn()
        val kids = if (on) withTimeoutOrNull(READ_MS) { children.first() }.orEmpty() else emptyList()
        val (points, last) = points(kids)
        val needed = lock.withLock {
            // Nothing to evict and nothing to mark: no ledger is written (after a leave the folder stays gone).
            if (!on && !store.dir.exists()) false else sync.check(points, last)
        }
        changed.update { it + 1 }
        on && needed
    }

    /** Asks [cloudOn] again before every request: the cloud may go off, or the phone leave, while blocks download. */
    suspend fun run(): SyncRun {
        val run = withContext(Dispatchers.IO) {
            lock.withLock {
                val start = epoch.get()
                val current = { epoch.get() == start }
                if (current() && cloudOn()) {
                    sync.sync(proceed = { current() && runBlocking { cloudOn() } }, aborted = { !current() })
                } else {
                    SyncRun.DONE
                }
            }
        }
        changed.update { it + 1 }
        return run
    }

    /** Leaving the family: a running download is stopped first, then the folder goes; no run of before writes again. */
    suspend fun forget() {
        epoch.incrementAndGet()
        withContext(Dispatchers.IO) { lock.withLock { store.forget() } }
        changed.update { it + 1 }
    }

    /** Whether a check is worth queueing: a parent's phone with the cloud on (MapWork.checkSoonIfParent). */
    suspend fun wanted(): Boolean = cloudOn()

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
