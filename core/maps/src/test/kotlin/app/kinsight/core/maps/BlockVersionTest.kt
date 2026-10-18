package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OpenFreeMap spec §7: the ledger says which build and how deep each block file is. */
class BlockVersionTest {
    @Test
    fun itRoundTripsThroughTheLedgerForm() {
        val v = BlockVersion("20261011_080001_pt", 14)
        assertEquals("20261011_080001_pt/14", v.encode())
        assertEquals(v, BlockVersion.parse(v.encode()))
        assertEquals(BlockVersion(BlockVersion.LOCAL, 13), BlockVersion.parse("local/13"))
    }

    /** A sha256 of the GitHub days, or anything else, is unknown: the file is fetched again. */
    @Test
    fun anythingElseIsUnknown() {
        listOf(null, "", "ab".repeat(32), "20261011_080001_pt/9", "20261011_080001_pt/15", "x/y/14", "../14", "/14")
            .forEach { assertNull(BlockVersion.parse(it), it.toString()) }
    }

    @Test
    fun theBuildDayIsItsDate() {
        assertEquals(1_791_676_800_000L, BlockVersion("20261011_080001_pt", 14).builtMs())
        assertNull(BlockVersion(BlockVersion.LOCAL, 14).builtMs())
        assertNull(BlockVersion("20261399_080001_pt", 14).builtMs())
    }
}
