package app.kinsight.core.maps

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline map spec §3, §7: a partial file and a scratch, an atomic rename; leftovers go. */
class BlockStoreTest {
    private val dir = createTempDirectory("maps").toFile()
    private val store = BlockStore(dir)
    private val cell = Cell(510, 389)
    private val bytes = "valencia".repeat(10_000).toByteArray()

    private fun leftovers() = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }

    @Test
    fun aWrittenBlockLandsUnderItsName() {
        store.install(cell) { part, scratch ->
            scratch.writeText("tiles")
            part.writeBytes(bytes)
        }
        assertContentEquals(bytes, store.file(cell).readBytes())
        assertEquals(setOf(cell), store.present())
        assertTrue(leftovers().isEmpty())
    }

    /** Review Focus 5: a write that fails half way leaves no partial, no scratch and no block. */
    @Test
    fun aFailedWriteLeavesNothing() {
        assertFailsWith<IOException> {
            store.install(cell) { part, scratch ->
                scratch.writeText("tiles")
                part.writeBytes(bytes)
                throw IOException("connection reset")
            }
        }
        assertFalse(store.file(cell).exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aNewBlockReplacesTheOldFile() {
        store.install(cell) { part, _ -> part.writeBytes(bytes) }
        val v2 = "version two".repeat(5_000).toByteArray()
        store.install(cell) { part, _ -> part.writeBytes(v2) }
        assertContentEquals(v2, store.file(cell).readBytes())
    }

    @Test
    fun freeBytesDoesNotCreateTheFolder() {
        val gone = File(dir, "gone")
        BlockStore(gone).freeBytes()
        assertFalse(gone.exists())
    }

    /** A crash mid-write leaves partials; the next run sweeps them, and the catalog of the GitHub days, first. */
    @Test
    fun leftoversAndTheOldCatalogAreSweptAway() {
        dir.mkdirs()
        File(dir, "z10-510-389.pmtiles.part").writeText("half a block")
        File(dir, "z10-510-389.tiles.part").writeText("tiles")
        File(dir, "ledger.json.tmp").writeText("{")
        File(dir, "catalog.json").writeText("{}")
        store.sweepPartials()
        assertTrue(leftovers().isEmpty())
        assertFalse(File(dir, "catalog.json").exists())
        assertTrue(store.present().isEmpty())
    }

    @Test
    fun onlyBlockFilesArePresent() {
        dir.mkdirs()
        File(dir, BlockStore.LEDGER).writeText("{}")
        File(dir, "z10-1-2.pmtiles.part").writeText("")
        File(dir, "z10-1-2.pmtiles").writeText("")
        assertEquals(setOf(Cell(1, 2)), store.present())
    }

    @Test
    fun theLedgerRoundTrips() {
        val l = BlockLedger(
            usage = mapOf(cell to 5L),
            installed = mapOf(cell to "20261011_080001_pt/14"),
            centres = mapOf(cell to 6L),
            capped = mapOf(Cell(511, 389) to 7L),
            source = "https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf",
            sourceFetchedMs = 8L,
            failed = mapOf(Cell(509, 389) to CellFailure(2, 9L)),
        )
        store.updateLedger { l }
        assertEquals(l, BlockStore(dir).ledger())
    }

    /** A phone of the GitHub days keeps its marks; its sha256 entries read as unknown versions later. */
    @Test
    fun aLedgerOfTheGitHubDaysStillReads() {
        dir.mkdirs()
        File(dir, BlockStore.LEDGER).writeText("""{"usage":{"510:389":5},"installed":{"510:389":"${"ab".repeat(32)}"},"catalogFetchedMs":7}""")
        assertEquals(BlockLedger(usage = mapOf(cell to 5L), installed = mapOf(cell to "ab".repeat(32))), store.ledger())
    }

    @Test
    fun aCorruptLedgerReadsEmpty() {
        dir.mkdirs()
        File(dir, BlockStore.LEDGER).writeText("{not json")
        assertEquals(BlockLedger(), store.ledger())
    }

    @Test
    fun forgettingDeletesTheFolder() {
        store.install(cell) { part, _ -> part.writeBytes(bytes) }
        store.forget()
        assertFalse(dir.exists())
    }
}
