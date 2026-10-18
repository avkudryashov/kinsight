package app.kinsight.core.data

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.MapNet
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.MapSync
import app.kinsight.core.maps.PmTiles
import app.kinsight.core.maps.SyncRun
import app.kinsight.core.maps.Tile
import app.kinsight.core.maps.TileGrid
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline map spec §3: the children's points drive the blocks, only while the family's cloud is on. */
class MapBlocksTest {
    private class CountingNet : MapNet {
        var calls = 0

        override fun tileJson(): String {
            calls++
            throw IOException("offline")
        }

        override fun tile(url: String): Tile? {
            calls++
            throw IOException("offline")
        }
    }

    private val now = 1_760_000_000_000L
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val a = GeoPoint(395_000_000, -5_000_000, 20, now - 60_000)
    private val b = GeoPoint(395_100_000, -5_100_000, 15, now)
    private val dir = createTempDirectory("maps").toFile()
    private val store = BlockStore(dir)
    private val net = CountingNet()

    private fun child(route: List<GeoPoint>, last: GeoPoint?) = ChildLocation(kid, null, last, route, emptyList(), emptyList())

    private fun blocks(on: Boolean) = MapBlocks(
        MapSync(store, net) { now }, store, flowOf(listOf(child(listOf(a, b), b))),
        cloudOn = { on }, downloading = flowOf(false),
    )

    private fun mp(g: GeoPoint) = MapPoint(g.latE7, g.lonE7, g.timeMs)

    @Test
    fun theRouteAndTheLastPointMarkTheMap() {
        val (points, last) = MapBlocks.points(listOf(child(listOf(a, b), b), child(emptyList(), null)))
        assertEquals(listOf(mp(a), mp(b), mp(b)), points)
        assertEquals(listOf(mp(b)), last)
    }

    /** Review Focus 2: with the cloud off no cell is marked and nothing is asked for. */
    @Test
    fun withTheCloudOffNothingIsMarkedOrFetched() = runTest {
        val maps = blocks(on = false)
        assertFalse(maps.check())
        assertEquals(SyncRun.DONE, maps.run())
        assertTrue(store.ledger().usage.isEmpty())
        assertEquals(0, net.calls)
    }

    @Test
    fun withTheCloudOnAPointAsksForTheNetwork() = runTest {
        val maps = blocks(on = true)
        assertTrue(maps.check())
        assertTrue(store.ledger().usage.isNotEmpty())
        assertEquals(SyncRun.RETRY, maps.run(), "the fake OpenFreeMap is offline")
        assertEquals(1, net.calls)
    }

    /** Review Focus 2: leaving the family takes the blocks (they name the cells the children were in). */
    @Test
    fun forgettingDeletesEveryBlock() = runTest {
        dir.mkdirs()
        File(dir, "z10-510-389.pmtiles").writeText("block")
        blocks(on = true).forget()
        assertFalse(dir.exists())
        assertFalse(blocks(on = false).check())
        assertFalse(dir.exists(), "a check after leaving writes no ledger")
    }

    /** A fake OpenFreeMap that holds the TileJSON, or the first tile, until [release]. */
    private class GateNet(private val holdTileJson: Boolean) : MapNet {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        val tiles = AtomicInteger()

        private fun hold() {
            held.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }

        override fun tileJson(): String {
            if (holdTileJson) hold()
            return """{"tiles":["https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf"],"maxzoom":14}"""
        }

        override fun tile(url: String): Tile? {
            tiles.incrementAndGet()
            if (!holdTileJson && first.getAndSet(false)) hold()
            return Tile(PmTiles.gzip(url.encodeToByteArray()), gzip = true)
        }
    }

    private val cell = TileGrid.cellOf(a.latE7, a.lonE7)

    /** Counted down each time a check reads the children (outside the lock). */
    private val childrenRead = CountDownLatch(2)

