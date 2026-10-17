package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapPlannerTest {
    private val now = 1_760_000_000_000L
    private val valencia = Cell(510, 389)

    @Test
    fun aPointMarksItsCellWithItsTime() =
        assertEquals(now - 5, MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now - 5)), now)[valencia])

    @Test
    fun aMarkNeverGoesBackInTime() =
        assertEquals(now, MapPlanner.touch(mapOf(valencia to now), listOf(MapPoint(395_000_000, -5_000_000, now - 99)), now)[valencia])

    private fun around(c: Cell) = (-1..1).flatMap { dx -> (-1..1).map { dy -> Cell(c.x + dx, c.y + dy) } }.toSet()

    /** Spec §3: the whole 3 × 3 neighbourhood of the point's cell, all together. */
    @Test
    fun aPointInACellsCentreWantsTheNineCellsAroundIt() {
        val centre = MapPoint(395_040_000, -5_273_000, now)
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(centre), now), now))
        assertEquals(9, around(valencia).size)
    }

    /** No extra side near an edge: the server learns the same 3 × 3 area wherever the point is in its cell. */
    @Test
    fun aPointAtAnEdgeWantsExactlyTheSameNine() {
        val nearEast = MapPoint(394_699_000, -3_763_000, now) // 2 km from the east edge
        val nearCorner = MapPoint(396_300_000, -3_600_000, now)
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(nearEast), now), now))
        assertEquals(around(valencia), MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(nearCorner), now), now))
    }

    @Test
    fun onlyTheNeighboursTheCatalogListsAreFetched() {
        val usage = MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now)), now)
        val listed = listOf(valencia, Cell(511, 389), Cell(509, 390)).associateWith { block(it) } + (Cell(600, 300) to block(Cell(600, 300)))
        val fetched = MapPlanner.downloads(MapPlanner.wanted(usage, now), usage, Catalog("2026-10", listed), emptyMap()).map { it.cell }.toSet()
        assertEquals(setOf(valencia, Cell(511, 389), Cell(509, 390)), fetched)
    }

    @Test
    fun evictionKeepsTheBlocksWithinOneCellOfARecentPoint() {
        val usage = MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now - 2 * 24 * 3_600_000L)), now)
        val present = setOf(valencia, Cell(511, 390), Cell(509, 388), Cell(512, 389), Cell(510, 391))
        assertEquals(setOf(Cell(512, 389), Cell(510, 391)), MapPlanner.evictions(present, usage, emptySet(), now))
    }

    @Test
    fun theNeighbourhoodOfTheChildsPointIsNeverEvicted() {
        val there = MapPoint(395_000_000, -5_000_000, now - 30 * 24 * 3_600_000L)
        assertEquals(around(valencia), MapPlanner.current(listOf(there)))
        assertEquals(setOf(Cell(512, 389)), MapPlanner.evictions(around(valencia) + Cell(512, 389), emptyMap(), MapPlanner.current(listOf(there)), now))
    }

    @Test
    fun twoPointsFarApartWantTwoNeighbourhoods() {
        val madrid = MapPoint(404_168_000, -37_038_000, now)
        val wanted = MapPlanner.wanted(MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now), madrid), now), now)
        assertEquals(around(valencia) + around(TileGrid.cellOf(404_168_000, -37_038_000)), wanted)
        assertEquals(18, wanted.size)
    }

    private fun block(c: Cell) = CatalogBlock(c, 10L, "0".repeat(64), "${MapLinks.DOWNLOAD_PREFIX}maps-2026-10/${c.name}.pmtiles")

    @Test
    fun aWeekOldMarkIsDropped() = assertEquals(emptyMap(), MapPlanner.touch(mapOf(valencia to now - MapPlanner.KEEP_MS), emptyList(), now))

    @Test
    fun aHundredMegabytesStaySpare() {
        assertTrue(MapPlanner.hasRoom(10L + (100L shl 20), 10L))
        assertFalse(MapPlanner.hasRoom(9L + (100L shl 20), 10L))
    }

    @Test
    fun aClockThatWentBackAsksForTheCatalogAgain() {
        assertTrue(MapPlanner.catalogDue(null, now))
        assertFalse(MapPlanner.catalogDue(now - 1, now))
        assertTrue(MapPlanner.catalogDue(now - MapPlanner.CATALOG_EVERY_MS, now))
        assertTrue(MapPlanner.catalogDue(now + 60_000, now))
    }
}
