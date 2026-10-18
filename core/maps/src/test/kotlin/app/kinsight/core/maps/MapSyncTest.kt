package app.kinsight.core.maps

import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OpenFreeMap spec §7 and the «Жизненный цикл» tests of §3, against a fake OpenFreeMap. */
class MapSyncTest {
    private class FakeNet : MapNet {
        @Volatile var tileJsonText: String? = tileJson(BUILD)
        @Volatile var failTileJson = false

        /** Every tile answers 503 (OpenFreeMap down). */
        @Volatile var down = false
        val tileJsonCalls = AtomicInteger()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val failing = ConcurrentHashMap<String, Int>()
        val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val tooBig: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** True: 204, no tile here. */
        @Volatile var empty: (String) -> Boolean = { false }

        /** Serve this many plain (not gzip) bytes for a URL, or null for a small gzip tile. */
        @Volatile var rawSize: (String) -> Int? = { null }
        private val inflight = AtomicInteger()
        val maxInflight = AtomicInteger()

        override fun tileJson(): String {
            tileJsonCalls.incrementAndGet()
            if (failTileJson) throw IOException("offline")
            return tileJsonText ?: throw IOException("HTTP 500")
        }

        override fun tile(url: String): Tile? {
            calls.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
            maxInflight.accumulateAndGet(inflight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(1)
                if (url in gone) throw SourceGone()
                if (down) throw IOException("HTTP 503")
                if (url in tooBig) throw TileTooBig()
                if (empty(url)) return null
                val left = failing[url]
                if (left != null && left > 0) {
                    failing[url] = left - 1
                    throw IOException("connection reset")
                }
                rawSize(url)?.let { return Tile(ByteArray(it), gzip = false) }
                return Tile(PmTiles.gzip(url.encodeToByteArray()), gzip = true)
            } finally {
                inflight.decrementAndGet()
            }
        }

        fun tiles() = calls.values.sumOf { it.get() }
    }

    private companion object {
        const val BUILD = "20261011_080001_pt"

        fun tileJson(build: String) = """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/$build/{z}/{x}/{y}.pbf"],"maxzoom":14}"""

        fun url(t: TileKey) = "https://tiles.openfreemap.org/planet/$BUILD/${t.z}/${t.x}/${t.y}.pbf"

        /** The z10 cell a tile address belongs to. */
        fun cellOf(url: String): Cell {
            val (z, x, y) = url.substringAfter("$BUILD/").removeSuffix(".pbf").split('/').map(String::toInt)
            return Cell(x shr (z - 10), y shr (z - 10))
        }
    }

    private val day = 24 * 3_600_000L
    private var now = 1_792_200_000_000L // 2026-10-17, six days after BUILD
    private val dir = createTempDirectory("maps").toFile()
    private var freeBytes = 10L shl 30
    private val store = BlockStore(dir) { freeBytes }
    private val net = FakeNet()
    private val pauses: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val sync = MapSync(store, net, pause = { pauses += it }) { now }
    private val valencia = Cell(510, 389)
    private val around = TileGrid.neighbourhood(valencia)

    private fun point(t: Long = now) = MapPoint(395_000_000, -5_000_000, t)

    private fun leftovers() = dir.listFiles().orEmpty().filter { it.name.endsWith(BlockStore.PART) }

    private fun install() {
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
    }

    /** Spec §7: one point brings its neighbourhood in one download, the centre to z14 and eight neighbours to z13. */
    @Test
    fun aPointBringsItsNeighbourhoodInOneDownload() {
        install()
        assertEquals(around, store.present())
        assertEquals(1, net.tileJsonCalls.get())
        assertEquals(1_021, net.tiles())
        assertEquals(1_021, net.calls.size, "no tile twice")
        assertEquals("$BUILD/14", store.ledger().installed[valencia])
        assertEquals("$BUILD/13", store.ledger().installed[Cell(511, 390)])
        assertEquals(14, PmTiles.maxZoomOf(store.file(valencia)))
        assertEquals(13, PmTiles.maxZoomOf(store.file(Cell(509, 388))))
        assertTrue(leftovers().isEmpty())
        now += 3_600_000L
        assertFalse(sync.check(listOf(point()), listOf(point())), "the neighbourhood is complete")
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(1, net.tileJsonCalls.get())
        assertEquals(1_021, net.tiles())
    }