    private val family: Flow<List<ChildLocation>> = flow {
        childrenRead.countDown()
        emit(listOf(child(listOf(a, b), b)))
    }

    private fun gated(holdTileJson: Boolean): Pair<MapBlocks, GateNet> {
        val gate = GateNet(holdTileJson)
        return MapBlocks(MapSync(store, gate) { now }, store, family, cloudOn = { true }, downloading = flowOf(false)) to gate
    }

    /**
     * Review Important 1: a check that read the children before a leave writes nothing after it. The check is held
     * before the lock, the leave runs, the check goes on: no folder, no ledger, no download asked for.
     */
    @Test
    fun aCheckCaughtByALeaveWritesNothing() = runBlocking {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val on = AtomicBoolean(true)
        val maps = MapBlocks(
            MapSync(store, net) { now }, store,
            flow {
                held.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                emit(listOf(child(listOf(a, b), b)))
            },
            cloudOn = { on.get() }, downloading = flowOf(false),
        )
        val checking = async(Dispatchers.IO) { maps.check() }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        on.set(false) // the group file is gone before MapBlocks.forget (AppGraph reset order)
        maps.forget()
        release.countDown()
        assertFalse(checking.await(), "no download is asked for")
        assertFalse(dir.exists(), "no ledger naming the children's cells")
        assertEquals(0, net.calls)
    }

    /** A check that comes while a download runs waits for it: the ledger, the sweep and the eviction never overlap. */
    @Test
    fun aCheckWaitsForTheRunningDownload() = runBlocking {
        val (maps, gate) = gated(holdTileJson = false)
        assertTrue(maps.check())
        val run = async(Dispatchers.IO) { maps.run() }
        assertTrue(gate.held.await(10, TimeUnit.SECONDS))
        val check = async(Dispatchers.IO) { maps.check() }
        assertTrue(childrenRead.await(10, TimeUnit.SECONDS), "the second check is past the children, at the lock")
        delay(300)
        assertFalse(check.isCompleted, "the check waits for the download")
        gate.release.countDown()
        assertEquals(SyncRun.DONE, run.await())
        assertFalse(check.await(), "the block is here now")
        assertTrue(store.file(cell).isFile)
        assertEquals("20261011_080001_pt/14", store.ledger().installed[cell])
        assertTrue(cell in store.ledger().usage)
    }

    /**
     * Fix round 1: WorkManager cancels the worker when the phone starts roaming. The blocking download must notice: no
     * tile is asked for after the cancel beyond the requests already in flight, and the partial cell is deleted.
     */
    @Test
    fun aCancelledRunAsksForNoMoreTiles() = runBlocking {
        val (maps, gate) = gated(holdTileJson = false)
        assertTrue(maps.check())
        val run = async(Dispatchers.IO) { maps.run() }
        assertTrue(gate.held.await(10, TimeUnit.SECONDS))
        run.cancel()
        val atCancel = gate.tiles.get()
        gate.release.countDown()
        run.join()
        assertTrue(gate.tiles.get() <= atCancel + 3, "${gate.tiles.get() - atCancel} tiles after the cancel")
        assertFalse(store.file(cell).exists())
        assertTrue(store.dir.listFiles().orEmpty().none { it.name.endsWith(BlockStore.PART) })
        assertTrue(store.ledger().installed.isEmpty())
    }

    /** Review Focus 2: leaving while a download runs leaves no block, no TileJSON cache and no ledger. */
    @Test
    fun forgettingDuringADownloadLeavesNothing() = runBlocking {
        val (maps, gate) = gated(holdTileJson = true)
        assertTrue(maps.check())
        val run = async(Dispatchers.IO) { maps.run() }
        assertTrue(gate.held.await(10, TimeUnit.SECONDS))
        val forget = async(Dispatchers.IO) { maps.forget() }
        delay(300)
        gate.release.countDown()
        run.await()
        forget.await()
        assertFalse(dir.exists())
    }
}
