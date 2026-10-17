package app.kinsight.debug

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.PmTiles
import app.kinsight.core.maps.PmTilesWriter
import app.kinsight.core.maps.TileGrid
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MAP_INSTALL_LOCAL and MAP_STATUS: a PMTiles block from elsewhere through the store's own install path, and what is there. */
class DebugMapsTest {
    private val now = 1_760_000_000_000L
    private val root = createTempDirectory("debugmaps").toFile()
    private val store = BlockStore(File(root, "maps")) { 5_000_000L }
    private val valencia = Cell(510, 389)

    /** A real z10..z13 block (85 tiles), as a phone that downloaded it has it. */
    private fun pushed(): File {
        val f = File(root, "maps-in/z10-510-389.pmtiles")
        f.parentFile.mkdirs()
        PmTilesWriter(File(root, "scratch")).use { w ->
            for (t in TileGrid.tiles(valencia, 13)) w.add(t.z, t.x, t.y, PmTiles.gzip("${t.z}".encodeToByteArray()))
            w.finish(f, TileGrid.bounds(valencia), "{}")
        }
        return f
    }

    private fun junk(b: ByteArray) = File(root, "maps-in/junk.pmtiles").apply {
        parentFile.mkdirs()
        writeBytes(b)
    }

    @Test
    fun aLocalBlockBecomesTheCellsBlockAndCountsAsUsedNow() {
        val f = pushed()
        val bytes = f.readBytes()
        assertEquals("installed 510 389 ${bytes.size}", DebugMaps.installLocal(store, 510, 389, f.path, now))
        assertContentEquals(bytes, store.file(valencia).readBytes())
        assertEquals("local/13", store.ledger().installed[valencia])
        assertEquals(now, store.ledger().usage[valencia])
        assertTrue(store.dir.listFiles().orEmpty().none { it.name.endsWith(BlockStore.PART) }, "the partial is gone")
        assertTrue(f.isFile, "the pushed file is left where it was")
    }

    @Test
    fun aNewerMarkIsKept() {
        store.updateLedger { it.copy(usage = mapOf(valencia to now + 5)) }
        DebugMaps.installLocal(store, 510, 389, pushed().path, now)
        assertEquals(now + 5, store.ledger().usage[valencia])
    }

    @Test
    fun badExtrasAreRefusedAndInstallNothing() {
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, null, now) }
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, File(root, "missing").path, now) }
        assertFailsWith<IllegalArgumentException> { DebugMaps.installLocal(store, 1024, 389, pushed().path, now) }
        assertFailsWith<IllegalArgumentException> { DebugMaps.installLocal(store, 510, -1, pushed().path, now) }
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, junk(ByteArray(0)).path, now) }
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, junk("not a block ".repeat(40).toByteArray()).path, now) }
        assertFalse(store.file(valencia).exists())
    }

    @Test
    fun theStatusListsCellsMarksSourceAndFreeBytes() {
        assertEquals("present=none used=none source=none free=5000000", DebugMaps.status(store))
        DebugMaps.installLocal(store, 510, 389, pushed().path, now)
        store.updateLedger {
            it.copy(usage = it.usage + (Cell(511, 389) to now - 7), source = "https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf")
        }
        assertEquals("present=510:389 used=510:389@$now,511:389@${now - 7} source=20261011_080001_pt free=5000000", DebugMaps.status(store))
    }
}
