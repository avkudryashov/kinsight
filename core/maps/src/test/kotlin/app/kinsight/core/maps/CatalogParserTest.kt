package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Offline map spec §2: `{ "version", "blocks": [ { "x", "y", "bytes", "sha256", "url" } ] }`. */
class CatalogParserTest {
    private val sha = "ab".repeat(32)

    private fun url(name: String, release: String = "maps-2026-10") = "${MapLinks.DOWNLOAD_PREFIX}$release/$name.pmtiles"

    private fun block(x: Int, y: Int, bytes: Long = 1_000, sha256: String = sha, url: String = url("z10-$x-$y")) =
        """{"x":$x,"y":$y,"bytes":$bytes,"sha256":"$sha256","url":"$url"}"""

    @Test
    fun aCatalogListsItsBlocksByCell() {
        val c = CatalogParser.parse("""{"version":"2026-10","blocks":[${block(510, 389)},${block(511, 389)}]}""")!!
        assertEquals("2026-10", c.version)
        assertEquals(setOf(Cell(510, 389), Cell(511, 389)), c.blocks.keys)
        assertEquals(CatalogBlock(Cell(510, 389), 1_000, sha, url("z10-510-389")), c.blocks[Cell(510, 389)])
    }

    /** Review Focus 3: a block that would fetch from elsewhere, or is not what its cell says, is never used. */
    @Test
    fun aBlockThatBreaksARuleIsLeftOut() {
        val bad = listOf(
            block(1, 1, url = "https://example.com/maps-2026-10/z10-1-1.pmtiles"),
            block(2, 2, url = "${MapLinks.DOWNLOAD_PREFIX}v0.8.3/z10-2-2.pmtiles"),
            block(3, 3, url = url("z10-9-9")),
            block(4, 4, url = url("z10-4-4") + "?x=1"),
            block(5, 5, sha256 = sha.uppercase()),
            block(6, 6, bytes = 0),
            block(7, 7, bytes = CatalogParser.MAX_BLOCK_BYTES + 1),
            block(1024, 8),
        )
        val c = CatalogParser.parse("""{"version":"2026-10","blocks":[${(bad + block(510, 389)).joinToString(",")}]}""")!!
        assertEquals(setOf(Cell(510, 389)), c.blocks.keys)
    }

    @Test
    fun whatIsNotACatalogIsNull() {
        assertNull(CatalogParser.parse("{not json"))
        assertNull(CatalogParser.parse("""{"blocks":[]}"""))
        assertNull(CatalogParser.parse("""{"version":"latest","blocks":[]}"""))
        assertNull(CatalogParser.parse("""{"version":"2026-10"}"""))
        assertNull(CatalogParser.parse("[]"))
    }
}