    /** Spec §7, OpenFreeMap terms: never more than four requests at once. */
    @Test
    fun atMostFourTilesAtOnce() {
        install()
        assertTrue(net.maxInflight.get() in 2..4, "${net.maxInflight.get()} at once")
    }

    /** Review Focus 5: a tile sent without gzip goes into the archive gzipped. */
    @Test
    fun aPlainTileIsStoredGzipped() {
        net.rawSize = { 100 }
        install()
        val tiles = SpecReader(store.file(valencia)).tiles
        assertEquals(341, tiles.size)
        assertTrue(tiles.values.all { it[0] == 0x1f.toByte() && it[1] == 0x8b.toByte() })
    }

    @Test
    fun withoutPointsNothingGoesOnline() {
        assertFalse(sync.check(emptyList(), emptyList()))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(0, net.tileJsonCalls.get())
        assertEquals(0, net.tiles())
    }

    @Test
    fun aBlockWithoutPointsForAWeekIsDeleted() {
        install()
        now += 7 * day
        assertFalse(sync.check(emptyList(), emptyList()))
        assertFalse(store.file(valencia).exists())
        assertFalse(valencia in store.ledger().installed)
    }

    @Test
    fun sixDaysWithoutPointsKeepTheBlock() {
        install()
        now += 6 * day
        sync.check(emptyList(), emptyList())
        assertTrue(store.file(valencia).exists())
    }

    @Test
    fun theNeighbourhoodWhereTheChildIsNowStays() {
        install()
        val there = point()
        now += 8 * day
        sync.check(emptyList(), listOf(there))
        assertEquals(around, store.present())
    }

    /** Review Focus 3: walking into the east neighbour brings it again at z14 and the new edge at z13, nothing else. */
    @Test
    fun aNeighbourBecomesACentreAndComesAgainDeeper() {
        install()
        now += 60_000
        val east = MapPoint(395_000_000, -2_000_000, now)
        assertTrue(sync.check(listOf(east), listOf(east)))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(1_021 + 341 + 3 * 85, net.tiles())
        assertEquals("$BUILD/14", store.ledger().installed[Cell(511, 389)])
        assertEquals("$BUILD/14", store.ledger().installed[valencia], "the old centre keeps its depth")
        assertEquals(around + TileGrid.neighbourhood(Cell(511, 389)), store.present())
    }

