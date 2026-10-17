package app.kinsight.core.maps

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline map spec §3: a temp file, the catalog's sha256, an atomic rename; partials go. */
class BlockStoreTest {
    private val dir = createTempDirectory("maps").toFile()
    private val store = BlockStore(dir)
    private val cell = Cell(510, 389)
    private val bytes = "valencia".repeat(10_000).toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun block(b: ByteArray = bytes) =
        CatalogBlock(cell, b.size.toLong(), sha(b), "${MapLinks.DOWNLOAD_PREFIX}maps-2026-10/${cell.name}.pmtiles")

    private fun leftovers() = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }

    @Test
    fun aVerifiedBlockLandsUnderItsName() {
        assertEquals(Install.OK, store.install(block(), bytes.inputStream()))
        assertContentEquals(bytes, store.file(cell).readBytes())
        assertEquals(setOf(cell), store.present())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aWrongHashLeavesNothing() {
        assertEquals(Install.MISMATCH, store.install(block().copy(sha256 = sha("other".toByteArray())), bytes.inputStream()))
        assertFalse(store.file(cell).exists())
        assertTrue(leftovers().isEmpty())
    }

    /** Review Focus 4: a server that sends more than the catalog says is cut off, nothing kept. */
    @Test
    fun moreBytesThanTheCatalogSaysAreRefused() {
        assertEquals(Install.MISMATCH, store.install(block().copy(bytes = bytes.size - 1L), bytes.inputStream()))
        assertFalse(store.file(cell).exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aBrokenStreamDeletesThePartial() {
        val broken = object : InputStream() {
            var n = 0

            override fun read(): Int = if (n++ < 50_000) 1 else throw IOException("connection reset")
        }
        assertFailsWith<IOException> { store.install(block(), broken) }
        assertFalse(store.file(cell).exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aNewVersionReplacesTheOldFile() {
        store.install(block(), bytes.inputStream())
        val v2 = "version two".repeat(5_000).toByteArray()
        assertEquals(Install.OK, store.install(block(v2), v2.inputStream()))
        assertContentEquals(v2, store.file(cell).readBytes())
    }

    /** A crash between the write and the rename leaves a partial; the next run sweeps it before anything else. */
    @Test
    fun aLeftoverPartialIsSweptAway() {
        dir.mkdirs()
        File(dir, "z10-510-389.pmtiles.part").writeText("half a block")
        File(dir, "ledger.json.tmp").writeText("{")
        store.sweepPartials()
        assertTrue(leftovers().isEmpty())
        assertTrue(store.present().isEmpty())
    }

    @Test
    fun onlyBlockFilesArePresent() {
        dir.mkdirs()
        File(dir, BlockStore.CATALOG).writeText("{}")
        File(dir, BlockStore.LEDGER).writeText("{}")
        File(dir, "z10-1-2.pmtiles.part").writeText("")
        File(dir, "z10-1-2.pmtiles").writeText("")
        assertEquals(setOf(Cell(1, 2)), store.present())
    }

    @Test
    fun theLedgerRoundTrips() {
        val l = BlockLedger(mapOf(cell to 5L), mapOf(cell to "ab".repeat(32)), catalogFetchedMs = 7L)
        store.updateLedger { l }
        assertEquals(l, BlockStore(dir).ledger())
    }

    @Test
    fun aCorruptLedgerReadsEmpty() {
        dir.mkdirs()
        File(dir, BlockStore.LEDGER).writeText("{not json")
        assertEquals(BlockLedger(), store.ledger())
    }

    @Test
    fun theCatalogIsCachedAsText() {
        store.saveCatalog("""{"version":"2026-10","blocks":[]}""")
        assertEquals("2026-10", store.catalog()?.version)
    }

    @Test
    fun forgettingDeletesTheFolder() {
        store.install(block(), bytes.inputStream())
        store.forget()
        assertFalse(dir.exists())
    }
}
