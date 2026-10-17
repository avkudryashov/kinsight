package app.kinsight.core.data

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.MapNet
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.MapSync
import app.kinsight.core.maps.SyncRun
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline map spec §3: the children's points drive the blocks, only while the family's cloud is on. */
class MapBlocksTest {
    private class CountingNet : MapNet {
        var calls = 0

        override fun catalog(): String {
            calls++
            throw IOException("offline")
        }

        override fun open(url: String): InputStream {
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
        assertEquals(SyncRun.RETRY, maps.run(), "the fake GitHub is offline")
        assertEquals(1, net.calls)
    }

    /** Review Focus 2: leaving the family takes the blocks (they name the cells the children were in). */
    @Test
    fun forgettingDeletesEveryBlock() {
        dir.mkdirs()
        File(dir, "z10-510-389.pmtiles").writeText("block")
        blocks(on = true).forget()
        assertFalse(dir.exists())
    }
}