    @Test
    fun theTileJsonIsAskedAtMostOnceADay() {
        install()
        now += day - 1
        val madrid = MapPoint(404_168_000, -37_038_000, now)
        assertTrue(sync.check(listOf(madrid), listOf(madrid)))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(1, net.tileJsonCalls.get(), "the address of a day ago still serves")
        now += 1
        val sydney = MapPoint(-338_688_000, 1_512_093_000, now)
        assertTrue(sync.check(listOf(sydney), listOf(sydney)))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2, net.tileJsonCalls.get())
    }

    @Test
    fun aNewBuildRenewsBlocksNinetyDaysOld() {
        install()
        now += 90 * day
        net.tileJsonText = tileJson("20270110_080001_pt")
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2, net.tileJsonCalls.get())
        assertEquals(2 * 1_021, net.tiles())
        assertEquals("20270110_080001_pt/14", store.ledger().installed[valencia])
    }

    @Test
    fun withoutANewerBuildAnOldBlockStays() {
        install()
        now += 90 * day
        assertTrue(sync.check(listOf(point()), listOf(point())), "the day-old TileJSON may name a newer build")
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2, net.tileJsonCalls.get())
        assertEquals(1_021, net.tiles(), "the same build: nothing fetched again")
        assertFalse(sync.check(listOf(point()), listOf(point())))
    }

    @Test
    fun withLittleRoomNothingStarts() {
        freeBytes = MapPlanner.MAX_BYTES + MapPlanner.SPARE_BYTES - 1
        assertFalse(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(0, net.tileJsonCalls.get())
        assertEquals(around, sync.streets(downloading = false).skipped)
        freeBytes = 1L shl 30
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertTrue(sync.streets(downloading = false).skipped.isEmpty())
    }

    /** Review Focus 5: a tile that fails three times drops its cell; the others come, the run asks for a retry. */
    @Test
    fun aTileThatKeepsFailingIsTriedThreeTimesAndTheRunRetries() {
        val first = url(TileKey(10, 510, 389))
        net.failing[first] = Int.MAX_VALUE
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(3, net.calls.getValue(first).get())
        assertEquals(listOf(1_000L, 2_000L), pauses.toList())
        assertFalse(store.file(valencia).exists())
        assertTrue(store.present().isEmpty(), "the run ends at the failed cell")
        assertEquals(CellFailure(1, now), store.ledger().failed[valencia])
        assertTrue(leftovers().isEmpty())
        assertEquals(SyncRun.RETRY, sync.sync(), "the failed cell still waits")
        assertEquals(3, net.calls.getValue(first).get(), "not asked again within its backoff")
        assertEquals(around - valencia, store.present(), "the other blocks come")
        now += MapPlanner.failureWait(1)
        net.failing.clear()
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(around, store.present())
        assertTrue(store.ledger().failed.isEmpty(), "a success clears the backoff")
    }

    /** Fix round 1: with OpenFreeMap down, one run tries one cell; the next waits out its backoff, which grows. */
    @Test
    fun aDownServerCostsOneCellPerRunAndBacksOff() {
        net.down = true
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(setOf(valencia), net.calls.keys.map(::cellOf).toSet(), "one cell attempted")
        assertTrue(net.tiles() <= 3 * 4 + 3, "${net.tiles()} requests: the failing chunk stops the others")
        assertEquals(1, store.ledger().failed.getValue(valencia).count)
        // Every cell fails once, one per run; then nothing is asked until the first backoff ends.
        repeat(8) { assertEquals(SyncRun.RETRY, sync.sync()) }
        assertEquals(around, net.calls.keys.map(::cellOf).toSet())
        val asked = net.tiles()
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(asked, net.tiles(), "every cell waits")
        now += MapPlanner.failureWait(1)
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(2, store.ledger().failed.getValue(valencia).count)
        assertEquals(10 * 60_000L, MapPlanner.failureWait(2))
        now += MapPlanner.failureWait(1)
        val before = net.calls.getValue(url(TileKey(10, 510, 389))).get()
        repeat(8) { sync.sync() }
        assertEquals(before, net.calls.getValue(url(TileKey(10, 510, 389))).get(), "the centre waits ten minutes now")
        net.down = false
        now += MapPlanner.failureWait(2)
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(around, store.present())
        assertTrue(store.ledger().failed.isEmpty())
    }

    /** Fix round 1: a tile over the size cap fails its cell at once, without three tries. */
    @Test
    fun aTileOverTheSizeCapFailsItsCellAtOnce() {
        val first = url(TileKey(10, 510, 389))
        net.tooBig += first
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(1, net.calls.getValue(first).get())
        assertEquals(1, store.ledger().failed.getValue(valencia).count)
    }

    /** Fix round 1: the 50 MB cap is counted per tile; at most the other in-flight requests pass the crossing tile. */
    @Test
    fun theFiftyMegabyteCapStopsAtTheCrossingTile() {
        net.rawSize = { 1_000_000 }
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
        assertTrue(net.tiles() in 51..54, "${net.tiles()} tiles of 1 MB")
        assertTrue(valencia in store.ledger().capped)
        assertTrue(store.present().isEmpty())
    }

    /** Fix round 1: retries count too; one run never sends more than 3 000 tile requests. */
    @Test
    fun retriesNeverTakeARunPastThreeThousandRequests() {
        TileGrid.tiles(valencia, 14).take(12).forEach { net.failing[url(it)] = 2 }
        val places = listOf(point(), MapPoint(404_168_000, -37_038_000, now), MapPoint(-338_688_000, 1_512_093_000, now))
        assertTrue(sync.check(places, places))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(MapPlanner.MAX_TILES, net.tiles())
        assertTrue(leftovers().isEmpty())
        assertTrue(store.ledger().failed.isEmpty(), "the limit is no cell's failure")
        assertTrue(sync.check(places, places))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(27, store.present().size)
    }

    /** Review Minor 2: a refused TileJSON is not asked for again within the day. */
    @Test
    fun aRefusedTileJsonWaitsADay() {
        net.tileJsonText = """{"tiles":["https://evil.example/planet/$BUILD/{z}/{x}/{y}.pbf"],"maxzoom":14}"""
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(1, net.tileJsonCalls.get())
        net.tileJsonText = tileJson(BUILD)
        now += MapPlanner.SOURCE_EVERY_MS
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2, net.tileJsonCalls.get())
    }

    /** Review Minor 1: a cell with no tile at all (every answer 204) waits a week like a capped one; the run goes on. */
    @Test
    fun aCellWithoutTilesWaitsAWeek() {
        net.empty = { cellOf(it) == valencia }
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(around - valencia, store.present())
        assertTrue(valencia in store.ledger().capped)
    }

    @Test
    fun aFlakyTileIsTriedAgain() {
        val first = url(TileKey(10, 510, 389))
        net.failing[first] = 2
        install()
        assertEquals(3, net.calls.getValue(first).get())
        assertEquals(around, store.present())
    }

    /** Review Focus 2: a removed build (404) ends the run; the next one asks for a fresh TileJSON. */
    @Test
    fun aGoneBuildAsksForANewTileJson() {
        net.gone += url(TileKey(10, 510, 389))
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertNull(store.ledger().sourceFetchedMs)
        assertTrue(store.present().isEmpty())
        net.gone.clear()
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2, net.tileJsonCalls.get())
        assertEquals(around, store.present())
    }

    @Test
    fun anUnreachableTileJsonIsRetried() {
        net.failTileJson = true
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertNull(store.ledger().source)
        assertEquals(0, net.tiles())
    }

    /** Review Focus 2: a TileJSON naming another host is never followed. */
    @Test
    fun aTileJsonPointingElsewhereIsRefused() {
        net.tileJsonText = """{"tiles":["https://evil.example/planet/$BUILD/{z}/{x}/{y}.pbf"],"maxzoom":14}"""
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertEquals(0, net.tiles())
        assertNull(store.ledger().source)
    }

    /** Review Focus 1: a centre over 50 MB ends the download; the cell waits a week, its neighbours still come. */
    @Test
    fun overFiftyMegabytesEndsTheDownloadAndTheCellWaitsAWeek() {
        net.rawSize = { if ("/14/" in it) 200_000 else null }
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
        assertTrue(store.present().isEmpty(), "the download ended at the cap")
        assertTrue(valencia in store.ledger().capped)
        assertTrue(leftovers().isEmpty())
        assertTrue(sync.check(listOf(point()), listOf(point())), "the neighbours are still wanted")
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(around - valencia, store.present())
        assertEquals(setOf(valencia), sync.streets(downloading = false).skipped)
        assertFalse(sync.check(listOf(point()), listOf(point())), "no loop over the capped cell")
        now += 7 * day
        assertTrue(sync.check(listOf(point()), listOf(point())), "a week later it is tried again")
    }

    /** Review Focus 4: three far places are more than 3 000 tiles; one run takes what fits, the next the rest. */
    @Test
    fun oneRunStaysWithinThreeThousandTiles() {
        val places = listOf(point(), MapPoint(404_168_000, -37_038_000, now), MapPoint(-338_688_000, 1_512_093_000, now))
        assertTrue(sync.check(places, places))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(2_978, net.tiles())
        assertTrue(sync.check(places, places))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(3 * 1_021, net.tiles())
        assertEquals(27, store.present().size)
    }

    @Test
    fun aRunThatMayNotProceedAsksForNothing() {
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync(proceed = { false }))
        assertEquals(0, net.tileJsonCalls.get())
        var asked = 0
        assertEquals(SyncRun.DONE, sync.sync(proceed = { asked++ == 0 }), "the TileJSON may go, the tiles may not")
        assertEquals(1, net.tileJsonCalls.get())
        assertEquals(0, net.tiles())
        assertTrue(store.present().isEmpty())
    }

    /** A leave in the middle of a cell stops the requests; the partial is deleted and no block appears. */
    @Test
    fun anAbortedDownloadLeavesNoBlock() {
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync(aborted = { net.tiles() > 40 }))
        assertTrue(net.tiles() < 341)
        assertTrue(store.present().isEmpty())
        assertTrue(leftovers().isEmpty())
        assertTrue(store.ledger().installed.isEmpty())
    }

    @Test
    fun aPointFromAheadOfTheClockStillAgesOut() {
        install()
        sync.check(listOf(point(now + 30 * day)), emptyList())
        now += 7 * day
        sync.check(emptyList(), emptyList())
        assertFalse(store.file(valencia).exists())
    }

    @Test
    fun theScreenSeesTheBlocks() {
        install()
        val s = sync.streets(downloading = true)
        assertEquals(around.associateWith { store.file(it).absolutePath }, s.blocks)
        assertTrue(s.skipped.isEmpty())
        assertTrue(s.downloading)
    }
}
