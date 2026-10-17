package app.kinsight.core.maps

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OpenFreeMap spec §7: the phone writes each cell's tiles into a PMTiles v3 file MapLibre opens. */
class PmTilesTest {
    private val dir = createTempDirectory("pmtiles").toFile()
    private val cell = Cell(510, 389)

    private fun tile(t: TileKey) = PmTiles.gzip("tile ${t.z}/${t.x}/${t.y}".encodeToByteArray())

    private fun write(maxZoom: Int, target: File = File(dir, "block.pmtiles")): File {
        target.parentFile.mkdirs()
        PmTilesWriter(File(dir, "scratch")).use { w ->
            for (t in TileGrid.tiles(cell, maxZoom).shuffled(Random(7))) w.add(t.z, t.x, t.y, tile(t))
            w.finish(target, TileGrid.bounds(cell), """{"attribution":"test"}""")
        }
        return target
    }

    /** Vectors from the spec's reference code (go-pmtiles ZxyToID). */
    @Test
    fun tileIdsFollowTheSpec() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        assertEquals(19_078_479L, PmTiles.tileId(12, 3423, 1763))
        assertEquals(518_844L, PmTiles.tileId(10, 510, 389))
    }

    @Test
    fun everyTileReadsBackByItsCoordinates() {
        val r = SpecReader(write(14))
        assertEquals(341, r.tiles.size)
        for (t in TileGrid.tiles(cell, 14)) assertContentEquals(tile(t), r.tiles.getValue(Triple(t.z, t.x, t.y)))
    }

    @Test
    fun theHeaderDescribesTheBlock() {
        val r = SpecReader(write(13))
        val h = r.header
        assertEquals(10, h.minZoom)
        assertEquals(13, h.maxZoom)
        assertEquals(1, h.clustered)
        assertEquals(PmTiles.COMPRESSION_GZIP, h.internalCompression)
        assertEquals(PmTiles.COMPRESSION_GZIP, h.tileCompression)
        assertEquals(PmTiles.TILE_TYPE_MVT, h.tileType)
        assertEquals(85L, h.addressedTiles)
        assertEquals(85L, h.tileEntries)
        assertEquals(85L, h.tileContents)
        assertEquals(0L, h.leafLength)
        val b = TileGrid.bounds(cell)
        assertEquals((b.west * 1e7).roundToInt(), h.minLonE7)
        assertEquals((b.south * 1e7).roundToInt(), h.minLatE7)
        assertEquals((b.east * 1e7).roundToInt(), h.maxLonE7)
        assertEquals((b.north * 1e7).roundToInt(), h.maxLatE7)
        assertTrue(h.rootOffset + h.rootLength <= PmTiles.FIRST_READ_BYTES)
        assertEquals("""{"attribution":"test"}""", r.metadata)
    }

    /** Clustered: the data follows the directory's id order without gaps. */
    @Test
    fun theTileDataIsInTileIdOrder() {
        val e = SpecReader(write(14)).entries
        for (i in 1 until e.size) {
            assertTrue(e[i].id > e[i - 1].id)
            assertEquals(e[i - 1].offset + e[i - 1].length, e[i].offset)
        }
        assertEquals(0L, e.first().offset)
    }

    @Test
    fun aTileThatIsNotGzipIsRefused() {
        PmTilesWriter(File(dir, "s")).use { w -> assertFailsWith<IllegalArgumentException> { w.add(10, 510, 389, "plain".toByteArray()) } }
    }

    @Test
    fun aTileTwiceIsRefused() {
        PmTilesWriter(File(dir, "s")).use { w ->
            w.add(10, 510, 389, tile(TileKey(10, 510, 389)))
            assertFailsWith<IllegalArgumentException> { w.add(10, 510, 389, tile(TileKey(10, 510, 389))) }
        }
    }

    @Test
    fun closingDeletesTheScratch() {
        val s = File(dir, "s")
        PmTilesWriter(s).use { it.add(10, 510, 389, tile(TileKey(10, 510, 389))) }
        assertFalse(s.exists())
    }

    @Test
    fun theMaxZoomIsReadFromTheHeader() {
        assertEquals(13, PmTiles.maxZoomOf(write(13)))
        assertNull(PmTiles.maxZoomOf(File(dir, "junk").apply { writeText("not a block at all, but long enough to hold a header ".repeat(4)) }))
        assertNull(PmTiles.maxZoomOf(File(dir, "missing")))
    }

    /** A truncated or unreadable file is no block: null, never an exception. */
    @Test
    fun aBrokenFileHasNoMaxZoom() {
        val cut = write(13).let { f -> File(dir, "cut").apply { writeBytes(f.readBytes().copyOf(100)) } }
        assertNull(PmTiles.maxZoomOf(cut))
        val locked = File(dir, "locked").apply { writeText("not a block at all, but long enough to hold a header ".repeat(4)) }
        locked.setReadable(false)
        try {
            assertNull(PmTiles.maxZoomOf(locked))
        } finally {
            locked.setReadable(true)
        }
    }

    /** The output goes to a partial file renamed at the end: a failure leaves the old target and no partial. */
    @Test
    fun aFailedFinishLeavesTheTargetAsItWas() {
        val target = File(dir, "out.pmtiles").apply { writeText("old") }
        val w = PmTilesWriter(File(dir, "s"))
        w.add(10, 510, 389, tile(TileKey(10, 510, 389)))
        w.close() // the scratch is gone: finish fails after it has started writing
        assertFailsWith<IOException> { w.finish(target, TileGrid.bounds(cell), "{}") }
        assertEquals("old", target.readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun aFinishedFileLeavesNoPartial() {
        write(13, File(dir, "done.pmtiles"))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    /** Left in build/ for the go-pmtiles check of Step 5. */
    @Test
    fun aSampleIsLeftForGoPmtiles() {
        val out = write(14, File("build/pmtiles-sample/z10-510-389.pmtiles"))
        assertTrue(out.length() > PmTiles.HEADER_BYTES)
    }
}
